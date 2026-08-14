package dev.agentknock.protocol

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

internal data class RouteKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

internal object VaultProtocol {
    private val baseDerivationSalt = "agentknock-v1".encodeToByteArray()
    private val routeDerivationInfo = "agentknock-v1 route".encodeToByteArray()

    fun validAddress(address: String): Boolean =
        address.isNotEmpty() && address.all { character ->
            character in 'a'..'z' || character == '-'
        }

    fun routeId(address: String): String = derive(
        input = address.encodeToByteArray(),
        salt = baseDerivationSalt,
        info = routeDerivationInfo,
        length = 16,
    ).toHex()

    fun generateRouteKeyPair(random: SecureRandom = SecureRandom()): RouteKeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return RouteKeyPair(
            privateKey = privateKey.encoded,
            publicKey = privateKey.generatePublicKey().encoded,
        )
    }

    fun generateAuthenticationToken(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(32).also(random::nextBytes)

    fun encodeAuthenticationToken(token: ByteArray): String {
        require(token.size == 32) { "A relay authentication token must be 32 bytes" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token)
    }

    private fun derive(
        input: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val output = ByteArray(length)
        HKDFBytesGenerator(SHA256Digest()).run {
            init(HKDFParameters(input, salt, info))
            generateBytes(output, 0, output.size)
        }
        return output
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
