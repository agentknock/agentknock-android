package dev.agentknock.push

import dev.agentknock.storage.request.RequestDecision
import dev.agentknock.storage.request.RequestDecisionResult
import dev.agentknock.storage.request.RequestNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestNotificationCoordinatorTest {
    @Test
    fun `request flow displays its initial value and distinct changes`() = runTest {
        val first = listOf(notification("first"))
        val second = listOf(notification("second"))
        val displayed = mutableListOf<List<RequestNotification>>()

        RequestNotificationCoordinator(
            scope = backgroundScope,
            requests = flowOf(first, first, second),
            displayRequests = { displayed += it },
        )
        runCurrent()

        assertEquals(listOf(first, second), displayed)
    }

    @Test
    fun `reconcile does not redisplay unchanged state in the same process`() = runTest {
        val current = listOf(notification("current"))
        val displayed = mutableListOf<List<RequestNotification>>()
        val coordinator =
            RequestNotificationCoordinator(
                scope = backgroundScope,
                requests = MutableStateFlow(current),
                displayRequests = { displayed += it },
            )
        runCurrent()

        coordinator.reconcile()

        assertEquals(listOf(current), displayed)
    }

    @Test
    fun `permission grant explicitly redisplays current state`() = runTest {
        val current = listOf(notification("current"))
        val displayed = mutableListOf<List<RequestNotification>>()
        val coordinator =
            RequestNotificationCoordinator(
                scope = backgroundScope,
                requests = MutableStateFlow(current),
                displayRequests = { displayed += it },
            )
        runCurrent()

        coordinator.redisplay()

        assertEquals(listOf(current, current), displayed)
    }

    @Test
    fun `notification display and action are serialized while flow drives redisplay`() = runTest {
        val requests = MutableStateFlow(listOf(notification("current")))
        val displayStarted = CompletableDeferred<Unit>()
        val releaseDisplay = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val coordinator =
            RequestNotificationCoordinator(
                scope = backgroundScope,
                requests = requests,
                displayRequests = {
                    effects += "display:${it.single().requestId}"
                    if (it.single().requestId == "current") {
                        displayStarted.complete(Unit)
                        releaseDisplay.await()
                    }
                },
            )

        runCurrent()
        displayStarted.await()
        val action = async {
            coordinator.performAction("current", RequestDecision.APPROVE) {
                effects += "action:start"
                requests.value = listOf(notification("updated"))
                effects += "action:end"
                RequestDecisionResult.Decided
            }
        }
        runCurrent()

        assertFalse(action.isCompleted)
        releaseDisplay.complete(Unit)
        action.await()
        runCurrent()

        assertEquals(
            listOf("display:current", "action:start", "action:end", "display:updated"),
            effects,
        )
    }

    @Test
    fun `failed action stays visible through unrelated updates and disappears when resolved`() =
        runTest {
            val request = notification("current")
            val requests = MutableStateFlow(listOf(request))
            val displayed = mutableListOf<List<RequestNotification>>()
            val coordinator =
                RequestNotificationCoordinator(backgroundScope, requests) { displayed += it }
            runCurrent()

            coordinator.performAction("current", RequestDecision.APPROVE) {
                RequestDecisionResult.SecretUnavailable
            }
            assertEquals("Couldn’t approve", displayed.last().single().actionFailure)
            requests.value = listOf(request, notification("other"))
            runCurrent()
            assertEquals("Couldn’t approve", displayed.last().first().actionFailure)
            assertEquals(null, displayed.last().last().actionFailure)

            requests.value = listOf(notification("other"))
            runCurrent()
            assertEquals(listOf(notification("other")), displayed.last())

            coordinator.performAction("gone", RequestDecision.DENY) {
                RequestDecisionResult.NotFound
            }
            assertEquals(listOf(notification("other")), displayed.last())
            coordinator.performAction("other", RequestDecision.DENY) {
                RequestDecisionResult.ClientUnavailable
            }
            assertEquals("Couldn’t deny", displayed.last().single().actionFailure)
        }

    private fun notification(id: String) =
        RequestNotification(
            requestId = id,
            title = id,
            summary = "summary",
            details = emptyList(),
            decisionAvailable = true,
        )
}
