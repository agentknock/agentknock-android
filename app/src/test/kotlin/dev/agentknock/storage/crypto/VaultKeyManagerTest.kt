package dev.agentknock.storage.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultKeyManagerTest {
    @Test
    fun `encryption key backing identifiers preserve their schema 1 encoding`() {
        val expected =
            mapOf(
                EncryptionKeyBacking.STRONGBOX to "STRONGBOX",
                EncryptionKeyBacking.TRUSTED_ENVIRONMENT to "TRUSTED_ENVIRONMENT",
                EncryptionKeyBacking.SOFTWARE to "SOFTWARE",
                EncryptionKeyBacking.UNKNOWN_SECURE to "UNKNOWN_SECURE",
                EncryptionKeyBacking.UNKNOWN to "UNKNOWN",
            )

        expected.forEach { (backing, storedName) ->
            assertEquals(storedName, backing.storedName)
            assertEquals(backing, EncryptionKeyBacking.fromStoredName(storedName))
        }
        assertNull(EncryptionKeyBacking.fromStoredName("future_backing"))
    }

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
        assertEquals(
            mapOf(
                VaultKeyPurpose.SECRET_VALUES.storedName to "secret-key",
                VaultKeyPurpose.DEVICE_STATE.storedName to "device-key",
            ),
            dao.activeKeyIds(),
        )
    }

    @Test
    fun `reuses available active keys`() = runTest {
        val dao =
            FakeVaultKeyDao().apply {
                keys += keyMetadata("secret-key", VaultKeyPurpose.SECRET_VALUES)
                keys += keyMetadata("device-key", VaultKeyPurpose.DEVICE_STATE)
            }
        val keyStore =
            FakeEncryptionKeyStore().apply {
                generate("secret-key")
                generate("device-key")
            }
        val manager = manager(dao, keyStore, "unused-key")

        val result = manager.initialize()

        assertEquals("secret-key", result.activeKeys[VaultKeyPurpose.SECRET_VALUES]?.id)
        assertEquals("device-key", result.activeKeys[VaultKeyPurpose.DEVICE_STATE]?.id)
        assertEquals(listOf("secret-key", "device-key"), keyStore.generatedKeyIds)
    }

    @Test
    fun `replaces only a key missing after restore and preserves its metadata`() = runTest {
        val dao =
            FakeVaultKeyDao().apply {
                keys += keyMetadata("unavailable-secret-key", VaultKeyPurpose.SECRET_VALUES)
                keys += keyMetadata("device-key", VaultKeyPurpose.DEVICE_STATE)
            }
        val keyStore = FakeEncryptionKeyStore().apply { generate("device-key") }
        val manager = manager(dao, keyStore, "replacement-secret-key")

        val result = manager.initialize()

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
    fun `replacement write key does not mask ciphertext that still references the lost key`() =
        runTest {
            val dao =
                FakeVaultKeyDao().apply {
                    keys += keyMetadata("lost-secret-key", VaultKeyPurpose.SECRET_VALUES)
                    keys += keyMetadata("device-key", VaultKeyPurpose.DEVICE_STATE)
                    referencedKeyIds.value = setOf("lost-secret-key")
                }
            val keyStore = FakeEncryptionKeyStore().apply { generate("device-key") }
            val manager = manager(dao, keyStore, "replacement-secret-key")

            val protection = manager.observeProtection().first()

            assertTrue(protection is VaultProtection.ActiveKeysAvailable)
            assertEquals(
                setOf(VaultKeyPurpose.SECRET_VALUES),
                protection.unavailableStoredData,
            )
            assertEquals(
                "replacement-secret-key",
                manager.activeKey(VaultKeyPurpose.SECRET_VALUES).id,
            )
        }

    @Test
    fun `reports when a current write key becomes unavailable`() = runTest {
        val dao =
            FakeVaultKeyDao().apply {
                keys += keyMetadata("secret-key", VaultKeyPurpose.SECRET_VALUES)
                keys += keyMetadata("device-key", VaultKeyPurpose.DEVICE_STATE)
            }
        val keyStore =
            FakeEncryptionKeyStore().apply {
                generate("secret-key")
                generate("device-key")
            }
        val manager = manager(dao, keyStore, "unused-key")
        manager.initialize()
        keyStore.delete("secret-key")

        val protection = manager.observeProtection().first()

        assertEquals(
            VaultProtection.ActiveKeysUnavailable(
                purposes = setOf(VaultKeyPurpose.SECRET_VALUES),
                unavailableStoredData = emptySet(),
            ),
            protection,
        )
    }

    @Test
    fun `protection updates when the last reference to a lost key is removed`() = runTest {
        val dao =
            FakeVaultKeyDao().apply {
                keys += keyMetadata("lost-secret-key", VaultKeyPurpose.SECRET_VALUES)
                keys += keyMetadata("device-key", VaultKeyPurpose.DEVICE_STATE)
                referencedKeyIds.value = setOf("lost-secret-key")
            }
        val keyStore = FakeEncryptionKeyStore().apply { generate("device-key") }
        val manager = manager(dao, keyStore, "replacement-secret-key")
        val protections = mutableListOf<VaultProtection>()
        val firstEmission = CompletableDeferred<Unit>()
        val collection = backgroundScope.launch {
            manager.observeProtection().take(2).collect { protection ->
                protections += protection
                firstEmission.complete(Unit)
            }
        }
        firstEmission.await()

        dao.referencedKeyIds.value = emptySet()
        collection.join()

        assertEquals(
            listOf(setOf(VaultKeyPurpose.SECRET_VALUES), emptySet()),
            protections.map(VaultProtection::unavailableStoredData),
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

    @Test
    fun `deletes a generated key when metadata activation fails`() = runTest {
        val dao = FakeVaultKeyDao().apply { failInsert = true }
        val keyStore = FakeEncryptionKeyStore()
        val manager = manager(dao, keyStore, "secret-key")

        val failure = runCatching { manager.initialize() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(managerKeyAvailable(keyStore, "secret-key"))
        assertEquals(listOf("secret-key"), keyStore.deletedKeyIds)
    }

    @Test
    fun `deletes a partially generated key when generation fails`() = runTest {
        val dao = FakeVaultKeyDao()
        val keyStore =
            FakeEncryptionKeyStore().apply {
                generateFailuresAfterInsert += "secret-key"
            }
        val manager = manager(dao, keyStore, "secret-key")

        val failure = runCatching { manager.initialize() }.exceptionOrNull()

        assertEquals("Generation failed for secret-key", failure?.message)
        assertFalse(managerKeyAvailable(keyStore, "secret-key"))
        assertEquals(listOf("secret-key"), keyStore.deletedKeyIds)
        assertTrue(dao.keys.isEmpty())
    }

    @Test
    fun `keeps metadata failure primary when generated key cleanup also fails`() = runTest {
        val dao = FakeVaultKeyDao().apply { failInsert = true }
        val keyStore = FakeEncryptionKeyStore().apply { deleteFailures += "secret-key" }
        val manager = manager(dao, keyStore, "secret-key")

        val failure = runCatching { manager.initialize() }.exceptionOrNull()
        val metadataFailure = checkNotNull(dao.lastInsertFailure)

        assertEquals("Metadata activation failed", failure?.message)
        assertEquals(1, metadataFailure.suppressed.size)
        assertEquals("Could not delete secret-key", metadataFailure.suppressed.single().message)
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

    private fun keyMetadata(id: String, purpose: VaultKeyPurpose) =
        VaultKeyEntity(
            id = id,
            purpose = purpose.storedName,
            active = true,
            createdAt = 1L,
            backing = EncryptionKeyBacking.SOFTWARE.storedName,
        )

    private fun managerKeyAvailable(keyStore: FakeEncryptionKeyStore, keyId: String): Boolean =
        keyStore.get(keyId) != null
}

internal class FakeVaultKeyDao : VaultKeyDao {
    val keys = mutableListOf<VaultKeyEntity>()
    val referencedKeyIds = MutableStateFlow<Set<String>>(emptySet())
    var failInsert = false
    var lastInsertFailure: IllegalStateException? = null

    override suspend fun getActiveKeys(purpose: String): List<VaultKeyEntity> =
        keys.filter { it.purpose == purpose && it.active }.sortedByDescending { it.createdAt }

    override suspend fun countKeys(purpose: String): Int = keys.count { it.purpose == purpose }

    override suspend fun getKey(id: String): VaultKeyEntity? = keys.find { it.id == id }

    override fun observeReferencedKeys(): Flow<List<VaultKeyEntity>> = referencedKeyIds.map { ids ->
        keys
            .filter { it.id in ids }
            .sortedWith(compareBy(VaultKeyEntity::purpose, VaultKeyEntity::id))
    }

    override suspend fun insertKey(key: VaultKeyEntity) {
        if (failInsert) {
            throw IllegalStateException("Metadata activation failed").also {
                lastInsertFailure = it
            }
        }
        check(keys.none { it.id == key.id })
        keys += key
    }

    override suspend fun deactivate(purpose: String) {
        keys.replaceAll { key ->
            if (key.purpose == purpose) key.copy(active = false) else key
        }
    }

    fun activeKeyIds(): Map<String, String> =
        keys.filter(VaultKeyEntity::active).associate { it.purpose to it.id }
}

internal class FakeEncryptionKeyStore : EncryptionKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()
    val generatedKeyIds = mutableListOf<String>()
    val deletedKeyIds = mutableListOf<String>()
    val deleteFailures = mutableSetOf<String>()
    val generateFailuresAfterInsert = mutableSetOf<String>()

    override fun get(keyId: String): SecretKey? = keys[keyId]

    override fun generate(keyId: String): GeneratedEncryptionKey {
        check(keyId !in keys)
        keys[keyId] =
            KeyGenerator.getInstance("AES").run {
                init(128)
                generateKey()
            }
        generatedKeyIds += keyId
        check(keyId !in generateFailuresAfterInsert) { "Generation failed for $keyId" }
        return GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)
    }

    override fun delete(keyId: String) {
        check(keyId !in deleteFailures) { "Could not delete $keyId" }
        deletedKeyIds += keyId
        keys.remove(keyId)
    }
}
