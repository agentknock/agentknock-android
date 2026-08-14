package dev.agentknock.storage.request

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import dev.agentknock.storage.crypto.LocalEncryptionKeyEntity
import dev.agentknock.storage.vault.VaultIdentityEntity
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "inbox_requests",
    foreignKeys = [
        ForeignKey(
            entity = InboxRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_request_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["relay_request_id"], unique = true),
        Index(value = ["parent_request_id"]),
        Index(value = ["listed", "id"]),
    ],
)
internal data class InboxRequestEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "relay_request_id")
    val relayRequestId: String,
    @ColumnInfo(name = "parent_request_id")
    val parentRequestId: Long?,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "listed")
    val listed: Boolean,
    @ColumnInfo(name = "request_json")
    val requestJson: String,
    @ColumnInfo(name = "response_json")
    val responseJson: String?,
    @ColumnInfo(name = "completion_json")
    val completionJson: String?,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
    @ColumnInfo(name = "request_acknowledged_at")
    val requestAcknowledgedAt: Long?,
    @ColumnInfo(name = "response_acknowledged_at")
    val responseAcknowledgedAt: Long?,
    @ColumnInfo(name = "completion_acknowledged_at")
    val completionAcknowledgedAt: Long?,
)

@Entity(
    tableName = "pairings",
    foreignKeys = [
        ForeignKey(
            entity = InboxRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = VaultIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["vault_identity_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["pairing_id"], unique = true),
        Index(value = ["vault_identity_id"]),
        Index(value = ["state"]),
    ],
)
internal data class PairingEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "vault_identity_id")
    val vaultIdentityId: String?,
    @ColumnInfo(name = "vault_address")
    val vaultAddress: String,
    @ColumnInfo(name = "route_id")
    val routeId: String,
    @ColumnInfo(name = "pairing_id")
    val pairingId: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "sas_option_0")
    val sasOption0: Long?,
    @ColumnInfo(name = "sas_option_1")
    val sasOption1: Long?,
    @ColumnInfo(name = "sas_option_2")
    val sasOption2: Long?,
    @ColumnInfo(name = "correct_sas_index")
    val correctSasIndex: Int?,
    @ColumnInfo(name = "cli_version")
    val cliVersion: String?,
    @ColumnInfo(name = "platform")
    val platform: String?,
    @ColumnInfo(name = "architecture")
    val architecture: String?,
    @ColumnInfo(name = "hostname")
    val hostname: String?,
    @ColumnInfo(name = "machine_id")
    val machineId: String?,
    @ColumnInfo(name = "os_version")
    val osVersion: String?,
    @ColumnInfo(name = "error")
    val error: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long?,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
)

@Entity(
    tableName = "pairing_secrets",
    foreignKeys = [
        ForeignKey(
            entity = PairingEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["pairing_request_id"],
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
        Index(value = ["pairing_request_id", "kind"], unique = true),
        Index(value = ["encryption_key_id"]),
    ],
)
internal data class PairingSecretEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "pairing_request_id")
    val pairingRequestId: Long,
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

@Entity(
    tableName = "credential_requests",
    foreignKeys = [
        ForeignKey(
            entity = InboxRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = PairingEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["pairing_request_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["pairing_request_id"]),
        Index(value = ["state"]),
    ],
)
internal data class CredentialRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "pairing_request_id")
    val pairingRequestId: Long?,
    @ColumnInfo(name = "pairing_id")
    val pairingId: String,
    @ColumnInfo(name = "vault_address")
    val vaultAddress: String,
    @ColumnInfo(name = "hostname")
    val hostname: String?,
    @ColumnInfo(name = "platform")
    val platform: String?,
    @ColumnInfo(name = "architecture")
    val architecture: String?,
    @ColumnInfo(name = "machine_id")
    val machineId: String?,
    @ColumnInfo(name = "os_version")
    val osVersion: String?,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "cli_version")
    val cliVersion: String,
    @ColumnInfo(name = "profiles_json")
    val profilesJson: String,
    @ColumnInfo(name = "profile_details_json")
    val profileDetailsJson: String,
    @ColumnInfo(name = "missing_profiles_json")
    val missingProfilesJson: String,
    @ColumnInfo(name = "reason")
    val reason: String?,
    @ColumnInfo(name = "command")
    val command: String,
    @ColumnInfo(name = "arguments_json")
    val argumentsJson: String,
    @ColumnInfo(name = "working_directory")
    val workingDirectory: String,
    @ColumnInfo(name = "resolved_path")
    val resolvedPath: String?,
    @ColumnInfo(name = "stdin_kind")
    val stdinKind: String,
    @ColumnInfo(name = "stdout_kind")
    val stdoutKind: String,
    @ColumnInfo(name = "stderr_kind")
    val stderrKind: String,
    @ColumnInfo(name = "launcher_chain_json")
    val launcherChainJson: String,
    @ColumnInfo(name = "decision")
    val decision: String?,
    @ColumnInfo(name = "completion_result")
    val completionResult: String?,
    @ColumnInfo(name = "completion_reason")
    val completionReason: String?,
    @ColumnInfo(name = "completion_message")
    val completionMessage: String?,
    @ColumnInfo(name = "error")
    val error: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long?,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
)

