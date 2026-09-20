package dev.agentknock.storage.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Real AES keys with a replaceable lifetime, so tests can simulate restoring without Keystore keys.
 */
internal class InMemoryEncryptionKeyStore : EncryptionKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()

    override fun get(keyId: String): SecretKey? = keys[keyId]

    override fun generate(keyId: String): GeneratedEncryptionKey {
        check(keyId !in keys)
        keys[keyId] =
            KeyGenerator.getInstance("AES").run {
                init(128)
                generateKey()
            }
        return GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)
    }

    override fun delete(keyId: String) {
        keys.remove(keyId)
    }
}
