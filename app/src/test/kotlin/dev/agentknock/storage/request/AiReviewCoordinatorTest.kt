package dev.agentknock.storage.request

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiReviewCoordinatorTest {
    @Test
    fun `cancelled owner rejects review so the request can fall back to manual approval`() =
        runTest {
            val coordinator = AiReviewCoordinator(backgroundScope)
            backgroundScope.cancel()

            assertFalse(
                coordinator.launch("request", onCompletion = {}) { error("Review must not run") }
            )
        }

    @Test
    fun `only one live review is admitted for a request`() = runTest {
        val coordinator = AiReviewCoordinator(backgroundScope)
        val started = CompletableDeferred<Unit>()

        assertTrue(
            coordinator.launch("request", onCompletion = {}) {
                started.complete(Unit)
                awaitCancellation()
            }
        )
        runCurrent()
        started.await()

        assertFalse(coordinator.launch("request", onCompletion = {}) {})
        assertTrue(coordinator.launch("other-request", onCompletion = {}) {})
    }
}
