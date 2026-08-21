package dev.agentknock.protocol

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

internal data class DeviceKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

internal object DeviceProtocol {
    private val baseDerivationSalt = "agentknock-v1".encodeToByteArray()
    private val addressDerivationInfo = "agentknock-v1 address".encodeToByteArray()

    fun validPairingAddress(address: String): Boolean = address
        .split('-')
        .all { word -> word.isNotEmpty() && word.all { it in 'a'..'z' } }

    fun addressId(address: String): String = derive(
        input = address.encodeToByteArray(),
        salt = baseDerivationSalt,
        info = addressDerivationInfo,
        length = 16,
    ).toHex()

    fun generateDeviceId(
        timestampMillis: Long = System.currentTimeMillis(),
        random: SecureRandom = SecureRandom(),
    ): String {
        require(timestampMillis in 0..MAX_ULID_TIMESTAMP) { "Invalid ULID timestamp" }
        val bytes = ByteArray(ULID_BYTES)
        var timestamp = timestampMillis
        for (index in 5 downTo 0) {
            bytes[index] = timestamp.toByte()
            timestamp = timestamp ushr Byte.SIZE_BITS
        }
        random.nextBytes(bytes, 6, bytes.size)

        var value = BigInteger(1, bytes)
        return CharArray(ULID_CHARACTERS) { index ->
            val shift = (ULID_CHARACTERS - index - 1) * 5
            ULID_ALPHABET[value.shiftRight(shift).and(ULID_MASK).toInt()]
        }.concatToString()
    }

    fun generateDeviceKeyPair(random: SecureRandom = SecureRandom()): DeviceKeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return DeviceKeyPair(
            privateKey = privateKey.encoded,
            publicKey = privateKey.generatePublicKey().encoded,
        )
    }

    fun generateDeviceToken(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(32).also(random::nextBytes)

    fun encodeDeviceToken(deviceToken: ByteArray): String {
        require(deviceToken.size == 32) { "A relay device token must be 32 bytes" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(deviceToken)
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

    private fun SecureRandom.nextBytes(bytes: ByteArray, fromIndex: Int, toIndex: Int) {
        val random = ByteArray(toIndex - fromIndex)
        nextBytes(random)
        random.copyInto(bytes, fromIndex)
    }

    private const val ULID_BYTES = 16
    private const val ULID_CHARACTERS = 26
    private const val MAX_ULID_TIMESTAMP = 0xffff_ffff_ffffL
    private const val ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val ULID_MASK = BigInteger.valueOf(31)
}
