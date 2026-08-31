package dev.agentknock.push

import dev.agentknock.storage.request.RequestNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestNotificationCoordinatorTest {
    @Test
    fun `notification action waits for an in-flight refresh`() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val coordinator = RequestNotificationCoordinator(
            scope = backgroundScope,
            currentRequests = {
                queryStarted.complete(Unit)
                releaseQuery.await()
                listOf(notification("current"))
            },
            displayRequests = { effects += "display:${it.single().requestId}" },
            displayWake = {},
        )

        val refresh = async { coordinator.refresh() }
        queryStarted.await()
        val action = async { coordinator.performAction { effects += "action" } }
        runCurrent()

        assertFalse(action.isCompleted)
        releaseQuery.complete(Unit)
        refresh.await()
        action.await()

        assertEquals(listOf("display:current", "action", "display:current"), effects)
    }

    private fun notification(id: String) = RequestNotification(
        requestId = id,
        title = id,
        summary = "summary",
        details = emptyList(),
        decisionAvailable = true,
    )
}
