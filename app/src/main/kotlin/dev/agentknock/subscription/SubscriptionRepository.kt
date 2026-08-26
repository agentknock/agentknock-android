package dev.agentknock.subscription

import dev.agentknock.relay.RelaySubscriptionClient
import dev.agentknock.relay.RelaySubscriptionResult
import dev.agentknock.storage.vault.RelayDeviceCredentialSource
import dev.agentknock.storage.vault.RelayDeviceCredentialsResult

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
    private val deviceCredentials: RelayDeviceCredentialSource,
    private val relay: RelaySubscriptionClient,
) {
    suspend fun status(): SubscriptionResult = withCredentials { deviceId, deviceToken ->
        relay.status(deviceId, deviceToken)
    }

    suspend fun redeem(redemptionToken: String): SubscriptionResult =
        withCredentials { deviceId, deviceToken ->
            relay.redeem(deviceId, deviceToken, redemptionToken)
        }

    private suspend fun withCredentials(
        operation: suspend (deviceId: String, deviceToken: String) -> RelaySubscriptionResult,
    ): SubscriptionResult = when (val credentials = deviceCredentials.activeDeviceCredentials()) {
        is RelayDeviceCredentialsResult.Available -> operation(
            credentials.credentials.deviceId,
            credentials.credentials.deviceToken,
        ).toSubscriptionResult()
        RelayDeviceCredentialsResult.Missing -> SubscriptionResult.NoDevice
        RelayDeviceCredentialsResult.CredentialsUnavailable ->
            SubscriptionResult.DeviceCredentialsUnavailable
        RelayDeviceCredentialsResult.CredentialsCorrupted ->
            SubscriptionResult.DeviceCredentialsCorrupted
        RelayDeviceCredentialsResult.UnsupportedEncryption ->
            SubscriptionResult.UnsupportedEncryption
    }
}

private fun RelaySubscriptionResult.toSubscriptionResult(): SubscriptionResult = when (this) {
    is RelaySubscriptionResult.Status -> SubscriptionResult.Status(active)
    is RelaySubscriptionResult.Rejected -> SubscriptionResult.Rejected(status, code, message)
    is RelaySubscriptionResult.Unavailable -> SubscriptionResult.Unavailable(cause.message)
    RelaySubscriptionResult.InvalidResponse -> SubscriptionResult.InvalidRelayResponse
}
