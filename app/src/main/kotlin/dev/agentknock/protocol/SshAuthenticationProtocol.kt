package dev.agentknock.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class SshAuthenticationRequestMessage(
    val clientSoftware: ClientSoftware,
    val invocationId: String,
    val invocationToken: ByteArray,
    val secret: String,
    val message: ByteArray,
)

internal data class SshAuthenticationMessageDetails(
    val username: String,
    val method: SshAuthenticationMethod,
    val algorithm: SshSignatureAlgorithm,
    val hostKeyAlgorithm: String? = null,
    val hostKeyFingerprint: String? = null,
)

internal enum class SshAuthenticationMethod(val wireName: String) {
    PUBLIC_KEY("publickey"),
    HOST_BOUND("publickey-hostbound-v00@openssh.com"),
}

internal enum class SshSignatureAlgorithm(val wireName: String) {
    ED25519("ssh-ed25519"),
    RSA_SHA256("rsa-sha2-256"),
    RSA_SHA512("rsa-sha2-512"),
}

internal sealed interface SshAuthenticationCompletion {
    val clientSoftware: ClientSoftware

    data class Approved(override val clientSoftware: ClientSoftware) :
        SshAuthenticationCompletion

    data class Denied(
        override val clientSoftware: ClientSoftware,
        val reason: String,
        val message: String,
    ) : SshAuthenticationCompletion

    data class Aborted(
        override val clientSoftware: ClientSoftware,
        val reason: String,
        val message: String,
    ) : SshAuthenticationCompletion
}

internal class SshAuthenticationProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): SshAuthenticationRequestMessage {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val request = json.decodeFromString<SshAuthenticationRequestWire>(
            plaintext.decodeToString(),
        )
        require(request.method == METHOD) { "Unexpected request method" }
        require(request.invocationId.isNotEmpty()) { "SSH authentication invocation ID is empty" }
        require(request.secret.isNotEmpty()) { "SSH authentication secret name is empty" }
        return SshAuthenticationRequestMessage(
            clientSoftware = clientSoftware,
            invocationId = request.invocationId,
            invocationToken = decodeBase64(request.invocationToken, "invocation token").also {
                require(it.size == INVOCATION_TOKEN_BYTES) {
                    "Invocation token must be $INVOCATION_TOKEN_BYTES bytes"
                }
            },
            secret = request.secret,
            message = decodeBase64(request.message, "SSH authentication message"),
        )
    }

    fun validateMessage(
        message: ByteArray,
        expectedPublicKeyBlob: ByteArray,
        expectedKeyAlgorithm: String,
    ): SshAuthenticationMessageDetails {
        val input = SshCursor(message)
        require(input.string().isNotEmpty()) { "SSH authentication has an empty session ID" }
        require(input.byte() == SSH_MSG_USERAUTH_REQUEST) {
            "SSH signature is not a user-authentication request"
        }
        val username = input.string().decodeToString(throwOnInvalidSequence = true)
        require(input.string().contentEquals(SSH_CONNECTION)) {
            "SSH authentication has an unsupported service"
        }
        val methodValue = input.string().decodeToString(throwOnInvalidSequence = true)
        val method = SshAuthenticationMethod.entries.singleOrNull {
            it.wireName == methodValue
        } ?: throw IllegalArgumentException("SSH authentication has an unsupported method")
        require(input.byte() == 1) { "SSH authentication does not contain a signature" }
        val algorithmValue = input.string().decodeToString(throwOnInvalidSequence = true)
        val algorithm = SshSignatureAlgorithm.entries.singleOrNull {
            it.wireName == algorithmValue
        } ?: throw IllegalArgumentException("Unsupported SSH signature algorithm")
        when (expectedKeyAlgorithm) {
            SSH_ED25519 -> require(algorithm == SshSignatureAlgorithm.ED25519) {
                "The SSH signature algorithm does not match the selected key"
            }
            SSH_RSA -> require(
                algorithm == SshSignatureAlgorithm.RSA_SHA256 ||
                    algorithm == SshSignatureAlgorithm.RSA_SHA512,
            ) { "The SSH signature algorithm does not match the selected key" }
            else -> throw IllegalArgumentException(
                "The selected key does not support SSH authentication",
            )
        }
        require(MessageDigest.isEqual(input.string(), expectedPublicKeyBlob)) {
            "SSH authentication contains a different public key"
        }
        val hostKey = if (method == SshAuthenticationMethod.HOST_BOUND) {
            input.string().also { requireValidHostKey(it) }
        } else {
            null
        }
        input.requireEnd()
        return SshAuthenticationMessageDetails(
            username = username,
            method = method,
            algorithm = algorithm,
            hostKeyAlgorithm = hostKey?.let(::sshBlobAlgorithm),
            hostKeyFingerprint = hostKey?.let(::sshFingerprint),
        )
    }

    fun approvedResponse(signature: ByteArray): ByteArray = json.encodeToString(
        SshAuthenticationResultWire.serializer(),
        SshAuthenticationResultWire(
            result = RESULT_APPROVED,
            signature = Base64.getEncoder().encodeToString(signature),
        ),
    ).encodeToByteArray()

    fun deniedResponse(reason: InvocationDenialReason, message: String): ByteArray =
        json.encodeToString(
            SshAuthenticationResultWire.serializer(),
            SshAuthenticationResultWire(
                result = RESULT_DENIED,
                reason = reason.wireName,
                message = message,
            ),
        ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): SshAuthenticationCompletion {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val completion = json.decodeFromString<SshAuthenticationResultWire>(
            plaintext.decodeToString(),
        )
        return when (completion.result) {
            RESULT_APPROVED -> {
                if (completion.signature != null) {
                    throw SerializationException(
                        "Approved SSH authentication completion contains a signature",
                    )
                }
                SshAuthenticationCompletion.Approved(clientSoftware)
            }
            RESULT_DENIED -> SshAuthenticationCompletion.Denied(
                clientSoftware = clientSoftware,
                reason = completion.reason
                    ?: throw SerializationException("Denied completion has no reason"),
                message = completion.message
                    ?: throw SerializationException("Denied completion has no message"),
            )
            RESULT_ABORTED -> SshAuthenticationCompletion.Aborted(
                clientSoftware = clientSoftware,
                reason = completion.reason
                    ?: throw SerializationException("Aborted completion has no reason"),
                message = completion.message
                    ?: throw SerializationException("Aborted completion has no message"),
            )
            else -> throw SerializationException(
                "Unsupported SSH authentication completion result",
            )
        }
    }

    private fun requireValidHostKey(blob: ByteArray) {
        val cursor = SshCursor(blob)
        require(cursor.string().isNotEmpty() && cursor.hasRemaining()) {
            "Host-bound SSH authentication has an invalid host key"
        }
    }

    private fun sshBlobAlgorithm(blob: ByteArray): String =
        SshCursor(blob).string().decodeToString(throwOnInvalidSequence = true)

    private fun sshFingerprint(blob: ByteArray): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(blob),
        )

    private fun decodeBase64(value: String, field: String): ByteArray = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrElse { throw IllegalArgumentException("Invalid $field", it) }

    companion object {
        const val METHOD = "SshAuthenticate"
        private const val INVOCATION_TOKEN_BYTES = 32
        private const val SSH_MSG_USERAUTH_REQUEST = 50
        private const val SSH_ED25519 = "ssh-ed25519"
        private const val SSH_RSA = "ssh-rsa"
        private val SSH_CONNECTION = "ssh-connection".encodeToByteArray()
        private const val RESULT_APPROVED = "APPROVED"
        private const val RESULT_DENIED = "DENIED"
        private const val RESULT_ABORTED = "ABORTED"
    }
}

