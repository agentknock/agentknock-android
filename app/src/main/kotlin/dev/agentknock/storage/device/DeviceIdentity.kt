package dev.agentknock.storage.device

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.EncryptedValue
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "device_identities",
    indices = [Index(value = ["role"])],
)
internal data class DeviceIdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "address")
    val address: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
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
    tableName = "device_credentials",
    primaryKeys = ["identity_id", "kind"],
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
        Index(value = ["encryption_key_id"]),
    ],
)
internal data class DeviceCredentialEntity(
    @ColumnInfo(name = "identity_id")
    val identityId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @Embedded
    val encryptedValue: EncryptedValue,
)

internal enum class DeviceIdentityRole(val storedName: String) {
    ACTIVE("active"),
    CANDIDATE("candidate"),
    RETIRED("retired"),
}

internal enum class DeviceCredentialKind(val storedName: String) {
    DEVICE_TOKEN("device_token"),
    DEVICE_PRIVATE_KEY("device_private_key"),
}

internal const val DEVICE_IDENTITY_REPLACED_REQUEST_ERROR =
    "This request could not continue because its previous device identity is no longer available."

@Dao
internal interface DeviceIdentityDao {
    @Query("SELECT * FROM device_identities ORDER BY role")
    fun observeIdentities(): Flow<List<DeviceIdentityEntity>>

    @Query("SELECT * FROM device_credentials ORDER BY identity_id, kind")
    fun observeCredentials(): Flow<List<DeviceCredentialEntity>>

    @Query("SELECT * FROM device_identities WHERE role = :role ORDER BY id")
    suspend fun getIdentityRows(role: String): List<DeviceIdentityEntity>

    @Transaction
    suspend fun getIdentity(role: String): DeviceIdentityEntity? {
        val identities = getIdentityRows(role)
        check(identities.size <= 1) { "Multiple $role device identities exist" }
        return identities.singleOrNull()
    }

    @Query("SELECT * FROM device_identities WHERE id = :id")
    suspend fun getIdentityById(id: String): DeviceIdentityEntity?

    @Query("SELECT * FROM device_credentials WHERE identity_id = :identityId ORDER BY kind")
    suspend fun getCredentials(identityId: String): List<DeviceCredentialEntity>

    @Query("DELETE FROM device_identities WHERE role = :role")
    suspend fun deleteIdentity(role: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM device_identities WHERE id = :id AND role = :role)")
    suspend fun identityExists(id: String, role: String): Boolean

    @Insert
    suspend fun insertIdentity(identity: DeviceIdentityEntity)

