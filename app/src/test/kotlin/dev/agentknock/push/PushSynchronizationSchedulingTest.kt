package dev.agentknock.push

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSynchronizationSchedulingTest {
    @Test
    fun `deadline work schedules retry-after values longer than worker execution limit`() {
        assertEquals(
            TimeUnit.MINUTES.toMillis(30),
            deadlineRetryWorkDelayMillis(TimeUnit.MINUTES.toMillis(30)),
        )
    }

    @Test
    fun `deadline work chunks values that could overflow WorkManager`() {
        val delay = deadlineRetryWorkDelayMillis(Long.MAX_VALUE)

        assertTrue(delay > 0)
        assertTrue(delay <= Long.MAX_VALUE - System.currentTimeMillis())
    }
}
