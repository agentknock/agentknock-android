package dev.agentknock.storage

import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.device.DeviceManagementRepository
import dev.agentknock.storage.device.DeviceManagementResult

internal sealed interface FactoryResetResult {
    data object Reset : FactoryResetResult
    data object NoDevice : FactoryResetResult
    data class RemoteRejected(val status: Int, val code: String?, val message: String?) :
        FactoryResetResult
    data class RemoteUnavailable(val message: String?) : FactoryResetResult
    data object InvalidRemoteResponse : FactoryResetResult
    data object DeviceCredentialsUnavailable : FactoryResetResult
}

internal class FactoryResetRepository(
    private val database: AgentknockDatabase,
    private val encryptionKeys: VaultKeyManager,
    private val deviceManagement: DeviceManagementRepository,
) {
    suspend fun reset(): FactoryResetResult = when (
        val remote = deviceManagement.deleteRemoteDevice()
    ) {
        DeviceManagementResult.Changed -> resetLocal()
        DeviceManagementResult.NoDevice -> FactoryResetResult.NoDevice
        DeviceManagementResult.CredentialsUnavailable,
        DeviceManagementResult.CredentialsCorrupted,
        DeviceManagementResult.UnsupportedEncryption,
        -> FactoryResetResult.DeviceCredentialsUnavailable
        is DeviceManagementResult.Rejected -> FactoryResetResult.RemoteRejected(
            remote.status,
            remote.code,
            remote.message,
        )
        is DeviceManagementResult.Unavailable -> FactoryResetResult.RemoteUnavailable(
            remote.message,
        )
        DeviceManagementResult.InvalidResponse -> FactoryResetResult.InvalidRemoteResponse
    }

    suspend fun resetLocalOnly(): FactoryResetResult = resetLocal()

    private suspend fun resetLocal(): FactoryResetResult {
        encryptionKeys.reset { database.clearAllTables() }
        return FactoryResetResult.Reset
    }
}
