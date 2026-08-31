package dev.agentknock.storage.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEncryptionKeyStoreTest {
    @Test
    fun importsAnAes128VaultKeyAsNonExportableKeyMaterial() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyStore = AndroidEncryptionKeyStore(context.packageManager)
        val keyId = "instrumentation-${UUID.randomUUID()}"
        val keyMaterial = ByteArray(16) { (it + 1).toByte() }

        try {
            keyStore.importKey(keyId, keyMaterial)

            assertTrue(keyStore.get(keyId) != null)
            assertTrue(keyId in keyStore.managedKeyIds())
            assertNull(keyStore.get(keyId)?.encoded)

            val encryption = AesGcmEncryption(keyStore)
            val location = EncryptionLocation("test", "record", "field")
            val plaintext = "vault value".encodeToByteArray()
            val encrypted = encryption.encrypt(keyId, location, plaintext)
            val decrypted = encryption.decrypt(encrypted, location)
            assertTrue(decrypted is DecryptionResult.Plaintext)
            assertArrayEquals(plaintext, (decrypted as DecryptionResult.Plaintext).value)
        } finally {
            keyStore.delete(keyId)
            keyMaterial.fill(0)
        }
    }
}
