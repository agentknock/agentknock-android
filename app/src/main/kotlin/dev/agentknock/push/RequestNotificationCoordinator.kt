package dev.agentknock.push

import dev.agentknock.storage.request.RequestNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes notification state with the request state that produced it. */
internal class RequestNotificationCoordinator(
    scope: CoroutineScope,
    private val requests: Flow<List<RequestNotification>>,
    private val displayRequests: suspend (List<RequestNotification>) -> Unit,
    private val displayWake: () -> Unit,
) {
    private val mutex = Mutex()
    private var displayedRequests: List<RequestNotification>? = null

    init {
        scope.launch {
            requests.collect { current ->
                mutex.withLock { displayIfNeeded(current) }
            }
        }
    }

    /** Reconciles notifications before a finite synchronization owner may lose process lifetime. */
    suspend fun reconcile() {
        mutex.withLock {
            displayIfNeeded(requests.first(), force = true)
        }
    }

    fun showWake() = displayWake()

    suspend fun performAction(action: suspend () -> Unit) {
        mutex.withLock {
            action()
            displayIfNeeded(requests.first(), force = true)
        }
    }

    private suspend fun displayIfNeeded(
        requests: List<RequestNotification>,
        force: Boolean = false,
    ) {
        if (!force && requests == displayedRequests) return
        displayRequests(requests)
        displayedRequests = requests
    }
}
