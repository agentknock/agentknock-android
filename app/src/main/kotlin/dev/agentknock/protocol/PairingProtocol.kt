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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

internal data class PairingClient(
    val cliVersion: String,
    val clientRandom: ByteArray,
    val platform: String,
    val architecture: String,
    val hostname: String?,
    val machineId: String?,
    val osVersion: String?,
)

internal data class EstablishedPairing(
    val pairingPsk: ByteArray,
    val sas: Long,
    val client: PairingClient,
)

internal data class SasChoices(
    val values: List<Long>,
    val correctIndex: Int,
)

internal data class PreparedFinishResponse(
    val response: JsonElement,
    val cliVersion: String,
)

internal data class OpenedPairedRequest(
    val plaintext: ByteArray,
    val pairingPsk: ByteArray,
)

internal class PairingProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
) {
    fun isInitialRequest(request: JsonElement): Boolean = runCatching {
        json.decodeFromJsonElement(PairingRequest.serializer(), request)
    }.isSuccess

    fun validateInitialRequest(request: JsonElement, address: String): Boolean {
        val decoded = runCatching {
            json.decodeFromJsonElement(PairingRequest.serializer(), request)
        }.getOrNull() ?: return false
        if (decoded.version != PROTOCOL_VERSION) return false
        val commitment = runCatching { BASE64_DECODER.decode(decoded.commitment) }.getOrNull()
            ?: return false
        return MessageDigest.isEqual(commitment, pairingCommitment(address))
    }

    fun generatePairingId(): String = ByteArray(IDENTIFIER_BYTES)
        .also(random::nextBytes)
        .toHex()

    fun initialResponse(pairingId: String, routePublicKey: ByteArray): JsonElement {
        require(pairingId.hexBytes()?.size == IDENTIFIER_BYTES) { "Invalid pairing id" }
        require(routePublicKey.size == X25519_KEY_BYTES) { "Invalid route public key" }
        return json.encodeToJsonElement(
            PairingResponse.serializer(),
            PairingResponse(
                pairingId = pairingId,
                routeKey = BASE64_ENCODER.encodeToString(routePublicKey),
            ),
        )
    }

    fun encryptedPairingId(request: JsonElement): String? = runCatching {
        json.decodeFromJsonElement(EncryptedRequest.serializer(), request)
    }.getOrNull()?.takeIf { it.version == PROTOCOL_VERSION }?.pairingId

    fun establish(
        routeId: String,
        requestId: String,
        pairingId: String,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        completion: JsonElement,
    ): EstablishedPairing {
        val decoded = json.decodeFromJsonElement(PairingCompletion.serializer(), completion)
        val encapsulatedKey = BASE64_DECODER.decode(decoded.key)
        val ciphertext = BASE64_DECODER.decode(decoded.ciphertext)
        val context = baseReceiver(
            routeId = routeId,
            pairingId = pairingId,
            requestId = requestId,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
            encapsulatedKey = encapsulatedKey,
        )
        val plaintext = context.open(EMPTY, ciphertext)
        val contents = json.decodeFromString(PairingContents.serializer(), plaintext.decodeToString())
        val clientRandom = BASE64_DECODER.decode(contents.clientRandom)
        require(clientRandom.size == CLIENT_RANDOM_BYTES) { "Invalid pairing client random" }
        val pairingIdBytes = requireNotNull(pairingId.hexBytes()) { "Invalid pairing id" }
        val pairingPsk = context.export(PSK_EXPORT_CONTEXT, PAIRING_PSK_BYTES)
        val sasInput = clientRandom + routePublicKey
        val sasBytes = derive(
            input = sasInput,
            salt = pairingIdBytes,
            info = SAS_DERIVATION_INFO,
            length = Long.SIZE_BYTES,
        )
        val sas = java.lang.Long.remainderUnsigned(
            ByteBuffer.wrap(sasBytes).long,
            SAS_MODULUS,
        )
        return EstablishedPairing(
            pairingPsk = pairingPsk,
            sas = sas,
            client = PairingClient(
                cliVersion = contents.cliVersion,
                clientRandom = clientRandom,
                platform = contents.platform,
                architecture = contents.architecture,
                hostname = contents.hostname,
                machineId = contents.machineId,
                osVersion = contents.osVersion,
            ),
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
        routeId: String,
        requestId: String,
        pairingId: String,
        pairingPsk: ByteArray,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        request: JsonElement,
        accepted: Boolean,
    ): PreparedFinishResponse {
        val opened = openPairedRequest(
            routeId = routeId,
            requestId = requestId,
            pairingId = pairingId,
            pairingPsk = pairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
            request = request,
        )
        val contents = json.decodeFromString(
            FinishRequest.serializer(),
            opened.plaintext.decodeToString(),
        )
        require(contents.method == FINISH_PAIRING_METHOD) { "Unexpected pairing method" }
        val result = if (accepted) RESULT_ACCEPTED else RESULT_REJECTED
        val responsePlaintext = json.encodeToString(
            FinishResult.serializer(),
            FinishResult(result),
        ).encodeToByteArray()
        return PreparedFinishResponse(
            response = sealPairedResponse(
                routeId = routeId,
                requestId = requestId,
                pairingId = pairingId,
                pairingPsk = opened.pairingPsk,
                routePrivateKey = routePrivateKey,
                routePublicKey = routePublicKey,
                request = request,
                plaintext = responsePlaintext,
            ),
            cliVersion = contents.cliVersion,
        )
    }

    fun verifyFinishCompletion(
        routeId: String,
        requestId: String,
        pairingId: String,
        pairingPsk: ByteArray,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        request: JsonElement,
        completion: JsonElement,
    ) {
        val plaintext = openPairedCompletion(
            routeId = routeId,
            requestId = requestId,
            pairingId = pairingId,
            pairingPsk = pairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
            request = request,
            completion = completion,
        )
        val result = json.decodeFromString(FinishCompletion.serializer(), plaintext.decodeToString())
        require(result.result == RESULT_ACCEPTED) { "Client did not accept pairing" }
    }

    fun openPairedRequest(
        routeId: String,
        requestId: String,
        pairingId: String,
        pairingPsk: ByteArray,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        request: JsonElement,
    ): OpenedPairedRequest {
        val opened = openPairedContext(
            routeId = routeId,
            requestId = requestId,
            pairingId = pairingId,
            pairingPsk = pairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
            request = request,
        )
        return OpenedPairedRequest(opened.plaintext, opened.pairingPsk)
    }

    fun sealPairedResponse(
        routeId: String,
        requestId: String,
        pairingId: String,
        pairingPsk: ByteArray,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        request: JsonElement,
        plaintext: ByteArray,
    ): JsonElement {
        val opened = openPairedContext(
            routeId = routeId,
            requestId = requestId,
            pairingId = pairingId,
            pairingPsk = pairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
            request = request,
        )
        val publicNonce = ByteArray(RESPONSE_NONCE_BYTES).also(random::nextBytes)
        val encapsulatedKey = BASE64_DECODER.decode(opened.request.key)
        val salt = encapsulatedKey + publicNonce
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
                nonce = BASE64_ENCODER.encodeToString(publicNonce),
                ciphertext = BASE64_ENCODER.encodeToString(ciphertext),
            ),
        )
    }

    fun openPairedCompletion(
        routeId: String,
        requestId: String,
        pairingId: String,
        pairingPsk: ByteArray,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        request: JsonElement,
        completion: JsonElement,
    ): ByteArray {
        val opened = openPairedContext(
            routeId = routeId,
            requestId = requestId,
            pairingId = pairingId,
            pairingPsk = pairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
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

    private fun pairingCommitment(address: String): ByteArray = derive(
        input = address.encodeToByteArray(),
        salt = BASE_DERIVATION_SALT,
        info = COMMITMENT_DERIVATION_INFO,
        length = PAIRING_PSK_BYTES,
    )

    private fun baseReceiver(
        routeId: String,
        pairingId: String,
        requestId: String,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        encapsulatedKey: ByteArray,
    ) = baseHpke.setupBaseR(
        encapsulatedKey,
        baseHpke.deserializePrivateKey(routePrivateKey, routePublicKey),
        protocolInfo(routeId, pairingId, requestId),
    )

    private fun pskReceiver(
        routeId: String,
        pairingId: String,
        requestId: String,
        pairingPsk: ByteArray,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        encapsulatedKey: ByteArray,
    ) = pskReceiver(
        routeId = routeId,
        pairingId = pairingId,
        requestId = requestId.ulidBytes(),
        pairingPsk = pairingPsk,
        routePrivateKey = routePrivateKey,
        routePublicKey = routePublicKey,
        encapsulatedKey = encapsulatedKey,
    )

    private fun pskReceiver(
        routeId: String,
        pairingId: String,
        requestId: ByteArray,
        pairingPsk: ByteArray,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        encapsulatedKey: ByteArray,
    ) = pskHpke.setupPSKR(
        encapsulatedKey,
        pskHpke.deserializePrivateKey(routePrivateKey, routePublicKey),
        protocolInfo(routeId, pairingId, requestId),
        pairingPsk,
        requireNotNull(pairingId.hexBytes()) { "Invalid pairing id" },
    )

    private fun protocolInfo(routeId: String, pairingId: String, requestId: String): ByteArray =
        protocolInfo(routeId, pairingId, requestId.ulidBytes())

    private fun protocolInfo(routeId: String, pairingId: String, requestId: ByteArray): ByteArray =
        PROTOCOL_VERSION_INFO +
            requireNotNull(routeId.hexBytes()) { "Invalid route id" } +
            requireNotNull(pairingId.hexBytes()) { "Invalid pairing id" } +
            requestId.also { require(it.size == IDENTIFIER_BYTES) { "Invalid request id" } }

    private fun openPairedContext(
        routeId: String,
        requestId: String,
        pairingId: String,
        pairingPsk: ByteArray,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        request: JsonElement,
    ): OpenedPairedContext {
        require(pairingPsk.size == PAIRING_PSK_BYTES) { "Invalid pairing PSK" }
        val decoded = json.decodeFromJsonElement(EncryptedRequest.serializer(), request)
        require(decoded.version == PROTOCOL_VERSION) { "Unsupported protocol version" }
        require(decoded.pairingId == pairingId) { "Pairing id mismatch" }
        val encapsulatedKey = BASE64_DECODER.decode(decoded.key)
        val ciphertext = BASE64_DECODER.decode(decoded.ciphertext)

        fun open(psk: ByteArray): OpenedPairedContext {
            val context = pskReceiver(
                routeId = routeId,
                pairingId = pairingId,
                requestId = requestId,
                pairingPsk = psk,
                routePrivateKey = routePrivateKey,
                routePublicKey = routePublicKey,
                encapsulatedKey = encapsulatedKey,
            )
            return OpenedPairedContext(
                request = decoded,
                context = context,
                plaintext = context.open(EMPTY, ciphertext),
                pairingPsk = psk,
            )
        }

        val current = runCatching { open(pairingPsk) }
        current.getOrNull()?.let { return it }
        val rotationKey = decoded.rotationKey ?: throw checkNotNull(current.exceptionOrNull())
        val rotatedPsk = rotatePairingPsk(
            routeId = routeId,
            pairingId = pairingId,
            pairingPsk = pairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
            rotationKey = BASE64_DECODER.decode(rotationKey),
        )
        return open(rotatedPsk)
    }

    private fun rotatePairingPsk(
        routeId: String,
        pairingId: String,
        pairingPsk: ByteArray,
        routePrivateKey: ByteArray,
        routePublicKey: ByteArray,
        rotationKey: ByteArray,
    ): ByteArray = pskReceiver(
        routeId = routeId,
        pairingId = pairingId,
        requestId = ByteArray(IDENTIFIER_BYTES),
        pairingPsk = pairingPsk,
        routePrivateKey = routePrivateKey,
        routePublicKey = routePublicKey,
        encapsulatedKey = rotationKey,
    ).export(PSK_EXPORT_CONTEXT, PAIRING_PSK_BYTES)

    private fun derive(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        HKDFBytesGenerator(SHA256Digest()).run {
            init(HKDFParameters(input, salt, info))
            generateBytes(output, 0, output.size)
        }
        return output
    }

    private fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), AUTHENTICATION_TAG_BITS, nonce))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        length += cipher.doFinal(output, length)
        return output.copyOf(length)
    }

    private fun String.hexBytes(): ByteArray? {
        if (length % 2 != 0 || any { it !in "0123456789abcdef" }) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
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

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
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
        const val FINISH_PAIRING_METHOD = "FinishPairing"
        const val RESULT_ACCEPTED = "ACCEPTED"
        const val RESULT_REJECTED = "REJECTED"
        const val IDENTIFIER_BYTES = 16
        const val X25519_KEY_BYTES = 32
        const val CLIENT_RANDOM_BYTES = 32
        const val PAIRING_PSK_BYTES = 32
        const val EXPORTED_SECRET_BYTES = 32
        const val CHACHA_KEY_BYTES = 32
        const val RESPONSE_NONCE_BYTES = 12
        const val AUTHENTICATION_TAG_BITS = 128
        const val SAS_MODULUS = 1_000_000_000_000L
        const val SAS_CHOICE_COUNT = 3
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
    val request: EncryptedRequest,
    val context: org.bouncycastle.crypto.hpke.HPKEContext,
    val plaintext: ByteArray,
    val pairingPsk: ByteArray,
)

@Serializable
private data class PairingRequest(
    val version: String,
    val commitment: String,
)

@Serializable
private data class PairingResponse(
    @SerialName("pairing_id") val pairingId: String,
    @SerialName("route_key") val routeKey: String,
)

@Serializable
private data class PairingCompletion(
    val key: String,
    val ciphertext: String,
)

@Serializable
private data class PairingContents(
    @SerialName("cli_version") val cliVersion: String,
    @SerialName("client_random") val clientRandom: String,
    val platform: String,
    val architecture: String,
    val hostname: String? = null,
    @SerialName("machine_id") val machineId: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
)

@Serializable
private data class EncryptedRequest(
    val version: String,
    @SerialName("pairing_id") val pairingId: String,
    val key: String,
    val ciphertext: String,
    @SerialName("rotation_key") val rotationKey: String? = null,
)

@Serializable
private data class FinishRequest(
    @SerialName("cli_version") val cliVersion: String,
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
    @SerialName("cli_version") val cliVersion: String,
    val result: String,
)
