package dev.agentknock.storage.request

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRetryDeadlineTest {
    @Test
    fun `wall clock moving forward cannot shorten an in-process retry delay`() {
        var storedState = RelayRetryDeadlineState()
        var wallTime = 10_000L
        var elapsedTime = 1_000L
        val deadline =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = { storedState = it },
                bootCount = 7,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { elapsedTime },
            )
        deadline.deferFor(1_000)

        wallTime = 100_000
        elapsedTime = 1_500

        assertEquals(500, deadline.remainingMillis())
    }

    @Test
    fun `wall clock moving backward cannot lengthen an in-process retry delay`() {
        var storedState = RelayRetryDeadlineState()
        var wallTime = 10_000L
        var elapsedTime = 1_000L
        val deadline =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = { storedState = it },
                bootCount = 7,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { elapsedTime },
            )
        deadline.deferFor(1_000)

        wallTime = 9_000
        elapsedTime = 1_500

        assertEquals(500, deadline.remainingMillis())
    }

    @Test
    fun `same boot recreation trusts the persisted monotonic deadline`() {
        val storedState =
            RelayRetryDeadlineState(
                wallNotBeforeMillis = 11_000,
                elapsedRealtimeNotBeforeMillis = 550,
                bootCount = 7,
            )
        var wallTime = 100_000L
        var elapsedTime = 300L
        val deadline =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = {},
                bootCount = 7,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { elapsedTime },
            )

        wallTime = 200_000
        elapsedTime = 350

        assertEquals(200, deadline.remainingMillis())
    }

    @Test
    fun `new boot seeds a monotonic deadline from persisted wall time`() {
        val storedState =
            RelayRetryDeadlineState(
                wallNotBeforeMillis = 11_000,
                elapsedRealtimeNotBeforeMillis = 50_000,
                bootCount = 7,
            )
        var writes = 0
        var wallTime = 10_500L
        var elapsedTime = 50L
        val deadline =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = { writes += 1 },
                bootCount = 8,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { elapsedTime },
            )

        wallTime = 100_000
        elapsedTime = 300

        assertEquals(250, deadline.remainingMillis())
        assertEquals(0, writes)
    }

    @Test
    fun `unknown boot seeds the in-process deadline from persisted wall time`() {
        val storedState =
            RelayRetryDeadlineState(
                wallNotBeforeMillis = 11_000,
                elapsedRealtimeNotBeforeMillis = 50_000,
                bootCount = 7,
            )
        var wallTime = 10_500L
        var elapsedTime = 50L
        val deadline =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = {},
                bootCount = null,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { elapsedTime },
            )

        wallTime = 100_000
        elapsedTime = 300

        assertEquals(250, deadline.remainingMillis())
    }

    @Test
    fun `expired same-boot deadline is not resurrected after a backward clock correction`() {
        var storedState = RelayRetryDeadlineState()
        var wallTime = 10_000L
        var elapsedTime = 1_000L
        val deadline =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = { storedState = it },
                bootCount = 7,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { elapsedTime },
            )
        deadline.deferFor(1_000)
        wallTime = 9_000
        elapsedTime = 2_500

        deadline.deferFor(100)

        assertEquals(9_100, storedState.wallNotBeforeMillis)
        val afterReboot =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = { storedState = it },
                bootCount = 8,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { 50 },
            )
        assertEquals(100, afterReboot.remainingMillis())
    }

    @Test
    fun `active monotonic deadline is reanchored after a wall clock correction`() {
        var storedState = RelayRetryDeadlineState()
        var wallTime = 10_000L
        var elapsedTime = 1_000L
        val deadline =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = { storedState = it },
                bootCount = 7,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { elapsedTime },
            )
        deadline.deferFor(1_000)
        wallTime = 9_000
        elapsedTime = 1_500

        deadline.deferFor(100)

        assertEquals(9_500, storedState.wallNotBeforeMillis)
        assertEquals(2_000, storedState.elapsedRealtimeNotBeforeMillis)
    }

    @Test
    fun `consuming an expired deadline prevents wall fallback from resurrecting it`() {
        var storedState = RelayRetryDeadlineState()
        var wallTime = 10_000L
        var elapsedTime = 1_000L
        val deadline =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = { storedState = it },
                bootCount = 7,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { elapsedTime },
            )
        deadline.deferFor(1_000)
        elapsedTime = 2_000

        assertEquals(0, deadline.remainingMillis())
        assertEquals(RelayRetryDeadlineState(), storedState)

        wallTime = 9_000
        val afterReboot =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = { storedState = it },
                bootCount = 8,
                currentTimeMillis = { wallTime },
                elapsedRealtimeMillis = { 50 },
            )
        assertEquals(0, afterReboot.remainingMillis())
    }

    @Test
    fun `concurrent defer cannot be overwritten by expired-state cleanup`() {
        var storedState =
            RelayRetryDeadlineState(
                wallNotBeforeMillis = 1_000,
                elapsedRealtimeNotBeforeMillis = 100,
                bootCount = 7,
            )
        val clearingStarted = CountDownLatch(1)
        val allowClear = CountDownLatch(1)
        val deadline =
            RelayRetryDeadline(
                readState = { storedState },
                writeState = { state ->
                    if (state == RelayRetryDeadlineState()) {
                        clearingStarted.countDown()
                        check(allowClear.await(5, TimeUnit.SECONDS))
                    }
                    storedState = state
                },
                bootCount = 7,
                currentTimeMillis = { 1_000 },
                elapsedRealtimeMillis = { 100 },
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val clear = executor.submit<Long> { deadline.remainingMillis() }
            assertTrue(clearingStarted.await(5, TimeUnit.SECONDS))
            val defer = executor.submit<Unit> { deadline.deferFor(1_000) }
            allowClear.countDown()

            assertEquals(0, clear.get(5, TimeUnit.SECONDS))
            defer.get(5, TimeUnit.SECONDS)
            assertEquals(
                RelayRetryDeadlineState(
                    wallNotBeforeMillis = 2_000,
                    elapsedRealtimeNotBeforeMillis = 1_100,
                    bootCount = 7,
                ),
                storedState,
            )
        } finally {
            allowClear.countDown()
            executor.shutdownNow()
        }
    }
}
