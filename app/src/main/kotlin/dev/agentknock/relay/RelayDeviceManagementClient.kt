package dev.agentknock.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

internal sealed interface RelayDeviceManagementResult {
    data object Changed : RelayDeviceManagementResult

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : RelayDeviceManagementResult

    data class Unavailable(val cause: IOException) : RelayDeviceManagementResult

    data object InvalidResponse : RelayDeviceManagementResult
}

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
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(RelayHttpTransport(client, relayUrl, json, dispatcher), json)

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
    ): RelayDeviceManagementResult = when (
        val result = transport.post(path, body, bearerToken = deviceToken)
    ) {
        is RelayHttpResult.Success -> if (
            runCatching { validResponse(result.body) }.getOrDefault(false)
        ) {
            RelayDeviceManagementResult.Changed
        } else {
            RelayDeviceManagementResult.InvalidResponse
        }
        is RelayHttpResult.Rejected -> RelayDeviceManagementResult.Rejected(
            status = result.status,
            code = result.code,
            message = result.message,
        )
        is RelayHttpResult.Unavailable -> RelayDeviceManagementResult.Unavailable(result.cause)
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
