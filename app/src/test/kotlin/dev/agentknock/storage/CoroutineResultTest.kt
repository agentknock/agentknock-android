package dev.agentknock.storage

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Test

class CoroutineResultTest {
    @Test
    fun `cancellation propagates`() {
        val cancellation = CancellationException("cancelled")

        val thrown =
            try {
                runCatchingNonCancellation<String> { throw cancellation }
                null
            } catch (failure: CancellationException) {
                failure
            }

        assertSame(cancellation, thrown)
    }
}
