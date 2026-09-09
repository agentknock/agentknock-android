package dev.agentknock.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable internal data class RelaySubscriptionStatus(val active: Boolean)

internal typealias RelaySubscriptionResult = RelayEndpointResult<RelaySubscriptionStatus>

internal interface RelaySubscriptionClient {
    suspend fun status(
        deviceId: String,
        deviceToken: String,
    ): RelaySubscriptionResult

    suspend fun redeem(
        deviceId: String,
        deviceToken: String,
        redemptionToken: String,
    ): RelaySubscriptionResult

    suspend fun updateFromGooglePlay(
        deviceId: String,
        deviceToken: String,
        purchaseToken: String,
    ): RelaySubscriptionResult
}

internal class HttpRelaySubscriptionClient(
    private val transport: RelayHttpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RelaySubscriptionClient {
    override suspend fun status(
        deviceId: String,
        deviceToken: String,
    ): RelaySubscriptionResult =
        post(
            path = "v1/device/$deviceId/subscription/status",
            deviceToken = deviceToken,
            body = "{}",
        )

    override suspend fun redeem(
        deviceId: String,
        deviceToken: String,
        redemptionToken: String,
    ): RelaySubscriptionResult =
        post(
            path = "v1/device/$deviceId/subscription/update",
            deviceToken = deviceToken,
            body =
                json.encodeToString(
                    SubscriptionRedemptionRequest(
                        source = "redemption",
                        redemptionToken = redemptionToken,
                    )
                ),
        )

    override suspend fun updateFromGooglePlay(
        deviceId: String,
        deviceToken: String,
        purchaseToken: String,
    ): RelaySubscriptionResult =
        post(
            path = "v1/device/$deviceId/subscription/update",
            deviceToken = deviceToken,
            body =
                json.encodeToString(
                    GooglePlaySubscriptionRequest(
                        source = "google_play",
                        purchaseToken = purchaseToken,
                    )
                ),
        )

    private suspend fun post(
        path: String,
        deviceToken: String,
        body: String,
    ): RelaySubscriptionResult =
        transport.post(path, body, bearerToken = deviceToken).decodeSuccess { encoded ->
            json.decodeFromString<RelaySubscriptionStatus>(encoded)
        }
}

@Serializable
private data class SubscriptionRedemptionRequest(
    val source: String,
    @SerialName("redemption_token") val redemptionToken: String,
)

@Serializable
private data class GooglePlaySubscriptionRequest(
    val source: String,
    @SerialName("purchase_token") val purchaseToken: String,
)