    @Insert
    suspend fun insertCredentials(credentials: List<DeviceCredentialEntity>)

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
        "UPDATE device_identities SET role = :retiredRole " +
            "WHERE id = :activeId AND role = :activeRole",
    )
    suspend fun retireActiveIdentity(
        activeId: String,
        activeRole: String,
        retiredRole: String,
    ): Int

    @Query(
        """
        UPDATE inbox_requests
        SET state = 'completed',
            listed = CASE
                WHEN kind IN ('pairing', 'secret_upload') THEN 0
                ELSE listed
            END,
            error = CASE
                WHEN error IS NULL THEN :error
                ELSE error || '\n\n' || :error
            END,
            updated_at = MAX(updated_at, :now),
            completed_at = :now
        WHERE device_identity_id = :identityId
          AND completed_at IS NULL
        """,
    )
    suspend fun abandonRequests(identityId: String, now: Long, error: String): Int

    @Query(
        """
        UPDATE pairing_attempts
        SET state = CASE
                WHEN state IN ('completed', 'rejected') THEN state
                ELSE 'rejected'
            END,
            desired_relay_client_state = NULL,
            pending_psk_encryption_format = NULL,
            pending_psk_encryption_key_id = NULL,
            pending_psk_nonce = NULL,
            pending_psk_ciphertext = NULL,
            decided_at = CASE
                WHEN state IN ('completed', 'rejected') THEN decided_at
                ELSE COALESCE(decided_at, :now)
            END
        WHERE request_id IN (
            SELECT id FROM inbox_requests WHERE device_identity_id = :identityId
        )
        """,
    )
    suspend fun abandonPairingAttempts(identityId: String, now: Long): Int

    @Query(
        """
        DELETE FROM secret_upload_environment_variables
        WHERE request_id IN (
            SELECT secret_upload_requests.request_id
            FROM secret_upload_requests
            JOIN inbox_requests
              ON inbox_requests.id = secret_upload_requests.request_id
            WHERE inbox_requests.device_identity_id = :identityId
              AND secret_upload_requests.decision IS NULL
        )
        """,
    )
    suspend fun deletePendingUploadEnvironmentValues(identityId: String): Int

    @Query(
        """
        DELETE FROM secret_upload_ssh_keys
        WHERE request_id IN (
            SELECT secret_upload_requests.request_id
            FROM secret_upload_requests
            JOIN inbox_requests
              ON inbox_requests.id = secret_upload_requests.request_id
            WHERE inbox_requests.device_identity_id = :identityId
              AND secret_upload_requests.decision IS NULL
        )
        """,
    )
    suspend fun deletePendingUploadSshKeys(identityId: String): Int

    @Query(
        """
        UPDATE secret_upload_requests
        SET decision = 'rejected',
            decided_at = COALESCE(decided_at, :now)
        WHERE decision IS NULL
          AND request_id IN (
            SELECT id FROM inbox_requests WHERE device_identity_id = :identityId
          )
        """,
    )
    suspend fun rejectPendingUploads(identityId: String, now: Long): Int

    @Query(
        """
        UPDATE clients
        SET desired_relay_client_state = NULL,
            updated_at = MAX(updated_at, :now)
        WHERE device_identity_id = :identityId
          AND desired_relay_client_state IS NOT NULL
        """,
    )
    suspend fun clearClientDesires(identityId: String, now: Long): Int

    @Query(
        """
        UPDATE device_identities
        SET address = :address,
            claimed_at = :claimedAt
        WHERE id = :activeId AND role = :activeRole
        """,
    )
    suspend fun updateActiveAddress(
        activeId: String,
        address: String,
        claimedAt: Long,
        activeRole: String,
    ): Int

    @Query(
        "UPDATE device_identities SET address = :address " +
            "WHERE id = :candidateId AND role = :candidateRole",
    )
    suspend fun updateCandidateAddress(
        candidateId: String,
        address: String,
        candidateRole: String,
    ): Int

    @Query("UPDATE device_identities SET instructions = :instructions WHERE role = :activeRole")
    suspend fun updateActiveInstructions(
        activeRole: String,
        instructions: String,
    ): Int

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
        credentials: List<DeviceCredentialEntity>,
        candidateRole: String,
    ) {
        deleteIdentity(candidateRole)
        insertIdentity(identity)
        insertCredentials(credentials)
    }

    @Transaction
    suspend fun promoteCandidate(
        candidateId: String,
        claimedAt: Long,
        activeRole: String,
        candidateRole: String,
        retiredRole: String,
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
                    claimedAt = claimedAt,
                    activeRole = activeRole,
                ) == 1,
            )
            return true
        }
        if (active != null) {
            abandonRequests(
                identityId = active.id,
                now = claimedAt,
                error = DEVICE_IDENTITY_REPLACED_REQUEST_ERROR,
            )
            abandonPairingAttempts(active.id, claimedAt)
            deletePendingUploadEnvironmentValues(active.id)
            deletePendingUploadSshKeys(active.id)
            rejectPendingUploads(active.id, claimedAt)
            clearClientDesires(active.id, claimedAt)
            check(retireActiveIdentity(active.id, activeRole, retiredRole) == 1)
        }
        return markCandidateActive(
            candidateId = candidateId,
            claimedAt = claimedAt,
            activeRole = activeRole,
            candidateRole = candidateRole,
        ) == 1
    }
}
