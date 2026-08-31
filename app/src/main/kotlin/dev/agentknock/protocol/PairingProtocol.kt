package dev.agentknock.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.bouncycastle.crypto.hpke.HPKE

internal data class PairingClientMetadata(
    val clientSoftware: ClientSoftware,
    val platform: String,
    val architecture: String,
    val hostname: String?,
    val machineId: String?,
    val osVersion: String?,
)

internal data class EstablishedPairing(
    val clientPsk: ByteArray,
    val sas: Long,
    val applicationPlaintext: ByteArray,
)

internal data class SasChoices(
    val values: List<Long>,
    val correctIndex: Int,
)

internal data class PreparedFinishResponse(
    val response: JsonElement,
)

internal class PairingProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
) {
    private val pairedRequests = PairedRequestProtocol(json, random)

    fun isInitialRequest(request: JsonElement): Boolean = runCatching {
        json.decodeFromJsonElement(PairingRequest.serializer(), request)
    }.isSuccess

    fun generateDeviceRandom(): ByteArray = ByteArray(DEVICE_RANDOM_BYTES).also(random::nextBytes)

    fun validateInitialRequest(request: JsonElement): Boolean =
        initialCommitment(request) != null

    fun validateFreshRequestId(requestId: String, now: Long): Boolean = runCatching {
        val bytes = requestId.ulidBytes()
        if (bytes.all { it == 0.toByte() }) return@runCatching false
        var timestamp = 0L
        repeat(6) { index ->
            timestamp = (timestamp shl 8) or (bytes[index].toLong() and 0xff)
        }
        timestamp >= now - REQUEST_ID_MAX_AGE_MILLIS &&
            timestamp <= now + REQUEST_ID_FUTURE_TOLERANCE_MILLIS
    }.getOrDefault(false)

    fun initialResponse(
        deviceId: String,
        devicePublicKey: ByteArray,
        deviceRandom: ByteArray,
    ): JsonElement {
        deviceId.ulidBytes()
        require(devicePublicKey.size == X25519_KEY_BYTES) { "Invalid device public key" }
        require(deviceRandom.size == DEVICE_RANDOM_BYTES) { "Invalid device random" }
        return json.encodeToJsonElement(
            PairingResponse.serializer(),
            PairingResponse(
                deviceId = deviceId,
                deviceKey = BASE64_ENCODER.encodeToString(devicePublicKey),
                deviceRandom = BASE64_ENCODER.encodeToString(deviceRandom),
            ),
        )
    }

    fun establish(
        deviceId: String,
        clientId: String,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        deviceRandom: ByteArray,
        initialRequest: JsonElement,
        completion: JsonElement,
    ): EstablishedPairing {
        val decoded = json.decodeFromJsonElement(PairingCompletion.serializer(), completion)
        val encapsulatedKey = decodeFixedBase64(decoded.key, X25519_KEY_BYTES, "pairing key")
        val secretCiphertext = BASE64_DECODER.decode(decoded.secret)
        val ciphertext = BASE64_DECODER.decode(decoded.ciphertext)
        val context = baseReceiver(
            deviceId = deviceId,
            clientId = clientId,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            encapsulatedKey = encapsulatedKey,
        )
        val clientSecret = context.open(EMPTY, secretCiphertext)
        val applicationPlaintext = context.open(EMPTY, ciphertext)
        require(clientSecret.size == CLIENT_SECRET_BYTES) { "Invalid pairing client secret" }
        require(deviceRandom.size == DEVICE_RANDOM_BYTES) { "Invalid device random" }
        val commitment = requireNotNull(initialCommitment(initialRequest)) {
            "Invalid initial pairing request"
        }
        require(MessageDigest.isEqual(commitment, pairingCommitment(clientSecret))) {
            "Pairing commitment mismatch"
        }
        val clientPsk = context.export(PSK_EXPORT_CONTEXT, CLIENT_PSK_BYTES)
        val sasBytes = derive(
            input = clientSecret,
            salt = deviceRandom,
            info = SAS_DERIVATION_INFO +
                deviceId.ulidBytes() +
                clientId.ulidBytes() +
                devicePublicKey,
            length = Long.SIZE_BYTES,
        )
        val sas = java.lang.Long.remainderUnsigned(
            ByteBuffer.wrap(sasBytes).long,
            SAS_MODULUS,
        )
        return EstablishedPairing(
            clientPsk = clientPsk,
            sas = sas,
            applicationPlaintext = applicationPlaintext,
        )
    }

    fun decodeClientMetadata(applicationPlaintext: ByteArray): PairingClientMetadata {
        val clientSoftware = json.decodeClientSoftware(applicationPlaintext)
        val contents = json.decodeFromString(
            PairingMetadata.serializer(),
            applicationPlaintext.decodeToString(),
        )
        return PairingClientMetadata(
            clientSoftware = clientSoftware,
            platform = contents.platform,
            architecture = contents.architecture,
            hostname = contents.hostname,
            machineId = contents.machineId,
            osVersion = contents.osVersion,
        )
    }

    fun sasChoices(correct: Long): SasChoices {
        require(correct in 0 until SAS_MODULUS)
        val values = linkedSetOf(correct)
        while (values.size < SAS_CHOICE_COUNT) {
            values += java.lang.Long.remainderUnsigned(random.nextLong(), SAS_MODULUS)
        }
        val shuffled = values.toMutableList()
        for (index in shuffled.lastIndex downTo 1) {
            val swap = random.nextInt(index + 1)
            val value = shuffled[index]
            shuffled[index] = shuffled[swap]
            shuffled[swap] = value
        }
        return SasChoices(shuffled, shuffled.indexOf(correct))
    }

    fun prepareFinishResponse(
        deviceId: String,
        requestId: String,
        clientId: String,
        clientPsk: ByteArray,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        request: JsonElement,
    ): PreparedFinishResponse {
        val opened = pairedRequests.openPairedRequest(
            deviceId = deviceId,
            requestId = requestId,
            clientId = clientId,
            clientPsk = clientPsk,
            allowRotation = false,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )
        val contents = json.decodeFromString(
            FinishRequest.serializer(),
            opened.plaintext.decodeToString(),
        )
        json.decodeClientSoftware(opened.plaintext)
        require(contents.method == PairedRequestProtocol.FINISH_PAIRING_METHOD) {
            "Unexpected pairing method"
        }
        val responsePlaintext = json.encodeToString(
            FinishResult.serializer(),
            FinishResult(RESULT_ACCEPTED),
        ).encodeToByteArray()
        return PreparedFinishResponse(
            response = pairedRequests.sealPairedResponse(
                deviceId = deviceId,
                requestId = requestId,
                clientId = clientId,
                clientPsk = opened.clientPsk,
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                request = request,
                plaintext = responsePlaintext,
            ),
        )
    }

    fun verifyFinishCompletion(
        deviceId: String,
        requestId: String,
        clientId: String,
        clientPsk: ByteArray,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        request: JsonElement,
        completion: JsonElement,
    ) {
        val plaintext = pairedRequests.openPairedCompletion(
            deviceId = deviceId,
            requestId = requestId,
            clientId = clientId,
            clientPsk = clientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
            completion = completion,
        )
        val result = json.decodeFromString(FinishCompletion.serializer(), plaintext.decodeToString())
        json.decodeClientSoftware(plaintext)
        require(result.result == RESULT_ACCEPTED) { "Client did not accept pairing" }
    }

    fun finishCompletionAccepted(plaintext: ByteArray): Boolean {
        json.decodeClientSoftware(plaintext)
        return json.decodeFromString(
            FinishCompletion.serializer(),
            plaintext.decodeToString(),
        ).result == RESULT_ACCEPTED
    }

    fun formatSas(value: Long): String {
        require(value in 0 until SAS_MODULUS)
        return String.format(
            Locale.ROOT,
            "%04d %04d %04d",
            value / 100_000_000,
            value / 10_000 % 10_000,
            value % 10_000,
        )
    }

    private fun initialCommitment(request: JsonElement): ByteArray? {
        val decoded = runCatching {
            json.decodeFromJsonElement(PairingRequest.serializer(), request)
        }.getOrNull() ?: return null
        if (decoded.version != PROTOCOL_VERSION) return null
        return runCatching { BASE64_DECODER.decode(decoded.commitment) }.getOrNull()
            ?.takeIf { it.size == COMMITMENT_BYTES }
    }

    private fun pairingCommitment(clientSecret: ByteArray): ByteArray = derive(
        input = clientSecret,
        salt = BASE_DERIVATION_SALT,
        info = COMMITMENT_DERIVATION_INFO,
        length = COMMITMENT_BYTES,
    )

    private fun baseReceiver(
        deviceId: String,
        clientId: String,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        encapsulatedKey: ByteArray,
    ) = baseHpke.setupBaseR(
        encapsulatedKey.also {
            require(it.size == X25519_KEY_BYTES) { "Invalid pairing key" }
        },
        baseHpke.deserializePrivateKey(
            devicePrivateKey.also {
                require(it.size == X25519_KEY_BYTES) { "Invalid device private key" }
            },
            devicePublicKey.also {
                require(it.size == X25519_KEY_BYTES) { "Invalid device public key" }
            },
        ),
        protocolInfo(deviceId, clientId),
    )

    private companion object {
        val BASE_DERIVATION_SALT = PROTOCOL_VERSION.encodeToByteArray()
        val COMMITMENT_DERIVATION_INFO = "agentknock-v1 commitment".encodeToByteArray()
        val SAS_DERIVATION_INFO = "agentknock-v1 sas".encodeToByteArray()
        const val RESULT_ACCEPTED = "ACCEPTED"
        const val CLIENT_SECRET_BYTES = 32
        const val DEVICE_RANDOM_BYTES = 32
        const val COMMITMENT_BYTES = 32
        const val SAS_MODULUS = 1_000_000_000_000L
        const val SAS_CHOICE_COUNT = 3
        const val REQUEST_ID_MAX_AGE_MILLIS = 24 * 60 * 60 * 1_000L
        const val REQUEST_ID_FUTURE_TOLERANCE_MILLIS = 5 * 60 * 1_000L
        val baseHpke = HPKE(
            HPKE.mode_base,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_CHACHA20_POLY1305,
        )
    }
}

@Serializable
private data class PairingRequest(
    val version: String,
    val commitment: String,
)

@Serializable
private data class PairingResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_key") val deviceKey: String,
    @SerialName("device_random") val deviceRandom: String,
)

@Serializable
private data class PairingCompletion(
    val key: String,
    val secret: String,
    val ciphertext: String,
)

@Serializable
private data class PairingMetadata(
    val platform: String,
    val architecture: String,
    val hostname: String? = null,
    @SerialName("machine_id") val machineId: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
)

@Serializable
private data class FinishRequest(
    val method: String,
)

@Serializable
private data class FinishResult(val result: String)

@Serializable
private data class FinishCompletion(
    val result: String,
)
