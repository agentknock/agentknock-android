package dev.agentknock.push

import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.relay.RelayPushRegistrationClient
import dev.agentknock.relay.RelayPushRegistrationResult
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.storage.device.RelayDeviceAuthorizationResult
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val requestRegistration: () -> Unit,
) {
    private val _registrationState = MutableStateFlow<RelayPushRegistrationState?>(null)

    val registrationState: StateFlow<RelayPushRegistrationState?> =
        _registrationState.asStateFlow()

    fun updateRelayState(state: RelayPushRegistrationState) {
        _registrationState.value = state
        if (state != RelayPushRegistrationState.REGISTERED) requestRegistration()
    }

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
            is RelayEndpointResult.Success -> PushRegistrationResult.Registered
            is RelayEndpointResult.Rejected -> PushRegistrationResult.RelayRejected(
                status = result.status,
                code = result.code,
                message = result.message,
            )
            is RelayEndpointResult.Unavailable -> {
                PushRegistrationResult.RelayUnavailable(result.cause.message)
            }
            RelayEndpointResult.InvalidResponse -> {
                PushRegistrationResult.InvalidRelayResponse
            }
        }
    }
}
