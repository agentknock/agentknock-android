package dev.agentknock.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

internal sealed interface RelaySubscriptionResult {
    data class Status(val active: Boolean) : RelaySubscriptionResult

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : RelaySubscriptionResult

    data class Unavailable(val cause: IOException) : RelaySubscriptionResult

    data object InvalidResponse : RelaySubscriptionResult
}

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
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(RelayHttpTransport(client, relayUrl, json, dispatcher), json)

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
    ): RelaySubscriptionResult = when (
        val result = transport.post(path, body, bearerToken = deviceToken)
    ) {
        is RelayHttpResult.Success -> runCatching {
            json.decodeFromString<SubscriptionStatusResponse>(result.body)
        }.fold(
            onSuccess = { RelaySubscriptionResult.Status(it.active) },
            onFailure = { RelaySubscriptionResult.InvalidResponse },
        )
        is RelayHttpResult.Rejected -> RelaySubscriptionResult.Rejected(
            status = result.status,
            code = result.code,
            message = result.message,
        )
        is RelayHttpResult.Unavailable -> RelaySubscriptionResult.Unavailable(result.cause)
    }
}

@Serializable
private data class SubscriptionRedemptionRequest(
    val source: String,
    @SerialName("redemption_token") val redemptionToken: String,
)

@Serializable
private data class SubscriptionStatusResponse(val active: Boolean)
