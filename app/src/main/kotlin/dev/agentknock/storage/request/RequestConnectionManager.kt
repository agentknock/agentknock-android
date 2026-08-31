package dev.agentknock.storage.request

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

@OptIn(ExperimentalCoroutinesApi::class)
internal class RequestConnectionManager(
    private val scope: CoroutineScope,
    synchronizeOnce: suspend () -> RequestSyncResult,
    private val listen: suspend (onCaughtUp: () -> Unit) -> RequestSyncResult,
    private val scheduleBackgroundSynchronization: () -> Unit,
    private val backgroundGracePeriodMillis: Long = BACKGROUND_GRACE_PERIOD_MILLIS,
    private val reconnectDelayMillis: Long = RECONNECT_DELAY_MILLIS,
    private val maximumReconnectDelayMillis: Long = MAXIMUM_RECONNECT_DELAY_MILLIS,
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
                            _syncing.value = false
                            scheduleBackgroundSynchronization()
                        }
                        ConnectionTarget.Inactive -> {
                            _syncing.value = false
                        }
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
     * Concurrent callers share one finite session. Work announced while that session is running is
     * folded into one additional pass, so it cannot fall just beyond the first pass's caught-up
     * boundary. This method deliberately returns immediately instead of waiting behind a live
     * foreground socket.
     */
    suspend fun synchronizeOnce(): OneShotSynchronizationResult {
        if (demand.value.paused || demand.value.hasLiveForegroundOwner()) {
            return OneShotSynchronizationResult.Covered
        }

        val completion = sessionLock.withLock {
            if (demand.value.paused || demand.value.hasLiveForegroundOwner()) return@withLock null
            when (val active = activeSession) {
                is ActiveSession.Foreground -> null
                is ActiveSession.OneShot -> {
                    active.repeatRequested = true
                    active.completion
                }
                null -> startOneShotLocked().completion
            }
        }
        return completion?.await() ?: OneShotSynchronizationResult.Covered
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
            val jobs = sessionLock.withLock {
                activeSession?.job?.let(::listOf).orEmpty()
            }
            if (jobs.isEmpty()) break
            jobs.forEach { job -> job.cancel() }
            jobs.joinAll()
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

    private fun startOneShotLocked(): ActiveSession.OneShot {
        val session = ActiveSession.OneShot()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runOneShot(session)
        }
        session.job = job
        activeSession = session
        job.start()
        return session
    }

    private suspend fun runOneShot(session: ActiveSession.OneShot) {
        try {
            while (currentCoroutineContext().isActive) {
                _syncing.value = true
                val result = runOperation(synchronizeOnceOperation)
                _lastSyncResult.value = result
                _syncing.value = false

                val repeat = sessionLock.withLock {
                    if (activeSession !== session) {
                        false
                    } else if (
                        demand.value.paused ||
                        demand.value.foregroundVisible ||
                        !session.repeatRequested
                    ) {
                        activeSession = null
                        session.completion.complete(
                            OneShotSynchronizationResult.Completed(result),
                        )
                        false
                    } else {
                        session.repeatRequested = false
                        true
                    }
                }
                if (!repeat) {
                    if (result == RequestSyncResult.Success) {
                        demand.update { current ->
                            if (
                                current.foregroundVisible &&
                                current.stoppedOnTerminalResult
                            ) {
                                current.copy(
                                    generation = current.generation + 1,
                                    stoppedOnTerminalResult = false,
                                )
                            } else {
                                current
                            }
                        }
                    }
                    return
                }
            }
        } finally {
            sessionLock.withLock {
                if (activeSession === session) activeSession = null
            }
            session.completion.complete(OneShotSynchronizationResult.Covered)
            _syncing.value = false
        }
    }

    private suspend fun maintainForegroundConnection(connectionGeneration: Long) {
        val session = reserveForegroundSession() ?: return
        try {
            var reconnectDelay = reconnectDelayMillis
            while (currentCoroutineContext().isActive && !demand.value.paused) {
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
                _lastSyncResult.value = result
                _syncing.value = false

                when (result) {
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
            sessionLock.withLock {
                if (activeSession === session) activeSession = null
            }
            _syncing.value = false
        }
    }

    private suspend fun waitForRetry(delayMillis: Long, synchronizationGeneration: Long) {
        withTimeoutOrNull(delayMillis) {
            demand.first { current ->
                current.synchronizationGeneration != synchronizationGeneration
            }
        }
    }

    private suspend fun reserveForegroundSession(): ActiveSession.Foreground? {
        val foregroundJob = checkNotNull(currentCoroutineContext()[Job])
        while (currentCoroutineContext().isActive && !demand.value.paused) {
            val reservation = sessionLock.withLock {
                if (demand.value.paused || !demand.value.foregroundVisible) {
                    ForegroundReservation.Stop
                } else {
                    when (val active = activeSession) {
                        null -> {
                            val session = ActiveSession.Foreground(foregroundJob)
                            activeSession = session
                            ForegroundReservation.Acquired(session)
                        }
                        else -> ForegroundReservation.Wait(active.job)
                    }
                }
            }
            when (reservation) {
                is ForegroundReservation.Acquired -> return reservation.session
                is ForegroundReservation.Wait -> reservation.job.join()
                ForegroundReservation.Stop -> return null
            }
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
        RequestSyncResult.RelayUnavailable(failure.message)
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
        val job: Job

        class Foreground(override val job: Job) : ActiveSession

        class OneShot : ActiveSession {
            override lateinit var job: Job
            var repeatRequested: Boolean = false
            val completion = CompletableDeferred<OneShotSynchronizationResult>()
        }
    }

    private sealed interface ForegroundReservation {
        data class Acquired(val session: ActiveSession.Foreground) : ForegroundReservation
        data class Wait(val job: Job) : ForegroundReservation
        data object Stop : ForegroundReservation
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
        const val BACKGROUND_GRACE_PERIOD_MILLIS = 5_000L
        const val RECONNECT_DELAY_MILLIS = 3_000L
        const val MAXIMUM_RECONNECT_DELAY_MILLIS = 60_000L
    }
}

internal sealed interface OneShotSynchronizationResult {
    data object Covered : OneShotSynchronizationResult

    data class Completed(val result: RequestSyncResult) : OneShotSynchronizationResult
}
