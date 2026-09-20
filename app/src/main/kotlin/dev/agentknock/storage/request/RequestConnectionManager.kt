package dev.agentknock.storage.request

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** One socket owner; activities and workers supply execution lifetime, never their own sockets. */
internal class RequestConnectionManager(
    private val scope: CoroutineScope,
    private val connect:
        suspend (
            idleChecks: ReceiveChannel<RelayConnectionProgress>,
            onProgress: (RelayConnectionProgress) -> Unit,
        ) -> RequestSyncResult,
    private val scheduleBackgroundSynchronization: () -> Unit,
    private val relayRetryDeadline: RelayRetryDeadline,
    private val activeReviews: StateFlow<Boolean>,
    private val displayProcessing: (Boolean?) -> Unit = {},
    private val backgroundGracePeriodMillis: Long = 35_000,
    private val reconnectDelayMillis: Long = 3_000,
    private val maximumReconnectDelayMillis: Long = 60_000,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val reportInternalFailure: (Exception) -> Unit = {
        Log.e("AgentknockConnection", "Request synchronization failed", it)
    },
) : DefaultLifecycleObserver {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val workers = mutableSetOf<CompletableDeferred<OneShotSynchronizationResult>>()
    @Volatile private var foreground = false
    @Volatile private var paused = false
    private var connection: Job? = null
    private var connectionToken: Any? = null
    private var idleChecks: Channel<RelayConnectionProgress>? = null
    private var progress = RelayConnectionProgress(true, false)
    private var timer: Job? = null
    private var backgroundedAt: Long? = null
    private var graceUntil: Long? = null
    private var backgroundDeadline: Long? = null
    private var idleSince: Long? = null
    private var handledWork = false
    private var processing = false
    private var retryAt = 0L
    private var retryDelay = reconnectDelayMillis
    private var stopped = false
    private var wakeGeneration = 0L
    private var displayed: Boolean? = null
    private val _syncing = MutableStateFlow(false)
    private val _lastSyncResult = MutableStateFlow<RequestSyncResult?>(null)
    val syncing = _syncing.asStateFlow()
    val lastSyncResult = _lastSyncResult.asStateFlow()

    private val controller = scope.launch {
        try {
            for (command in commands) {
                try {
                    command()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    stopConnection()
                    stopped = true
                    val result = internalFailure(failure)
                    _lastSyncResult.value = result
                    finishWorkers(OneShotSynchronizationResult.Completed(result))
                    displayProcessing(null)
                    displayed = null
                }
            }
        } finally {
            commands.close()
            withContext(NonCancellable) { stopConnection() }
            timer?.cancel()
            workers.forEach { it.cancel() }
            displayProcessing(null)
        }
    }

    init {
        require(backgroundGracePeriodMillis >= 0)
        require(reconnectDelayMillis > 0 && maximumReconnectDelayMillis >= reconnectDelayMillis)
        scope.launch { activeReviews.collect { update {} } }
    }

    override fun onStart(owner: LifecycleOwner) = appForegrounded()

    override fun onStop(owner: LifecycleOwner) = appBackgrounded()

    fun appForegrounded() = update {
        foreground = true
        wakeGeneration++
        graceUntil = null
        backgroundedAt = null
        stopped = false
        retryAt = 0
        finishWorkers(OneShotSynchronizationResult.Covered)
    }

    fun appBackgrounded() = update {
        if (foreground) {
            foreground = false
            backgroundedAt = if (connection != null) elapsedRealtimeMillis() else null
            graceUntil = backgroundedAt?.plus(backgroundGracePeriodMillis)
            if (!paused) scheduleBackgroundSynchronization()
        }
    }

    fun requestSynchronization() {
        update {
            wakeGeneration++
            stopped = false
            retryAt = 0
        }
        if (!foreground && !paused) scheduleBackgroundSynchronization()
    }

    fun refresh() = update {
        stopConnection()
        wakeGeneration++
        stopped = false
        retryAt = 0
    }

    suspend fun synchronizeOnce(): OneShotSynchronizationResult {
        val finished = CompletableDeferred<OneShotSynchronizationResult>()
        update {
            if (!finished.isCancelled) {
                if (paused || foreground) {
                    if (foreground && !paused && connection == null) {
                        wakeGeneration++
                        stopped = false
                        retryAt = 0
                    }
                    finished.complete(OneShotSynchronizationResult.Covered)
                } else {
                    workers += finished
                    wakeGeneration++
                    graceUntil = null
                    stopped = false
                    retryAt = 0
                }
            }
        }
        return try {
            select {
                finished.onAwait { it }
                controller.onJoin { throw CancellationException("Request processing stopped") }
            }
        } finally {
            finished.cancel()
            withContext(NonCancellable) { updateAndAwait { workers.remove(finished) } }
        }
    }

    suspend fun pauseAndJoin() = updateAndAwait {
        paused = true
        graceUntil = null
        stopConnection()
        finishWorkers(OneShotSynchronizationResult.Covered)
    }

    fun resume() = update {
        if (paused) {
            paused = false
            stopped = false
            if (!foreground) scheduleBackgroundSynchronization()
        }
    }

    private fun update(action: suspend () -> Unit) {
        commands.trySend {
            action()
            reconcile()
        }
    }

    private suspend fun updateAndAwait(action: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        commands.trySend {
            try {
                action()
                reconcile()
                done.complete(Unit)
            } catch (failure: Exception) {
                done.completeExceptionally(failure)
                throw failure
            }
        }
        select<Unit> {
            done.onAwait {}
            controller.onJoin {}
        }
    }

    private suspend fun reconcile() {
        timer?.cancel()
        timer = null
        val now = elapsedRealtimeMillis()
        if (paused || (!foreground && workers.isEmpty() && (graceUntil ?: 0) <= now)) {
            stopConnection()
            backgroundDeadline = null
        } else if (!foreground && backgroundDeadline?.let { it <= now } == true) {
            stopConnection()
            _lastSyncResult.value = RequestSyncResult.ContinuationRequired
            if (!activeReviews.value)
                finishWorkers(
                    OneShotSynchronizationResult.Completed(RequestSyncResult.ContinuationRequired)
                )
        } else {
            val idleDeadline = idleSince?.let {
                if (handledWork || backgroundedAt != null)
                    maxOf(it, backgroundedAt ?: it) + backgroundGracePeriodMillis
                else now
            }
            if (
                !foreground &&
                    connection != null &&
                    !processing &&
                    !activeReviews.value &&
                    idleDeadline != null &&
                    idleDeadline <= now
            ) {
                idleChecks?.trySend(progress)
            } else if (connection == null) {
                val serverDelay = relayRetryDeadline.remainingMillis()
                if (!foreground && !activeReviews.value && (stopped || serverDelay > 0)) {
                    finishWorkers(
                        if (serverDelay > 0) OneShotSynchronizationResult.Deferred(serverDelay)
                        else
                            OneShotSynchronizationResult.Completed(
                                checkNotNull(_lastSyncResult.value)
                            )
                    )
                    graceUntil = null
                } else if (!stopped && serverDelay == 0L && retryAt <= now) {
                    startConnection()
                }
            }
            if (
                !foreground &&
                    workers.isNotEmpty() &&
                    connection != null &&
                    backgroundDeadline == null
            ) {
                backgroundDeadline = now + BACKGROUND_SESSION_LIMIT_MILLIS
            }
            val next =
                listOfNotNull(
                        if (!foreground && workers.isEmpty()) graceUntil else null,
                        if (!foreground) backgroundDeadline else null,
                        if (
                            !foreground && connection != null && !processing && !activeReviews.value
                        )
                            idleDeadline
                        else null,
                        if (connection == null && !stopped)
                            maxOf(retryAt, now + relayRetryDeadline.remainingMillis())
                        else null,
                    )
                    .filter { it > now }
                    .minOrNull()
            if (next != null)
                timer = scope.launch {
                    delay(next - now)
                    update {}
                }
        }
        _syncing.value = connection != null && processing
        val visible =
            if (!foreground && workers.isNotEmpty()) processing || activeReviews.value else null
        if (visible != displayed) {
            displayProcessing(visible)
            displayed = visible
        }
    }

    private fun startConnection() {
        val token = Any()
        var generation = wakeGeneration
        val checks = Channel<RelayConnectionProgress>(Channel.CONFLATED)
        idleChecks = checks
        connectionToken = token
        processing = true
        idleSince = null
        handledWork = false
        connection = scope.launch {
            val result =
                try {
                    connect(checks) { current ->
                        update {
                            if (connectionToken === token) {
                                if (!current.processing && (processing || idleSince == null))
                                    idleSince = elapsedRealtimeMillis()
                                progress = current
                                processing = current.processing
                                handledWork = current.handledWork
                                if (!processing) {
                                    generation = wakeGeneration
                                    retryDelay = reconnectDelayMillis
                                    _lastSyncResult.value = RequestSyncResult.Success
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    internalFailure(failure)
                }
            update {
                if (connectionToken === token) {
                    connection = null
                    connectionToken = null
                    idleChecks = null
                    if (!foreground) backgroundedAt = null
                    processing = false
                    _lastSyncResult.value = result
                    val serverDelay =
                        (result as? RequestSyncResult.RelayUnavailable)?.retryAfterMillis
                    if (serverDelay != null && serverDelay > 0)
                        relayRetryDeadline.deferFor(serverDelay)
                    val freshWake = generation != wakeGeneration
                    stopped =
                        !freshWake &&
                            result !is RequestSyncResult.RelayUnavailable &&
                            result != RequestSyncResult.Success
                    if (!foreground && !activeReviews.value) {
                        finishWorkers(OneShotSynchronizationResult.Completed(result))
                        graceUntil = null
                    } else {
                        retryAt = if (freshWake) 0 else elapsedRealtimeMillis() + retryDelay
                        retryDelay = (retryDelay * 2).coerceAtMost(maximumReconnectDelayMillis)
                    }
                }
            }
        }
    }

    private suspend fun stopConnection() {
        connectionToken = null
        connection?.cancelAndJoin()
        connection = null
        idleChecks = null
        if (!foreground) backgroundedAt = null
        processing = false
        _syncing.value = false
    }

    private fun internalFailure(failure: Exception): RequestSyncResult.InternalFailure {
        runCatching { reportInternalFailure(failure) }
        return RequestSyncResult.InternalFailure(
            failure::class.java.simpleName.ifBlank { "Exception" }
        )
    }

    private fun finishWorkers(result: OneShotSynchronizationResult) {
        workers.forEach { it.complete(result) }
        workers.clear()
        backgroundDeadline = null
    }

    private companion object {
        const val BACKGROUND_SESSION_LIMIT_MILLIS = 120_000L
    }
}

internal sealed interface OneShotSynchronizationResult {
    data object Covered : OneShotSynchronizationResult

    data class Deferred(val retryAfterMillis: Long) : OneShotSynchronizationResult

    data class Completed(val result: RequestSyncResult) : OneShotSynchronizationResult
}
