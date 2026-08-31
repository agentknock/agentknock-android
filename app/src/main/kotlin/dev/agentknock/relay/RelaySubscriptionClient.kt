package dev.agentknock.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

internal data class RelaySubscriptionStatus(val active: Boolean)

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
}

internal class HttpRelaySubscriptionClient(
    private val transport: RelayHttpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RelaySubscriptionClient {
    constructor(
        client: OkHttpClient,
        relayUrl: String = DEFAULT_RELAY_URL,
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(RelayHttpTransport(client, relayUrl, json), json)

    override suspend fun status(
        deviceId: String,
        deviceToken: String,
    ): RelaySubscriptionResult = post(
        path = "v1/device/$deviceId/subscription/status",
        deviceToken = deviceToken,
        body = "{}",
    )

    override suspend fun redeem(
        deviceId: String,
        deviceToken: String,
        redemptionToken: String,
    ): RelaySubscriptionResult = post(
        path = "v1/device/$deviceId/subscription/update",
        deviceToken = deviceToken,
        body = json.encodeToString(
            SubscriptionRedemptionRequest(
                source = "redemption",
                redemptionToken = redemptionToken,
            ),
        ),
    )

    private suspend fun post(
        path: String,
        deviceToken: String,
        body: String,
    ): RelaySubscriptionResult = transport.post(path, body, bearerToken = deviceToken)
        .decodeSuccess { encoded ->
            RelaySubscriptionStatus(
                active = json.decodeFromString<SubscriptionStatusResponse>(encoded).active,
            )
        }
}

@Serializable
private data class SubscriptionRedemptionRequest(
    val source: String,
    @SerialName("redemption_token") val redemptionToken: String,
)

@Serializable
private data class SubscriptionStatusResponse(val active: Boolean)
