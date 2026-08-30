package dev.agentknock.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

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
    private val transport: RelayHttpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RelayPushRegistrationClient {
    constructor(
        client: OkHttpClient,
        relayUrl: String = DEFAULT_RELAY_URL,
        json: Json = Json { ignoreUnknownKeys = true },
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(RelayHttpTransport(client, relayUrl, json, dispatcher), json)

    override suspend fun register(
        deviceId: String,
        deviceToken: String,
        firebaseInstallationId: String,
    ): RelayPushRegistrationResult {
        val body = json.encodeToString(
            PushRegistrationRequest.serializer(),
            PushRegistrationRequest(firebaseInstallationId),
        )
        return when (
            val result = transport.post(
                path = "v1/device/$deviceId/push",
                body = body,
                bearerToken = deviceToken,
            )
        ) {
            is RelayHttpResult.Success -> {
                val registered = runCatching {
                    json.decodeFromString<PushRegistrationResponse>(result.body).state ==
                        RelayPushRegistrationState.REGISTERED.wireName
                }.getOrDefault(false)
                if (registered) {
                    RelayPushRegistrationResult.Registered
                } else {
                    RelayPushRegistrationResult.InvalidResponse
                }
            }
            is RelayHttpResult.Rejected -> RelayPushRegistrationResult.Rejected(
                status = result.status,
                code = result.code,
                message = result.message,
            )
            is RelayHttpResult.Unavailable -> RelayPushRegistrationResult.Unavailable(result.cause)
        }
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
