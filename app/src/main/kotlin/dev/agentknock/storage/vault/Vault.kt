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
import dev.agentknock.storage.crypto.VaultKeyEntity
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "device_identities",
    indices = [
        Index(value = ["role"], unique = true),
        Index(value = ["address_id"], unique = true),
    ],
)
internal data class DeviceIdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "address")
    val address: String,
    @ColumnInfo(name = "address_id")
    val addressId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "device_public_key")
    val devicePublicKey: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "claimed_at")
    val claimedAt: Long?,
    @ColumnInfo(name = "claim_attempted_at")
    val claimAttemptedAt: Long? = null,
    @ColumnInfo(name = "pairing_enabled")
    val pairingEnabled: Boolean = true,
    @ColumnInfo(name = "instructions")
    val instructions: String = "",
)

@Entity(
    tableName = "vault_secrets",
    foreignKeys = [
        ForeignKey(
            entity = DeviceIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["identity_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = VaultKeyEntity::class,
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
    @Query("SELECT * FROM device_identities ORDER BY role")
    fun observeIdentities(): Flow<List<DeviceIdentityEntity>>

    @Query("SELECT * FROM vault_secrets ORDER BY identity_id, kind")
    fun observeSecrets(): Flow<List<VaultSecretEntity>>

    @Query("SELECT * FROM device_identities WHERE role = :role")
    suspend fun getIdentity(role: String): DeviceIdentityEntity?

    @Query("SELECT * FROM device_identities WHERE id = :id")
    suspend fun getIdentityById(id: String): DeviceIdentityEntity?

    @Query("SELECT * FROM vault_secrets WHERE identity_id = :identityId ORDER BY kind")
    suspend fun getSecrets(identityId: String): List<VaultSecretEntity>

    @Query("DELETE FROM device_identities WHERE role = :role")
    suspend fun deleteIdentity(role: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM device_identities WHERE id = :id AND role = :role)")
    suspend fun identityExists(id: String, role: String): Boolean

    @Insert
    suspend fun insertIdentity(identity: DeviceIdentityEntity)

    @Insert
    suspend fun insertSecrets(secrets: List<VaultSecretEntity>)

    @Query(
        """
        UPDATE device_identities
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

    @Query(
        """
        UPDATE device_identities
        SET address = :address,
            address_id = :addressId,
            claimed_at = :claimedAt
        WHERE id = :activeId AND role = :activeRole
        """,
    )
    suspend fun updateActiveAddress(
        activeId: String,
        address: String,
        addressId: String,
        claimedAt: Long,
        activeRole: String,
    ): Int

    @Query("UPDATE device_identities SET instructions = :instructions WHERE role = :activeRole")
    suspend fun updateActiveInstructions(activeRole: String, instructions: String): Int

    @Query(
        """
        UPDATE device_identities
        SET pairing_enabled = :enabled
        WHERE id = :identityId AND role = :activeRole
        """,
    )
    suspend fun updatePairingEnabled(
        identityId: String,
        enabled: Boolean,
        activeRole: String,
    ): Int

    @Query(
        """
        UPDATE device_identities
        SET claim_attempted_at = :attemptedAt
        WHERE id = :candidateId
          AND role = :candidateRole
          AND claim_attempted_at IS NULL
        """,
    )
    suspend fun markCandidateClaimAttempted(
        candidateId: String,
        attemptedAt: Long,
        candidateRole: String,
    ): Int

    @Transaction
    suspend fun replaceCandidate(
        identity: DeviceIdentityEntity,
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
        val candidate = getIdentityById(candidateId) ?: return false
        val active = getIdentity(activeRole)
        if (active != null && active.deviceId == candidate.deviceId) {
            deleteIdentity(candidateRole)
            check(
                updateActiveAddress(
                    activeId = active.id,
                    address = candidate.address,
                    addressId = candidate.addressId,
                    claimedAt = claimedAt,
                    activeRole = activeRole,
                ) == 1,
            )
            return true
        }
        deleteIdentity(activeRole)
        return markCandidateActive(
            candidateId = candidateId,
            claimedAt = claimedAt,
            activeRole = activeRole,
            candidateRole = candidateRole,
        ) == 1
    }
}
