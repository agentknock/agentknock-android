package dev.agentknock.storage

import dev.agentknock.storage.device.DeviceManagementResult
import dev.agentknock.storage.device.DeviceOperationGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FactoryResetCoordinatorTest {
    @Test
    fun `confirmed remote deletion quiesces erases and resumes in order`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            deleteRemoteDevice = {
                events += "delete"
                DeviceManagementResult.Changed
            },
            awaitReady = { events += "ready" },
            pauseRuntime = { events += "pause" },
            recoverInterruptedWork = { events += "recover" },
            clearLocalState = { events += "clear" },
            resumeRuntime = { events += "resume:$it" },
        )

        assertEquals(FactoryResetResult.Reset, coordinator.reset())
        assertEquals(
            listOf("ready", "pause", "recover", "delete", "clear", "resume:true"),
            events,
        )
    }

    @Test
    fun `missing local device requires explicit local reset`() = runTest {
        var cleared = false
        val coordinator = coordinator(
            deleteRemoteDevice = { DeviceManagementResult.NoDevice },
            clearLocalState = { cleared = true },
        )

        assertEquals(FactoryResetResult.NoDevice, coordinator.reset())
        assertFalse(cleared)
    }

    @Test
    fun `remote failure retains local state and resumes the existing runtime`() = runTest {
        var cleared = false
        val resumed = mutableListOf<Boolean>()
        val coordinator = coordinator(
            deleteRemoteDevice = { DeviceManagementResult.Unavailable("offline") },
            clearLocalState = { cleared = true },
            resumeRuntime = resumed::add,
        )

        assertEquals(
            FactoryResetResult.RemoteUnavailable("offline"),
            coordinator.reset(),
        )
        assertFalse(cleared)
        assertEquals(listOf(false), resumed)
    }

    @Test
    fun `local reset skips relay deletion`() = runTest {
        var remoteCalls = 0
        val coordinator = coordinator(
            deleteRemoteDevice = {
                remoteCalls += 1
                DeviceManagementResult.Changed
            },
        )

        assertEquals(FactoryResetResult.Reset, coordinator.resetLocalOnly())
        assertEquals(0, remoteCalls)
    }

    @Test
    fun `local reset can recover after normal readiness failed`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            awaitReady = { error("broken storage") },
            awaitReadyForRecovery = { events += "settled" },
            clearLocalState = { events += "clear" },
        )

        assertEquals(FactoryResetResult.Reset, coordinator.resetLocalOnly())
        assertEquals(listOf("settled", "clear"), events)
    }

    @Test
    fun `cancellation after remote confirmation cannot interrupt local erasure`() = runTest {
        val clearStarted = CompletableDeferred<Unit>()
        val finishClear = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            deleteRemoteDevice = { DeviceManagementResult.Changed },
            clearLocalState = {
                events += "clear:start"
                clearStarted.complete(Unit)
                finishClear.await()
                events += "clear:end"
            },
            resumeRuntime = { events += "resume:$it" },
        )

        val reset = launch { coordinator.reset() }
        clearStarted.await()
        reset.cancel()
        runCurrent()
        assertFalse(reset.isCompleted)

        finishClear.complete(Unit)
        reset.join()
        assertEquals(listOf("clear:start", "clear:end", "resume:true"), events)
    }

    @Test
    fun `cancellation before remote confirmation retains local state`() = runTest {
        val deleteStarted = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        var cleared = false
        val resumed = mutableListOf<Boolean>()
        val coordinator = coordinator(
            deleteRemoteDevice = {
                deleteStarted.complete(Unit)
                finishDelete.await()
                DeviceManagementResult.Changed
            },
            clearLocalState = { cleared = true },
            resumeRuntime = resumed::add,
        )

        val reset = launch { coordinator.reset() }
        deleteStarted.await()
        reset.cancelAndJoin()

        assertFalse(cleared)
        assertEquals(listOf(false), resumed)
    }

    @Test
    fun `local clear failure is not reported as a reset`() = runTest {
        val resumed = mutableListOf<Boolean>()
        val coordinator = coordinator(
            clearLocalState = { error("keystore failure") },
            resumeRuntime = resumed::add,
        )
        var failure: Throwable? = null

        try {
            coordinator.reset()
        } catch (caught: Throwable) {
            failure = caught
        }

        assertEquals("keystore failure", failure?.message)
        assertEquals(listOf(false), resumed)
    }

    @Test
    fun `failure after irreversible clear still resumes from empty state`() = runTest {
        val resumed = mutableListOf<Boolean>()
        val coordinator = coordinator(
            clearLocalStateOperation = { markIrreversiblyCleared ->
                markIrreversiblyCleared()
                error("keystore deletion failed")
            },
            resumeRuntime = resumed::add,
        )

        assertEquals(FactoryResetResult.Reset, coordinator.reset())
        assertEquals(listOf(true), resumed)
    }

    @Test
    fun `partial pause failure still resumes runtime`() = runTest {
        val resumed = mutableListOf<Boolean>()
        val coordinator = coordinator(
            pauseRuntime = { error("pause failure") },
            resumeRuntime = resumed::add,
        )
        var failure: Throwable? = null

        try {
            coordinator.reset()
        } catch (caught: Throwable) {
            failure = caught
        }

        assertEquals("pause failure", failure?.message)
        assertEquals(listOf(false), resumed)
    }

    @Test
    fun `concurrent reset attempts are serialized`() = runTest {
        val firstDeleteStarted = CompletableDeferred<Unit>()
        val finishFirstDelete = CompletableDeferred<Unit>()
        var deleteCalls = 0
        val coordinator = coordinator(
            deleteRemoteDevice = {
                deleteCalls += 1
                if (deleteCalls == 1) {
                    firstDeleteStarted.complete(Unit)
                    finishFirstDelete.await()
                }
                DeviceManagementResult.Changed
            },
        )

        val first = async { coordinator.reset() }
        firstDeleteStarted.await()
        val second = async { coordinator.reset() }
        runCurrent()
        assertEquals(1, deleteCalls)

        finishFirstDelete.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, deleteCalls)
    }

    @Test
    fun `reset waits for an in-flight device lifecycle operation`() = runTest {
        val operations = DeviceOperationGate()
        val claimStarted = CompletableDeferred<Unit>()
        val finishClaim = CompletableDeferred<Unit>()
        var deleteCalls = 0
        val claim = launch {
            operations.run {
                claimStarted.complete(Unit)
                finishClaim.await()
            }
        }
        claimStarted.await()
        val reset = async {
            coordinator(
                deleteRemoteDevice = {
                    deleteCalls += 1
                    DeviceManagementResult.Changed
                },
                deviceOperations = operations,
            ).reset()
        }

        runCurrent()
        assertEquals(0, deleteCalls)

        finishClaim.complete(Unit)
        claim.join()
        assertEquals(FactoryResetResult.Reset, reset.await())
        assertEquals(1, deleteCalls)
    }

    private fun coordinator(
        deleteRemoteDevice: suspend () -> DeviceManagementResult = {
            DeviceManagementResult.Changed
        },
        awaitReady: suspend () -> Unit = {},
        awaitReadyForRecovery: suspend () -> Unit = awaitReady,
        pauseRuntime: suspend () -> Unit = {},
        recoverInterruptedWork: suspend () -> Unit = {},
        clearLocalState: suspend () -> Unit = {},
        clearLocalStateOperation: (suspend (() -> Unit) -> Unit)? = null,
        resumeRuntime: suspend (Boolean) -> Unit = {},
        deviceOperations: DeviceOperationGate = DeviceOperationGate(),
    ) = FactoryResetCoordinator(
        deleteRemoteDevice = deleteRemoteDevice,
        awaitReady = awaitReady,
        awaitReadyForRecovery = awaitReadyForRecovery,
        pauseRuntime = pauseRuntime,
        recoverInterruptedWork = recoverInterruptedWork,
        clearLocalState = clearLocalStateOperation ?: { markIrreversiblyCleared ->
            clearLocalState()
            markIrreversiblyCleared()
        },
        resumeRuntime = resumeRuntime,
        deviceOperations = deviceOperations,
    )
}
