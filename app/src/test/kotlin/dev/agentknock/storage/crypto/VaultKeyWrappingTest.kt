package dev.agentknock.storage.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultKeyWrappingTest {
    private val root = ByteArray(16) { (it + 1).toByte() }
    private val vaultKey = ByteArray(16) { (it + 33).toByte() }

    @Test
    fun `wraps and unwraps an AES-128 vault key`() {
        val wrapped = VaultKeyWrapping.wrap(
            recoveryRootId = "root-id",
            recoveryRoot = root,
            vaultKeyId = "vault-key-id",
            purpose = VaultKeyPurpose.SECRET_VALUES,
            vaultKeyMaterial = vaultKey,
        )

        val result = VaultKeyWrapping.unwrap(
            wrapped = wrapped,
            recoveryRoot = root,
            vaultKeyId = "vault-key-id",
            purpose = VaultKeyPurpose.SECRET_VALUES,
        )

        assertTrue(result is VaultKeyUnwrapResult.KeyMaterial)
        assertArrayEquals(vaultKey, (result as VaultKeyUnwrapResult.KeyMaterial).value)
        assertEquals(12, wrapped.nonce.size)
        assertEquals(32, wrapped.ciphertext.size)
    }

    @Test
    fun `binds the recovery root id vault key id and purpose`() {
        val wrapped = wrapped()

        val wrongRootId = VaultKeyWrapping.unwrap(
            wrapped.copy(recoveryRootId = "other-root"),
            root,
            "vault-key-id",
            VaultKeyPurpose.SECRET_VALUES,
        )
        val wrongVaultKeyId = VaultKeyWrapping.unwrap(
            wrapped,
            root,
            "other-vault-key",
            VaultKeyPurpose.SECRET_VALUES,
        )
        val wrongPurpose = VaultKeyWrapping.unwrap(
            wrapped,
            root,
            "vault-key-id",
            VaultKeyPurpose.DEVICE_STATE,
        )

        assertEquals(VaultKeyUnwrapResult.AuthenticationFailed, wrongRootId)
        assertEquals(VaultKeyUnwrapResult.AuthenticationFailed, wrongVaultKeyId)
        assertEquals(VaultKeyUnwrapResult.AuthenticationFailed, wrongPurpose)
    }

    @Test
    fun `rejects a wrong root and modified ciphertext`() {
        val wrapped = wrapped()
        val wrongRoot = ByteArray(16) { (it + 2).toByte() }
        val modified = wrapped.copy(ciphertext = wrapped.ciphertext.copyOf().apply { this[0]++ })

        assertEquals(
            VaultKeyUnwrapResult.AuthenticationFailed,
            VaultKeyWrapping.unwrap(
                wrapped,
                wrongRoot,
                "vault-key-id",
                VaultKeyPurpose.SECRET_VALUES,
            ),
        )
        assertEquals(
            VaultKeyUnwrapResult.AuthenticationFailed,
            VaultKeyWrapping.unwrap(
                modified,
                root,
                "vault-key-id",
                VaultKeyPurpose.SECRET_VALUES,
            ),
        )
    }

    @Test
    fun `rejects a blank vault key id before unwrapping`() {
        assertEquals(
            VaultKeyUnwrapResult.UnsupportedFormat,
            VaultKeyWrapping.unwrap(
                wrapped = wrapped(),
                recoveryRoot = root,
                vaultKeyId = "",
                purpose = VaultKeyPurpose.SECRET_VALUES,
            ),
        )
    }

    private fun wrapped() = VaultKeyWrapping.wrap(
        recoveryRootId = "root-id",
        recoveryRoot = root,
        vaultKeyId = "vault-key-id",
        purpose = VaultKeyPurpose.SECRET_VALUES,
        vaultKeyMaterial = vaultKey,
    )
}
