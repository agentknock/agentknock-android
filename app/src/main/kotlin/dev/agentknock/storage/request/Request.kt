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
import androidx.room3.Upsert
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.secret.SecretClientApprovalOverrideEntity
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.TemporaryAccessGrantEntity
import dev.agentknock.storage.vault.DeviceIdentityEntity
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
            entity = DeviceIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["device_identity_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["client_id"], unique = true),
        Index(value = ["device_identity_id"]),
        Index(value = ["state"]),
    ],
)
internal data class PairingEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "device_identity_id")
    val deviceIdentityId: String?,
    @ColumnInfo(name = "pairing_address")
    val pairingAddress: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "friendly_name")
    val friendlyName: String?,
    @ColumnInfo(name = "device_random")
    val deviceRandom: ByteArray,
    @ColumnInfo(name = "desired_relay_client_state")
    val desiredRelayClientState: String?,
    @ColumnInfo(name = "relay_client_state")
    val relayClientState: String?,
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
    val clientSoftwareJson: String?,
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
    @ColumnInfo(name = "instructions")
    val instructions: String = "",
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
            entity = VaultKeyEntity::class,
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
    tableName = "request_secrets",
    foreignKeys = [
        ForeignKey(
            entity = InboxRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["request_id"],
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
        Index(value = ["request_id"], unique = true),
        Index(value = ["encryption_key_id"]),
    ],
)
internal data class RequestSecretEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "request_id")
    val requestId: Long,
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
)

@Entity(
    tableName = "secret_use_requests",
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
internal data class SecretUseRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "pairing_request_id")
    val pairingRequestId: Long?,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "client_name")
    val clientName: String,
    @ColumnInfo(name = "pairing_address")
    val pairingAddress: String,
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
    @ColumnInfo(name = "invocation_token_hash")
    val invocationTokenHash: ByteArray?,
    @ColumnInfo(name = "contains_sensitive_material", defaultValue = "1")
    val containsSensitiveMaterial: Boolean,
    @ColumnInfo(name = "cli_version")
    val clientSoftwareJson: String,
    @ColumnInfo(name = "secrets_json")
    val secretsJson: String,
    @ColumnInfo(name = "secret_details_json")
    val secretDetailsJson: String,
    @ColumnInfo(name = "provided_secrets_json")
    val providedSecretsJson: String?,
    @ColumnInfo(name = "missing_secrets_json")
    val missingSecretsJson: String,
    @ColumnInfo(name = "reason")
    val reason: String?,
    @ColumnInfo(name = "command")
    val command: String,
    @ColumnInfo(name = "arguments_json")
    val argumentsJson: String,
    @ColumnInfo(name = "working_directory")
    val workingDirectory: String,
    @ColumnInfo(name = "executable_path")
    val executablePath: String,
    @ColumnInfo(name = "executable_hash")
    val executableHash: String?,
    @ColumnInfo(name = "executable_mode")
    val executableMode: String,
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
    @ColumnInfo(name = "decision_source")
    val decisionSource: String?,
    @ColumnInfo(name = "rule_evaluation_json")
    val approvalEvaluationJson: String?,
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
    tableName = "git_sign_requests",
    foreignKeys = [
        ForeignKey(
            entity = InboxRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["state"])],
)
internal data class GitSignRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "secret_name")
    val secretName: String,
    @ColumnInfo(name = "message")
    val message: ByteArray,
    @ColumnInfo(name = "repository_json")
    val repositoryJson: String?,
    @ColumnInfo(name = "rule_evaluation_json")
    val approvalEvaluationJson: String?,
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
    tableName = "secret_list_requests",
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
internal data class SecretListRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "pairing_request_id")
    val pairingRequestId: Long?,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "pairing_address")
    val pairingAddress: String,
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
    val clientSoftwareJson: String,
    @ColumnInfo(name = "secrets_json")
    val secretsJson: String,
    @ColumnInfo(name = "error")
    val error: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
)

