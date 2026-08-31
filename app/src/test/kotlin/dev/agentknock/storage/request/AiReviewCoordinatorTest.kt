package dev.agentknock.storage.request

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReviewCoordinatorTest {
    @Test
    fun `pause cancels admitted reviews and refuses late work until resumed`() = runTest {
        val coordinator = AiReviewCoordinator(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        assertTrue(
            coordinator.launch("first") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        runCurrent()
        started.await()

        coordinator.pauseAndCancel()

        assertTrue(cancelled.isCompleted)
        assertFalse(coordinator.launch("late") {})

        coordinator.resume()
        assertTrue(coordinator.launch("after-resume") {})
        runCurrent()
    }
}
