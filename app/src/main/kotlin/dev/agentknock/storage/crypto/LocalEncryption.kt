package dev.agentknock.storage.crypto

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Entity(tableName = "local_encryption_keys")
internal data class LocalEncryptionKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "backing")
    val backing: String,
)

@Entity(
    tableName = "local_encryption_state",
    foreignKeys = [
        ForeignKey(
            entity = LocalEncryptionKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["active_key_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["active_key_id"])],
)
internal data class LocalEncryptionStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int,
    @ColumnInfo(name = "active_key_id")
    val activeKeyId: String,
)

@Dao
internal interface LocalEncryptionDao {
    @Query("SELECT * FROM local_encryption_state WHERE singleton_id = 0")
    suspend fun getState(): LocalEncryptionStateEntity?

    @Query("SELECT count(*) FROM local_encryption_keys")
    suspend fun countKeys(): Int

    @Query("SELECT id FROM local_encryption_keys")
    suspend fun getKeyIds(): List<String>

    @Query("SELECT * FROM local_encryption_keys WHERE id = :id")
    suspend fun getKey(id: String): LocalEncryptionKeyEntity?

    @Insert
    suspend fun insertKey(key: LocalEncryptionKeyEntity)

    @Upsert
    suspend fun upsertState(state: LocalEncryptionStateEntity)

    @Transaction
    suspend fun activate(key: LocalEncryptionKeyEntity) {
        insertKey(key)
        upsertState(LocalEncryptionStateEntity(singletonId = 0, activeKeyId = key.id))
    }
}

internal data class ActiveEncryptionKey(
    val id: String,
)

internal sealed interface LocalEncryptionProtection {
    data class Available(val backing: EncryptionKeyBacking) : LocalEncryptionProtection
    data object KeyUnavailable : LocalEncryptionProtection
    data object Unknown : LocalEncryptionProtection
}

internal sealed interface LocalStorageInitialization {
    val activeKey: ActiveEncryptionKey

    data class Created(
        override val activeKey: ActiveEncryptionKey,
    ) : LocalStorageInitialization

    data class Ready(
        override val activeKey: ActiveEncryptionKey,
    ) : LocalStorageInitialization

    data class RecoveredWithoutKeys(
        val unavailableKeyId: String,
        override val activeKey: ActiveEncryptionKey,
    ) : LocalStorageInitialization
}

internal class LocalEncryptionKeyManager(
    private val dao: LocalEncryptionDao,
    private val keyStore: EncryptionKeyStore,
    private val newKeyId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val keyStoreDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val initializationMutex = Mutex()
    private var initialization: LocalStorageInitialization? = null

    suspend fun initialize(): LocalStorageInitialization = initializationMutex.withLock {
        initialization?.let { return@withLock it }

        val state = dao.getState()
        val result = when {
            state == null && dao.countKeys() == 0 ->
                LocalStorageInitialization.Created(createAndActivateKey())

            state == null ->
                error("Local encryption key metadata exists without an active key")

            keyExists(state.activeKeyId) ->
                LocalStorageInitialization.Ready(ActiveEncryptionKey(state.activeKeyId))

            else ->
                LocalStorageInitialization.RecoveredWithoutKeys(
                    unavailableKeyId = state.activeKeyId,
                    activeKey = createAndActivateKey(),
                )
        }
        initialization = result
        result
    }

    suspend fun activeKey(): ActiveEncryptionKey = initialize().activeKey

    suspend fun keyAvailable(keyId: String): Boolean = keyExists(keyId)

    suspend fun activeProtection(): LocalEncryptionProtection {
        val active = initialize().activeKey
        val metadata = dao.getKey(active.id) ?: return LocalEncryptionProtection.Unknown
        if (!keyExists(active.id)) return LocalEncryptionProtection.KeyUnavailable
        val backing = runCatching { EncryptionKeyBacking.valueOf(metadata.backing) }.getOrNull()
            ?: return LocalEncryptionProtection.Unknown
        return LocalEncryptionProtection.Available(backing)
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

    private suspend fun createAndActivateKey(): ActiveEncryptionKey {
        val keyId = newKeyId()
        val generated = withContext(keyStoreDispatcher) { keyStore.generate(keyId) }
        dao.activate(
            LocalEncryptionKeyEntity(
                id = keyId,
                createdAt = currentTimeMillis(),
                backing = generated.backing.name,
            ),
        )
        return ActiveEncryptionKey(keyId)
    }

    private suspend fun keyExists(keyId: String): Boolean =
        withContext(keyStoreDispatcher) { keyStore.contains(keyId) }
}
