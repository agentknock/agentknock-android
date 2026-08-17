package dev.agentknock.storage

import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import dev.agentknock.storage.vault.DeviceManagementRepository
import dev.agentknock.storage.vault.DeviceManagementResult

internal sealed interface FactoryResetResult {
    data object Reset : FactoryResetResult
    data object NoDevice : FactoryResetResult
    data class RemoteRejected(val status: Int, val code: String?, val message: String?) :
        FactoryResetResult
    data class RemoteUnavailable(val message: String?) : FactoryResetResult
    data object InvalidRemoteResponse : FactoryResetResult
    data object LocalSecretsUnavailable : FactoryResetResult
}

internal class FactoryResetRepository(
    private val database: AgentKnockDatabase,
    private val encryptionKeys: LocalEncryptionKeyManager,
    private val deviceManagement: DeviceManagementRepository,
) {
    suspend fun reset(): FactoryResetResult = when (
        val remote = deviceManagement.deleteRemoteDevice()
    ) {
        DeviceManagementResult.Changed -> resetLocal()
        DeviceManagementResult.NoDevice -> FactoryResetResult.NoDevice
        DeviceManagementResult.SecretsUnavailable,
        DeviceManagementResult.SecretsCorrupted,
        DeviceManagementResult.UnsupportedEncryption,
        -> FactoryResetResult.LocalSecretsUnavailable
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
