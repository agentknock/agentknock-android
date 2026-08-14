package dev.agentknock.storage.crypto

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptionLocation(
    val recordType: String,
    val recordId: String,
    val fieldName: String,
)

internal data class EncryptedValue(
    val formatVersion: Int,
    val keyId: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

internal sealed interface DecryptionResult {
    data class Plaintext(val value: ByteArray) : DecryptionResult

    data object KeyUnavailable : DecryptionResult

    data object AuthenticationFailed : DecryptionResult

    data object UnsupportedFormat : DecryptionResult
}

internal interface EncryptionKeySource {
    fun get(keyId: String): SecretKey?
}

internal class AesGcmEncryption(
    private val keys: EncryptionKeySource,
) {
    fun encrypt(
        keyId: String,
        location: EncryptionLocation,
        plaintext: ByteArray,
    ): EncryptedValue {
        val key = checkNotNull(keys.get(keyId)) { "Encryption key is unavailable: $keyId" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == NONCE_BYTES) { "AES-GCM provider returned a non-standard nonce" }
        cipher.updateAAD(associatedData(FORMAT_VERSION, keyId, location))
        return EncryptedValue(
            formatVersion = FORMAT_VERSION,
            keyId = keyId,
            nonce = cipher.iv,
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    fun decrypt(
        encrypted: EncryptedValue,
        location: EncryptionLocation,
    ): DecryptionResult {
        if (encrypted.formatVersion != FORMAT_VERSION || encrypted.nonce.size != NONCE_BYTES) {
            return DecryptionResult.UnsupportedFormat
        }
        val key = keys.get(encrypted.keyId) ?: return DecryptionResult.KeyUnavailable
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(AUTHENTICATION_TAG_BITS, encrypted.nonce),
            )
            cipher.updateAAD(associatedData(encrypted.formatVersion, encrypted.keyId, location))
            DecryptionResult.Plaintext(cipher.doFinal(encrypted.ciphertext))
        } catch (_: AEADBadTagException) {
            DecryptionResult.AuthenticationFailed
        } catch (exception: GeneralSecurityException) {
            throw IllegalStateException("Could not decrypt an encrypted value", exception)
        }
    }

    private fun associatedData(
        formatVersion: Int,
        keyId: String,
        location: EncryptionLocation,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(formatVersion)
            output.writeLengthPrefixed(DOMAIN)
            output.writeLengthPrefixed(keyId)
            output.writeLengthPrefixed(location.recordType)
            output.writeLengthPrefixed(location.recordId)
            output.writeLengthPrefixed(location.fieldName)
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeLengthPrefixed(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val NONCE_BYTES = 12
        private const val AUTHENTICATION_TAG_BITS = 128
        private const val DOMAIN = "dev.agentknock.encrypted-value"
    }
}