internal fun sshSignatureBlob(algorithm: SshSignatureAlgorithm, signature: ByteArray): ByteArray =
    ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(algorithm.wireName.encodeToByteArray().size)
            output.write(algorithm.wireName.encodeToByteArray())
            output.writeInt(signature.size)
            output.write(signature)
        }
        bytes.toByteArray()
    }

private class SshCursor(input: ByteArray) {
    private val value = input
    private var offset = 0

    fun hasRemaining(): Boolean = offset < value.size

    fun byte(): Int {
        require(offset < value.size) { "Truncated SSH data" }
        return value[offset++].toInt() and 0xff
    }

    fun string(): ByteArray {
        require(value.size - offset >= Int.SIZE_BYTES) { "Truncated SSH data" }
        val size = ((value[offset].toInt() and 0xff) shl 24) or
            ((value[offset + 1].toInt() and 0xff) shl 16) or
            ((value[offset + 2].toInt() and 0xff) shl 8) or
            (value[offset + 3].toInt() and 0xff)
        offset += Int.SIZE_BYTES
        require(size >= 0 && size <= value.size - offset) { "Truncated SSH data" }
        return value.copyOfRange(offset, offset + size).also { offset += size }
    }

    fun requireEnd() {
        require(offset == value.size) { "Unexpected trailing SSH data" }
    }
}

@Serializable
private data class SshAuthenticationRequestWire(
    val method: String,
    @SerialName("invocation_id") val invocationId: String,
    @SerialName("invocation_token") val invocationToken: String,
    val secret: String,
    val message: String,
)

@Serializable
private data class SshAuthenticationResultWire(
    val result: String,
    val signature: String? = null,
    val reason: String? = null,
    val message: String? = null,
)
