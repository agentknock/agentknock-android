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

internal sealed interface RelayPushRegistrationResult {
    data object Registered : RelayPushRegistrationResult

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : RelayPushRegistrationResult

    data class Unavailable(val cause: IOException) : RelayPushRegistrationResult

    data object InvalidResponse : RelayPushRegistrationResult
}

internal interface RelayPushRegistrationClient {
    suspend fun register(
        deviceId: String,
        deviceToken: String,
        firebaseInstallationId: String,
    ): RelayPushRegistrationResult
}

internal class HttpRelayPushRegistrationClient(
    private val client: OkHttpClient,
    private val relayUrl: String = "https://relay.agentknock.dev/",
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelayPushRegistrationClient {
    override suspend fun register(
        deviceId: String,
        deviceToken: String,
        firebaseInstallationId: String,
    ): RelayPushRegistrationResult = withContext(dispatcher) {
        val body = json.encodeToString(
            PushRegistrationRequest.serializer(),
            PushRegistrationRequest(firebaseInstallationId),
        )
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/v1/device/$deviceId/push")
            .header("Authorization", "Bearer $deviceToken")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful) {
                    val registered = runCatching {
                        json.decodeFromString(
                            PushRegistrationResponse.serializer(),
                            responseBody,
                        ).state == RelayPushRegistrationState.REGISTERED.wireName
                    }.getOrDefault(false)
                    if (registered) {
                        RelayPushRegistrationResult.Registered
                    } else {
                        RelayPushRegistrationResult.InvalidResponse
                    }
                } else {
                    val error = runCatching {
                        json.decodeFromString(PushRegistrationError.serializer(), responseBody)
                    }.getOrNull()
                    RelayPushRegistrationResult.Rejected(
                        status = response.code,
                        code = error?.code,
                        message = error?.message,
                    )
                }
            }
        } catch (exception: IOException) {
            RelayPushRegistrationResult.Unavailable(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
private data class PushRegistrationRequest(
    @SerialName("fid") val firebaseInstallationId: String,
)

@Serializable
private data class PushRegistrationResponse(
    @SerialName("push_registration") val state: String,
)

@Serializable
private data class PushRegistrationError(
    @SerialName("error") val code: String,
    val message: String,
)
