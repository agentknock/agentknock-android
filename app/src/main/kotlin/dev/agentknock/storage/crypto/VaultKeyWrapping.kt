package dev.agentknock.storage.crypto

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class WrappedVaultKey(
    val formatVersion: Int,
    val recoveryRootId: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

internal sealed interface VaultKeyUnwrapResult {
    data class KeyMaterial(val value: ByteArray) : VaultKeyUnwrapResult

    data object AuthenticationFailed : VaultKeyUnwrapResult

    data object UnsupportedFormat : VaultKeyUnwrapResult
}

/**
 * Wraps exportable vault keys when recovery is enabled. A normal installation has no recovery
 * root and keeps its vault keys non-exportable in Android Keystore instead.
 */
internal object VaultKeyWrapping {
    fun wrap(
        recoveryRootId: String,
        recoveryRoot: ByteArray,
        vaultKeyId: String,
        purpose: VaultKeyPurpose,
        vaultKeyMaterial: ByteArray,
    ): WrappedVaultKey {
        require(recoveryRootId.isNotBlank()) { "Recovery-root ID must not be blank" }
        require(vaultKeyId.isNotBlank()) { "Vault-key ID must not be blank" }
        require(recoveryRoot.size == KEY_BYTES) { "Recovery roots must be AES-128 keys" }
        require(vaultKeyMaterial.size == KEY_BYTES) { "Vault keys must be AES-128 keys" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(recoveryRoot, "AES"))
        check(cipher.iv.size == NONCE_BYTES) { "AES-GCM provider returned a non-standard nonce" }
        cipher.updateAAD(associatedData(FORMAT_VERSION, recoveryRootId, vaultKeyId, purpose))
        return WrappedVaultKey(
            formatVersion = FORMAT_VERSION,
            recoveryRootId = recoveryRootId,
            nonce = cipher.iv,
            ciphertext = cipher.doFinal(vaultKeyMaterial),
        )
    }

    fun unwrap(
        wrapped: WrappedVaultKey,
        recoveryRoot: ByteArray,
        vaultKeyId: String,
        purpose: VaultKeyPurpose,
    ): VaultKeyUnwrapResult {
        if (
            wrapped.formatVersion != FORMAT_VERSION ||
            wrapped.recoveryRootId.isBlank() ||
            wrapped.nonce.size != NONCE_BYTES ||
            wrapped.ciphertext.size != KEY_BYTES + TAG_BYTES
        ) {
            return VaultKeyUnwrapResult.UnsupportedFormat
        }
        require(recoveryRoot.size == KEY_BYTES) { "Recovery roots must be AES-128 keys" }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(recoveryRoot, "AES"),
                GCMParameterSpec(TAG_BITS, wrapped.nonce),
            )
            cipher.updateAAD(
                associatedData(
                    wrapped.formatVersion,
                    wrapped.recoveryRootId,
                    vaultKeyId,
                    purpose,
                ),
            )
            VaultKeyUnwrapResult.KeyMaterial(cipher.doFinal(wrapped.ciphertext))
        } catch (_: AEADBadTagException) {
            VaultKeyUnwrapResult.AuthenticationFailed
        } catch (exception: GeneralSecurityException) {
            throw IllegalStateException("Could not unwrap a vault key", exception)
        }
    }

    private fun associatedData(
        formatVersion: Int,
        recoveryRootId: String,
        vaultKeyId: String,
        purpose: VaultKeyPurpose,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(formatVersion)
            output.writeLengthPrefixed(DOMAIN)
            output.writeLengthPrefixed(recoveryRootId)
            output.writeLengthPrefixed(vaultKeyId)
            output.writeLengthPrefixed(purpose.storedName)
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeLengthPrefixed(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FORMAT_VERSION = 1
    private const val KEY_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8
    private const val DOMAIN = "dev.agentknock.wrapped-vault-key"
}
