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

internal sealed interface RelayClaimResult {
    data object Claimed : RelayClaimResult

    data object AddressUnavailable : RelayClaimResult

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : RelayClaimResult

    data class Unavailable(val cause: IOException) : RelayClaimResult

    data object InvalidResponse : RelayClaimResult
}

internal interface RelayClaimClient {
    suspend fun claim(routeId: String, authenticationToken: String): RelayClaimResult
}

internal class HttpRelayClaimClient(
    private val client: OkHttpClient,
    private val relayUrl: String = "https://relay.agentknock.dev/",
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelayClaimClient {
    override suspend fun claim(
        routeId: String,
        authenticationToken: String,
    ): RelayClaimResult = withContext(dispatcher) {
        val body = json.encodeToString(
            ClaimRequest.serializer(),
            ClaimRequest(
                authenticationToken = authenticationToken,
                attestation = DevelopmentAttestation(development = true),
            ),
        )
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/v1/route/$routeId/claim")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use {
                val responseBody = it.body.string()
                when {
                    it.isSuccessful -> {
                        val claimed = runCatching {
                            json.decodeFromString(ClaimResponse.serializer(), responseBody)
                        }.getOrNull()
                        if (claimed?.claimed == true) {
                            RelayClaimResult.Claimed
                        } else {
                            RelayClaimResult.InvalidResponse
                        }
                    }
                    it.code == 409 -> RelayClaimResult.AddressUnavailable
                    else -> {
                        val error = runCatching {
                            json.decodeFromString(RelayError.serializer(), responseBody)
                        }.getOrNull()
                        RelayClaimResult.Rejected(
                            status = it.code,
                            code = error?.error,
                            message = error?.message,
                        )
                    }
                }
            }
        } catch (exception: IOException) {
            return@withContext RelayClaimResult.Unavailable(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
private data class ClaimRequest(
    @SerialName("authentication_token")
    val authenticationToken: String,
    val attestation: DevelopmentAttestation,
)

@Serializable
private data class DevelopmentAttestation(
    val development: Boolean,
)

@Serializable
private data class ClaimResponse(
    val claimed: Boolean,
)

@Serializable
private data class RelayError(
    val error: String,
    val message: String,
)
