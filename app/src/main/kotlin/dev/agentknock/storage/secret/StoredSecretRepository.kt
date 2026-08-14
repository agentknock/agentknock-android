package dev.agentknock.storage.secret

import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal data class StoredSecretMetadata(
    val id: String,
    val label: String,
    val createdAt: Long,
    val updatedAt: Long,
)

internal sealed interface StoredSecretValue {
    data class Available(val value: ByteArray) : StoredSecretValue

    data class Unavailable(val keyId: String) : StoredSecretValue

    data object Corrupted : StoredSecretValue

    data object UnsupportedFormat : StoredSecretValue

    data object NotFound : StoredSecretValue
}

internal class StoredSecretRepository(
    private val dao: StoredSecretDao,
    private val keyManager: LocalEncryptionKeyManager,
    private val encryption: AesGcmEncryption,
    private val newSecretId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun observeMetadata(): Flow<List<StoredSecretMetadata>> = dao.observeMetadata().map { rows ->
        rows.map { row ->
            StoredSecretMetadata(
                id = row.id,
                label = row.label,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
            )
        }
    }

    suspend fun create(label: String, value: ByteArray): String {
        require(label.isNotBlank()) { "A stored secret must have a label" }
        val id = newSecretId()
        val key = keyManager.activeKey()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(key.id, location(id), value)
        }
        val now = currentTimeMillis()
        dao.insert(
            StoredSecretEntity(
                id = id,
                label = label,
                encryptionFormat = encrypted.formatVersion,
                encryptionKeyId = encrypted.keyId,
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun readValue(id: String): StoredSecretValue {
        val secret = dao.get(id) ?: return StoredSecretValue.NotFound
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = EncryptedValue(
                    formatVersion = secret.encryptionFormat,
                    keyId = secret.encryptionKeyId,
                    nonce = secret.nonce,
                    ciphertext = secret.ciphertext,
                ),
                location = location(id),
            )
        }
        return when (result) {
            is DecryptionResult.Plaintext -> StoredSecretValue.Available(result.value)
            DecryptionResult.KeyUnavailable ->
                StoredSecretValue.Unavailable(secret.encryptionKeyId)
            DecryptionResult.AuthenticationFailed -> StoredSecretValue.Corrupted
            DecryptionResult.UnsupportedFormat -> StoredSecretValue.UnsupportedFormat
        }
    }

    suspend fun replaceValue(id: String, value: ByteArray): Boolean {
        if (dao.get(id) == null) return false
        val key = keyManager.activeKey()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(key.id, location(id), value)
        }
        return dao.replaceValue(
            id = id,
            format = encrypted.formatVersion,
            keyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            updatedAt = currentTimeMillis(),
        ) == 1
    }

    suspend fun rename(id: String, label: String): Boolean {
        require(label.isNotBlank()) { "A stored secret must have a label" }
        return dao.rename(id, label, currentTimeMillis()) == 1
    }

    suspend fun delete(id: String): Boolean {
        val secret = dao.get(id) ?: return false
        dao.delete(secret)
        return true
    }

    private fun location(id: String) = EncryptionLocation(
        recordType = "stored_secret",
        recordId = id,
        fieldName = "value",
    )
}
