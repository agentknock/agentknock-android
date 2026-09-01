package dev.agentknock.protocol

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

internal data class OpenedPairedRequest(
    val plaintext: ByteArray,
    val clientPsk: ByteArray,
    val keySource: PairedRequestKeySource,
    val responseContext: PairedResponseContext,
)

internal data class PairedResponseContext(
    val encapsulatedKey: ByteArray,
    val exportedSecret: ByteArray,
)

internal enum class PairedRequestKeySource {
    CURRENT,
    PREVIOUS,
    ROTATED,
}

internal class PairedRequestProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
) {
    fun method(plaintext: ByteArray): String =
        json.decodeFromString<RequestMethodWire>(plaintext.decodeToString()).method

    fun errorResponse(code: PairedRequestErrorCode): ByteArray =
        json.encodeToString(
            PairedRequestErrorResponseWire.serializer(),
            PairedRequestErrorResponseWire(
                error = code.wireName,
                message = code.message,
            ),
        ).encodeToByteArray()

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
        return opened.toOpenedRequest()
    }

    fun sealPairedResponse(
        opened: OpenedPairedRequest,
        plaintext: ByteArray,
    ): JsonElement = sealPairedResponse(opened.responseContext, plaintext)

    private fun sealPairedResponse(
        responseContext: PairedResponseContext,
        plaintext: ByteArray,
    ): JsonElement {
        val responseRandom = ByteArray(RESPONSE_RANDOM_BYTES).also(random::nextBytes)
        val salt = responseContext.encapsulatedKey + responseRandom
        val key = derive(responseContext.exportedSecret, salt, RESPONSE_KEY_INFO, CHACHA_KEY_BYTES)
        val nonce = derive(
            responseContext.exportedSecret,
            salt,
            RESPONSE_NONCE_INFO,
            RESPONSE_NONCE_BYTES,
        )
        val ciphertext = chachaSeal(key, nonce, plaintext)
        return json.encodeToJsonElement(
            EncryptedResponse.serializer(),
            EncryptedResponse(
                nonce = BASE64_ENCODER.encodeToString(responseRandom),
                ciphertext = BASE64_ENCODER.encodeToString(ciphertext),
            ),
        )
    }

    /** Reconstructs response context for a delayed response after the opened request is gone. */
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
        return sealPairedResponse(opened.toOpenedRequest(), plaintext)
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
                encapsulatedKey = encapsulatedKey,
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

    private fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), AUTHENTICATION_TAG_BITS, nonce))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        length += cipher.doFinal(output, length)
        return output.copyOf(length)
    }

    private fun OpenedPairedContext.toOpenedRequest() = OpenedPairedRequest(
        plaintext = plaintext,
        clientPsk = clientPsk,
        keySource = keySource,
        responseContext = PairedResponseContext(
            encapsulatedKey = encapsulatedKey,
            exportedSecret = context.export(
                RESPONSE_EXPORT_CONTEXT,
                EXPORTED_SECRET_BYTES,
            ),
        ),
    )

    companion object {
        const val FINISH_PAIRING_METHOD = "PairingFinish"
        private val RESPONSE_EXPORT_CONTEXT = "agentknock-v1 response".encodeToByteArray()
        private val RESPONSE_KEY_INFO = "key".encodeToByteArray()
        private val RESPONSE_NONCE_INFO = "nonce".encodeToByteArray()
        private const val EXPORTED_SECRET_BYTES = 32
        private const val CHACHA_KEY_BYTES = 32
        private const val RESPONSE_RANDOM_BYTES = 32
        private const val RESPONSE_NONCE_BYTES = 12
        private const val AUTHENTICATION_TAG_BITS = 128
        private val pskHpke = HPKE(
            HPKE.mode_psk,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_CHACHA20_POLY1305,
        )
    }
}

internal enum class PairedRequestErrorCode(
    val wireName: String,
    val message: String,
) {
    INVALID_REQUEST(
        wireName = "INVALID_REQUEST",
        message = "The request could not be understood.",
    ),
    UNSUPPORTED_METHOD(
        wireName = "UNSUPPORTED_METHOD",
        message = "The requested operation is not supported.",
    ),
    INVALID_STATE(
        wireName = "INVALID_STATE",
        message = "The requested operation is not available in the current state.",
    ),
}

private data class OpenedPairedContext(
    val encapsulatedKey: ByteArray,
    val context: org.bouncycastle.crypto.hpke.HPKEContext,
    val plaintext: ByteArray,
    val clientPsk: ByteArray,
    val keySource: PairedRequestKeySource,
)

internal fun protocolInfo(deviceId: String, requestId: String): ByteArray =
    protocolInfo(deviceId, requestId.ulidBytes())

internal fun protocolInfo(deviceId: String, requestId: ByteArray): ByteArray =
    PROTOCOL_VERSION_INFO +
        deviceId.ulidBytes() +
        requestId.also { require(it.size == IDENTIFIER_BYTES) { "Invalid request id" } }

internal fun derive(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val output = ByteArray(length)
    HKDFBytesGenerator(SHA256Digest()).run {
        init(HKDFParameters(input, salt, info))
        generateBytes(output, 0, output.size)
    }
    return output
}

internal fun decodeFixedBase64(value: String, size: Int, name: String): ByteArray =
    BASE64_DECODER.decode(value).also {
        require(it.size == size) { "Invalid $name" }
    }

internal fun String.ulidBytes(): ByteArray {
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

internal const val PROTOCOL_VERSION = "agentknock-v1"
internal val PROTOCOL_VERSION_INFO = PROTOCOL_VERSION.encodeToByteArray() + ByteArray(3)
internal val PSK_EXPORT_CONTEXT = "agentknock-v1 psk".encodeToByteArray()
internal val EMPTY = ByteArray(0)
internal const val IDENTIFIER_BYTES = 16
internal const val X25519_KEY_BYTES = 32
internal const val CLIENT_PSK_BYTES = 32
internal val BASE64_DECODER: Base64.Decoder = Base64.getDecoder()
internal val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()
private const val ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

@Serializable
private data class RequestMethodWire(val method: String)

@Serializable
private data class PairedRequestErrorResponseWire(
    val error: String,
    val message: String,
)

@Serializable
private data class EncryptedRequestCore(
    val version: String,
    val key: String,
    val ciphertext: String,
)

@Serializable
private data class EncryptedResponse(
    val nonce: String,
    val ciphertext: String,
)

@Serializable
private data class EncryptedCompletion(val ciphertext: String)
