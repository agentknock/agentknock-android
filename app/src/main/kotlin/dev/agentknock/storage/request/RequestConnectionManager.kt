package dev.agentknock.storage.request

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

@OptIn(ExperimentalCoroutinesApi::class)
internal class RequestConnectionManager(
    private val scope: CoroutineScope,
    synchronizeOnce: suspend () -> RequestSyncResult,
    private val listen: suspend (onCaughtUp: () -> Unit) -> RequestSyncResult,
    private val scheduleBackgroundSynchronization: () -> Unit,
    private val relayRetryDeadline: RelayRetryDeadline,
    private val backgroundGracePeriodMillis: Long = BACKGROUND_GRACE_PERIOD_MILLIS,
    private val reconnectDelayMillis: Long = RECONNECT_DELAY_MILLIS,
    private val maximumReconnectDelayMillis: Long = MAXIMUM_RECONNECT_DELAY_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val reportInternalFailure: (Exception) -> Unit = { failure ->
        Log.e(TAG, "Request synchronization failed", failure)
    },
) : DefaultLifecycleObserver {
    private val synchronizeOnceOperation = synchronizeOnce
    private val demand = MutableStateFlow(ConnectionDemand())
    private val sessionLock = Mutex()
    private var activeSession: ActiveSession? = null

    private val _syncing = MutableStateFlow(false)
    private val _lastSyncResult = MutableStateFlow<RequestSyncResult?>(null)

    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    val lastSyncResult: StateFlow<RequestSyncResult?> = _lastSyncResult.asStateFlow()

    init {
        require(backgroundGracePeriodMillis >= 0)
        require(reconnectDelayMillis >= 0)
        require(maximumReconnectDelayMillis >= reconnectDelayMillis)

        scope.launch {
            demand
                .flatMapLatest { current ->
                    flow {
                        if (current.paused) {
                            emit(ConnectionTarget.Inactive)
                        } else if (
                            current.foregroundVisible &&
                            !current.stoppedOnTerminalResult
                        ) {
                            emit(ConnectionTarget.Foreground(current.generation))
                        } else if (current.backgroundHandoffPending) {
                            delay(backgroundGracePeriodMillis)
                            emit(ConnectionTarget.BackgroundHandoff)
                        } else {
                            emit(ConnectionTarget.Inactive)
                        }
                    }
                }
                .distinctUntilChanged()
                .collectLatest { target ->
                    when (target) {
                        is ConnectionTarget.Foreground ->
                            maintainForegroundConnection(target.generation)
                        ConnectionTarget.BackgroundHandoff -> {
                            // collectLatest has cancelled and joined the foreground owner before
                            // entering this branch, so durable work cannot race its live socket.
                            demand.update { current ->
                                if (current.foregroundVisible) {
                                    current
                                } else {
                                    current.copy(backgroundHandoffPending = false)
                                }
                            }
                            scheduleBackgroundSynchronization()
                        }
                        ConnectionTarget.Inactive -> Unit
                    }
                }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        appForegrounded()
    }

    override fun onStop(owner: LifecycleOwner) {
        appBackgrounded()
    }

    fun appForegrounded() {
        demand.update { current ->
            current.copy(
                foregroundVisible = true,
                backgroundHandoffPending = false,
                stoppedOnTerminalResult = false,
                generation = current.generation +
                    if (current.stoppedOnTerminalResult) 1 else 0,
            )
        }
    }

    fun appBackgrounded() {
        demand.update { current ->
            if (!current.foregroundVisible) {
                current
            } else {
                current.copy(
                    foregroundVisible = false,
                    backgroundHandoffPending = !current.paused,
                )
            }
        }
    }

    fun refresh() {
        demand.update { current ->
            if (current.foregroundVisible && !current.paused) {
                current.copy(
                    generation = current.generation + 1,
                    stoppedOnTerminalResult = false,
                )
            } else {
                current.copy(stoppedOnTerminalResult = false)
            }
        }
    }

    /**
     * Announces new relay work without creating a competing connection.
     *
     * A visible app already has (or is starting) its live session. In the background the durable
     * scheduler owns process lifetime and eventually calls [synchronizeOnce].
     */
    fun requestSynchronization() {
        if (demand.value.paused) return
        if (demand.value.foregroundVisible) {
            demand.update { current ->
                if (current.paused || !current.foregroundVisible) {
                    current
                } else if (current.stoppedOnTerminalResult) {
                    current.copy(
                        generation = current.generation + 1,
                        synchronizationGeneration = current.synchronizationGeneration + 1,
                        stoppedOnTerminalResult = false,
                    )
                } else {
                    current.copy(
                        synchronizationGeneration = current.synchronizationGeneration + 1,
                    )
                }
            }
        } else {
            scheduleBackgroundSynchronization()
        }
    }

    /**
     * Runs one finite synchronization when no foreground session covers the device.
     *
     * The unique background work chain owns its finite relay operation. A server retry deadline is
     * returned to durable work without occupying a process-scope job. This method returns
     * immediately instead of waiting behind an existing relay session.
     */
    suspend fun synchronizeOnce(): OneShotSynchronizationResult {
        val callerJob = checkNotNull(currentCoroutineContext()[Job])
        val session = sessionLock.withLock {
            val retryDelayMillis = relayRetryDeadline.remainingMillis()
            when {
                demand.value.paused || demand.value.hasLiveForegroundOwner() ->
                    return OneShotSynchronizationResult.Covered
                retryDelayMillis > 0 ->
                    return OneShotSynchronizationResult.Deferred(retryDelayMillis)
                activeSession != null -> return OneShotSynchronizationResult.Covered
                else -> {
                    val session = ActiveSession.OneShot(callerJob)
                    activeSession = session
                    session
                }
            }
        }
        return runOneShot(session)
    }

    /** Stops new sessions, cancels the current owner, and does not return until it has exited. */
    suspend fun pauseAndJoin() {
        demand.update { current ->
            current.copy(
                paused = true,
                backgroundHandoffPending = false,
            )
        }

        while (true) {
            val sessions = sessionLock.withLock {
                activeSession?.let(::listOf).orEmpty()
            }
            if (sessions.isEmpty()) break
            sessions.forEach { session -> session.ownerJob.cancel() }
            sessions.forEach { session -> session.released.await() }
        }
        _syncing.value = false
    }

    /** Allows lifecycle or scheduled work to establish sessions again after [pauseAndJoin]. */
    fun resume() {
        if (!demand.value.paused) return
        var scheduleReconciliation = false
        demand.update { current ->
            scheduleReconciliation = !current.foregroundVisible
            current.copy(
                paused = false,
                backgroundHandoffPending = false,
                stoppedOnTerminalResult = false,
                generation = current.generation + if (current.foregroundVisible) 1 else 0,
            )
        }
        if (scheduleReconciliation) scheduleBackgroundSynchronization()
    }

    private suspend fun runOneShot(
        session: ActiveSession.OneShot,
    ): OneShotSynchronizationResult {
        try {
            _syncing.value = true
            val result = rememberServerRetryDirective(
                runOperation(synchronizeOnceOperation),
            )
            _lastSyncResult.value = result
            if (result == RequestSyncResult.Success) {
                demand.update { current ->
                    if (current.foregroundVisible && current.stoppedOnTerminalResult) {
                        current.copy(
                            generation = current.generation + 1,
                            stoppedOnTerminalResult = false,
                        )
                    } else {
                        current
                    }
                }
            }
            return OneShotSynchronizationResult.Completed(result)
        } finally {
            withContext(NonCancellable) {
                sessionLock.withLock {
                    if (activeSession === session) activeSession = null
                    if (activeSession == null) _syncing.value = false
                    session.released.complete(Unit)
                }
            }
        }
    }

    private suspend fun maintainForegroundConnection(connectionGeneration: Long) {
        val session = reserveForegroundSession() ?: return
        try {
            var reconnectDelay = reconnectDelayMillis
            while (currentCoroutineContext().isActive && !demand.value.paused) {
                awaitServerRetryWindow()
                val synchronizationGeneration = demand.value.synchronizationGeneration
                var caughtUp = false
                _syncing.value = true
                val result = runOperation {
                    listen {
                        caughtUp = true
                        reconnectDelay = reconnectDelayMillis
                        _lastSyncResult.value = RequestSyncResult.Success
                        _syncing.value = false
                    }
                }
                val finalResult = rememberServerRetryDirective(result)
                _lastSyncResult.value = finalResult
                _syncing.value = false

                when (finalResult) {
                    is RequestSyncResult.RelayUnavailable -> {
                        waitForRetry(reconnectDelay, synchronizationGeneration)
                        reconnectDelay = nextReconnectDelay(reconnectDelay)
                    }
                    RequestSyncResult.Success -> {
                        waitForRetry(reconnectDelayMillis, synchronizationGeneration)
                        reconnectDelay = reconnectDelayMillis
                    }
                    else -> {
                        val retry: Boolean = sessionLock.withLock {
                            while (true) {
                                val current = demand.value
                                if (
                                    activeSession !== session ||
                                    current.generation != connectionGeneration
                                ) {
                                    break
                                }
                                if (
                                    current.synchronizationGeneration !=
                                    synchronizationGeneration
                                ) {
                                    return@withLock true
                                }
                                if (
                                    demand.compareAndSet(
                                        current,
                                        current.copy(stoppedOnTerminalResult = true),
                                    )
                                ) {
                                    activeSession = null
                                    break
                                }
                            }
                            false
                        }
                        if (retry) continue
                        return
                    }
                }

                // A connection that reached caught-up state starts the next outage at the base
                // delay, even if the closing result itself was unavailable.
                if (caughtUp) reconnectDelay = reconnectDelayMillis
            }
        } finally {
            withContext(NonCancellable) {
                sessionLock.withLock {
                    if (activeSession === session) activeSession = null
                    if (activeSession == null) _syncing.value = false
                    session.released.complete(Unit)
                }
            }
        }
    }

    private suspend fun waitForRetry(delayMillis: Long, synchronizationGeneration: Long) {
        val localRetryNotBefore = retryDeadline(delayMillis)
        awaitServerRetryWindow()
        val remainingLocalDelay = (localRetryNotBefore - elapsedRealtimeMillis())
            .coerceAtLeast(0)
        withTimeoutOrNull(remainingLocalDelay) {
            demand.first { current ->
                current.synchronizationGeneration != synchronizationGeneration
            }
        }
    }

    private fun rememberServerRetryDirective(result: RequestSyncResult): RequestSyncResult {
        val delayMillis = (result as? RequestSyncResult.RelayUnavailable)
            ?.retryAfterMillis
            ?.takeIf { it > 0 }
            ?: return result
        return try {
            relayRetryDeadline.deferFor(delayMillis)
            result
        } catch (failure: Exception) {
            internalFailure(failure)
        }
    }

    private suspend fun awaitServerRetryWindow() {
        while (true) {
            val remaining = relayRetryDeadline.remainingMillis()
            if (remaining == 0L) return
            delay(remaining)
        }
    }

    private fun retryDeadline(delayMillis: Long): Long {
        val now = elapsedRealtimeMillis()
        return if (delayMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMillis
    }

    private suspend fun reserveForegroundSession(): ActiveSession.Foreground? {
        val foregroundJob = checkNotNull(currentCoroutineContext()[Job])
        while (currentCoroutineContext().isActive && !demand.value.paused) {
            val existingSessionRelease = sessionLock.withLock {
                if (demand.value.paused || !demand.value.foregroundVisible) {
                    return null
                }
                activeSession?.released ?: run {
                    val session = ActiveSession.Foreground(foregroundJob)
                    activeSession = session
                    return session
                }
            }
            existingSessionRelease.await()
        }
        return null
    }

    private suspend fun runOperation(
        operation: suspend () -> RequestSyncResult,
    ): RequestSyncResult = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        internalFailure(failure)
    }

    private fun internalFailure(failure: Exception): RequestSyncResult.InternalFailure {
        runCatching { reportInternalFailure(failure) }
        return RequestSyncResult.InternalFailure(
            failure::class.java.simpleName.ifBlank { "Exception" },
        )
    }

    private fun nextReconnectDelay(current: Long): Long = min(
        maximumReconnectDelayMillis,
        if (current > maximumReconnectDelayMillis / 2) {
            maximumReconnectDelayMillis
        } else {
            current * 2
        },
    )

    private sealed interface ActiveSession {
        val ownerJob: Job
        val released: CompletableDeferred<Unit>

        class Foreground(
            override val ownerJob: Job,
            override val released: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : ActiveSession

        class OneShot(
            override val ownerJob: Job,
            override val released: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : ActiveSession
    }

    private data class ConnectionDemand(
        val foregroundVisible: Boolean = false,
        val generation: Long = 0,
        val synchronizationGeneration: Long = 0,
        val paused: Boolean = false,
        val backgroundHandoffPending: Boolean = false,
        val stoppedOnTerminalResult: Boolean = false,
    ) {
        fun hasLiveForegroundOwner(): Boolean =
            foregroundVisible && !paused && !stoppedOnTerminalResult
    }

    private sealed interface ConnectionTarget {
        data class Foreground(val generation: Long) : ConnectionTarget
        data object BackgroundHandoff : ConnectionTarget
        data object Inactive : ConnectionTarget
    }

    private companion object {
        const val TAG = "AgentknockConnection"
        const val BACKGROUND_GRACE_PERIOD_MILLIS = 5_000L
        const val RECONNECT_DELAY_MILLIS = 3_000L
        const val MAXIMUM_RECONNECT_DELAY_MILLIS = 60_000L
    }
}

internal sealed interface OneShotSynchronizationResult {
    data object Covered : OneShotSynchronizationResult

    data class Deferred(val retryAfterMillis: Long) : OneShotSynchronizationResult {
        init {
            require(retryAfterMillis > 0)
        }
    }

    data class Completed(val result: RequestSyncResult) : OneShotSynchronizationResult
}
