package dev.agentknock.storage.request

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestConnectionManagerTest {
    @Test
    fun `keeps a caught-up connection open through the background grace period`() = runTest {
        var connections = 0
        var cancellations = 0
        var backgroundSynchronizations = 0
        val manager = manager(
            listen = { onCaughtUp ->
                connections += 1
                onCaughtUp()
                try {
                    awaitCancellation()
                } finally {
                    cancellations += 1
                }
            },
            scheduleBackgroundSynchronization = { backgroundSynchronizations += 1 },
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
        assertEquals(0, backgroundSynchronizations)

        manager.appBackgrounded()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, cancellations)
        assertEquals(1, backgroundSynchronizations)

        manager.appBackgrounded()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, backgroundSynchronizations)
    }

    @Test
    fun `initial background state does not schedule synchronization`() = runTest {
        var scheduled = 0
        manager(
            scheduleBackgroundSynchronization = { scheduled += 1 },
            backgroundGracePeriodMillis = 5_000,
        )

        advanceTimeBy(100_000)
        runCurrent()

        assertEquals(0, scheduled)
    }

    @Test
    fun `background handoff schedules only after foreground session cleanup`() = runTest {
        val events = mutableListOf<String>()
        val manager = manager(
            listen = { onCaughtUp ->
                onCaughtUp()
                try {
                    awaitCancellation()
                } finally {
                    events += "connection closed"
                }
            },
            scheduleBackgroundSynchronization = {
                events += "background scheduled"
            },
            backgroundGracePeriodMillis = 5_000,
        )
        manager.appForegrounded()
        runCurrent()

        manager.appBackgrounded()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(emptyList<String>(), events)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(
            listOf("connection closed", "background scheduled"),
            events,
        )
    }

    @Test
    fun `pause during background grace suppresses handoff and background resume reconciles once`() =
        runTest {
            var scheduled = 0
            val manager = manager(
                listen = { onCaughtUp ->
                    onCaughtUp()
                    awaitCancellation()
                },
                scheduleBackgroundSynchronization = { scheduled += 1 },
                backgroundGracePeriodMillis = 5_000,
            )
            manager.appForegrounded()
            runCurrent()
            manager.appBackgrounded()
            advanceTimeBy(4_999)
            runCurrent()

            manager.pauseAndJoin()
            assertEquals(0, scheduled)

            manager.resume()
            assertEquals(1, scheduled)

            manager.resume()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, scheduled)
        }

    @Test
    fun `refresh replaces the foreground connection`() = runTest {
        var connections = 0
        var cancellations = 0
        val manager = manager(
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
    fun `finite synchronization returns immediately behind a foreground socket`() = runTest {
        var finiteSynchronizations = 0
        val manager = manager(
            synchronizeOnce = {
                finiteSynchronizations += 1
                RequestSyncResult.Success
            },
            listen = { onCaughtUp ->
                onCaughtUp()
                awaitCancellation()
            },
        )
        manager.appForegrounded()
        runCurrent()

        val result = manager.synchronizeOnce()

        assertEquals(0, finiteSynchronizations)
        assertEquals(OneShotSynchronizationResult.Covered, result)
    }

    @Test
    fun `foreground waits for a finite synchronization without overlapping it`() = runTest {
        val finiteStarted = CompletableDeferred<Unit>()
        val finishFinite = CompletableDeferred<Unit>()
        var activeTransports = 0
        var maximumActiveTransports = 0
        var connections = 0
        val manager = manager(
            synchronizeOnce = {
                activeTransports += 1
                maximumActiveTransports = maxOf(maximumActiveTransports, activeTransports)
                finiteStarted.complete(Unit)
                try {
                    finishFinite.await()
                    RequestSyncResult.Success
                } finally {
                    activeTransports -= 1
                }
            },
            listen = { onCaughtUp ->
                activeTransports += 1
                maximumActiveTransports = maxOf(maximumActiveTransports, activeTransports)
                connections += 1
                onCaughtUp()
                try {
                    awaitCancellation()
                } finally {
                    activeTransports -= 1
                }
            },
        )

        val finite = launch { manager.synchronizeOnce() }
        finiteStarted.await()
        manager.appForegrounded()
        runCurrent()
        assertEquals(0, connections)

        finishFinite.complete(Unit)
        finite.join()
        runCurrent()

        assertEquals(1, connections)
        assertEquals(1, maximumActiveTransports)
    }

    @Test
    fun `requests arriving during a finite synchronization coalesce into one follow-up pass`() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val finishFirst = CompletableDeferred<Unit>()
            var synchronizations = 0
            val manager = manager(
                synchronizeOnce = {
                    synchronizations += 1
                    if (synchronizations == 1) {
                        firstStarted.complete(Unit)
                        finishFirst.await()
                    }
                    RequestSyncResult.Success
                },
            )

            val first = async { manager.synchronizeOnce() }
            firstStarted.await()
            val second = async { manager.synchronizeOnce() }
            val third = async { manager.synchronizeOnce() }
            runCurrent()
            assertEquals(1, synchronizations)

            finishFirst.complete(Unit)
            val expected = OneShotSynchronizationResult.Completed(RequestSyncResult.Success)
            assertEquals(expected, first.await())
            assertEquals(expected, second.await())
            assertEquals(expected, third.await())

            assertEquals(2, synchronizations)
            assertEquals(RequestSyncResult.Success, manager.lastSyncResult.value)
            assertFalse(manager.syncing.value)
        }

    @Test
    fun `background synchronization request schedules durable work during a finite pass`() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val finishFirst = CompletableDeferred<Unit>()
            var synchronizations = 0
            var scheduled = 0
            lateinit var manager: RequestConnectionManager
            manager = manager(
                synchronizeOnce = {
                    synchronizations += 1
                    if (synchronizations == 1) {
                        firstStarted.complete(Unit)
                        finishFirst.await()
                    }
                    RequestSyncResult.Success
                },
                scheduleBackgroundSynchronization = {
                    scheduled += 1
                    backgroundScope.launch { manager.synchronizeOnce() }
                },
            )

            val first = launch { manager.synchronizeOnce() }
            firstStarted.await()
            manager.requestSynchronization()
            runCurrent()

            assertEquals(1, scheduled)
            assertEquals(1, synchronizations)

            finishFirst.complete(Unit)
            first.join()
            runCurrent()

            assertEquals(2, synchronizations)
        }

    @Test
    fun `foreground synchronization request keeps the healthy live session`() =
        runTest {
            var scheduled = 0
            var connections = 0
            val manager = manager(
                listen = { onCaughtUp ->
                    connections += 1
                    onCaughtUp()
                    awaitCancellation()
                },
                scheduleBackgroundSynchronization = { scheduled += 1 },
            )
            manager.appForegrounded()
            runCurrent()

            manager.requestSynchronization()
            runCurrent()

            assertEquals(0, scheduled)
            assertEquals(1, connections)
        }

    @Test
    fun `foreground synchronization request interrupts relay failure backoff`() = runTest {
        var attempts = 0
        val manager = manager(
            listen = {
                attempts += 1
                if (attempts == 1) {
                    RequestSyncResult.RelayUnavailable("offline")
                } else {
                    awaitCancellation()
                }
            },
            reconnectDelayMillis = 60_000,
        )
        manager.appForegrounded()
        runCurrent()
        assertEquals(1, attempts)

        manager.requestSynchronization()
        runCurrent()

        assertEquals(2, attempts)
    }

    @Test
    fun `foreground synchronization request interrupts post-success reconnect delay`() = runTest {
        var attempts = 0
        val manager = manager(
            listen = {
                attempts += 1
                if (attempts == 1) {
                    RequestSyncResult.Success
                } else {
                    awaitCancellation()
                }
            },
            reconnectDelayMillis = 60_000,
        )
        manager.appForegrounded()
        runCurrent()
        assertEquals(1, attempts)

        manager.requestSynchronization()
        runCurrent()

        assertEquals(2, attempts)
    }

    @Test
    fun `pause cancels and joins the active session and resume reconnects`() = runTest {
        var connections = 0
        var cancellations = 0
        val manager = manager(
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

        manager.pauseAndJoin()
        assertEquals(1, cancellations)
        assertFalse(manager.syncing.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, connections)

        manager.resume()
        runCurrent()
        assertEquals(2, connections)
    }

    @Test
    fun `pause cancels and joins a finite synchronization`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancellationObserved = false
        val manager = manager(
            synchronizeOnce = {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancellationObserved = true
                }
            },
        )
        val caller = async { manager.synchronizeOnce() }
        started.await()

        manager.pauseAndJoin()

        assertEquals(OneShotSynchronizationResult.Covered, caller.await())
        assertTrue(cancellationObserved)
        assertFalse(manager.syncing.value)
    }

    @Test
    fun `terminal result stops retries until an explicit refresh`() = runTest {
        var attempts = 0
        val manager = manager(
            listen = {
                attempts += 1
                RequestSyncResult.NoDevice
            },
            reconnectDelayMillis = 100,
        )

        manager.appForegrounded()
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(1, attempts)

        manager.refresh()
        runCurrent()
        assertEquals(2, attempts)
    }

    @Test
    fun `new durable work restarts a foreground session stopped by a terminal result`() = runTest {
        var attempts = 0
        val manager = manager(
            listen = {
                attempts += 1
                RequestSyncResult.NoDevice
            },
        )
        manager.appForegrounded()
        runCurrent()
        assertEquals(1, attempts)

        manager.requestSynchronization()
        runCurrent()

        assertEquals(2, attempts)
    }

    @Test
    fun `terminal session cannot stop work announced as it returns`() = runTest {
        var attempts = 0
        lateinit var manager: RequestConnectionManager
        manager = manager(
            listen = {
                attempts += 1
                if (attempts == 1) {
                    manager.requestSynchronization()
                    RequestSyncResult.NoDevice
                } else {
                    awaitCancellation()
                }
            },
        )

        manager.appForegrounded()
        runCurrent()

        assertEquals(2, attempts)
    }

    @Test
    fun `finite synchronization is not covered by a stopped foreground session`() = runTest {
        var finiteSynchronizations = 0
        val manager = manager(
            synchronizeOnce = {
                finiteSynchronizations += 1
                RequestSyncResult.Success
            },
            listen = { RequestSyncResult.NoDevice },
        )
        manager.appForegrounded()
        runCurrent()

        assertEquals(
            OneShotSynchronizationResult.Completed(RequestSyncResult.Success),
            manager.synchronizeOnce(),
        )
        assertEquals(1, finiteSynchronizations)
    }

    @Test
    fun `foreground lifecycle transition restarts a terminal session`() = runTest {
        var attempts = 0
        val manager = manager(
            listen = {
                attempts += 1
                RequestSyncResult.RelayRejected(401, "revoked")
            },
        )
        manager.appForegrounded()
        runCurrent()
        assertEquals(1, attempts)

        manager.appBackgrounded()
        manager.appForegrounded()
        runCurrent()

        assertEquals(2, attempts)
    }

    @Test
    fun `relay failures back off exponentially and cap the local delay`() = runTest {
        var attempts = 0
        val manager = manager(
            listen = {
                attempts += 1
                RequestSyncResult.RelayUnavailable("offline")
            },
            reconnectDelayMillis = 100,
            maximumReconnectDelayMillis = 250,
        )
        manager.appForegrounded()
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, attempts)
        advanceTimeBy(199)
        runCurrent()
        assertEquals(2, attempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, attempts)
        advanceTimeBy(249)
        runCurrent()
        assertEquals(3, attempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, attempts)
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        synchronizeOnce: suspend () -> RequestSyncResult = { RequestSyncResult.Success },
        listen: suspend (() -> Unit) -> RequestSyncResult = { RequestSyncResult.Success },
        scheduleBackgroundSynchronization: () -> Unit = {},
        backgroundGracePeriodMillis: Long = 5_000,
        reconnectDelayMillis: Long = 3_000,
        maximumReconnectDelayMillis: Long = 60_000,
    ) = RequestConnectionManager(
        scope = backgroundScope,
        synchronizeOnce = synchronizeOnce,
        listen = listen,
        scheduleBackgroundSynchronization = scheduleBackgroundSynchronization,
        backgroundGracePeriodMillis = backgroundGracePeriodMillis,
        reconnectDelayMillis = reconnectDelayMillis,
        maximumReconnectDelayMillis = maximumReconnectDelayMillis,
    )
}
