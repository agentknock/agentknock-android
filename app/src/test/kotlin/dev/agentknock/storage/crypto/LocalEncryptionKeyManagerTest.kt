package dev.agentknock.storage.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEncryptionKeyManagerTest {
    @Test
    fun `creates the first local encryption key`() = runTest {
        val dao = FakeLocalEncryptionDao()
        val keyStore = FakeEncryptionKeyStore()
        val manager = manager(dao, keyStore, "first-key")

        val result = manager.initialize()

        assertTrue(result is LocalStorageInitialization.Created)
        assertEquals("first-key", result.activeKey.id)
        assertEquals("first-key", dao.state?.activeKeyId)
        assertEquals(listOf("first-key"), dao.keys.map { it.id })
        assertEquals(EncryptionKeyBacking.SOFTWARE.name, dao.keys.single().backing)
    }

    @Test
    fun `reuses an available active key`() = runTest {
        val dao = FakeLocalEncryptionDao().apply {
            keys += keyMetadata("existing-key")
            state = LocalEncryptionStateEntity(0, "existing-key")
        }
        val keyStore = FakeEncryptionKeyStore().apply { generate("existing-key") }
        val manager = manager(dao, keyStore, "unused-key")

        val result = manager.initialize()

        assertTrue(result is LocalStorageInitialization.Ready)
        assertEquals("existing-key", result.activeKey.id)
        assertEquals(listOf("existing-key"), keyStore.generatedKeyIds)
    }

    @Test
    fun `creates a new generation after restore and preserves old metadata`() = runTest {
        val dao = FakeLocalEncryptionDao().apply {
            keys += keyMetadata("unavailable-key")
            state = LocalEncryptionStateEntity(0, "unavailable-key")
        }
        val keyStore = FakeEncryptionKeyStore()
        val manager = manager(dao, keyStore, "replacement-key")

        val result = manager.initialize()

        assertTrue(result is LocalStorageInitialization.RecoveredWithoutKeys)
        result as LocalStorageInitialization.RecoveredWithoutKeys
        assertEquals("unavailable-key", result.unavailableKeyId)
        assertEquals("replacement-key", result.activeKey.id)
        assertEquals("replacement-key", dao.state?.activeKeyId)
        assertEquals(
            listOf("unavailable-key", "replacement-key"),
            dao.keys.map { it.id },
        )
    }

    @Test
    fun `initializes only once per process`() = runTest {
        val dao = FakeLocalEncryptionDao()
        val keyStore = FakeEncryptionKeyStore()
        val manager = manager(dao, keyStore, "first-key")

        val first = manager.initialize()
        val second = manager.initialize()

        assertSame(first, second)
        assertEquals(listOf("first-key"), keyStore.generatedKeyIds)
    }

    private fun manager(
        dao: FakeLocalEncryptionDao,
        keyStore: FakeEncryptionKeyStore,
        keyId: String,
    ) = LocalEncryptionKeyManager(
        dao = dao,
        keyStore = keyStore,
        newKeyId = { keyId },
        currentTimeMillis = { 1234L },
    )

    private fun keyMetadata(id: String) = LocalEncryptionKeyEntity(
        id = id,
        createdAt = 1L,
        backing = EncryptionKeyBacking.SOFTWARE.name,
    )
}

internal class FakeLocalEncryptionDao : LocalEncryptionDao {
    val keys = mutableListOf<LocalEncryptionKeyEntity>()
    var state: LocalEncryptionStateEntity? = null

    override suspend fun getState(): LocalEncryptionStateEntity? = state

    override suspend fun countKeys(): Int = keys.size

    override suspend fun getKeyIds(): List<String> = keys.map { it.id }

    override suspend fun getKey(id: String): LocalEncryptionKeyEntity? =
        keys.find { it.id == id }

    override suspend fun insertKey(key: LocalEncryptionKeyEntity) {
        check(keys.none { it.id == key.id })
        keys += key
    }

    override suspend fun upsertState(state: LocalEncryptionStateEntity) {
        this.state = state
    }
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

    override fun delete(keyId: String) {
        keys.remove(keyId)
    }
}
