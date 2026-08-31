package dev.agentknock.storage

import dev.agentknock.storage.device.DeviceManagementResult
import dev.agentknock.storage.device.DeviceOperationGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface FactoryResetResult {
    data object Reset : FactoryResetResult
    data object NoDevice : FactoryResetResult
    data class RemoteRejected(val status: Int, val code: String?, val message: String?) :
        FactoryResetResult
    data class RemoteUnavailable(val message: String?) : FactoryResetResult
    data object InvalidRemoteResponse : FactoryResetResult
    data object DeviceCredentialsUnavailable : FactoryResetResult
}

/**
 * Serializes remote deletion and local erasure with the runtime components that can use storage.
 *
 * Once the relay confirms deletion, local erasure is non-cancellable. A caller cannot leave a
 * deleted relay device pointing at retained local state. Missing local authorization is not relay
 * confirmation and requires the caller to offer an explicit local-only recovery path.
 */
internal class FactoryResetCoordinator(
    private val deleteRemoteDevice: suspend () -> DeviceManagementResult,
    private val awaitReady: suspend () -> Unit,
    private val awaitReadyForRecovery: suspend () -> Unit,
    private val pauseRuntime: suspend () -> Unit,
    private val recoverInterruptedWork: suspend () -> Unit,
    private val clearLocalState: suspend (markIrreversiblyCleared: () -> Unit) -> Unit,
    private val resumeRuntime: suspend (localStateCleared: Boolean) -> Unit,
    private val deviceOperations: DeviceOperationGate,
) {
    private val mutex = Mutex()

    suspend fun reset(): FactoryResetResult = reset(deleteRemote = true)

    suspend fun resetLocalOnly(): FactoryResetResult = reset(deleteRemote = false)

    private suspend fun reset(deleteRemote: Boolean): FactoryResetResult = mutex.withLock {
        deviceOperations.run {
            if (deleteRemote) awaitReady() else awaitReadyForRecovery()
            var localStateCleared = false
            try {
                pauseRuntime()
                if (deleteRemote) recoverInterruptedWork()
                val remoteResult = if (deleteRemote) deleteRemoteDevice() else null
                when (remoteResult) {
                    null,
                    DeviceManagementResult.Changed,
                    -> withContext(NonCancellable) {
                        try {
                            clearLocalState { localStateCleared = true }
                            FactoryResetResult.Reset
                        } catch (failure: Throwable) {
                            if (localStateCleared) {
                                // Room has committed the wipe. Old Keystore aliases contain no
                                // usable data, and runtime recovery must proceed from empty state.
                                FactoryResetResult.Reset
                            } else {
                                throw failure
                            }
                        }
                    }

                    DeviceManagementResult.NoDevice -> FactoryResetResult.NoDevice

                    DeviceManagementResult.CredentialsUnavailable,
                    DeviceManagementResult.CredentialsCorrupted,
                    DeviceManagementResult.UnsupportedEncryption,
                    -> FactoryResetResult.DeviceCredentialsUnavailable

                    is DeviceManagementResult.Rejected -> FactoryResetResult.RemoteRejected(
                        remoteResult.status,
                        remoteResult.code,
                        remoteResult.message,
                    )

                    is DeviceManagementResult.Unavailable -> FactoryResetResult.RemoteUnavailable(
                        remoteResult.message,
                    )

                    DeviceManagementResult.InvalidResponse ->
                        FactoryResetResult.InvalidRemoteResponse
                }
            } finally {
                withContext(NonCancellable) {
                    resumeRuntime(localStateCleared)
                }
            }
        }
    }
}
