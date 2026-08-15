package dev.agentknock.push

import dev.agentknock.relay.RelayPushRegistrationClient
import dev.agentknock.relay.RelayPushRegistrationResult
import dev.agentknock.storage.vault.RelayDeviceCredentialSource
import dev.agentknock.storage.vault.RelayDeviceCredentialsResult

internal sealed interface PushRegistrationResult {
    data object Registered : PushRegistrationResult

    data object NoVault : PushRegistrationResult

    data object VaultSecretsUnavailable : PushRegistrationResult

    data object VaultSecretsCorrupted : PushRegistrationResult

    data object UnsupportedVaultEncryption : PushRegistrationResult

    data class RelayRejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : PushRegistrationResult

    data class RelayUnavailable(val message: String?) : PushRegistrationResult

    data object InvalidRelayResponse : PushRegistrationResult
}

internal class PushRegistrationRepository(
    private val deviceCredentials: RelayDeviceCredentialSource,
    private val relay: RelayPushRegistrationClient,
) {
    suspend fun register(firebaseInstallationId: String): PushRegistrationResult {
        val credentials = when (val result = deviceCredentials.activeDeviceCredentials()) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            RelayDeviceCredentialsResult.Missing -> return PushRegistrationResult.NoVault
            RelayDeviceCredentialsResult.SecretsUnavailable -> {
                return PushRegistrationResult.VaultSecretsUnavailable
            }
            RelayDeviceCredentialsResult.SecretsCorrupted -> {
                return PushRegistrationResult.VaultSecretsCorrupted
            }
            RelayDeviceCredentialsResult.UnsupportedEncryption -> {
                return PushRegistrationResult.UnsupportedVaultEncryption
            }
        }
        return when (
            val result = relay.register(
                deviceId = credentials.deviceId,
                deviceToken = credentials.deviceToken,
                firebaseInstallationId = firebaseInstallationId,
            )
        ) {
            RelayPushRegistrationResult.Registered -> PushRegistrationResult.Registered
            is RelayPushRegistrationResult.Rejected -> PushRegistrationResult.RelayRejected(
                status = result.status,
                code = result.code,
                message = result.message,
            )
            is RelayPushRegistrationResult.Unavailable -> {
                PushRegistrationResult.RelayUnavailable(result.cause.message)
            }
            RelayPushRegistrationResult.InvalidResponse -> {
                PushRegistrationResult.InvalidRelayResponse
            }
        }
    }
}
