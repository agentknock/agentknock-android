package dev.agentknock.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    private val client: OkHttpClient,
    private val relayUrl: String = "https://relay.agentknock.dev/",
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelaySubscriptionClient {
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
    ): RelaySubscriptionResult = withContext(dispatcher) {
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/$path")
            .header("Authorization", "Bearer $deviceToken")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful) {
                    runCatching {
                        json.decodeFromString<SubscriptionStatusResponse>(responseBody)
                    }.fold(
                        onSuccess = { RelaySubscriptionResult.Status(it.active) },
                        onFailure = { RelaySubscriptionResult.InvalidResponse },
                    )
                } else {
                    val error = runCatching {
                        json.decodeFromString<SubscriptionErrorResponse>(responseBody)
                    }.getOrNull()
                    RelaySubscriptionResult.Rejected(
                        status = response.code,
                        code = error?.code,
                        message = error?.message,
                    )
                }
            }
        } catch (exception: IOException) {
            RelaySubscriptionResult.Unavailable(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
private data class SubscriptionRedemptionRequest(
    val source: String,
    @SerialName("redemption_token") val redemptionToken: String,
)

@Serializable
private data class SubscriptionStatusResponse(val active: Boolean)

@Serializable
private data class SubscriptionErrorResponse(
    @SerialName("error") val code: String,
    val message: String,
)
