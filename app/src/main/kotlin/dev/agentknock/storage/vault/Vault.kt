package dev.agentknock.storage.vault

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import dev.agentknock.storage.crypto.LocalEncryptionKeyEntity
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "vault_identities",
    indices = [
        Index(value = ["role"], unique = true),
        Index(value = ["route_id"], unique = true),
    ],
)
internal data class VaultIdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "address")
    val address: String,
    @ColumnInfo(name = "route_id")
    val routeId: String,
    @ColumnInfo(name = "route_public_key")
    val routePublicKey: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "claimed_at")
    val claimedAt: Long?,
)

@Entity(
    tableName = "vault_secrets",
    foreignKeys = [
        ForeignKey(
            entity = VaultIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["identity_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = LocalEncryptionKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["encryption_key_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["identity_id", "kind"], unique = true),
        Index(value = ["encryption_key_id"]),
    ],
)
internal data class VaultSecretEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "identity_id")
    val identityId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
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

@Dao
internal interface VaultDao {
    @Query("SELECT * FROM vault_identities ORDER BY role")
    fun observeIdentities(): Flow<List<VaultIdentityEntity>>

    @Query("SELECT * FROM vault_secrets ORDER BY identity_id, kind")
    fun observeSecrets(): Flow<List<VaultSecretEntity>>

    @Query("SELECT * FROM vault_identities WHERE role = :role")
    suspend fun getIdentity(role: String): VaultIdentityEntity?

    @Query("SELECT * FROM vault_identities WHERE id = :id")
    suspend fun getIdentityById(id: String): VaultIdentityEntity?

    @Query("SELECT * FROM vault_secrets WHERE identity_id = :identityId ORDER BY kind")
    suspend fun getSecrets(identityId: String): List<VaultSecretEntity>

    @Query("DELETE FROM vault_identities WHERE role = :role")
    suspend fun deleteIdentity(role: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM vault_identities WHERE id = :id AND role = :role)")
    suspend fun identityExists(id: String, role: String): Boolean

    @Insert
    suspend fun insertIdentity(identity: VaultIdentityEntity)

    @Insert
    suspend fun insertSecrets(secrets: List<VaultSecretEntity>)

    @Query(
        """
        UPDATE vault_identities
        SET role = :activeRole, claimed_at = :claimedAt
        WHERE id = :candidateId AND role = :candidateRole
        """,
    )
    suspend fun markCandidateActive(
        candidateId: String,
        claimedAt: Long,
        activeRole: String,
        candidateRole: String,
    ): Int

    @Transaction
    suspend fun replaceCandidate(
        identity: VaultIdentityEntity,
        secrets: List<VaultSecretEntity>,
        candidateRole: String,
    ) {
        deleteIdentity(candidateRole)
        insertIdentity(identity)
        insertSecrets(secrets)
    }

    @Transaction
    suspend fun promoteCandidate(
        candidateId: String,
        claimedAt: Long,
        activeRole: String,
        candidateRole: String,
    ): Boolean {
        if (!identityExists(candidateId, candidateRole)) return false
        deleteIdentity(activeRole)
        return markCandidateActive(
            candidateId = candidateId,
            claimedAt = claimedAt,
            activeRole = activeRole,
            candidateRole = candidateRole,
        ) == 1
    }
}
