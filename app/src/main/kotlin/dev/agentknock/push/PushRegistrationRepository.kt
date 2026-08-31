package dev.agentknock.push

import dev.agentknock.relay.RelayPushRegistrationClient
import dev.agentknock.relay.RelayPushRegistrationResult
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentialsResult

internal sealed interface PushRegistrationResult {
    data object Registered : PushRegistrationResult

    data object NoDevice : PushRegistrationResult

    data object DeviceCredentialsUnavailable : PushRegistrationResult

    data object DeviceCredentialsCorrupted : PushRegistrationResult

    data object UnsupportedDeviceCredentialEncryption : PushRegistrationResult

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
            RelayDeviceCredentialsResult.Missing -> return PushRegistrationResult.NoDevice
            RelayDeviceCredentialsResult.CredentialsUnavailable -> {
                return PushRegistrationResult.DeviceCredentialsUnavailable
            }
            RelayDeviceCredentialsResult.CredentialsCorrupted -> {
                return PushRegistrationResult.DeviceCredentialsCorrupted
            }
            RelayDeviceCredentialsResult.UnsupportedEncryption -> {
                return PushRegistrationResult.UnsupportedDeviceCredentialEncryption
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
