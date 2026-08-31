package dev.agentknock.push

import dev.agentknock.relay.RelayPushRegistrationClient
import dev.agentknock.relay.RelayPushRegistrationResult
import dev.agentknock.storage.device.RelayDeviceAuthorizationResult
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource

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
    private val deviceAuthorization: RelayDeviceAuthorizationSource,
    private val relay: RelayPushRegistrationClient,
) {
    suspend fun register(firebaseInstallationId: String): PushRegistrationResult {
        val authorization = when (val result = deviceAuthorization.activeDeviceAuthorization()) {
            is RelayDeviceAuthorizationResult.Available -> result.authorization
            RelayDeviceAuthorizationResult.Missing -> return PushRegistrationResult.NoDevice
            RelayDeviceAuthorizationResult.Unavailable -> {
                return PushRegistrationResult.DeviceCredentialsUnavailable
            }
            RelayDeviceAuthorizationResult.Corrupted -> {
                return PushRegistrationResult.DeviceCredentialsCorrupted
            }
            RelayDeviceAuthorizationResult.UnsupportedEncryption -> {
                return PushRegistrationResult.UnsupportedDeviceCredentialEncryption
            }
        }
        return when (
            val result = relay.register(
                deviceId = authorization.deviceId,
                deviceToken = authorization.deviceToken,
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
