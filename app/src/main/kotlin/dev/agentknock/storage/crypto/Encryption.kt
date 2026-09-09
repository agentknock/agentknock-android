package dev.agentknock.storage.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.room3.ColumnInfo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptionLocation(
    val recordType: String,
    val recordId: String,
    val fieldName: String,
    val bindings: List<EncryptionBinding> = emptyList(),
)

internal data class EncryptionBinding(
    val name: String,
    val value: String,
)

internal data class EncryptedValue(
    @ColumnInfo(name = "encryption_format") val formatVersion: Int,
    @ColumnInfo(name = "encryption_key_id") val keyId: String,
    @ColumnInfo(name = "nonce") val nonce: ByteArray,
    @ColumnInfo(name = "ciphertext") val ciphertext: ByteArray,
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

internal class AesGcmEncryption(private val keys: EncryptionKeySource) {
    fun encrypt(
        keyId: String,
        location: EncryptionLocation,
        plaintext: ByteArray,
    ): EncryptedValue {
        require(keyId.isNotBlank()) { "Encryption-key ID must not be blank" }
        val key = checkNotNull(keys.get(keyId)) { "Encryption key is unavailable: $keyId" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == NONCE_BYTES) { "AES-GCM provider returned a non-standard nonce" }
        cipher.updateAAD(associatedData(FORMAT_VERSION, keyId, location))
        val ciphertext = cipher.doFinal(plaintext)
        check(ciphertext.size == plaintext.size + AUTHENTICATION_TAG_BYTES) {
            "AES-GCM provider returned a non-standard authentication tag"
        }
        return EncryptedValue(
            formatVersion = FORMAT_VERSION,
            keyId = keyId,
            nonce = cipher.iv,
            ciphertext = ciphertext,
        )
    }

    fun decrypt(
        encrypted: EncryptedValue,
        location: EncryptionLocation,
    ): DecryptionResult {
        if (
            encrypted.formatVersion != FORMAT_VERSION ||
                encrypted.keyId.isBlank() ||
                encrypted.nonce.size != NONCE_BYTES
        ) {
            return DecryptionResult.UnsupportedFormat
        }
        if (encrypted.ciphertext.size < AUTHENTICATION_TAG_BYTES) {
            return DecryptionResult.AuthenticationFailed
        }
        val key =
            try {
                keys.get(encrypted.keyId)
            } catch (_: UnrecoverableKeyException) {
                null
            } catch (_: KeyPermanentlyInvalidatedException) {
                null
            } ?: return DecryptionResult.KeyUnavailable
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
        } catch (_: KeyPermanentlyInvalidatedException) {
            DecryptionResult.KeyUnavailable
        } catch (exception: GeneralSecurityException) {
            throw IllegalStateException("Could not decrypt an encrypted value", exception)
        }
    }

    private fun associatedData(
        formatVersion: Int,
        keyId: String,
        location: EncryptionLocation,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(formatVersion)
                output.writeLengthPrefixed(DOMAIN)
                output.writeLengthPrefixed(keyId)
                output.writeLengthPrefixed(location.recordType)
                output.writeLengthPrefixed(location.recordId)
                output.writeLengthPrefixed(location.fieldName)
                val bindings = location.bindings.sortedBy(EncryptionBinding::name)
                require(bindings.map(EncryptionBinding::name).distinct().size == bindings.size) {
                    "Encryption binding names must be unique"
                }
                output.writeInt(bindings.size)
                bindings.forEach { binding ->
                    output.writeLengthPrefixed(binding.name)
                    output.writeLengthPrefixed(binding.value)
                }
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
        private const val AUTHENTICATION_TAG_BYTES = AUTHENTICATION_TAG_BITS / Byte.SIZE_BITS
        private const val DOMAIN = "dev.agentknock.encrypted-value"
    }
}
