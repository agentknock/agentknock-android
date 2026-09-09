package dev.agentknock.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal typealias RelayPushRegistrationResult = RelayEndpointResult<Unit>

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
    override suspend fun register(
        deviceId: String,
        deviceToken: String,
        firebaseInstallationId: String,
    ): RelayPushRegistrationResult {
        val body = json.encodeToString(
            PushRegistrationRequest.serializer(),
            PushRegistrationRequest(firebaseInstallationId),
        )
        return transport.post(
            path = "v1/device/$deviceId/push",
            body = body,
            bearerToken = deviceToken,
        )
            .decodeSuccess { encoded ->
                val response = json.decodeFromString<PushRegistrationResponse>(encoded)
                require(response.state == RelayPushRegistrationState.REGISTERED.wireName)
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