@Entity(
    tableName = "secret_upload_requests",
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
internal data class SecretUploadRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "pairing_request_id")
    val pairingRequestId: Long?,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "client_name")
    val clientName: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "cli_version")
    val clientSoftwareJson: String,
    @ColumnInfo(name = "mode")
    val mode: String,
    @ColumnInfo(name = "uploaded_name")
    val uploadedName: String,
    @ColumnInfo(name = "approved_name")
    val approvedName: String?,
    @ColumnInfo(name = "description_provided")
    val descriptionProvided: Boolean,
    @ColumnInfo(name = "description")
    val description: String?,
    @ColumnInfo(name = "secret_type")
    val secretType: String,
    @ColumnInfo(name = "summary_json")
    val summaryJson: String,
    @ColumnInfo(name = "error")
    val error: String?,
    @ColumnInfo(name = "transport_result")
    val transportResult: String,
    @ColumnInfo(name = "transport_message")
    val transportMessage: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long?,
    @ColumnInfo(name = "transport_completed_at")
    val transportCompletedAt: Long?,
)

@Entity(
    tableName = "secret_upload_environment_variables",
    foreignKeys = [
        ForeignKey(
            entity = SecretUploadRequestEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
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
        Index(value = ["request_id", "name"], unique = true),
        Index(value = ["encryption_key_id"]),
    ],
)
internal data class SecretUploadEnvironmentVariableEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "sensitive")
    val sensitive: Boolean,
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
)

