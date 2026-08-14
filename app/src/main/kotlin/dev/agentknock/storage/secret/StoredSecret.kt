package dev.agentknock.storage.secret

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import dev.agentknock.storage.crypto.LocalEncryptionKeyEntity
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "stored_secrets",
    foreignKeys = [
        ForeignKey(
            entity = LocalEncryptionKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["encryption_key_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["encryption_key_id"])],
)
internal data class StoredSecretEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "label")
    val label: String,
    @ColumnInfo(name = "encryption_format")
    val encryptionFormat: Int,
    @ColumnInfo(name = "encryption_key_id")
    val encryptionKeyId: String,
    @ColumnInfo(name = "nonce")
    val nonce: ByteArray,
    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

internal data class StoredSecretMetadataRow(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "label")
    val label: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Dao
internal interface StoredSecretDao {
    @Query(
        """
        SELECT id, label, created_at, updated_at
        FROM stored_secrets
        ORDER BY label COLLATE NOCASE, id
        """,
    )
    fun observeMetadata(): Flow<List<StoredSecretMetadataRow>>

    @Query("SELECT * FROM stored_secrets WHERE id = :id")
    suspend fun get(id: String): StoredSecretEntity?

    @Insert
    suspend fun insert(secret: StoredSecretEntity)

    @Query(
        """
        UPDATE stored_secrets
        SET encryption_format = :format,
            encryption_key_id = :keyId,
            nonce = :nonce,
            ciphertext = :ciphertext,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun replaceValue(
        id: String,
        format: Int,
        keyId: String,
        nonce: ByteArray,
        ciphertext: ByteArray,
        updatedAt: Long,
    ): Int

    @Query("UPDATE stored_secrets SET label = :label, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, label: String, updatedAt: Long): Int

    @Delete
    suspend fun delete(secret: StoredSecretEntity)
}
