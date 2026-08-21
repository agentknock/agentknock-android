package dev.agentknock.storage.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultKeyManagerTest {
    @Test
    fun `creates separate keys for secret values and device state`() = runTest {
        val dao = FakeVaultKeyDao()
        val keyStore = FakeEncryptionKeyStore()
        val manager = manager(dao, keyStore, "secret-key", "device-key")

        val result = manager.initialize()

        assertEquals(
            mapOf(
                VaultKeyPurpose.SECRET_VALUES to ActiveVaultKey("secret-key"),
                VaultKeyPurpose.DEVICE_STATE to ActiveVaultKey("device-key"),
            ),
            result.activeKeys,
        )
        assertTrue(result.unavailableKeyIds.isEmpty())
        assertEquals(
            mapOf(
                VaultKeyPurpose.SECRET_VALUES.storedName to "secret-key",
                VaultKeyPurpose.DEVICE_STATE.storedName to "device-key",
            ),
            dao.activeKeyIds(),
        )
        assertTrue(dao.keys.all { it.recoveryRootId == null })
    }

    @Test
    fun `reuses available active keys`() = runTest {
        val dao = FakeVaultKeyDao().apply {
            keys += keyMetadata("secret-key", VaultKeyPurpose.SECRET_VALUES)
            keys += keyMetadata("device-key", VaultKeyPurpose.DEVICE_STATE)
        }
        val keyStore = FakeEncryptionKeyStore().apply {
            generate("secret-key")
            generate("device-key")
        }
        val manager = manager(dao, keyStore, "unused-key")

        val result = manager.initialize()

        assertTrue(result.unavailableKeyIds.isEmpty())
        assertEquals("secret-key", result.activeKeys[VaultKeyPurpose.SECRET_VALUES]?.id)
        assertEquals("device-key", result.activeKeys[VaultKeyPurpose.DEVICE_STATE]?.id)
        assertEquals(listOf("secret-key", "device-key"), keyStore.generatedKeyIds)
    }

    @Test
    fun `replaces only a key missing after restore and preserves its metadata`() = runTest {
        val dao = FakeVaultKeyDao().apply {
            keys += keyMetadata("unavailable-secret-key", VaultKeyPurpose.SECRET_VALUES)
            keys += keyMetadata("device-key", VaultKeyPurpose.DEVICE_STATE)
        }
        val keyStore = FakeEncryptionKeyStore().apply { generate("device-key") }
        val manager = manager(dao, keyStore, "replacement-secret-key")

        val result = manager.initialize()

        assertEquals(
            mapOf(VaultKeyPurpose.SECRET_VALUES to "unavailable-secret-key"),
            result.unavailableKeyIds,
        )
        assertEquals(
            "replacement-secret-key",
            result.activeKeys[VaultKeyPurpose.SECRET_VALUES]?.id,
        )
        assertEquals("device-key", result.activeKeys[VaultKeyPurpose.DEVICE_STATE]?.id)
        assertEquals(
            listOf("unavailable-secret-key", "device-key", "replacement-secret-key"),
            dao.keys.map { it.id },
        )
        assertEquals(
            mapOf(
                VaultKeyPurpose.SECRET_VALUES.storedName to "replacement-secret-key",
                VaultKeyPurpose.DEVICE_STATE.storedName to "device-key",
            ),
            dao.activeKeyIds(),
        )
    }

    @Test
    fun `initializes only once per process`() = runTest {
        val dao = FakeVaultKeyDao()
        val keyStore = FakeEncryptionKeyStore()
        val manager = manager(dao, keyStore, "secret-key", "device-key")

        val first = manager.initialize()
        val second = manager.initialize()

        assertSame(first, second)
        assertEquals(listOf("secret-key", "device-key"), keyStore.generatedKeyIds)
    }

    private fun manager(
        dao: FakeVaultKeyDao,
        keyStore: FakeEncryptionKeyStore,
        vararg keyIds: String,
    ): VaultKeyManager {
        val ids = ArrayDeque(keyIds.toList())
        return VaultKeyManager(
            dao = dao,
            keyStore = keyStore,
            newKeyId = { ids.removeFirst() },
            currentTimeMillis = { 1234L },
        )
    }

    private fun keyMetadata(id: String, purpose: VaultKeyPurpose) = VaultKeyEntity(
        id = id,
        purpose = purpose.storedName,
        active = true,
        createdAt = 1L,
        backing = EncryptionKeyBacking.SOFTWARE.name,
    )
}

internal class FakeVaultKeyDao : VaultKeyDao {
    val keys = mutableListOf<VaultKeyEntity>()

    override suspend fun getActiveKeys(purpose: String): List<VaultKeyEntity> =
        keys.filter { it.purpose == purpose && it.active }.sortedByDescending { it.createdAt }

    override suspend fun countKeys(purpose: String): Int = keys.count { it.purpose == purpose }

    override suspend fun getKeyIds(): List<String> = keys.map { it.id }

    override suspend fun getKey(id: String): VaultKeyEntity? = keys.find { it.id == id }

    override suspend fun insertKey(key: VaultKeyEntity) {
        check(keys.none { it.id == key.id })
        keys += key
    }

    override suspend fun deactivate(purpose: String) {
        keys.replaceAll { key ->
            if (key.purpose == purpose) key.copy(active = false) else key
        }
    }

    fun activeKeyIds(): Map<String, String> = keys
        .filter(VaultKeyEntity::active)
        .associate { it.purpose to it.id }
}

internal class FakeEncryptionKeyStore : EncryptionKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()
    val generatedKeyIds = mutableListOf<String>()

    override fun contains(keyId: String): Boolean = keyId in keys

    override fun get(keyId: String): SecretKey? = keys[keyId]

    override fun generate(keyId: String): GeneratedEncryptionKey {
        check(keyId !in keys)
        keys[keyId] = KeyGenerator.getInstance("AES").run {
            init(128)
            generateKey()
        }
        generatedKeyIds += keyId
        return GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)
    }

    override fun importKey(keyId: String, keyMaterial: ByteArray): GeneratedEncryptionKey {
        check(keyId !in keys)
        require(keyMaterial.size == 16)
        keys[keyId] = SecretKeySpec(keyMaterial.copyOf(), "AES")
        return GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)
    }

    override fun delete(keyId: String) {
        keys.remove(keyId)
    }
}