@Entity(
    tableName = "secret_upload_ssh_keys",
    foreignKeys = [
        ForeignKey(
            entity = SecretUploadRequestEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
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
    indices = [Index(value = ["encryption_key_id"])],
)
internal data class SecretUploadSshKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    @ColumnInfo(name = "algorithm")
    val algorithm: String,
    @ColumnInfo(name = "public_key")
    val publicKey: ByteArray,
    @ColumnInfo(name = "comment")
    val comment: String,
    @ColumnInfo(name = "private_key_format")
    val privateKeyFormat: String,
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
)

internal data class AuthorizationPolicyCommitment(
    val mode: String,
    val temporaryAccessExpiresAt: Long?,
)

internal data class AuthorizationDeviceInstructionsCommitment(
    val deviceIdentityId: String,
    val instructions: String,
)

internal data class AuthorizationCommitment(
    val secretRevisions: Map<String, Long>,
    val policies: Map<String, AuthorizationPolicyCommitment>,
    val deviceInstructions: AuthorizationDeviceInstructionsCommitment? = null,
)

@Dao
internal interface RequestDao {
    @Query("SELECT * FROM inbox_requests WHERE listed = 1 ORDER BY id DESC LIMIT 100")
    fun observeListedRequests(): Flow<List<InboxRequestEntity>>

    @Query("SELECT count(*) FROM inbox_requests WHERE listed = 1")
    fun observeListedRequestCount(): Flow<Int>

    @Query("SELECT * FROM pairings ORDER BY request_id DESC")
    fun observePairings(): Flow<List<PairingEntity>>

    @Query("SELECT * FROM secret_use_requests ORDER BY request_id DESC")
    fun observeSecretUseRequests(): Flow<List<SecretUseRequestEntity>>

    @Query("SELECT * FROM git_sign_requests ORDER BY request_id DESC")
    fun observeGitSignRequests(): Flow<List<GitSignRequestEntity>>

    @Query("SELECT * FROM secret_list_requests ORDER BY request_id DESC")
    fun observeSecretListRequests(): Flow<List<SecretListRequestEntity>>

    @Query("SELECT * FROM secret_upload_requests ORDER BY request_id DESC")
    fun observeSecretUploadRequests(): Flow<List<SecretUploadRequestEntity>>

    @Query("SELECT * FROM inbox_requests WHERE id = :id")
    fun observeRequest(id: Long): Flow<InboxRequestEntity?>

    @Query("SELECT * FROM pairings WHERE request_id = :requestId")
    fun observePairing(requestId: Long): Flow<PairingEntity?>

    @Query("SELECT * FROM secret_use_requests WHERE request_id = :requestId")
    fun observeSecretUseRequest(requestId: Long): Flow<SecretUseRequestEntity?>

    @Query("SELECT * FROM git_sign_requests WHERE request_id = :requestId")
    fun observeGitSignRequest(requestId: Long): Flow<GitSignRequestEntity?>

    @Query("SELECT * FROM secret_list_requests WHERE request_id = :requestId")
    fun observeSecretListRequest(requestId: Long): Flow<SecretListRequestEntity?>

    @Query("SELECT * FROM secret_upload_requests WHERE request_id = :requestId")
    fun observeSecretUploadRequest(requestId: Long): Flow<SecretUploadRequestEntity?>

    @Query(
        "SELECT * FROM secret_upload_environment_variables WHERE request_id = :requestId ORDER BY name",
    )
    fun observeSecretUploadEnvironmentVariables(
        requestId: Long,
    ): Flow<List<SecretUploadEnvironmentVariableEntity>>

    @Query("SELECT * FROM secret_upload_ssh_keys WHERE request_id = :requestId")
    fun observeSecretUploadSshKey(requestId: Long): Flow<SecretUploadSshKeyEntity?>

    @Query("SELECT * FROM pairings WHERE client_id = :clientId")
    fun observePairingByClientId(clientId: String): Flow<PairingEntity?>

    @Query("SELECT * FROM inbox_requests WHERE relay_request_id = :relayRequestId")
    suspend fun getRequestByRelayId(relayRequestId: String): InboxRequestEntity?

    @Query("SELECT * FROM inbox_requests WHERE id = :id")
    suspend fun getRequestById(id: Long): InboxRequestEntity?

    @Query("SELECT * FROM pairings WHERE request_id = :requestId")
    suspend fun getPairing(requestId: Long): PairingEntity?

    @Query("SELECT * FROM pairings WHERE client_id = :clientId")
    suspend fun getPairingByClientId(clientId: String): PairingEntity?

    @Query("SELECT * FROM secrets WHERE id IN (:secretIds)")
    suspend fun getAuthorizationSecrets(secretIds: List<String>): List<SecretEntity>

    @Query(
        "SELECT instructions FROM device_identities " +
            "WHERE id = :deviceIdentityId AND role = 'active'",
    )
    suspend fun getAuthorizationDeviceInstructions(deviceIdentityId: String): String?

    @Query(
        "SELECT * FROM secret_client_approval_overrides " +
            "WHERE client_id = :clientId AND secret_id IN (:secretIds)",
    )
    suspend fun getAuthorizationOverrides(
        clientId: String,
        secretIds: List<String>,
    ): List<SecretClientApprovalOverrideEntity>

    @Query(
        "SELECT * FROM temporary_access_grants " +
            "WHERE client_id = :clientId AND secret_id IN (:secretIds) AND operation = :operation",
    )
    suspend fun getAuthorizationGrants(
        clientId: String,
        secretIds: List<String>,
        operation: String,
    ): List<TemporaryAccessGrantEntity>

    @Query("SELECT * FROM secret_use_requests WHERE request_id = :requestId")
    suspend fun getSecretUseRequest(requestId: Long): SecretUseRequestEntity?

    @Query("SELECT * FROM git_sign_requests WHERE request_id = :requestId")
    suspend fun getGitSignRequest(requestId: Long): GitSignRequestEntity?

    @Query("SELECT * FROM secret_list_requests WHERE request_id = :requestId")
    suspend fun getSecretListRequest(requestId: Long): SecretListRequestEntity?

    @Query("SELECT * FROM secret_upload_requests WHERE request_id = :requestId")
    suspend fun getSecretUploadRequest(requestId: Long): SecretUploadRequestEntity?

    @Query(
        "SELECT * FROM secret_upload_environment_variables WHERE request_id = :requestId ORDER BY name",
    )
    suspend fun getSecretUploadEnvironmentVariables(
        requestId: Long,
    ): List<SecretUploadEnvironmentVariableEntity>

    @Query("SELECT * FROM secret_upload_ssh_keys WHERE request_id = :requestId")
    suspend fun getSecretUploadSshKey(requestId: Long): SecretUploadSshKeyEntity?

    @Query("SELECT * FROM pairings ORDER BY request_id")
    suspend fun getPairings(): List<PairingEntity>

    @Query(
        "SELECT * FROM pairing_secrets WHERE pairing_request_id = :pairingRequestId AND kind = :kind",
    )
    suspend fun getPairingSecret(pairingRequestId: Long, kind: String): PairingSecretEntity?

    @Query("SELECT * FROM request_secrets WHERE request_id = :requestId")
    suspend fun getRequestSecret(requestId: Long): RequestSecretEntity?

    @Query(
        "SELECT * FROM inbox_requests WHERE response_json IS NOT NULL AND response_acknowledged_at IS NULL",
    )
    suspend fun getUnacknowledgedResponses(): List<InboxRequestEntity>

    @Query(
        """
        SELECT * FROM inbox_requests
        WHERE completed_at IS NULL
           OR response_acknowledged_at IS NULL AND response_json IS NOT NULL
        ORDER BY id
        """,
    )
    suspend fun getUnsettledRequests(): List<InboxRequestEntity>

    @Insert
    suspend fun insertRequest(request: InboxRequestEntity): Long

    @Insert
    suspend fun insertPairing(pairing: PairingEntity)

    @Insert
    suspend fun insertPairingSecret(secret: PairingSecretEntity)

    @Insert
    suspend fun insertRequestSecret(secret: RequestSecretEntity)

    @Insert
    suspend fun insertSecretUseRequestRow(request: SecretUseRequestEntity)

    @Insert
    suspend fun insertGitSignRequestRow(request: GitSignRequestEntity)

    @Insert
    suspend fun insertSecretListRequestRow(request: SecretListRequestEntity)

    @Insert
    suspend fun insertSecretUploadRequestRow(request: SecretUploadRequestEntity)

    @Insert
    suspend fun insertSecretUploadEnvironmentVariables(
        variables: List<SecretUploadEnvironmentVariableEntity>,
    )

    @Insert
    suspend fun insertSecretUploadSshKey(key: SecretUploadSshKeyEntity)

    @Upsert
    suspend fun upsertPairingSecret(secret: PairingSecretEntity)

    @Update
    suspend fun updateRequest(request: InboxRequestEntity): Int

    @Update
    suspend fun updatePairing(pairing: PairingEntity): Int

    @Update
    suspend fun updatePairingSecret(secret: PairingSecretEntity): Int

    @Update
    suspend fun updateSecretUseRequestRow(request: SecretUseRequestEntity): Int

    @Update
    suspend fun updateGitSignRequestRow(request: GitSignRequestEntity): Int

    @Update
    suspend fun updateSecretListRequestRow(request: SecretListRequestEntity): Int

    @Update
    suspend fun updateSecretUploadRequestRow(request: SecretUploadRequestEntity): Int

    @Update
    suspend fun updateSecretUploadEnvironmentVariable(
        variable: SecretUploadEnvironmentVariableEntity,
    ): Int

    @Query("DELETE FROM pairing_secrets WHERE pairing_request_id = :pairingRequestId AND kind = :kind")
    suspend fun deletePairingSecret(pairingRequestId: Long, kind: String): Int

    @Query("DELETE FROM pairing_secrets WHERE pairing_request_id = :pairingRequestId")
    suspend fun deletePairingSecrets(pairingRequestId: Long): Int

    @Query("DELETE FROM temporary_access_grants WHERE client_id = :clientId")
    suspend fun deleteTemporaryAccessGrantsForClient(clientId: String): Int

    @Query("DELETE FROM secret_upload_environment_variables WHERE request_id = :requestId")
    suspend fun deleteSecretUploadEnvironmentVariables(requestId: Long): Int

    @Query("DELETE FROM secret_upload_ssh_keys WHERE request_id = :requestId")
    suspend fun deleteSecretUploadSshKey(requestId: Long): Int

    @Query(
        """
        DELETE FROM secret_upload_environment_variables
        WHERE request_id IN (
            SELECT request_id FROM secret_upload_requests WHERE state != 'review_pending'
        )
        """,
    )
    suspend fun discardDecidedSecretUploadEnvironmentValues(): Int

    @Query(
        """
        DELETE FROM secret_upload_ssh_keys
        WHERE request_id IN (
            SELECT request_id FROM secret_upload_requests WHERE state != 'review_pending'
        )
        """,
    )
    suspend fun discardDecidedSecretUploadSshKeys(): Int

    @Transaction
    suspend fun discardDecidedSecretUploadValues(): Int =
        discardDecidedSecretUploadEnvironmentValues() + discardDecidedSecretUploadSshKeys()

    @Transaction
    suspend fun revokePairing(pairing: PairingEntity) {
        check(updatePairing(pairing) == 1)
        deletePairingSecrets(pairing.requestId)
        deleteTemporaryAccessGrantsForClient(pairing.clientId)
    }

    @Transaction
    suspend fun rejectPairing(request: InboxRequestEntity, pairing: PairingEntity) {
        check(updateRequest(request) == 1)
        check(updatePairing(pairing) == 1)
        deletePairingSecrets(pairing.requestId)
        deleteTemporaryAccessGrantsForClient(pairing.clientId)
        trimCompletedHistory()
    }

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
        UPDATE inbox_requests SET listed = 0
        WHERE listed = 1
          AND completed_at IS NOT NULL
          AND id NOT IN (
            SELECT id FROM inbox_requests WHERE listed = 1 ORDER BY id DESC LIMIT 100
          )
          AND id NOT IN (
            SELECT request_id FROM pairings
            WHERE state = 'active'
              AND device_identity_id IS NOT NULL
              AND COALESCE(desired_relay_client_state, relay_client_state) != 'revoked'
          )
        """,
    )
    suspend fun trimCompletedHistory(): Int

    @Query(
        """
        UPDATE inbox_requests SET listed = 0
        WHERE listed = 1
          AND completed_at IS NOT NULL
          AND id NOT IN (
            SELECT request_id FROM pairings
            WHERE state = 'active'
              AND device_identity_id IS NOT NULL
              AND COALESCE(desired_relay_client_state, relay_client_state) != 'revoked'
          )
        """,
    )
    suspend fun clearCompletedHistory(): Int

    @Query(
        """
        DELETE FROM inbox_requests
        WHERE listed = 0
          AND completed_at IS NOT NULL
          AND received_at <= :receivedBefore
          AND request_acknowledged_at IS NOT NULL
          AND (response_json IS NULL OR response_acknowledged_at IS NOT NULL)
          AND (completion_json IS NULL OR completion_acknowledged_at IS NOT NULL)
        """,
    )
    suspend fun deleteSettledHiddenRequests(receivedBefore: Long): Int

    @Query(
        "SELECT * FROM inbox_requests WHERE listed = 1 AND state = 'action_required' ORDER BY id DESC",
    )
    suspend fun getActionRequiredRequests(): List<InboxRequestEntity>

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
    suspend fun insertSecretUseRequest(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        requestSecret: RequestSecretEntity,
        currentPairingSecret: PairingSecretEntity?,
        previousPairingSecret: PairingSecretEntity?,
    ): Long {
        val requestId = insertRequest(request)
        insertSecretUseRequestRow(secretUseRequest.copy(requestId = requestId))
        storeAcceptedSecrets(requestId, requestSecret, currentPairingSecret, previousPairingSecret)
        trimCompletedHistory()
        return requestId
    }

    @Transaction
    suspend fun insertGitSignRequest(
        request: InboxRequestEntity,
        gitSignRequest: GitSignRequestEntity,
        requestSecret: RequestSecretEntity,
        currentPairingSecret: PairingSecretEntity?,
        previousPairingSecret: PairingSecretEntity?,
    ): Long {
        val requestId = insertRequest(request)
        insertGitSignRequestRow(gitSignRequest.copy(requestId = requestId))
        storeAcceptedSecrets(requestId, requestSecret, currentPairingSecret, previousPairingSecret)
        trimCompletedHistory()
        return requestId
    }

    @Transaction
    suspend fun insertSecretUseRequestIfAuthorized(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        requestSecret: RequestSecretEntity,
        currentPairingSecret: PairingSecretEntity?,
        previousPairingSecret: PairingSecretEntity?,
        authorization: AuthorizationCommitment,
        clientId: String,
        operation: String,
        now: Long,
    ): Long? {
        if (!authorizationMatches(authorization, clientId, operation, now)) return null
        return insertSecretUseRequest(
            request,
            secretUseRequest,
            requestSecret,
            currentPairingSecret,
            previousPairingSecret,
        )
    }

    @Transaction
    suspend fun insertGitSignRequestIfAuthorized(
        request: InboxRequestEntity,
        gitSignRequest: GitSignRequestEntity,
        requestSecret: RequestSecretEntity,
        currentPairingSecret: PairingSecretEntity?,
        previousPairingSecret: PairingSecretEntity?,
        authorization: AuthorizationCommitment,
        clientId: String,
        operation: String,
        now: Long,
    ): Long? {
        if (!authorizationMatches(authorization, clientId, operation, now)) return null
        return insertGitSignRequest(
            request,
            gitSignRequest,
            requestSecret,
            currentPairingSecret,
            previousPairingSecret,
        )
    }

    @Transaction
    suspend fun insertSecretListRequest(
        request: InboxRequestEntity,
        secretListRequest: SecretListRequestEntity,
        requestSecret: RequestSecretEntity,
        currentPairingSecret: PairingSecretEntity?,
        previousPairingSecret: PairingSecretEntity?,
    ): Long {
        val requestId = insertRequest(request)
        insertSecretListRequestRow(secretListRequest.copy(requestId = requestId))
        storeAcceptedSecrets(requestId, requestSecret, currentPairingSecret, previousPairingSecret)
        trimCompletedHistory()
        return requestId
    }

    @Transaction
    suspend fun insertSecretUploadRequest(
        request: InboxRequestEntity,
        secretUpload: SecretUploadRequestEntity,
        environmentVariables: List<SecretUploadEnvironmentVariableEntity>,
        sshKey: SecretUploadSshKeyEntity?,
        requestSecret: RequestSecretEntity,
        currentPairingSecret: PairingSecretEntity?,
        previousPairingSecret: PairingSecretEntity?,
    ): Long {
        val requestId = insertRequest(request)
        insertSecretUploadRequestRow(secretUpload.copy(requestId = requestId))
        if (environmentVariables.isNotEmpty()) {
            insertSecretUploadEnvironmentVariables(
                environmentVariables.map { it.copy(requestId = requestId) },
            )
        }
        sshKey?.let { insertSecretUploadSshKey(it.copy(requestId = requestId)) }
        storeAcceptedSecrets(requestId, requestSecret, currentPairingSecret, previousPairingSecret)
        trimCompletedHistory()
        return requestId
    }

    @Transaction
    suspend fun insertHiddenPairedRequest(
        request: InboxRequestEntity,
        requestSecret: RequestSecretEntity,
        currentPairingSecret: PairingSecretEntity?,
        previousPairingSecret: PairingSecretEntity?,
    ): Long {
        val requestId = insertRequest(request)
        storeAcceptedSecrets(requestId, requestSecret, currentPairingSecret, previousPairingSecret)
        return requestId
    }

    @Transaction
    suspend fun insertPairingRemoval(
        request: InboxRequestEntity,
        requestSecret: RequestSecretEntity,
        pairing: PairingEntity,
    ): Long {
        val requestId = insertRequest(request)
        insertRequestSecret(requestSecret.copy(requestId = requestId))
        check(updatePairing(pairing) == 1)
        deletePairingSecrets(pairing.requestId)
        deleteTemporaryAccessGrantsForClient(pairing.clientId)
        trimCompletedHistory()
        return requestId
    }

    @Transaction
    suspend fun storeAcceptedSecrets(
        requestId: Long,
        requestSecret: RequestSecretEntity,
        currentPairingSecret: PairingSecretEntity?,
        previousPairingSecret: PairingSecretEntity?,
    ) {
        insertRequestSecret(requestSecret.copy(requestId = requestId))
        if (currentPairingSecret != null || previousPairingSecret != null) {
            checkNotNull(currentPairingSecret)
            checkNotNull(previousPairingSecret)
            deletePairingSecret(previousPairingSecret.pairingRequestId, previousPairingSecret.kind)
            upsertPairingSecret(previousPairingSecret)
            upsertPairingSecret(currentPairingSecret)
        }
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
    suspend fun updateSecretUseRequest(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
    ) {
        check(updateRequest(request) == 1)
        check(updateSecretUseRequestRow(secretUseRequest) == 1)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun updateSecretUseRequestIfAuthorized(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        authorization: AuthorizationCommitment,
        clientId: String,
        operation: String,
        now: Long,
    ): Boolean {
        if (!authorizationMatches(authorization, clientId, operation, now)) return false
        updateSecretUseRequest(request, secretUseRequest)
        return true
    }

    @Transaction
    suspend fun updateGitSignRequest(
        request: InboxRequestEntity,
        gitSignRequest: GitSignRequestEntity,
    ) {
        check(updateRequest(request) == 1)
        check(updateGitSignRequestRow(gitSignRequest) == 1)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun updateGitSignRequestIfAuthorized(
        request: InboxRequestEntity,
        gitSignRequest: GitSignRequestEntity,
        authorization: AuthorizationCommitment,
        clientId: String,
        operation: String,
        now: Long,
    ): Boolean {
        if (!authorizationMatches(authorization, clientId, operation, now)) return false
        updateGitSignRequest(request, gitSignRequest)
        return true
    }

    @Transaction
    suspend fun authorizationMatches(
        authorization: AuthorizationCommitment,
        clientId: String,
        operation: String,
        now: Long,
    ): Boolean {
        val pairing = getPairingByClientId(clientId) ?: return false
        if (
            pairing.state != "active" ||
            pairing.relayClientState != "active" ||
            pairing.desiredRelayClientState?.let { it != "active" } == true
        ) {
            return false
        }
        authorization.deviceInstructions?.let { expected ->
            if (
                getAuthorizationDeviceInstructions(expected.deviceIdentityId) !=
                expected.instructions
            ) {
                return false
            }
        }
        val secretIds = authorization.secretRevisions.keys
        if (!secretIds.containsAll(authorization.policies.keys)) return false
        if (secretIds.isEmpty()) return authorization.policies.isEmpty()
        val secrets = getAuthorizationSecrets(secretIds.toList()).associateBy(SecretEntity::id)
        if (secrets.size != secretIds.size) return false
        if (authorization.secretRevisions.any { (id, revision) ->
                secrets.getValue(id).revision != revision
            }
        ) {
            return false
        }
        if (authorization.policies.isEmpty()) return true
        val policyIds = authorization.policies.keys.toList()
        val overrides = getAuthorizationOverrides(clientId, policyIds)
            .associateBy(SecretClientApprovalOverrideEntity::secretId)
        val activeGrants = getAuthorizationGrants(clientId, policyIds, operation)
            .filter { it.expiresAt > now }
            .associateBy(TemporaryAccessGrantEntity::secretId)
        return authorization.policies.all { (secretId, expected) ->
            val currentMode = overrides[secretId]?.approvalMode
                ?: secrets.getValue(secretId).approvalMode
            currentMode == expected.mode &&
                activeGrants[secretId]?.expiresAt == expected.temporaryAccessExpiresAt
        }
    }

    @Transaction
    suspend fun updateSecretListRequest(
        request: InboxRequestEntity,
        secretListRequest: SecretListRequestEntity,
    ) {
        check(updateRequest(request) == 1)
        check(updateSecretListRequestRow(secretListRequest) == 1)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun updateSecretUploadRequest(
        request: InboxRequestEntity,
        secretUpload: SecretUploadRequestEntity,
        discardUploadedValues: Boolean = false,
    ) {
        check(updateRequest(request) == 1)
        check(updateSecretUploadRequestRow(secretUpload) == 1)
        if (discardUploadedValues) {
            deleteSecretUploadEnvironmentVariables(request.id)
            deleteSecretUploadSshKey(request.id)
        }
        trimCompletedHistory()
    }

    @Transaction
    suspend fun finishPairing(
        rootRequest: InboxRequestEntity,
        pairing: PairingEntity,
        finishRequest: InboxRequestEntity,
        requestSecret: RequestSecretEntity,
        currentPairingSecret: PairingSecretEntity?,
        previousPairingSecret: PairingSecretEntity?,
    ) {
        check(updateRequest(rootRequest) == 1)
        check(updatePairing(pairing) == 1)
        val finishRequestId = insertRequest(finishRequest)
        storeAcceptedSecrets(
            finishRequestId,
            requestSecret,
            currentPairingSecret,
            previousPairingSecret,
        )
        trimCompletedHistory()
    }
}
