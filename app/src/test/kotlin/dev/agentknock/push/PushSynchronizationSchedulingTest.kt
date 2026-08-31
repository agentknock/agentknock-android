package dev.agentknock.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

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
        assertEquals(
            TimeUnit.HOURS.toMillis(24),
            deadlineRetryWorkDelayMillis(Long.MAX_VALUE),
        )
    }
}
