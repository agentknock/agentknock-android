package dev.agentknock.storage.request

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiReviewCoordinatorTest {
    @Test
    fun `only one live review is admitted for a request`() = runTest {
        val coordinator = AiReviewCoordinator(backgroundScope)
        val started = CompletableDeferred<Unit>()

        assertTrue(
            coordinator.launch("request") {
                started.complete(Unit)
                awaitCancellation()
            },
        )
        runCurrent()
        started.await()

        assertFalse(coordinator.launch("request") {})
        assertTrue(coordinator.launch("other-request") {})
    }
}
