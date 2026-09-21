package dev.agentknock.storage.request

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestConnectionManagerTest {
    @Test
    fun `foreground and workers reuse the same socket in both directions`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        val socket = f.sockets.single()
        socket.progress(false, false)
        runCurrent()
        f.manager.appBackgrounded()
        runCurrent()
        assertEquals(1, f.scheduled)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        advanceTimeBy(34_999)
        runCurrent()
        assertFalse(worker.isCompleted)
        assertFalse(socket.closed)
        assertEquals(1, f.sockets.size)
        f.manager.appForegrounded()
        runCurrent()
        assertEquals(OneShotSynchronizationResult.Covered, worker.await())
        assertEquals(null, f.notifications.lastOrNull())
        assertFalse(socket.closed)
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(1, f.sockets.size)
        assertFalse(socket.closed)
    }

    @Test
    fun `returning to foreground also preserves a socket first opened by a worker`() = runTest {
        val f = Fixture(this)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        val socket = f.sockets.single()
        f.manager.appForegrounded()
        runCurrent()
        assertEquals(OneShotSynchronizationResult.Covered, worker.await())
        assertEquals(null, f.notifications.lastOrNull())
        assertFalse(socket.closed)
        assertEquals(1, f.sockets.size)
    }

    @Test
    fun `idle background socket closes after one grace window without reopening`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        val socket = f.sockets.single()
        socket.progress(false, false)
        runCurrent()
        f.manager.appBackgrounded()
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        advanceTimeBy(35_000)
        runCurrent()
        assertEquals(success, worker.await())
        assertTrue(socket.closed)
        assertEquals(1, f.sockets.size)
        assertEquals(null, f.notifications.last())
        val duplicate = async { f.manager.synchronizeOnce() }
        runCurrent()
        f.sockets.last().progress(false, false)
        runCurrent()
        assertEquals(success, duplicate.await())
    }

    @Test
    fun `only actual work resets the idle reuse deadline`() = runTest {
        val f = Fixture(this)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        val socket = f.sockets.single()
        socket.progress(false, true)
        runCurrent()
        assertFalse(f.manager.syncing.value)
        assertEquals(RequestSyncResult.Success, f.manager.lastSyncResult.value)
        assertEquals(false, f.notifications.last())
        advanceTimeBy(20_000)
        socket.progress(true, true)
        runCurrent()
        assertTrue(f.manager.syncing.value)
        assertEquals(true, f.notifications.last())
        advanceTimeBy(10_000)
        socket.progress(false, true)
        runCurrent()
        advanceTimeBy(34_000)
        socket.progress(false, true)
        f.manager.requestSynchronization()
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(socket.closed)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(success, worker.await())
        assertTrue(socket.closed)
    }

    @Test
    fun `empty wake finishes without spending an idle reuse window`() = runTest {
        val f = Fixture(this)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        f.sockets.single().progress(false, false)
        runCurrent()
        assertEquals(success, worker.await())
        assertTrue(f.sockets.single().closed)
    }

    @Test
    fun `grace is bounded if the scheduled background worker has not started`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        f.manager.appBackgrounded()
        advanceTimeBy(35_000)
        runCurrent()
        assertTrue(f.sockets.single().closed)
        assertEquals(1, f.scheduled)
    }

    @Test
    fun `cancelling the last worker closes the socket before cancellation returns`() = runTest {
        val f = Fixture(this)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        worker.cancelAndJoin()
        assertTrue(f.sockets.single().closed)
        assertFalse(f.manager.syncing.value)
        assertEquals(null, f.notifications.last())
    }

    @Test
    fun `cancelling one worker cannot close another owner's socket or hide its notification`() =
        runTest {
            val f = Fixture(this)
            val first = async { f.manager.synchronizeOnce() }
            val second = async { f.manager.synchronizeOnce() }
            runCurrent()
            first.cancelAndJoin()
            assertFalse(f.sockets.single().closed)
            assertEquals(true, f.notifications.last())
            f.sockets.single().progress(false, false)
            runCurrent()
            assertEquals(success, second.await())
            assertEquals(null, f.notifications.last())
        }

    @Test
    fun `worker cancellation does not cancel or repeat a billable review`() = runTest {
        val reviews = AiReviewCoordinator(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        reviews.launch("request", {}) {
            calls++
            finish.await()
        }
        val f = Fixture(this, reviews.active)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        f.sockets.single().finish(RequestSyncResult.RelayUnavailable("busy", 5_000))
        runCurrent()
        worker.cancelAndJoin()
        assertTrue(reviews.hasActiveReviews)
        assertFalse(reviews.launch("request", {}) { calls++ })
        assertEquals(1, calls)
        val retry = async { f.manager.synchronizeOnce() }
        runCurrent()
        assertFalse(retry.isCompleted)
        assertEquals(1, f.sockets.size)
        finish.complete(Unit)
        runCurrent()
        assertEquals(OneShotSynchronizationResult.Deferred(5_000), retry.await())
        assertFalse(reviews.hasActiveReviews)
    }

    @Test
    fun `fresh wake reconnects while an earlier AI review remains active`() = runTest {
        val active = MutableStateFlow(true)
        val f = Fixture(this, active)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        f.sockets.single().finish(RequestSyncResult.RelayUnavailable("offline"))
        runCurrent()
        assertFalse(worker.isCompleted)
        f.manager.requestSynchronization()
        runCurrent()
        assertEquals(2, f.sockets.size)
        active.value = false
        f.sockets.last().progress(false, false)
        runCurrent()
        assertEquals(success, worker.await())
    }

    @Test
    fun `wake racing a failed background connection still respects the server deadline`() =
        runTest {
            val active = MutableStateFlow(true)
            val f = Fixture(this, active)
            val worker = async { f.manager.synchronizeOnce() }
            runCurrent()
            f.manager.requestSynchronization()
            f.sockets.single().finish(RequestSyncResult.RelayUnavailable("busy", 5_000))
            runCurrent()
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(1, f.sockets.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, f.sockets.size)
            assertFalse(worker.isCompleted)
            active.value = false
            f.sockets.last().progress(false, false)
            runCurrent()
            assertEquals(success, worker.await())
        }

    @Test
    fun `socket failure does not release the background owner before AI finishes`() = runTest {
        val active = MutableStateFlow(true)
        val f = Fixture(this, active)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        f.sockets.single().finish(RequestSyncResult.RelayUnavailable("offline", 5_000))
        runCurrent()
        assertFalse(worker.isCompleted)
        assertEquals(true, f.notifications.last())
        advanceTimeBy(1_000)
        active.value = false
        runCurrent()
        assertEquals(OneShotSynchronizationResult.Deferred(4_000), worker.await())
    }

    @Test
    fun `a review keeps its connection beyond the session budget until it finishes`() = runTest {
        val active = MutableStateFlow(true)
        val f = Fixture(this, active)
        f.manager.appForegrounded()
        runCurrent()
        advanceTimeBy(600_000)
        f.manager.appBackgrounded()
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()
        assertFalse(f.sockets.single().closed)
        advanceTimeBy(20_000)
        runCurrent()
        assertFalse(f.sockets.single().closed)
        assertFalse(worker.isCompleted)
        f.manager.requestSynchronization()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(f.sockets.single().closed)
        active.value = false
        runCurrent()
        assertEquals(continuation, worker.await())
    }

    @Test
    fun `reconnecting does not reset the worker budget`() = runTest {
        val active = MutableStateFlow(true)
        val f = Fixture(this, active)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        advanceTimeBy(30_000)
        f.sockets.single().finish(RequestSyncResult.RelayUnavailable("offline"))
        runCurrent()
        f.manager.requestSynchronization()
        runCurrent()
        advanceTimeBy(89_999)
        runCurrent()
        assertFalse(f.sockets.last().closed)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(f.sockets.last().closed)
        assertFalse(worker.isCompleted)
        f.sockets.last().finish(RequestSyncResult.RelayUnavailable("offline"))
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(3, f.sockets.size)
        assertFalse(f.sockets.last().closed)
        active.value = false
        runCurrent()
        assertEquals(continuation, worker.await())
        assertTrue(f.sockets.last().closed)
    }

    @Test
    fun `fresh work cannot bypass a server deadline`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        f.sockets.single().finish(RequestSyncResult.RelayUnavailable("busy", 5_000))
        runCurrent()
        f.manager.requestSynchronization()
        assertEquals(OneShotSynchronizationResult.Covered, f.manager.synchronizeOnce())
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, f.sockets.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, f.sockets.size)
    }

    @Test
    fun `server deadline survives a controller restart and remains durable work`() = runTest {
        var stored = RelayRetryDeadlineState()
        fun deadline() =
            RelayRetryDeadline(
                { stored },
                { stored = it },
                1,
                { testScheduler.currentTime },
                { testScheduler.currentTime },
            )
        val first = Fixture(this, deadline = deadline())
        val worker = async { first.manager.synchronizeOnce() }
        runCurrent()
        first.sockets.single().finish(RequestSyncResult.RelayUnavailable("busy", 5_000))
        runCurrent()
        worker.await()
        val second = Fixture(this, deadline = deadline())
        assertEquals(OneShotSynchronizationResult.Deferred(5_000), second.manager.synchronizeOnce())
        assertTrue(second.sockets.isEmpty())
        advanceTimeBy(4_999)
        assertEquals(OneShotSynchronizationResult.Deferred(1), second.manager.synchronizeOnce())
        advanceTimeBy(1)
        val retry = async { second.manager.synchronizeOnce() }
        runCurrent()
        assertEquals(1, second.sockets.size)
        retry.cancelAndJoin()
    }

    @Test
    fun `terminal results stop until an explicit wake and a racing wake is not lost`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        f.sockets.single().finish(RequestSyncResult.NoDevice)
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(1, f.sockets.size)
        f.manager.requestSynchronization()
        runCurrent()
        assertEquals(2, f.sockets.size)
        // Deliver the new demand before the failed connection posts its result.
        f.manager.requestSynchronization()
        f.sockets.last().finish(RequestSyncResult.NoDevice)
        runCurrent()
        assertEquals(3, f.sockets.size)
    }

    @Test
    fun `refresh replaces a socket while ordinary wakes preserve it`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        f.manager.requestSynchronization()
        runCurrent()
        assertEquals(OneShotSynchronizationResult.Covered, f.manager.synchronizeOnce())
        assertEquals(1, f.sockets.size)
        f.manager.refresh()
        runCurrent()
        assertTrue(f.sockets.first().closed)
        assertEquals(2, f.sockets.size)
    }

    @Test
    fun `pause joins all connection work and suppresses wakes until resume`() = runTest {
        val f = Fixture(this)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        f.manager.pauseAndJoin()
        assertTrue(f.sockets.single().closed)
        assertEquals(OneShotSynchronizationResult.Covered, worker.await())
        f.manager.requestSynchronization()
        runCurrent()
        assertEquals(1, f.sockets.size)
        assertEquals(0, f.scheduled)
        f.manager.resume()
        runCurrent()
        assertEquals(1, f.scheduled)
        f.manager.resume()
        runCurrent()
        assertEquals(1, f.scheduled)
    }

    @Test
    fun `transient background failures retry without releasing the worker then yield durably`() =
        runTest {
            val f = Fixture(this)
            val worker = async { f.manager.synchronizeOnce() }
            runCurrent()
            repeat(2) { attempt ->
                f.sockets.last().finish(RequestSyncResult.RelayUnavailable("offline"))
                runCurrent()
                assertFalse(worker.isCompleted)
                advanceTimeBy(if (attempt == 0) 3_000 else 6_000)
                runCurrent()
                assertEquals(attempt + 2, f.sockets.size)
            }
            val failure = RequestSyncResult.RelayUnavailable("still offline")
            f.sockets.last().finish(failure)
            runCurrent()
            assertEquals(OneShotSynchronizationResult.Completed(failure), worker.await())
        }

    @Test
    fun `background retry recovers a transient failure and reuses the connection`() = runTest {
        val f = Fixture(this)
        val worker = async { f.manager.synchronizeOnce() }
        runCurrent()
        f.sockets.single().finish(RequestSyncResult.RelayUnavailable("offline"))
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        f.sockets.last().progress(false, true)
        runCurrent()
        assertFalse(worker.isCompleted)
        advanceTimeBy(35_000)
        runCurrent()
        assertEquals(success, worker.await())
    }

    @Test
    fun `foreground failures back off and reset after a healthy connection`() = runTest {
        val f = Fixture(this, reconnectDelay = 100, maximumDelay = 250)
        f.manager.appForegrounded()
        runCurrent()
        repeat(3) { index ->
            f.sockets.last().finish(RequestSyncResult.RelayUnavailable("offline"))
            runCurrent()
            advanceTimeBy(listOf(100L, 200L, 250L)[index] - 1)
            runCurrent()
            assertEquals(index + 1, f.sockets.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(index + 2, f.sockets.size)
        }
        f.sockets.last().progress(false, false)
        runCurrent()
        f.sockets.last().finish(RequestSyncResult.RelayUnavailable("offline"))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(5, f.sockets.size)
    }

    @Test
    fun `a wake arriving while an idle socket closes remains eligible for the next worker`() =
        runTest {
            val f = Fixture(this)
            val first = async { f.manager.synchronizeOnce() }
            runCurrent()
            val oldSocket = f.sockets.single()
            oldSocket.closeGate = CompletableDeferred()
            try {
                oldSocket.progress(false, true)
                runCurrent()
                advanceTimeBy(35_000)
                runCurrent()
                assertFalse(first.isCompleted)
                f.manager.requestSynchronization()
                val next = async {
                    first.await()
                    f.manager.synchronizeOnce()
                }
                runCurrent()
                assertEquals(1, f.scheduled)
                assertEquals(1, f.sockets.size)
                oldSocket.closeGate!!.complete(Unit)
                runCurrent()
                assertEquals(success, first.await())
                assertEquals(2, f.sockets.size)
                oldSocket.progress(false, false)
                runCurrent()
                assertTrue(f.manager.syncing.value)
                assertEquals(true, f.notifications.last())
                next.cancelAndJoin()
            } finally {
                oldSocket.closeGate!!.complete(Unit)
            }
        }

    @Test
    fun `internal failures are reported and do not disable later wakes`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        f.sockets.single().result.completeExceptionally(IllegalStateException("broken storage"))
        runCurrent()
        assertEquals(
            RequestSyncResult.InternalFailure("IllegalStateException"),
            f.manager.lastSyncResult.value,
        )
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(1, f.sockets.size)
        assertEquals(1, f.failures.size)
        f.manager.requestSynchronization()
        runCurrent()
        assertEquals(2, f.sockets.size)
    }

    @Test
    fun `failing to persist a server deadline releases the worker without killing the controller`() =
        runTest {
            val f =
                Fixture(
                    this,
                    deadline =
                        RelayRetryDeadline(
                            { RelayRetryDeadlineState() },
                            { error("storage unavailable") },
                            1,
                            { testScheduler.currentTime },
                            { testScheduler.currentTime },
                        ),
                )
            val worker = async { f.manager.synchronizeOnce() }
            runCurrent()
            f.sockets.single().finish(RequestSyncResult.RelayUnavailable("busy", 5_000))
            runCurrent()
            assertEquals(
                OneShotSynchronizationResult.Completed(
                    RequestSyncResult.InternalFailure("IllegalStateException")
                ),
                worker.await(),
            )
            advanceTimeBy(5_000)
            val next = async { f.manager.synchronizeOnce() }
            runCurrent()
            assertEquals(2, f.sockets.size)
            next.cancelAndJoin()
        }

    @Test
    fun `a queued worker restarts a stopped foreground connection`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        f.sockets.single().finish(RequestSyncResult.NoDevice)
        runCurrent()
        assertEquals(OneShotSynchronizationResult.Covered, f.manager.synchronizeOnce())
        runCurrent()
        assertEquals(2, f.sockets.size)
        assertFalse(f.sockets.last().closed)
    }

    @Test
    fun `foreground wakes interrupt local backoff after failure or completed synchronization`() =
        runTest {
            for (result in
                listOf(RequestSyncResult.RelayUnavailable("offline"), RequestSyncResult.Success)) {
                val f = Fixture(this)
                f.manager.appForegrounded()
                runCurrent()
                f.sockets.single().finish(result)
                runCurrent()
                assertEquals(1, f.sockets.size)
                f.manager.requestSynchronization()
                runCurrent()
                assertEquals(2, f.sockets.size)
            }
        }

    @Test
    fun `returning to the app or refreshing restarts a terminal connection`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        f.sockets.single().finish(RequestSyncResult.RelayRejected(401, "revoked"))
        runCurrent()
        f.manager.appBackgrounded()
        f.manager.appForegrounded()
        runCurrent()
        assertEquals(2, f.sockets.size)
        f.sockets.last().finish(RequestSyncResult.NoDevice)
        runCurrent()
        f.manager.refresh()
        runCurrent()
        assertEquals(3, f.sockets.size)
    }

    @Test
    fun `pause suppresses foreground connections until resumed`() = runTest {
        val f = Fixture(this)
        f.manager.appForegrounded()
        runCurrent()
        f.manager.pauseAndJoin()
        assertTrue(f.sockets.single().closed)
        f.manager.requestSynchronization()
        assertEquals(OneShotSynchronizationResult.Covered, f.manager.synchronizeOnce())
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(1, f.sockets.size)
        f.manager.resume()
        runCurrent()
        assertEquals(2, f.sockets.size)
    }

    @Test
    fun `initial background lifecycle does not start unsolicited work`() = runTest {
        val f = Fixture(this)
        f.manager.appBackgrounded()
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(0, f.scheduled)
        assertTrue(f.sockets.isEmpty())
    }

    @Test
    fun `zero server delay permits local retry without persisting a deadline`() = runTest {
        var writes = 0
        val f =
            Fixture(
                this,
                deadline =
                    RelayRetryDeadline(
                        { RelayRetryDeadlineState() },
                        { writes++ },
                        1,
                        { testScheduler.currentTime },
                        { testScheduler.currentTime },
                    ),
            )
        val first = async { f.manager.synchronizeOnce() }
        runCurrent()
        val result = RequestSyncResult.RelayUnavailable("retry", 0)
        f.sockets.single().finish(result)
        runCurrent()
        assertFalse(first.isCompleted)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(2, f.sockets.size)
        assertEquals(0, writes)
        first.cancelAndJoin()
    }

    private class Socket(private val report: (RelayConnectionProgress) -> Unit) {
        var currentProgress = RelayConnectionProgress(true, false)

        fun progress(processing: Boolean, worked: Boolean) {
            currentProgress = RelayConnectionProgress(processing, worked)
            report(currentProgress)
        }

        val result = CompletableDeferred<RequestSyncResult>()
        var closed = false
        var closeGate: CompletableDeferred<Unit>? = null

        suspend fun run(
            idleChecks: kotlinx.coroutines.channels.ReceiveChannel<RelayConnectionProgress>
        ): RequestSyncResult {
            while (true) {
                val outcome =
                    kotlinx.coroutines.selects.select<RequestSyncResult?> {
                        result.onAwait { it }
                        idleChecks.onReceive {
                            if (it === currentProgress && !it.processing) RequestSyncResult.Success
                            else null
                        }
                    }
                if (outcome != null) return outcome
            }
        }

        fun finish(result: RequestSyncResult) {
            this.result.complete(result)
        }
    }

    private class Fixture(
        scope: TestScope,
        active: kotlinx.coroutines.flow.StateFlow<Boolean> = MutableStateFlow(false),
        deadline: RelayRetryDeadline? = null,
        reconnectDelay: Long = 3_000,
        maximumDelay: Long = 60_000,
    ) {
        val sockets = mutableListOf<Socket>()
        val notifications = mutableListOf<Boolean?>()
        val failures = mutableListOf<Exception>()
        var scheduled = 0
        val manager =
            RequestConnectionManager(
                scope = scope.backgroundScope,
                connect = { idleChecks, progress ->
                    val socket = Socket(progress)
                    sockets += socket
                    try {
                        socket.run(idleChecks)
                    } finally {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            socket.closeGate?.await()
                        }
                        socket.closed = true
                    }
                },
                scheduleBackgroundSynchronization = { scheduled++ },
                relayRetryDeadline =
                    deadline
                        ?: RelayRetryDeadline(
                            { RelayRetryDeadlineState() },
                            {},
                            1,
                            { scope.testScheduler.currentTime },
                            { scope.testScheduler.currentTime },
                        ),
                activeReviews = active,
                displayProcessing = { notifications += it },
                reportInternalFailure = { failures += it },
                elapsedRealtimeMillis = { scope.testScheduler.currentTime },
                reconnectDelayMillis = reconnectDelay,
                maximumReconnectDelayMillis = maximumDelay,
            )
    }

    private val success = OneShotSynchronizationResult.Completed(RequestSyncResult.Success)
    private val continuation =
        OneShotSynchronizationResult.Completed(RequestSyncResult.ContinuationRequired)
}
