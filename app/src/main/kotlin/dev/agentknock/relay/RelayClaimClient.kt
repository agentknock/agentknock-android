package dev.agentknock.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

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
    private val transport: RelayHttpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val attestationProvider: DeviceAttestationProvider = AndroidKeyAttestationProvider(),
) : RelayClaimClient {
    constructor(
        client: OkHttpClient,
        relayUrl: String = DEFAULT_RELAY_URL,
        json: Json = Json { ignoreUnknownKeys = true },
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        attestationProvider: DeviceAttestationProvider = AndroidKeyAttestationProvider(),
    ) : this(
        transport = RelayHttpTransport(client, relayUrl, json, dispatcher),
        json = json,
        dispatcher = dispatcher,
        attestationProvider = attestationProvider,
    )

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
        val claimResult = transport.post(
            path = "v1/device/$deviceId/claim",
            body = claimBody,
        )
        when (claimResult) {
            is RelayHttpResult.Success -> {
                val valid = runCatching {
                    val response = json.decodeFromString<DeviceClaimResponse>(claimResult.body)
                    response.claimed && response.deviceId == deviceId
                }.getOrDefault(false)
                if (!valid) return@withContext RelayClaimResult.InvalidResponse
            }
            is RelayHttpResult.Rejected -> return@withContext RelayClaimResult.Rejected(
                claimResult.status,
                claimResult.code,
                claimResult.message,
            )
            is RelayHttpResult.Unavailable -> {
                return@withContext RelayClaimResult.Unavailable(claimResult.cause)
            }
        }

        val addressBody = json.encodeToString(DeviceAddressRequest(addressId))
        when (
            val result = transport.post(
                path = "v1/device/$deviceId/address",
                body = addressBody,
                bearerToken = deviceToken,
            )
        ) {
            is RelayHttpResult.Success -> {
                val valid = runCatching {
                    json.decodeFromString<DeviceAddressResponse>(result.body).addressId == addressId
                }.getOrDefault(false)
                if (valid) RelayClaimResult.Claimed else RelayClaimResult.InvalidResponse
            }
            is RelayHttpResult.Rejected -> {
                if (result.status == 409 && result.code == ADDRESS_ALREADY_CLAIMED) {
                    RelayClaimResult.AddressUnavailable
                } else {
                    RelayClaimResult.Rejected(result.status, result.code, result.message)
                }
            }
            is RelayHttpResult.Unavailable -> RelayClaimResult.Unavailable(result.cause)
        }
    }
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

private const val ADDRESS_ALREADY_CLAIMED = "ADDRESS_ALREADY_CLAIMED"