@Entity(
    tableName = "profile_list_requests",
    foreignKeys = [
        ForeignKey(
            entity = InboxRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = PairingEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["pairing_request_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["pairing_request_id"]),
        Index(value = ["state"]),
    ],
)
internal data class ProfileListRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "pairing_request_id")
    val pairingRequestId: Long?,
    @ColumnInfo(name = "pairing_id")
    val pairingId: String,
    @ColumnInfo(name = "vault_address")
    val vaultAddress: String,
    @ColumnInfo(name = "hostname")
    val hostname: String?,
    @ColumnInfo(name = "platform")
    val platform: String?,
    @ColumnInfo(name = "architecture")
    val architecture: String?,
    @ColumnInfo(name = "machine_id")
    val machineId: String?,
    @ColumnInfo(name = "os_version")
    val osVersion: String?,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "cli_version")
    val cliVersion: String,
    @ColumnInfo(name = "profiles_json")
    val profilesJson: String,
    @ColumnInfo(name = "error")
    val error: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
)

@Dao
internal interface RequestDao {
    @Query("SELECT * FROM inbox_requests WHERE listed = 1 ORDER BY id DESC LIMIT 100")
    fun observeListedRequests(): Flow<List<InboxRequestEntity>>

    @Query("SELECT * FROM pairings ORDER BY request_id DESC")
    fun observePairings(): Flow<List<PairingEntity>>

    @Query("SELECT * FROM credential_requests ORDER BY request_id DESC")
    fun observeCredentialRequests(): Flow<List<CredentialRequestEntity>>

    @Query("SELECT * FROM profile_list_requests ORDER BY request_id DESC")
    fun observeProfileListRequests(): Flow<List<ProfileListRequestEntity>>

    @Query("SELECT * FROM inbox_requests WHERE id = :id")
    fun observeRequest(id: Long): Flow<InboxRequestEntity?>

    @Query("SELECT * FROM pairings WHERE request_id = :requestId")
    fun observePairing(requestId: Long): Flow<PairingEntity?>

    @Query("SELECT * FROM credential_requests WHERE request_id = :requestId")
    fun observeCredentialRequest(requestId: Long): Flow<CredentialRequestEntity?>

    @Query("SELECT * FROM profile_list_requests WHERE request_id = :requestId")
    fun observeProfileListRequest(requestId: Long): Flow<ProfileListRequestEntity?>

    @Query("SELECT * FROM inbox_requests WHERE relay_request_id = :relayRequestId")
    suspend fun getRequestByRelayId(relayRequestId: String): InboxRequestEntity?

    @Query("SELECT * FROM inbox_requests WHERE id = :id")
    suspend fun getRequestById(id: Long): InboxRequestEntity?

    @Query("SELECT * FROM pairings WHERE request_id = :requestId")
    suspend fun getPairing(requestId: Long): PairingEntity?

    @Query("SELECT * FROM pairings WHERE pairing_id = :pairingId")
    suspend fun getPairingByPairingId(pairingId: String): PairingEntity?

    @Query("SELECT * FROM credential_requests WHERE request_id = :requestId")
    suspend fun getCredentialRequest(requestId: Long): CredentialRequestEntity?

    @Query("SELECT * FROM profile_list_requests WHERE request_id = :requestId")
    suspend fun getProfileListRequest(requestId: Long): ProfileListRequestEntity?

    @Query("SELECT * FROM pairings ORDER BY request_id")
    suspend fun getPairings(): List<PairingEntity>

    @Query(
        "SELECT * FROM pairing_secrets WHERE pairing_request_id = :pairingRequestId AND kind = :kind",
    )
    suspend fun getPairingSecret(pairingRequestId: Long, kind: String): PairingSecretEntity?

    @Query(
        "SELECT * FROM inbox_requests WHERE response_json IS NOT NULL AND response_acknowledged_at IS NULL",
    )
    suspend fun getUnacknowledgedResponses(): List<InboxRequestEntity>

    @Insert
    suspend fun insertRequest(request: InboxRequestEntity): Long

    @Insert
    suspend fun insertPairing(pairing: PairingEntity)

    @Insert
    suspend fun insertPairingSecret(secret: PairingSecretEntity)

    @Insert
    suspend fun insertCredentialRequestRow(request: CredentialRequestEntity)

