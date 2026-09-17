package dev.agentknock.push

import dev.agentknock.storage.request.RequestDecision
import dev.agentknock.storage.request.RequestDecisionResult
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
) {
    private val mutex = Mutex()
    private val actionFailures = mutableMapOf<String, String>()
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
            displayIfNeeded(requests.first())
        }
    }

    /** Displays current requests after notification permission is granted. */
    suspend fun redisplay() {
        mutex.withLock {
            displayedRequests = null
            displayIfNeeded(requests.first())
        }
    }

    suspend fun performAction(
        requestId: String,
        decision: RequestDecision,
        action: suspend () -> RequestDecisionResult,
    ) {
        mutex.withLock {
            val result = action()
            if (result == RequestDecisionResult.Decided) {
                actionFailures.remove(requestId)
            } else {
                actionFailures[requestId] =
                    if (decision == RequestDecision.DENY) "Couldn’t deny" else "Couldn’t approve"
            }
            displayIfNeeded(requests.first())
        }
    }

    private suspend fun displayIfNeeded(requests: List<RequestNotification>) {
        actionFailures.keys.retainAll(requests.map { it.requestId }.toSet())
        val current = requests.map { request ->
            actionFailures[request.requestId]?.let { request.copy(actionFailure = it) } ?: request
        }
        if (current == displayedRequests) return
        displayRequests(current)
        displayedRequests = current
    }
}
