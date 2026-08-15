package dev.agentknock.storage.request

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
internal class RequestConnectionManager(
    private val scope: CoroutineScope,
    private val listen: suspend (onCaughtUp: () -> Unit) -> RequestSyncResult,
    private val backgroundGracePeriodMillis: Long = BACKGROUND_GRACE_PERIOD_MILLIS,
    private val reconnectDelayMillis: Long = RECONNECT_DELAY_MILLIS,
) : DefaultLifecycleObserver {
    private val demand = MutableStateFlow(ConnectionDemand())
    private val _syncing = MutableStateFlow(false)
    private val _lastSyncResult = MutableStateFlow<RequestSyncResult?>(null)

    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    val lastSyncResult: StateFlow<RequestSyncResult?> = _lastSyncResult.asStateFlow()

    init {
        scope.launch {
            demand
                .flatMapLatest { current ->
                    flow {
                        if (!current.foregroundVisible) {
                            delay(backgroundGracePeriodMillis)
                        }
                        emit(current.generation.takeIf { current.foregroundVisible })
                    }
                }
                .distinctUntilChanged()
                .collectLatest { generation ->
                    if (generation == null) {
                        _syncing.value = false
                    } else {
                        maintainConnection()
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
        demand.update { current -> current.copy(foregroundVisible = true) }
    }

    fun appBackgrounded() {
        demand.update { current -> current.copy(foregroundVisible = false) }
    }

    fun refresh() {
        demand.update { current ->
            if (current.foregroundVisible) {
                current.copy(generation = current.generation + 1)
            } else {
                current
            }
        }
    }

    private suspend fun maintainConnection() {
        try {
            while (currentCoroutineContext().isActive) {
                _syncing.value = true
                val result = try {
                    listen {
                        _lastSyncResult.value = RequestSyncResult.Success
                        _syncing.value = false
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    RequestSyncResult.RelayUnavailable(failure.message)
                }
                _lastSyncResult.value = result
                _syncing.value = false
                delay(reconnectDelayMillis)
            }
        } finally {
            _syncing.value = false
        }
    }

    private data class ConnectionDemand(
        val foregroundVisible: Boolean = false,
        val generation: Long = 0,
    )

    private companion object {
        const val BACKGROUND_GRACE_PERIOD_MILLIS = 5_000L
        const val RECONNECT_DELAY_MILLIS = 3_000L
    }
}
