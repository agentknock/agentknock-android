package dev.agentknock.storage.request

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    fun `requests arriving during a finite synchronization each run a sequential follow-up`() =
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

            assertEquals(3, synchronizations)
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

        caller.join()
        assertTrue(caller.isCancelled)
        assertTrue(cancellationObserved)
        assertFalse(manager.syncing.value)
    }

    @Test
    fun `cancelling the finite synchronization caller cancels its relay operation`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancellationObserved = false
        var attempts = 0
        val manager = manager(
            synchronizeOnce = {
                attempts += 1
                if (attempts == 1) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancellationObserved = true
                    }
                }
                RequestSyncResult.Success
            },
        )
        val caller = launch { manager.synchronizeOnce() }
        started.await()

        caller.cancelAndJoin()

        assertTrue(cancellationObserved)
        assertFalse(manager.syncing.value)
        assertEquals(
            OneShotSynchronizationResult.Completed(RequestSyncResult.Success),
            manager.synchronizeOnce(),
        )
        assertEquals(2, attempts)
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
    fun `new synchronization work cannot bypass the server retry delay`() = runTest {
        var attempts = 0
        val manager = manager(
            listen = {
                attempts += 1
                if (attempts == 1) {
                    RequestSyncResult.RelayUnavailable(
                        message = "rate limited",
                        retryAfterMillis = 1_000,
                    )
                } else {
                    awaitCancellation()
                }
            },
            reconnectDelayMillis = 100,
        )
        manager.appForegrounded()
        runCurrent()
        assertEquals(1, attempts)

        manager.requestSynchronization()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, attempts)
    }

    @Test
    fun `server retry delay applies to a later finite synchronization`() = runTest {
        var attempts = 0
        val manager = manager(
            synchronizeOnce = {
                attempts += 1
                if (attempts == 1) {
                    RequestSyncResult.RelayUnavailable(
                        message = "rate limited",
                        retryAfterMillis = 1_000,
                    )
                } else {
                    RequestSyncResult.Success
                }
            },
        )

        assertEquals(
            OneShotSynchronizationResult.Completed(
                RequestSyncResult.RelayUnavailable("rate limited", 1_000),
            ),
            manager.synchronizeOnce(),
        )
        assertEquals(
            OneShotSynchronizationResult.Deferred(1_000),
            manager.synchronizeOnce(),
        )
        assertEquals(1, attempts)

        advanceTimeBy(999)
        assertEquals(
            OneShotSynchronizationResult.Deferred(1),
            manager.synchronizeOnce(),
        )
        assertEquals(1, attempts)
        advanceTimeBy(1)

        assertEquals(
            OneShotSynchronizationResult.Completed(RequestSyncResult.Success),
            manager.synchronizeOnce(),
        )
        assertEquals(2, attempts)
    }

    @Test
    fun `zero retry directive remains immediate and is not persisted`() = runTest {
        var attempts = 0
        var deadlineWrites = 0
        val manager = manager(
            synchronizeOnce = {
                attempts += 1
                if (attempts == 1) {
                    RequestSyncResult.RelayUnavailable(
                        message = "try again",
                        retryAfterMillis = 0,
                    )
                } else {
                    RequestSyncResult.Success
                }
            },
            relayRetryDeadline = RelayRetryDeadline(
                readState = { RelayRetryDeadlineState() },
                writeState = { deadlineWrites += 1 },
                bootCount = 7,
                currentTimeMillis = { 1_000 },
                elapsedRealtimeMillis = { 1_000 },
            ),
        )

        assertEquals(
            OneShotSynchronizationResult.Completed(
                RequestSyncResult.RelayUnavailable("try again", 0),
            ),
            manager.synchronizeOnce(),
        )
        assertEquals(
            OneShotSynchronizationResult.Completed(RequestSyncResult.Success),
            manager.synchronizeOnce(),
        )
        assertEquals(2, attempts)
        assertEquals(0, deadlineWrites)
    }

    @Test
    fun `server retry delay defers a concurrent follow-up without a sleeping session`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var attempts = 0
        val manager = manager(
            synchronizeOnce = {
                attempts += 1
                started.complete(Unit)
                finish.await()
                RequestSyncResult.RelayUnavailable("rate limited", 60_000)
            },
        )

        val first = async { manager.synchronizeOnce() }
        started.await()
        val concurrent = async { manager.synchronizeOnce() }
        runCurrent()
        finish.complete(Unit)

        assertEquals(
            OneShotSynchronizationResult.Completed(
                RequestSyncResult.RelayUnavailable("rate limited", 60_000),
            ),
            first.await(),
        )
        assertEquals(OneShotSynchronizationResult.Deferred(60_000), concurrent.await())
        assertEquals(1, attempts)
        assertFalse(manager.syncing.value)
    }

    @Test
    fun `concurrent caller follows an internal failure with a fresh pass`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        var attempts = 0
        val manager = manager(
            synchronizeOnce = {
                attempts += 1
                if (attempts == 1) {
                    firstStarted.complete(Unit)
                    finishFirst.await()
                    error("broken local state")
                }
                RequestSyncResult.Success
            },
        )

        val first = async { manager.synchronizeOnce() }
        firstStarted.await()
        val second = async { manager.synchronizeOnce() }
        runCurrent()
        finishFirst.complete(Unit)

        assertEquals(
            OneShotSynchronizationResult.Completed(
                RequestSyncResult.InternalFailure("IllegalStateException"),
            ),
            first.await(),
        )
        assertEquals(
            OneShotSynchronizationResult.Completed(RequestSyncResult.Success),
            second.await(),
        )
        assertEquals(2, attempts)
    }

    @Test
    fun `server retry deadline survives manager recreation`() = runTest {
        var storedDeadline = RelayRetryDeadlineState()
        fun retryDeadline() = RelayRetryDeadline(
            readState = { storedDeadline },
            writeState = { storedDeadline = it },
            bootCount = 7,
            currentTimeMillis = { testScheduler.currentTime },
            elapsedRealtimeMillis = { testScheduler.currentTime },
        )
        val first = manager(
            synchronizeOnce = {
                RequestSyncResult.RelayUnavailable("rate limited", 1_000)
            },
            relayRetryDeadline = retryDeadline(),
        )
        assertEquals(
            OneShotSynchronizationResult.Completed(
                RequestSyncResult.RelayUnavailable("rate limited", 1_000),
            ),
            first.synchronizeOnce(),
        )

        var attemptsAfterRestart = 0
        val recreated = manager(
            synchronizeOnce = {
                attemptsAfterRestart += 1
                RequestSyncResult.Success
            },
            relayRetryDeadline = retryDeadline(),
        )
        assertEquals(
            OneShotSynchronizationResult.Deferred(1_000),
            recreated.synchronizeOnce(),
        )
        assertEquals(0, attemptsAfterRestart)
    }

    @Test
    fun `unexpected local failure is terminal instead of a network retry`() = runTest {
        var attempts = 0
        val failure = IllegalStateException("broken local state")
        var reportedFailure: Exception? = null
        val manager = manager(
            listen = {
                attempts += 1
                throw failure
            },
            reconnectDelayMillis = 100,
            reportInternalFailure = { reportedFailure = it },
        )

        manager.appForegrounded()
        runCurrent()

        val result = manager.lastSyncResult.value
        assertEquals(
            RequestSyncResult.InternalFailure("IllegalStateException"),
            result,
        )
        assertTrue(reportedFailure === failure)
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(1, attempts)
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
        relayRetryDeadline: RelayRetryDeadline? = null,
        reportInternalFailure: (Exception) -> Unit = {},
    ) = RequestConnectionManager(
        scope = backgroundScope,
        synchronizeOnce = synchronizeOnce,
        listen = listen,
        scheduleBackgroundSynchronization = scheduleBackgroundSynchronization,
        relayRetryDeadline = relayRetryDeadline ?: inMemoryRetryDeadline(),
        backgroundGracePeriodMillis = backgroundGracePeriodMillis,
        reconnectDelayMillis = reconnectDelayMillis,
        maximumReconnectDelayMillis = maximumReconnectDelayMillis,
        elapsedRealtimeMillis = { testScheduler.currentTime },
        reportInternalFailure = reportInternalFailure,
    )

    private fun kotlinx.coroutines.test.TestScope.inMemoryRetryDeadline(): RelayRetryDeadline {
        var state = RelayRetryDeadlineState()
        return RelayRetryDeadline(
            readState = { state },
            writeState = { state = it },
            bootCount = 7,
            currentTimeMillis = { testScheduler.currentTime },
            elapsedRealtimeMillis = { testScheduler.currentTime },
        )
    }
}
