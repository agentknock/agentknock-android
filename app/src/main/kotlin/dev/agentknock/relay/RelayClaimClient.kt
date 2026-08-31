package dev.agentknock.relay

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

internal enum class RelayClaimOutcome {
    CLAIMED,
    ADDRESS_UNAVAILABLE,
}

internal typealias RelayClaimResult = RelayEndpointResult<RelayClaimOutcome>

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
        when (
            val claimResult = transport.post(
                path = "v1/device/$deviceId/claim",
                body = claimBody,
            ).decodeSuccess { body ->
                val response = json.decodeFromString<DeviceClaimResponse>(body)
                require(response.claimed && response.deviceId == deviceId)
            }
        ) {
            is RelayEndpointResult.Success -> Unit
            is RelayEndpointResult.Rejected -> return@withContext claimResult
            is RelayEndpointResult.Unavailable -> return@withContext claimResult
            RelayEndpointResult.InvalidResponse ->
                return@withContext RelayEndpointResult.InvalidResponse
        }

        val addressBody = json.encodeToString(DeviceAddressRequest(addressId))
        val addressResult = transport.post(
            path = "v1/device/$deviceId/address",
            body = addressBody,
            bearerToken = deviceToken,
        )
        if (
            addressResult is RelayEndpointResult.Rejected &&
            addressResult.status == 409 &&
            addressResult.code == ADDRESS_ALREADY_CLAIMED
        ) {
            RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE)
        } else {
            addressResult.decodeSuccess { body ->
                val response = json.decodeFromString<DeviceAddressResponse>(body)
                require(response.addressId == addressId)
                RelayClaimOutcome.CLAIMED
            }
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
