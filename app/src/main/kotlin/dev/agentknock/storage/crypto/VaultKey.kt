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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class VaultKeyPurpose(val storedName: String) {
    SECRET_VALUES("secret_values"),
    DEVICE_STATE("device_state"),
    ;

    companion object {
        fun fromStoredName(value: String): VaultKeyPurpose? = entries.find { it.storedName == value }
    }
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
)

@Dao
internal interface VaultKeyDao {
    @Query("SELECT * FROM vault_keys WHERE purpose = :purpose AND active = 1 ORDER BY created_at DESC")
    suspend fun getActiveKeys(purpose: String): List<VaultKeyEntity>

    @Query("SELECT count(*) FROM vault_keys WHERE purpose = :purpose")
    suspend fun countKeys(purpose: String): Int

    @Query("SELECT * FROM vault_keys WHERE id = :id")
    suspend fun getKey(id: String): VaultKeyEntity?

    @Query(
        """
        SELECT vault_keys.*
        FROM vault_keys
        JOIN (
            SELECT encryption_key_id AS key_id FROM environment_variables
            UNION
            SELECT encryption_key_id AS key_id FROM ssh_keys
            UNION
            SELECT encryption_key_id AS key_id FROM secret_upload_environment_variables
            UNION
            SELECT encryption_key_id AS key_id FROM secret_upload_ssh_keys
            UNION
            SELECT encryption_key_id AS key_id FROM device_credentials
            UNION
            SELECT encryption_key_id AS key_id FROM client_psks
            UNION
            SELECT encryption_key_id AS key_id FROM request_psks
            UNION
            SELECT pending_psk_encryption_key_id AS key_id FROM pairing_attempts
        ) AS referenced_keys ON referenced_keys.key_id = vault_keys.id
        ORDER BY vault_keys.purpose, vault_keys.id
        """,
    )
    fun observeReferencedKeys(): Flow<List<VaultKeyEntity>>

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
    val unavailableStoredData: Set<VaultKeyPurpose>

    data class ActiveKeysAvailable(
        val backings: Map<VaultKeyPurpose, EncryptionKeyBacking>,
        override val unavailableStoredData: Set<VaultKeyPurpose>,
    ) : VaultProtection

    data class ActiveKeysUnavailable(
        val purposes: Set<VaultKeyPurpose>,
        override val unavailableStoredData: Set<VaultKeyPurpose>,
    ) : VaultProtection

    data class ActiveKeyProtectionUnknown(
        override val unavailableStoredData: Set<VaultKeyPurpose>,
    ) : VaultProtection
}

internal data class VaultKeyInitialization(
    val activeKeys: Map<VaultKeyPurpose, ActiveVaultKey>,
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

                keyAvailableInStore(active.id) -> ActiveVaultKey(active.id)

                else -> {
                    createAndActivateKey(purpose)
                }
            }
        }

        VaultKeyInitialization(
            activeKeys = activeKeys.toMap(),
        ).also { initialization = it }
    }

    suspend fun activeKey(purpose: VaultKeyPurpose): ActiveVaultKey =
        checkNotNull(initialize().activeKeys[purpose])

    suspend fun keyAvailable(keyId: String): Boolean = keyAvailableInStore(keyId)

    fun observeProtection(): Flow<VaultProtection> = dao.observeReferencedKeys().map { referenced ->
        val active = initialize().activeKeys
        val availability = mutableMapOf<String, Boolean>()
        (active.values.map(ActiveVaultKey::id) + referenced.map(VaultKeyEntity::id))
            .distinct()
            .forEach { id -> availability[id] = keyAvailableInStore(id) }

        val unavailableStoredData = mutableSetOf<VaultKeyPurpose>()
        referenced.forEach { key ->
            if (availability[key.id] == false) {
                val purpose = VaultKeyPurpose.fromStoredName(key.purpose)
                    ?: return@map VaultProtection.ActiveKeyProtectionUnknown(
                        VaultKeyPurpose.entries.toSet(),
                    )
                unavailableStoredData += purpose
            }
        }

        val unavailableActiveKeys = active
            .filterValues { availability[it.id] == false }
            .keys
        if (unavailableActiveKeys.isNotEmpty()) {
            return@map VaultProtection.ActiveKeysUnavailable(
                purposes = unavailableActiveKeys,
                unavailableStoredData = unavailableStoredData.toSet(),
            )
        }

        val backings = mutableMapOf<VaultKeyPurpose, EncryptionKeyBacking>()
        active.forEach { (purpose, key) ->
            val metadata = dao.getKey(key.id)
                ?: return@map VaultProtection.ActiveKeyProtectionUnknown(
                    unavailableStoredData,
                )
            val backing = EncryptionKeyBacking.fromStoredName(metadata.backing)
                ?: return@map VaultProtection.ActiveKeyProtectionUnknown(
                    unavailableStoredData,
                )
            backings[purpose] = backing
        }
        VaultProtection.ActiveKeysAvailable(
            backings = backings,
            unavailableStoredData = unavailableStoredData.toSet(),
        )
    }

    private suspend fun createAndActivateKey(
        purpose: VaultKeyPurpose,
    ): ActiveVaultKey = withContext(NonCancellable) {
        val keyId = newKeyId()
        val generated = try {
            withContext(keyStoreDispatcher) { keyStore.generate(keyId) }
        } catch (failure: Throwable) {
            val unowned = runCatching { dao.getKey(keyId) == null }
                .onFailure(failure::addSuppressed)
                .getOrDefault(false)
            if (unowned) {
                withContext(keyStoreDispatcher) {
                    runCatching { keyStore.delete(keyId) }
                        .exceptionOrNull()
                        ?.let(failure::addSuppressed)
                }
            }
            throw failure
        }
        try {
            dao.activate(
                VaultKeyEntity(
                    id = keyId,
                    purpose = purpose.storedName,
                    active = true,
                    createdAt = currentTimeMillis(),
                    backing = generated.backing.storedName,
                ),
            )
        } catch (failure: Throwable) {
            withContext(keyStoreDispatcher) {
                runCatching { keyStore.delete(keyId) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        }
        ActiveVaultKey(keyId)
    }

    private suspend fun keyAvailableInStore(keyId: String): Boolean =
        withContext(keyStoreDispatcher) { keyStore.get(keyId) != null }

}
