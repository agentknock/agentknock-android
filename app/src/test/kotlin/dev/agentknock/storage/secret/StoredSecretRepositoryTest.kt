package dev.agentknock.storage.secret

import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.FakeEncryptionKeyStore
import dev.agentknock.storage.crypto.FakeLocalEncryptionDao
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredSecretRepositoryTest {
    @Test
    fun `stores each secret in its own encrypted row`() = runTest {
        val fixture = Fixture()

        fixture.repository.create("Cloudflare token", "first-value".toByteArray())
        fixture.repository.create("GitHub token", "second-value".toByteArray())

        assertEquals(2, fixture.secrets.rows.value.size)
        assertFalse(
            fixture.secrets.rows.value[0].ciphertext.contentEquals("first-value".toByteArray()),
        )
        assertEquals(
            listOf("Cloudflare token", "GitHub token"),
            fixture.repository.observeMetadata().first().map { it.label },
        )
    }

    @Test
    fun `reads and replaces a stored secret`() = runTest {
        val fixture = Fixture()
        val id = fixture.repository.create("API token", "old-value".toByteArray())

        val original = fixture.repository.readValue(id)
        assertTrue(original is StoredSecretValue.Available)
        assertArrayEquals("old-value".toByteArray(), (original as StoredSecretValue.Available).value)

        fixture.repository.replaceValue(id, "new-value".toByteArray())

        val replacement = fixture.repository.readValue(id)
        assertTrue(replacement is StoredSecretValue.Available)
        assertArrayEquals(
            "new-value".toByteArray(),
            (replacement as StoredSecretValue.Available).value,
        )
    }

    @Test
    fun `keeps restored ciphertext unavailable until the value is replaced`() = runTest {
        val original = Fixture(keyId = "original-key")
        val id = original.repository.create("API token", "old-value".toByteArray())
        val restoredKeyMetadata = original.encryptionMetadata
        val restoredSecrets = original.secrets

        val replacementKeyStore = FakeEncryptionKeyStore()
        val replacementManager = LocalEncryptionKeyManager(
            dao = restoredKeyMetadata,
            keyStore = replacementKeyStore,
            newKeyId = { "replacement-key" },
            currentTimeMillis = { 200L },
        )
        val restoredRepository = StoredSecretRepository(
            dao = restoredSecrets,
            keyManager = replacementManager,
            encryption = AesGcmEncryption(replacementKeyStore),
            newSecretId = { error("not creating another secret") },
            currentTimeMillis = { 300L },
        )

        replacementManager.initialize()
        val unavailable = restoredRepository.readValue(id)

        assertEquals(1, restoredSecrets.rows.value.size)
        assertTrue(unavailable is StoredSecretValue.Unavailable)
        assertEquals(
            "original-key",
            (unavailable as StoredSecretValue.Unavailable).keyId,
        )

        restoredRepository.replaceValue(id, "re-entered-value".toByteArray())
        val available = restoredRepository.readValue(id)

        assertEquals(1, restoredSecrets.rows.value.size)
        assertEquals("replacement-key", restoredSecrets.rows.value.single().encryptionKeyId)
        assertTrue(available is StoredSecretValue.Available)
        assertArrayEquals(
            "re-entered-value".toByteArray(),
            (available as StoredSecretValue.Available).value,
        )
    }

    private class Fixture(keyId: String = "storage-key") {
        val encryptionMetadata = FakeLocalEncryptionDao()
        val keyStore = FakeEncryptionKeyStore()
        val secrets = FakeStoredSecretDao()
        private var nextSecret = 0
        private val keyManager = LocalEncryptionKeyManager(
            dao = encryptionMetadata,
            keyStore = keyStore,
            newKeyId = { keyId },
            currentTimeMillis = { 100L },
        )
        val repository = StoredSecretRepository(
            dao = secrets,
            keyManager = keyManager,
            encryption = AesGcmEncryption(keyStore),
            newSecretId = { "secret-${++nextSecret}" },
            currentTimeMillis = { 100L },
        )
    }

    private class FakeStoredSecretDao : StoredSecretDao {
        val rows = MutableStateFlow<List<StoredSecretEntity>>(emptyList())

        override fun observeMetadata(): Flow<List<StoredSecretMetadataRow>> =
            rows.map { secrets -> secrets.map { it.metadata() } }

        override suspend fun get(id: String): StoredSecretEntity? = rows.value.find { it.id == id }

        override suspend fun insert(secret: StoredSecretEntity) {
            check(rows.value.none { it.id == secret.id })
            rows.value += secret
        }

        override suspend fun replaceValue(
            id: String,
            format: Int,
            keyId: String,
            nonce: ByteArray,
            ciphertext: ByteArray,
            updatedAt: Long,
        ): Int = update(id) {
            copy(
                encryptionFormat = format,
                encryptionKeyId = keyId,
                nonce = nonce,
                ciphertext = ciphertext,
                updatedAt = updatedAt,
            )
        }

        override suspend fun rename(id: String, label: String, updatedAt: Long): Int = update(id) {
            copy(label = label, updatedAt = updatedAt)
        }

        override suspend fun delete(secret: StoredSecretEntity) {
            rows.value = rows.value.filterNot { it.id == secret.id }
        }

        private fun update(
            id: String,
            transform: StoredSecretEntity.() -> StoredSecretEntity,
        ): Int {
            if (rows.value.none { it.id == id }) return 0
            rows.value = rows.value.map { if (it.id == id) it.transform() else it }
            return 1
        }

        private fun StoredSecretEntity.metadata() = StoredSecretMetadataRow(
            id = id,
            label = label,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
