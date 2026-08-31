package dev.agentknock.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

internal typealias RelayDeviceManagementResult = RelayEndpointResult<Unit>

internal interface RelayDeviceManagementClient {
    suspend fun setPairingEnabled(
        deviceId: String,
        deviceToken: String,
        enabled: Boolean,
    ): RelayDeviceManagementResult

    suspend fun deleteDevice(
        deviceId: String,
        deviceToken: String,
    ): RelayDeviceManagementResult
}

internal class HttpRelayDeviceManagementClient(
    private val transport: RelayHttpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RelayDeviceManagementClient {
    constructor(
        client: OkHttpClient,
        relayUrl: String = DEFAULT_RELAY_URL,
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(RelayHttpTransport(client, relayUrl, json), json)

    override suspend fun setPairingEnabled(
        deviceId: String,
        deviceToken: String,
        enabled: Boolean,
    ): RelayDeviceManagementResult = post(
        path = "v1/device/$deviceId/pairing",
        deviceToken = deviceToken,
        body = json.encodeToString(PairingAdmissionRequest(enabled)),
    ) { body -> json.decodeFromString<PairingAdmissionResponse>(body).enabled == enabled }

    override suspend fun deleteDevice(
        deviceId: String,
        deviceToken: String,
    ): RelayDeviceManagementResult = post(
        path = "v1/device/$deviceId/delete",
        deviceToken = deviceToken,
        body = "{}",
    ) { body -> json.decodeFromString<DeviceDeletionResponse>(body).deleted }

    private suspend fun post(
        path: String,
        deviceToken: String,
        body: String,
        validResponse: (String) -> Boolean,
    ): RelayDeviceManagementResult = transport.post(path, body, bearerToken = deviceToken)
        .decodeSuccess { encoded ->
            require(validResponse(encoded))
        }
}

@Serializable
private data class PairingAdmissionRequest(val enabled: Boolean)

@Serializable
private data class PairingAdmissionResponse(
    @SerialName("pairing_enabled") val enabled: Boolean,
)

@Serializable
private data class DeviceDeletionResponse(val deleted: Boolean)
