package dev.agentknock.subscription

import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.relay.RelaySubscriptionClient
import dev.agentknock.relay.RelaySubscriptionResult
import dev.agentknock.storage.device.RelayDeviceAuthorizationResult
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource

internal sealed interface SubscriptionResult {
    data class Status(val active: Boolean) : SubscriptionResult

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : SubscriptionResult

    data class Unavailable(val message: String?) : SubscriptionResult

    data object NoDevice : SubscriptionResult

    data object DeviceCredentialsUnavailable : SubscriptionResult

    data object DeviceCredentialsCorrupted : SubscriptionResult

    data object UnsupportedEncryption : SubscriptionResult

    data object InvalidRelayResponse : SubscriptionResult
}

internal class SubscriptionRepository(
    private val deviceAuthorization: RelayDeviceAuthorizationSource,
    private val relay: RelaySubscriptionClient,
) {
    suspend fun status(): SubscriptionResult = withAuthorization { deviceId, deviceToken ->
        relay.status(deviceId, deviceToken)
    }

    suspend fun redeem(redemptionToken: String): SubscriptionResult =
        withAuthorization { deviceId, deviceToken ->
            relay.redeem(deviceId, deviceToken, redemptionToken)
        }

    private suspend fun withAuthorization(
        operation: suspend (deviceId: String, deviceToken: String) -> RelaySubscriptionResult,
    ): SubscriptionResult = when (val result = deviceAuthorization.activeDeviceAuthorization()) {
        is RelayDeviceAuthorizationResult.Available -> operation(
            result.authorization.deviceId,
            result.authorization.deviceToken,
        ).toSubscriptionResult()
        RelayDeviceAuthorizationResult.Missing -> SubscriptionResult.NoDevice
        RelayDeviceAuthorizationResult.Unavailable ->
            SubscriptionResult.DeviceCredentialsUnavailable
        RelayDeviceAuthorizationResult.Corrupted ->
            SubscriptionResult.DeviceCredentialsCorrupted
        RelayDeviceAuthorizationResult.UnsupportedEncryption ->
            SubscriptionResult.UnsupportedEncryption
    }
}

private fun RelaySubscriptionResult.toSubscriptionResult(): SubscriptionResult = when (this) {
    is RelayEndpointResult.Success -> SubscriptionResult.Status(value.active)
    is RelayEndpointResult.Rejected -> SubscriptionResult.Rejected(status, code, message)
    is RelayEndpointResult.Unavailable -> SubscriptionResult.Unavailable(cause.message)
    RelayEndpointResult.InvalidResponse -> SubscriptionResult.InvalidRelayResponse
}
