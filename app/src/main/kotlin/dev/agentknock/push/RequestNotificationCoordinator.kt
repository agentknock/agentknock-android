package dev.agentknock.push

import dev.agentknock.storage.request.RequestNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes notification state with the request state that produced it. */
internal class RequestNotificationCoordinator(
    private val scope: CoroutineScope,
    private val currentRequests: suspend () -> List<RequestNotification>,
    private val displayRequests: suspend (List<RequestNotification>) -> Unit,
    private val displayWake: suspend () -> Unit,
) {
    private val mutex = Mutex()

    suspend fun refresh() {
        mutex.withLock {
            displayRequests(currentRequests())
        }
    }

    fun showWake() {
        scope.launch {
            mutex.withLock { displayWake() }
        }
    }

    suspend fun performAction(action: suspend () -> Unit) {
        mutex.withLock {
            action()
            displayRequests(currentRequests())
        }
    }
}
