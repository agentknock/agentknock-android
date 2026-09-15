package dev.agentknock.push

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestProcessingCoordinatorTest {
    @Test
    fun `an idle or completed owner cannot hide another owner's processing`() {
        val displayed = mutableListOf<RequestProcessingState?>()
        val coordinator = RequestProcessingCoordinator { displayed += it }
        val first = coordinator.start()
        first.setProcessing(false)
        val second = coordinator.start()
        first.close()
        assertEquals(RequestProcessingState.PROCESSING, displayed.last())

        second.setProcessing(false)
        first.setProcessing(true)
        assertEquals(RequestProcessingState.LISTENING, displayed.last())
        second.close()
        assertEquals(
            listOf(
                RequestProcessingState.PROCESSING,
                RequestProcessingState.LISTENING,
                RequestProcessingState.PROCESSING,
                RequestProcessingState.LISTENING,
                null,
            ),
            displayed,
        )
    }

    @Test
    fun `worker cancellation releases its notification even before synchronization finishes`() =
        runTest {
            var displayed: RequestProcessingState? = null
            val coordinator = RequestProcessingCoordinator { displayed = it }
            val worker = launch {
                coordinator.start().use { awaitCancellation() }
            }
            runCurrent()
            assertEquals(RequestProcessingState.PROCESSING, displayed)

            worker.cancelAndJoin()

            assertEquals(null, displayed)
        }
}
