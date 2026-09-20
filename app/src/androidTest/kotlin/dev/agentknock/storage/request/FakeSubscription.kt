package dev.agentknock.storage.request

import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.relay.RelaySubscriptionClient
import dev.agentknock.relay.RelaySubscriptionResult
import dev.agentknock.storage.device.DeviceCredentialResult
import dev.agentknock.storage.device.RelayDeviceAuthorization
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource
import dev.agentknock.subscription.SubscriptionRepository

internal class FakeSubscription(deviceId: String) {
    var statusCalls = 0
    val repository =
        SubscriptionRepository(
            deviceAuthorization =
                RelayDeviceAuthorizationSource {
                    DeviceCredentialResult.Available(
                        RelayDeviceAuthorization("device-identity", deviceId, "device-token")
                    )
                },
            relay =
                object : RelaySubscriptionClient {
                    override suspend fun status(
                        deviceId: String,
                        deviceToken: String,
                    ): RelaySubscriptionResult {
                        statusCalls++
                        return RelayEndpointResult.Unavailable(
                            java.io.IOException("Status endpoint unavailable")
                        )
                    }

                    override suspend fun redeem(
                        deviceId: String,
                        deviceToken: String,
                        redemptionToken: String,
                    ): RelaySubscriptionResult = error("No redemption expected")

                    override suspend fun updateFromGooglePlay(
                        deviceId: String,
                        deviceToken: String,
                        purchaseToken: String,
                    ): RelaySubscriptionResult = error("No purchase expected")
                },
        )
}
