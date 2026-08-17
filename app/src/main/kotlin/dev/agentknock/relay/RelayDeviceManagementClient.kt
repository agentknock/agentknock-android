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
    private val client: OkHttpClient,
    private val relayUrl: String = "https://relay.agentknock.dev/",
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelayDeviceManagementClient {
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
    ): RelayDeviceManagementResult = withContext(dispatcher) {
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/$path")
            .header("Authorization", "Bearer $deviceToken")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful) {
                    if (runCatching { validResponse(responseBody) }.getOrDefault(false)) {
                        RelayDeviceManagementResult.Changed
                    } else {
                        RelayDeviceManagementResult.InvalidResponse
                    }
                } else {
                    val error = runCatching {
                        json.decodeFromString<ManagementErrorResponse>(responseBody)
                    }.getOrNull()
                    RelayDeviceManagementResult.Rejected(
                        status = response.code,
                        code = error?.code,
                        message = error?.message,
                    )
                }
            }
        } catch (exception: IOException) {
            RelayDeviceManagementResult.Unavailable(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
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

@Serializable
private data class ManagementErrorResponse(
    @SerialName("error") val code: String,
    val message: String,
)
