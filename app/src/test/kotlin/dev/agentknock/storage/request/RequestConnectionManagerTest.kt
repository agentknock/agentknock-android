package dev.agentknock.storage.request

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestConnectionManagerTest {
    @Test
    fun `keeps a caught-up connection open only while the app is visible`() = runTest {
        var connections = 0
        var cancellations = 0
        val manager = RequestConnectionManager(
            scope = backgroundScope,
            listen = { onCaughtUp ->
                connections += 1
                onCaughtUp()
                try {
                    awaitCancellation()
                } finally {
                    cancellations += 1
                }
            },
            backgroundGracePeriodMillis = 5_000,
        )

        manager.appForegrounded()
        runCurrent()

        assertEquals(1, connections)
        assertEquals(RequestSyncResult.Success, manager.lastSyncResult.value)
        assertFalse(manager.syncing.value)

        manager.appBackgrounded()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(0, cancellations)

        manager.appForegrounded()
        runCurrent()
        assertEquals(1, connections)
        assertEquals(0, cancellations)

        manager.appBackgrounded()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, cancellations)
    }

    @Test
    fun `refresh replaces the foreground connection`() = runTest {
        var connections = 0
        var cancellations = 0
        val manager = RequestConnectionManager(
            scope = backgroundScope,
            listen = { onCaughtUp ->
                connections += 1
                onCaughtUp()
                try {
                    awaitCancellation()
                } finally {
                    cancellations += 1
                }
            },
        )

        manager.appForegrounded()
        runCurrent()
        manager.refresh()
        runCurrent()

        assertEquals(2, connections)
        assertEquals(1, cancellations)
    }

    @Test
    fun `retries a failed connection while the app remains visible`() = runTest {
        var attempts = 0
        val failure = RequestSyncResult.RelayUnavailable("offline")
        val manager = RequestConnectionManager(
            scope = backgroundScope,
            listen = {
                attempts += 1
                failure
            },
            reconnectDelayMillis = 3_000,
        )

        manager.appForegrounded()
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(failure, manager.lastSyncResult.value)

        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, attempts)
    }
}
