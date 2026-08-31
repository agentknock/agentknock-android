package dev.agentknock.push

import dev.agentknock.storage.request.RequestNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestNotificationCoordinatorTest {
    @Test
    fun `pause invalidates an in-flight snapshot and every refresh queued behind it`() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        var queries = 0
        val effects = mutableListOf<String>()
        val coordinator = coordinator(
            currentRequests = {
                queries += 1
                queryStarted.complete(Unit)
                releaseQuery.await()
                listOf(notification("stale"))
            },
            displayRequests = { effects += "display:${it.single().requestId}" },
            clear = { effects += "clear" },
        )

        val inFlightRefresh = async { coordinator.refresh() }
        queryStarted.await()
        val queuedRefresh = async { coordinator.refresh() }
        val pause = launch { coordinator.pauseAndClear() }
        runCurrent()

        releaseQuery.complete(Unit)
        inFlightRefresh.await()
        queuedRefresh.await()
        pause.join()

        assertEquals(1, queries)
        assertEquals(listOf("clear"), effects)
    }

    @Test
    fun `wake queued before pause cannot reappear after resume`() = runTest {
        val effects = mutableListOf<String>()
        val coordinator = coordinator(
            displayWake = { effects += "wake" },
            clear = { effects += "clear" },
        )

        coordinator.showWake()
        coordinator.pauseAndClear()
        coordinator.resume(refresh = false)
        runCurrent()

        assertEquals(listOf("clear"), effects)

        coordinator.showWake()
        runCurrent()
        assertEquals(listOf("clear", "wake"), effects)
    }

    @Test
    fun `pause clears after an already displaying wake and suppresses later queued wakes`() = runTest {
        val wakeStarted = CompletableDeferred<Unit>()
        val releaseWake = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val coordinator = coordinator(
            displayWake = {
                effects += "wake:start"
                wakeStarted.complete(Unit)
                releaseWake.await()
                effects += "wake:end"
            },
            clear = { effects += "clear" },
        )

        coordinator.showWake()
        runCurrent()
        wakeStarted.await()
        coordinator.showWake()
        val pause = launch { coordinator.pauseAndClear() }
        runCurrent()

        releaseWake.complete(Unit)
        pause.join()
        runCurrent()

        assertEquals(listOf("wake:start", "wake:end", "clear"), effects)
    }

    @Test
    fun `resume refreshes from current state while holding the display sequence`() = runTest {
        var currentId = "before"
        val displayed = mutableListOf<String>()
        val coordinator = coordinator(
            currentRequests = { listOf(notification(currentId)) },
            displayRequests = { displayed += it.single().requestId },
        )

        coordinator.pauseAndClear()
        currentId = "after"
        coordinator.resume(refresh = true)

        assertEquals(listOf("after"), displayed)
    }

    @Test
    fun `pause waits for an admitted notification action and rejects actions while paused`() =
        runTest {
        val actionStarted = CompletableDeferred<Unit>()
        val finishAction = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val coordinator = coordinator(
            displayRequests = { effects += "display" },
            clear = { effects += "clear" },
        )

        val action = async {
            coordinator.performAction {
                effects += "action:start"
                actionStarted.complete(Unit)
                finishAction.await()
                effects += "action:end"
            }
        }
        actionStarted.await()
        val pause = async { coordinator.pauseAndClear() }
        runCurrent()

        assertFalse(pause.isCompleted)
        finishAction.complete(Unit)
        assertTrue(action.await())
        pause.await()
        assertEquals(listOf("action:start", "action:end", "clear"), effects)
        assertFalse(coordinator.performAction { effects += "while-paused" })
        assertEquals(listOf("action:start", "action:end", "clear"), effects)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        currentRequests: suspend () -> List<RequestNotification> = { emptyList() },
        displayRequests: suspend (List<RequestNotification>) -> Unit = {},
        displayWake: suspend () -> Unit = {},
        clear: suspend () -> Unit = {},
    ) = RequestNotificationCoordinator(
        scope = backgroundScope,
        currentRequests = currentRequests,
        displayRequests = displayRequests,
        displayWake = displayWake,
        clear = clear,
    )

    private fun notification(id: String) = RequestNotification(
        requestId = id,
        title = id,
        summary = "summary",
        details = emptyList(),
        decisionAvailable = true,
    )
}
