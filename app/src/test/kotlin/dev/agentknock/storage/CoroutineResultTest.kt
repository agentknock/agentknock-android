package dev.agentknock.storage

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineResultTest {
    @Test
    fun `ordinary exceptions become failures`() {
        val failure = IllegalArgumentException("invalid")

        val result = runCatchingNonCancellation<String> { throw failure }

        assertTrue(result.isFailure)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun `cancellation propagates`() {
        val cancellation = CancellationException("cancelled")

        val thrown = try {
            runCatchingNonCancellation<String> { throw cancellation }
            null
        } catch (failure: CancellationException) {
            failure
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `successful values are preserved`() {
        assertEquals("value", runCatchingNonCancellation { "value" }.getOrNull())
    }
}
