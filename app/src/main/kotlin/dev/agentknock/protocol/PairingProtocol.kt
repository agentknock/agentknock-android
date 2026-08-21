package dev.agentknock.protocol

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

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

internal data class OpenedPairedRequest(
    val plaintext: ByteArray,
    val clientPsk: ByteArray,
    val keySource: PairedRequestKeySource,
)

internal enum class PairedRequestKeySource {
    CURRENT,
    PREVIOUS,
    ROTATED,
}

internal class PairingProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
) {
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
        val opened = openPairedRequest(
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
        require(contents.method == FINISH_PAIRING_METHOD) { "Unexpected pairing method" }
        val responsePlaintext = json.encodeToString(
            FinishResult.serializer(),
            FinishResult(RESULT_ACCEPTED),
        ).encodeToByteArray()
        return PreparedFinishResponse(
            response = sealPairedResponse(
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
        val plaintext = openPairedCompletion(
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

    fun openPairedRequest(
        deviceId: String,
        requestId: String,
        clientId: String,
        clientPsk: ByteArray,
        previousClientPsk: ByteArray? = null,
        allowRotation: Boolean = true,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        request: JsonElement,
    ): OpenedPairedRequest {
        val opened = openPairedContext(
            deviceId = deviceId,
            requestId = requestId,
            clientId = clientId,
            clientPsk = clientPsk,
            previousClientPsk = previousClientPsk,
            allowRotation = allowRotation,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )
        return OpenedPairedRequest(opened.plaintext, opened.clientPsk, opened.keySource)
    }

    fun sealPairedResponse(
        deviceId: String,
        requestId: String,
        clientId: String,
        clientPsk: ByteArray,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        request: JsonElement,
        plaintext: ByteArray,
    ): JsonElement {
        val opened = openPairedContext(
            deviceId = deviceId,
            requestId = requestId,
            clientId = clientId,
            clientPsk = clientPsk,
            previousClientPsk = null,
            allowRotation = false,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )
        val responseRandom = ByteArray(RESPONSE_RANDOM_BYTES).also(random::nextBytes)
        val encapsulatedKey = decodeFixedBase64(
            opened.request.key,
            X25519_KEY_BYTES,
            "request key",
        )
        val salt = encapsulatedKey + responseRandom
        val exportedSecret = opened.context.export(
            RESPONSE_EXPORT_CONTEXT,
            EXPORTED_SECRET_BYTES,
        )
        val key = derive(exportedSecret, salt, RESPONSE_KEY_INFO, CHACHA_KEY_BYTES)
        val nonce = derive(exportedSecret, salt, RESPONSE_NONCE_INFO, RESPONSE_NONCE_BYTES)
        val ciphertext = chachaSeal(key, nonce, plaintext)
        return json.encodeToJsonElement(
            EncryptedResponse.serializer(),
            EncryptedResponse(
                nonce = BASE64_ENCODER.encodeToString(responseRandom),
                ciphertext = BASE64_ENCODER.encodeToString(ciphertext),
            ),
        )
    }

    fun openPairedCompletion(
        deviceId: String,
        requestId: String,
        clientId: String,
        clientPsk: ByteArray,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        request: JsonElement,
        completion: JsonElement,
    ): ByteArray {
        val opened = openPairedContext(
            deviceId = deviceId,
            requestId = requestId,
            clientId = clientId,
            clientPsk = clientPsk,
            previousClientPsk = null,
            allowRotation = false,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )
        val decoded = json.decodeFromJsonElement(EncryptedCompletion.serializer(), completion)
        return opened.context.open(EMPTY, BASE64_DECODER.decode(decoded.ciphertext))
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

    private fun pskReceiver(
        deviceId: String,
        clientId: String,
        requestId: String,
        clientPsk: ByteArray,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        encapsulatedKey: ByteArray,
    ) = pskReceiver(
        deviceId = deviceId,
        clientId = clientId,
        requestId = requestId.ulidBytes(),
        clientPsk = clientPsk,
        devicePrivateKey = devicePrivateKey,
        devicePublicKey = devicePublicKey,
        encapsulatedKey = encapsulatedKey,
    )

    private fun pskReceiver(
        deviceId: String,
        clientId: String,
        requestId: ByteArray,
        clientPsk: ByteArray,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        encapsulatedKey: ByteArray,
    ) = pskHpke.setupPSKR(
        encapsulatedKey.also {
            require(it.size == X25519_KEY_BYTES) { "Invalid request key" }
        },
        pskHpke.deserializePrivateKey(
            devicePrivateKey.also {
                require(it.size == X25519_KEY_BYTES) { "Invalid device private key" }
            },
            devicePublicKey.also {
                require(it.size == X25519_KEY_BYTES) { "Invalid device public key" }
            },
        ),
        protocolInfo(deviceId, requestId),
        clientPsk,
        clientId.ulidBytes(),
    )

    private fun protocolInfo(deviceId: String, requestId: String): ByteArray =
        protocolInfo(deviceId, requestId.ulidBytes())

    private fun protocolInfo(deviceId: String, requestId: ByteArray): ByteArray =
        PROTOCOL_VERSION_INFO +
            deviceId.ulidBytes() +
            requestId.also { require(it.size == IDENTIFIER_BYTES) { "Invalid request id" } }

    private fun openPairedContext(
        deviceId: String,
        requestId: String,
        clientId: String,
        clientPsk: ByteArray,
        previousClientPsk: ByteArray?,
        allowRotation: Boolean,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        request: JsonElement,
    ): OpenedPairedContext {
        require(clientPsk.size == CLIENT_PSK_BYTES) { "Invalid client PSK" }
        val requestObject = request as? JsonObject ?: error("Invalid encrypted request")
        val decoded = json.decodeFromJsonElement(EncryptedRequestCore.serializer(), request)
        require(decoded.version == PROTOCOL_VERSION) { "Unsupported protocol version" }
        val encapsulatedKey = decodeFixedBase64(decoded.key, X25519_KEY_BYTES, "request key")
        val ciphertext = BASE64_DECODER.decode(decoded.ciphertext)

        fun open(psk: ByteArray, source: PairedRequestKeySource): OpenedPairedContext {
            val context = pskReceiver(
                deviceId = deviceId,
                clientId = clientId,
                requestId = requestId,
                clientPsk = psk,
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                encapsulatedKey = encapsulatedKey,
            )
            return OpenedPairedContext(
                request = decoded,
                context = context,
                plaintext = context.open(EMPTY, ciphertext),
                clientPsk = psk,
                keySource = source,
            )
        }

        val current = runCatching { open(clientPsk, PairedRequestKeySource.CURRENT) }
        current.getOrNull()?.let { return it }
        val previous = previousClientPsk?.let { previousPsk ->
            runCatching { open(previousPsk, PairedRequestKeySource.PREVIOUS) }
        }
        previous?.getOrNull()?.let { return it }
        if (!allowRotation) {
            throw checkNotNull(previous?.exceptionOrNull() ?: current.exceptionOrNull())
        }
        val rotationValue = requestObject["rotation_key"]
            ?: throw checkNotNull(previous?.exceptionOrNull() ?: current.exceptionOrNull())
        val rotationPrimitive = rotationValue as? JsonPrimitive
        require(rotationPrimitive?.isString == true) { "Invalid rotation key" }
        val rotationKey = decodeFixedBase64(
            rotationPrimitive.content,
            X25519_KEY_BYTES,
            "rotation key",
        )
        val rotatedClientPsk = rotateClientPsk(
            deviceId = deviceId,
            clientId = clientId,
            clientPsk = clientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            rotationKey = rotationKey,
        )
        return open(rotatedClientPsk, PairedRequestKeySource.ROTATED)
    }

    private fun rotateClientPsk(
        deviceId: String,
        clientId: String,
        clientPsk: ByteArray,
        devicePrivateKey: ByteArray,
        devicePublicKey: ByteArray,
        rotationKey: ByteArray,
    ): ByteArray = pskReceiver(
        deviceId = deviceId,
        clientId = clientId,
        requestId = ByteArray(IDENTIFIER_BYTES),
        clientPsk = clientPsk,
        devicePrivateKey = devicePrivateKey,
        devicePublicKey = devicePublicKey,
        encapsulatedKey = rotationKey,
    ).export(PSK_EXPORT_CONTEXT, CLIENT_PSK_BYTES)

    private fun derive(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        HKDFBytesGenerator(SHA256Digest()).run {
            init(HKDFParameters(input, salt, info))
            generateBytes(output, 0, output.size)
        }
        return output
    }

    private fun decodeFixedBase64(value: String, size: Int, name: String): ByteArray =
        BASE64_DECODER.decode(value).also {
            require(it.size == size) { "Invalid $name" }
        }

    private fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), AUTHENTICATION_TAG_BITS, nonce))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        length += cipher.doFinal(output, length)
        return output.copyOf(length)
    }

    private fun String.ulidBytes(): ByteArray {
        require(length == 26 && first() in '0'..'7') { "Invalid request id" }
        var value = BigInteger.ZERO
        for (character in this) {
            val digit = ULID_ALPHABET.indexOf(character)
            require(digit >= 0) { "Invalid request id" }
            value = value.shiftLeft(5).or(BigInteger.valueOf(digit.toLong()))
        }
        val encoded = value.toByteArray()
        require(encoded.size <= IDENTIFIER_BYTES + 1) { "Invalid request id" }
        return ByteArray(IDENTIFIER_BYTES).also { output ->
            val sourceOffset = (encoded.size - IDENTIFIER_BYTES).coerceAtLeast(0)
            val length = encoded.size - sourceOffset
            encoded.copyInto(
                output,
                destinationOffset = IDENTIFIER_BYTES - length,
                startIndex = sourceOffset,
            )
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = "agentknock-v1"
        val PROTOCOL_VERSION_INFO = PROTOCOL_VERSION.encodeToByteArray() + ByteArray(3)
        val BASE_DERIVATION_SALT = PROTOCOL_VERSION.encodeToByteArray()
        val COMMITMENT_DERIVATION_INFO = "agentknock-v1 commitment".encodeToByteArray()
        val PSK_EXPORT_CONTEXT = "agentknock-v1 psk".encodeToByteArray()
        val SAS_DERIVATION_INFO = "agentknock-v1 sas".encodeToByteArray()
        val RESPONSE_EXPORT_CONTEXT = "agentknock-v1 response".encodeToByteArray()
        val RESPONSE_KEY_INFO = "key".encodeToByteArray()
        val RESPONSE_NONCE_INFO = "nonce".encodeToByteArray()
        val EMPTY = ByteArray(0)
        const val FINISH_PAIRING_METHOD = "PairingFinish"
        const val RESULT_ACCEPTED = "ACCEPTED"
        const val IDENTIFIER_BYTES = 16
        const val X25519_KEY_BYTES = 32
        const val CLIENT_SECRET_BYTES = 32
        const val DEVICE_RANDOM_BYTES = 32
        const val COMMITMENT_BYTES = 32
        const val CLIENT_PSK_BYTES = 32
        const val EXPORTED_SECRET_BYTES = 32
        const val CHACHA_KEY_BYTES = 32
        const val RESPONSE_RANDOM_BYTES = 32
        const val RESPONSE_NONCE_BYTES = 12
        const val AUTHENTICATION_TAG_BITS = 128
        const val SAS_MODULUS = 1_000_000_000_000L
        const val SAS_CHOICE_COUNT = 3
        const val REQUEST_ID_MAX_AGE_MILLIS = 24 * 60 * 60 * 1_000L
        const val REQUEST_ID_FUTURE_TOLERANCE_MILLIS = 5 * 60 * 1_000L
        const val ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val BASE64_DECODER: Base64.Decoder = Base64.getDecoder()
        val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()
        val baseHpke = HPKE(
            HPKE.mode_base,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_CHACHA20_POLY1305,
        )
        val pskHpke = HPKE(
            HPKE.mode_psk,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_CHACHA20_POLY1305,
        )
    }

}

private data class OpenedPairedContext(
    val request: EncryptedRequestCore,
    val context: org.bouncycastle.crypto.hpke.HPKEContext,
    val plaintext: ByteArray,
    val clientPsk: ByteArray,
    val keySource: PairedRequestKeySource,
)

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
private data class EncryptedRequestCore(
    val version: String,
    val key: String,
    val ciphertext: String,
)

@Serializable
private data class FinishRequest(
    val method: String,
)

@Serializable
private data class FinishResult(val result: String)

@Serializable
private data class EncryptedResponse(
    val nonce: String,
    val ciphertext: String,
)

@Serializable
private data class EncryptedCompletion(val ciphertext: String)

@Serializable
private data class FinishCompletion(
    val result: String,
)
