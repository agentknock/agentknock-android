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
    suspend fun claim(
        deviceId: String,
        addressId: String,
        deviceToken: String,
        provideAttestation: Boolean,
    ): RelayClaimResult
}

internal class HttpRelayClaimClient(
    private val client: OkHttpClient,
    private val relayUrl: String = "https://relay.agentknock.dev/",
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val attestationProvider: DeviceAttestationProvider = AndroidKeyAttestationProvider(),
) : RelayClaimClient {
    override suspend fun claim(
        deviceId: String,
        addressId: String,
        deviceToken: String,
        provideAttestation: Boolean,
    ): RelayClaimResult = withContext(dispatcher) {
        val claimBody = json.encodeToString(
            DeviceClaimRequest.serializer(),
            DeviceClaimRequest(
                deviceToken = deviceToken,
                attestation = if (provideAttestation) {
                    attestationProvider.attest(deviceId, deviceToken)
                } else {
                    null
                },
            ),
        )
        try {
            val claimResult = post(
                path = "v1/device/$deviceId/claim",
                body = claimBody,
            ) { responseBody ->
                val response = json.decodeFromString(
                    DeviceClaimResponse.serializer(),
                    responseBody,
                )
                response.claimed && response.deviceId == deviceId
            }
            if (claimResult != PostResult.Success) {
                return@withContext claimResult.toClaimResult(addressRequest = false)
            }

            val addressBody = json.encodeToString(
                DeviceAddressRequest.serializer(),
                DeviceAddressRequest(addressId),
            )
            post(
                path = "v1/device/$deviceId/address",
                body = addressBody,
                deviceToken = deviceToken,
            ) { responseBody ->
                json.decodeFromString(
                    DeviceAddressResponse.serializer(),
                    responseBody,
                ).addressId == addressId
            }.toClaimResult(addressRequest = true)
        } catch (exception: IOException) {
            RelayClaimResult.Unavailable(exception)
        }
    }

    private fun post(
        path: String,
        body: String,
        deviceToken: String? = null,
        validResponse: (String) -> Boolean,
    ): PostResult {
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/$path")
            .apply {
                deviceToken?.let { header("Authorization", "Bearer $it") }
            }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (response.isSuccessful) {
                if (runCatching { validResponse(responseBody) }.getOrDefault(false)) {
                    PostResult.Success
                } else {
                    PostResult.InvalidResponse
                }
            } else {
                val error = runCatching {
                    json.decodeFromString(RelayErrorResponse.serializer(), responseBody)
                }.getOrNull()
                PostResult.Rejected(response.code, error?.code, error?.message)
            }
        }
    }

    private fun PostResult.toClaimResult(addressRequest: Boolean): RelayClaimResult = when (this) {
        PostResult.Success -> RelayClaimResult.Claimed
        is PostResult.Rejected -> if (
            addressRequest && status == 409 && code == ADDRESS_ALREADY_CLAIMED
        ) {
            RelayClaimResult.AddressUnavailable
        } else {
            RelayClaimResult.Rejected(status, code, message)
        }
        PostResult.InvalidResponse -> RelayClaimResult.InvalidResponse
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private sealed interface PostResult {
    data object Success : PostResult

    data class Rejected(val status: Int, val code: String?, val message: String?) : PostResult

    data object InvalidResponse : PostResult
}

@Serializable
private data class DeviceClaimRequest(
    @SerialName("device_token")
    val deviceToken: String,
    val attestation: AndroidKeyAttestation? = null,
)

@Serializable
internal data class AndroidKeyAttestation(
    val type: String,
    @SerialName("certificate_chain")
    val certificateChain: List<String>,
)

@Serializable
private data class DeviceClaimResponse(
    val claimed: Boolean,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
private data class DeviceAddressRequest(@SerialName("address_id") val addressId: String)

@Serializable
private data class DeviceAddressResponse(@SerialName("address_id") val addressId: String)

@Serializable
private data class RelayErrorResponse(
    @SerialName("error") val code: String,
    val message: String,
)

private const val ADDRESS_ALREADY_CLAIMED = "ADDRESS_ALREADY_CLAIMED"
