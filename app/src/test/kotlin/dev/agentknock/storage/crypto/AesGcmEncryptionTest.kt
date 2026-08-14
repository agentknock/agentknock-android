package dev.agentknock.storage.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmEncryptionTest {
    private val key = KeyGenerator.getInstance("AES").run {
        init(128)
        generateKey()
    }
    private val keys = MapKeySource(mapOf(KEY_ID to key))
    private val encryption = AesGcmEncryption(keys)
    private val location = EncryptionLocation("stored_secret", "secret-id", "value")

    @Test
    fun `encrypts and decrypts a value`() {
        val plaintext = "not for sqlite".toByteArray()

        val encrypted = encryption.encrypt(KEY_ID, location, plaintext)

        assertEquals(1, encrypted.formatVersion)
        assertEquals(12, encrypted.nonce.size)
        assertFalse(plaintext.contentEquals(encrypted.ciphertext))
        val result = encryption.decrypt(encrypted, location)
        assertTrue(result is DecryptionResult.Plaintext)
        assertArrayEquals(plaintext, (result as DecryptionResult.Plaintext).value)
    }

    @Test
    fun `uses a fresh nonce for each encryption`() {
        val first = encryption.encrypt(KEY_ID, location, byteArrayOf(1))
        val second = encryption.encrypt(KEY_ID, location, byteArrayOf(1))

        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun `rejects ciphertext moved to another row`() {
        val encrypted = encryption.encrypt(KEY_ID, location, byteArrayOf(1, 2, 3))
        val otherLocation = location.copy(recordId = "other-secret-id")

        assertEquals(
            DecryptionResult.AuthenticationFailed,
            encryption.decrypt(encrypted, otherLocation),
        )
    }

    @Test
    fun `reports tampered ciphertext`() {
        val encrypted = encryption.encrypt(KEY_ID, location, byteArrayOf(1, 2, 3))
        encrypted.ciphertext[0] = (encrypted.ciphertext[0].toInt() xor 1).toByte()

        assertEquals(
            DecryptionResult.AuthenticationFailed,
            encryption.decrypt(encrypted, location),
        )
    }

    @Test
    fun `reports an unavailable key without treating the value as corrupt`() {
        val encrypted = encryption.encrypt(KEY_ID, location, byteArrayOf(1, 2, 3))
        val restoredEncryption = AesGcmEncryption(MapKeySource(emptyMap()))

        assertEquals(
            DecryptionResult.KeyUnavailable,
            restoredEncryption.decrypt(encrypted, location),
        )
    }

    private class MapKeySource(
        private val keys: Map<String, SecretKey>,
    ) : EncryptionKeySource {
        override fun get(keyId: String): SecretKey? = keys[keyId]
    }

    private companion object {
        const val KEY_ID = "key-id"
    }
}
