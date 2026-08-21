package dev.agentknock.storage.crypto

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class VaultKeyPurpose(val storedName: String) {
    SECRET_VALUES("secret_values"),
    DEVICE_STATE("device_state"),
}

@Entity(
    tableName = "vault_keys",
    indices = [Index(value = ["purpose", "active"])],
)
internal data class VaultKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "purpose")
    val purpose: String,
    @ColumnInfo(name = "active")
    val active: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "backing")
    val backing: String,
    @ColumnInfo(name = "recovery_root_id")
    val recoveryRootId: String? = null,
    @ColumnInfo(name = "wrapping_format")
    val wrappingFormat: Int? = null,
    @ColumnInfo(name = "wrapping_nonce")
    val wrappingNonce: ByteArray? = null,
    @ColumnInfo(name = "wrapped_key")
    val wrappedKey: ByteArray? = null,
)

@Dao
internal interface VaultKeyDao {
    @Query("SELECT * FROM vault_keys WHERE purpose = :purpose AND active = 1 ORDER BY created_at DESC")
    suspend fun getActiveKeys(purpose: String): List<VaultKeyEntity>

    @Query("SELECT count(*) FROM vault_keys WHERE purpose = :purpose")
    suspend fun countKeys(purpose: String): Int

    @Query("SELECT id FROM vault_keys")
    suspend fun getKeyIds(): List<String>

    @Query("SELECT * FROM vault_keys WHERE id = :id")
    suspend fun getKey(id: String): VaultKeyEntity?

    @Insert
    suspend fun insertKey(key: VaultKeyEntity)

    @Query("UPDATE vault_keys SET active = 0 WHERE purpose = :purpose AND active = 1")
    suspend fun deactivate(purpose: String)

    @Transaction
    suspend fun activate(key: VaultKeyEntity) {
        require(key.active) { "A newly activated vault key must be active" }
        deactivate(key.purpose)
        insertKey(key)
    }
}

internal data class ActiveVaultKey(
    val id: String,
)

internal sealed interface VaultProtection {
    data class Available(
        val backings: Map<VaultKeyPurpose, EncryptionKeyBacking>,
    ) : VaultProtection

    data class KeyUnavailable(
        val purposes: Set<VaultKeyPurpose>,
    ) : VaultProtection

    data object Unknown : VaultProtection
}

internal data class VaultKeyInitialization(
    val activeKeys: Map<VaultKeyPurpose, ActiveVaultKey>,
    val unavailableKeyIds: Map<VaultKeyPurpose, String>,
)

internal class VaultKeyManager(
    private val dao: VaultKeyDao,
    private val keyStore: EncryptionKeyStore,
    private val newKeyId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val keyStoreDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val initializationMutex = Mutex()
    private var initialization: VaultKeyInitialization? = null

    suspend fun initialize(): VaultKeyInitialization = initializationMutex.withLock {
        initialization?.let { return@withLock it }

        val activeKeys = mutableMapOf<VaultKeyPurpose, ActiveVaultKey>()
        val unavailableKeyIds = mutableMapOf<VaultKeyPurpose, String>()
        VaultKeyPurpose.entries.forEach { purpose ->
            val activeRows = dao.getActiveKeys(purpose.storedName)
            check(activeRows.size <= 1) {
                "Multiple active vault keys exist for ${purpose.storedName}"
            }
            val active = activeRows.singleOrNull()
            activeKeys[purpose] = when {
                active == null && dao.countKeys(purpose.storedName) == 0 -> {
                    createAndActivateKey(purpose)
                }

                active == null -> error("Vault-key metadata exists without an active ${purpose.storedName} key")

                keyExists(active.id) -> ActiveVaultKey(active.id)

                else -> {
                    unavailableKeyIds[purpose] = active.id
                    createAndActivateKey(purpose)
                }
            }
        }

        VaultKeyInitialization(
            activeKeys = activeKeys.toMap(),
            unavailableKeyIds = unavailableKeyIds.toMap(),
        ).also { initialization = it }
    }

    suspend fun activeKey(purpose: VaultKeyPurpose): ActiveVaultKey =
        checkNotNull(initialize().activeKeys[purpose])

    suspend fun keyAvailable(keyId: String): Boolean = keyExists(keyId)

    suspend fun activeProtection(): VaultProtection {
        val active = initialize().activeKeys
        val unavailable = active.filterValues { !keyExists(it.id) }.keys
        if (unavailable.isNotEmpty()) return VaultProtection.KeyUnavailable(unavailable)

        val backings = mutableMapOf<VaultKeyPurpose, EncryptionKeyBacking>()
        active.forEach { (purpose, key) ->
            val metadata = dao.getKey(key.id) ?: return VaultProtection.Unknown
            val backing = runCatching { EncryptionKeyBacking.valueOf(metadata.backing) }.getOrNull()
                ?: return VaultProtection.Unknown
            backings[purpose] = backing
        }
        return VaultProtection.Available(backings)
    }

    suspend fun reset(clearData: suspend () -> Unit) {
        initializationMutex.withLock {
            val keyIds = dao.getKeyIds()
            clearData()
            withContext(keyStoreDispatcher) {
                keyIds.forEach(keyStore::delete)
            }
            initialization = null
        }
        initialize()
    }

    private suspend fun createAndActivateKey(purpose: VaultKeyPurpose): ActiveVaultKey {
        val keyId = newKeyId()
        val generated = withContext(keyStoreDispatcher) { keyStore.generate(keyId) }
        dao.activate(
            VaultKeyEntity(
                id = keyId,
                purpose = purpose.storedName,
                active = true,
                createdAt = currentTimeMillis(),
                backing = generated.backing.name,
            ),
        )
        return ActiveVaultKey(keyId)
    }

    private suspend fun keyExists(keyId: String): Boolean =
        withContext(keyStoreDispatcher) { keyStore.contains(keyId) }
}