    @Insert
    suspend fun insertProfileListRequestRow(request: ProfileListRequestEntity)

    @Update
    suspend fun updateRequest(request: InboxRequestEntity): Int

    @Update
    suspend fun updatePairing(pairing: PairingEntity): Int

    @Update
    suspend fun updatePairingSecret(secret: PairingSecretEntity): Int

    @Update
    suspend fun updateCredentialRequestRow(request: CredentialRequestEntity): Int

    @Update
    suspend fun updateProfileListRequestRow(request: ProfileListRequestEntity): Int

    @Query(
        """
        UPDATE inbox_requests
        SET request_acknowledged_at = COALESCE(request_acknowledged_at, :acknowledgedAt),
            updated_at = :acknowledgedAt
        WHERE relay_request_id = :relayRequestId
        """,
    )
    suspend fun markRequestAcknowledged(relayRequestId: String, acknowledgedAt: Long): Int

    @Query(
        """
        UPDATE inbox_requests
        SET response_acknowledged_at = COALESCE(response_acknowledged_at, :acknowledgedAt),
            updated_at = :acknowledgedAt
        WHERE relay_request_id = :relayRequestId
        """,
    )
    suspend fun markResponseAcknowledged(relayRequestId: String, acknowledgedAt: Long): Int

    @Query(
        """
        UPDATE inbox_requests
        SET completion_acknowledged_at = COALESCE(completion_acknowledged_at, :acknowledgedAt),
            updated_at = :acknowledgedAt
        WHERE relay_request_id = :relayRequestId
        """,
    )
    suspend fun markCompletionAcknowledged(relayRequestId: String, acknowledgedAt: Long): Int

    @Query(
        """
        DELETE FROM inbox_requests
        WHERE listed = 1
          AND completed_at IS NOT NULL
          AND id NOT IN (
            SELECT id FROM inbox_requests WHERE listed = 1 ORDER BY id DESC LIMIT 100
          )
          AND id NOT IN (
            SELECT request_id FROM pairings
            WHERE state = 'active' AND vault_identity_id IS NOT NULL
          )
        """,
    )
    suspend fun trimCompletedHistory(): Int

    @Transaction
    suspend fun insertPairingRequest(
        request: InboxRequestEntity,
        pairing: PairingEntity,
    ): Long {
        val requestId = insertRequest(request)
        insertPairing(pairing.copy(requestId = requestId))
        trimCompletedHistory()
        return requestId
    }

    @Transaction
    suspend fun insertCredentialRequest(
        request: InboxRequestEntity,
        credentialRequest: CredentialRequestEntity,
        rotatedPairingSecret: PairingSecretEntity?,
    ): Long {
        val requestId = insertRequest(request)
        insertCredentialRequestRow(credentialRequest.copy(requestId = requestId))
        rotatedPairingSecret?.let { check(updatePairingSecret(it) == 1) }
        trimCompletedHistory()
        return requestId
    }

    @Transaction
    suspend fun insertProfileListRequest(
        request: InboxRequestEntity,
        profileListRequest: ProfileListRequestEntity,
        rotatedPairingSecret: PairingSecretEntity?,
    ): Long {
        val requestId = insertRequest(request)
        insertProfileListRequestRow(profileListRequest.copy(requestId = requestId))
        rotatedPairingSecret?.let { check(updatePairingSecret(it) == 1) }
        trimCompletedHistory()
        return requestId
    }

    @Transaction
    suspend fun recordInitialCompletion(
        request: InboxRequestEntity,
        pairing: PairingEntity,
        secret: PairingSecretEntity,
    ) {
        check(updateRequest(request) == 1)
        check(updatePairing(pairing) == 1)
        insertPairingSecret(secret)
    }

    @Transaction
    suspend fun updatePairingRequest(
        request: InboxRequestEntity,
        pairing: PairingEntity,
    ) {
        check(updateRequest(request) == 1)
        check(updatePairing(pairing) == 1)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun updateCredentialRequest(
        request: InboxRequestEntity,
        credentialRequest: CredentialRequestEntity,
    ) {
        check(updateRequest(request) == 1)
        check(updateCredentialRequestRow(credentialRequest) == 1)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun updateProfileListRequest(
        request: InboxRequestEntity,
        profileListRequest: ProfileListRequestEntity,
    ) {
        check(updateRequest(request) == 1)
        check(updateProfileListRequestRow(profileListRequest) == 1)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun finishPairing(
        rootRequest: InboxRequestEntity,
        pairing: PairingEntity,
        finishRequest: InboxRequestEntity,
    ) {
        check(updateRequest(rootRequest) == 1)
        check(updatePairing(pairing) == 1)
        check(updateRequest(finishRequest) == 1)
        trimCompletedHistory()
    }
}
