package dev.agentknock.storage.request

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Ignore
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.secret.SecretClientApprovalOverrideEntity
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.TemporaryAccessGrantEntity
import dev.agentknock.storage.device.DeviceIdentityEntity
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "inbox_requests",
    foreignKeys = [
        ForeignKey(
            entity = InboxRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_request_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = DeviceIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["device_identity_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["parent_request_id"]),
        Index(value = ["device_identity_id"]),
        Index(value = ["listed", "received_at", "id"]),
    ],
)
internal data class InboxRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "parent_request_id")
    val parentRequestId: String?,
    @ColumnInfo(name = "device_identity_id")
    val deviceIdentityId: String,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "client_name_snapshot")
    val clientNameSnapshot: String,
    @ColumnInfo(name = "client_software_json")
    val clientSoftwareJson: String?,
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
    @ColumnInfo(name = "error")
    val error: String?,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
    @ColumnInfo(name = "exchange_ended_at")
    val exchangeEndedAt: Long?,
    @ColumnInfo(name = "response_outbox_finished")
    val responseOutboxFinished: Boolean,
)

@Entity(
    tableName = "pairing_attempts",
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
            childColumns = ["pending_psk_encryption_key_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["pending_psk_encryption_key_id"]),
    ],
)
internal data class PairingAttemptEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "pairing_address")
    val pairingAddress: String,
    @ColumnInfo(name = "friendly_name")
    val friendlyName: String?,
    @ColumnInfo(name = "device_random")
    val deviceRandom: ByteArray,
    @ColumnInfo(name = "desired_relay_client_state")
    val desiredRelayClientState: String?,
    @ColumnInfo(name = "relay_client_state")
    val relayClientState: String,
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
    @Embedded(prefix = "pending_psk_")
    val pendingPsk: EncryptedValue?,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long?,
) {
    @get:Ignore
    val clientId: String
        get() = requestId
}

private fun PairingAttemptEntity.hasCompletePendingPsk(): Boolean =
    pendingPsk != null

private fun PairingAttemptEntity.hasNoPendingPsk(): Boolean =
    pendingPsk == null

@Entity(
    tableName = "clients",
    foreignKeys = [
        ForeignKey(
            entity = DeviceIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["device_identity_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["device_identity_id"])],
)
internal data class ClientEntity(
    @PrimaryKey
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "device_identity_id")
    val deviceIdentityId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "instructions")
    val instructions: String,
    @ColumnInfo(name = "desired_relay_client_state")
    val desiredRelayClientState: String?,
    @ColumnInfo(name = "relay_client_state")
    val relayClientState: String,
    @ColumnInfo(name = "client_software_json")
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
    @ColumnInfo(name = "paired_at")
    val pairedAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long?,
)

@Entity(
    tableName = "client_psks",
    primaryKeys = ["client_id", "slot"],
    foreignKeys = [
        ForeignKey(
            entity = ClientEntity::class,
            parentColumns = ["client_id"],
            childColumns = ["client_id"],
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
internal data class ClientPskEntity(
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "slot")
    val slot: String,
    @Embedded
    val encryptedPsk: EncryptedValue,
    @ColumnInfo(name = "stored_at")
    val storedAt: Long,
)

@Entity(
    tableName = "request_psks",
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
    indices = [Index(value = ["encryption_key_id"])],
)
internal data class RequestPskEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @Embedded
    val encryptedPsk: EncryptedValue,
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
    ],
)
internal data class SecretUseRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
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
    @ColumnInfo(name = "invocation_token_hash")
    val invocationTokenHash: ByteArray,
    @ColumnInfo(name = "contains_sensitive_material")
    val containsSensitiveMaterial: Boolean,
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
    @ColumnInfo(name = "approval_evaluation_json")
    val approvalEvaluationJson: String?,
    @ColumnInfo(name = "completion_result")
    val completionResult: String?,
    @ColumnInfo(name = "completion_reason")
    val completionReason: String?,
    @ColumnInfo(name = "completion_message")
    val completionMessage: String?,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long?,
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
)
internal data class GitSignRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "secret_name")
    val secretName: String,
    @ColumnInfo(name = "message")
    val message: ByteArray,
    @ColumnInfo(name = "repository_json")
    val repositoryJson: String?,
    @ColumnInfo(name = "approval_evaluation_json")
    val approvalEvaluationJson: String?,
    @ColumnInfo(name = "decision")
    val decision: String?,
    @ColumnInfo(name = "completion_result")
    val completionResult: String?,
    @ColumnInfo(name = "completion_reason")
    val completionReason: String?,
    @ColumnInfo(name = "completion_message")
    val completionMessage: String?,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long?,
)

@Entity(
    tableName = "ssh_authentication_requests",
    foreignKeys = [
        ForeignKey(
            entity = InboxRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
)
internal data class SshAuthenticationRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "secret_name")
    val secretName: String,
    @ColumnInfo(name = "message")
    val message: ByteArray?,
    @ColumnInfo(name = "username")
    val username: String,
    @ColumnInfo(name = "method")
    val method: String,
    @ColumnInfo(name = "algorithm")
    val algorithm: String,
    @ColumnInfo(name = "host_key_algorithm")
    val hostKeyAlgorithm: String?,
    @ColumnInfo(name = "host_key_fingerprint")
    val hostKeyFingerprint: String?,
    @ColumnInfo(name = "approval_evaluation_json")
    val approvalEvaluationJson: String?,
    @ColumnInfo(name = "decision")
    val decision: String?,
    @ColumnInfo(name = "completion_result")
    val completionResult: String?,
    @ColumnInfo(name = "completion_reason")
    val completionReason: String?,
    @ColumnInfo(name = "completion_message")
    val completionMessage: String?,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long?,
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
    ],
)
internal data class SecretUploadRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "decision")
    val decision: String?,
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
    @ColumnInfo(name = "target_secret_id")
    val targetSecretId: String?,
    @ColumnInfo(name = "target_secret_revision")
    val targetSecretRevision: Long?,
    @ColumnInfo(name = "summary_json")
    val summaryJson: String,
    @ColumnInfo(name = "intake_error")
    val intakeError: String?,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long?,
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
    val requestId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "sensitive")
    val sensitive: Boolean,
    @Embedded
    val encryptedValue: EncryptedValue,
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
    val requestId: String,
    @ColumnInfo(name = "algorithm")
    val algorithm: String,
    @ColumnInfo(name = "public_key")
    val publicKey: ByteArray,
    @ColumnInfo(name = "comment")
    val comment: String,
    @Embedded
    val encryptedPrivateKey: EncryptedValue,
)

internal data class AuthorizationPolicyCommitment(
    val mode: String,
    val temporaryAccessExpiresAt: Long?,
)

internal data class AuthorizationInstructionsCommitment(
    val deviceIdentityId: String,
    val deviceInstructions: String,
    val clientId: String,
    val clientName: String,
    val clientInstructions: String,
)

internal data class AuthorizationCommitment(
    val secretRevisions: Map<String, Long>,
    val policies: Map<String, AuthorizationPolicyCommitment>,
    val expectedAbsentSecretNames: Set<String> = emptySet(),
    val instructions: AuthorizationInstructionsCommitment? = null,
)

internal enum class ConditionalRequestUpdate {
    APPLIED,
    ACTION_REQUIRED,
    UNAVAILABLE,
}

@Dao
internal interface RequestDao {
    @Query(
        "SELECT inbox_requests.* FROM inbox_requests " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
        "WHERE inbox_requests.listed = 1 AND device_identities.role = 'active' " +
            "AND inbox_requests.kind IN ('secret_use', 'git_sign', 'ssh_authenticate') " +
            "ORDER BY inbox_requests.received_at DESC, inbox_requests.id DESC",
    )
    fun observeListedRequests(): Flow<List<InboxRequestEntity>>

    @Query(
        "SELECT inbox_requests.* FROM inbox_requests " +
            "JOIN pairing_attempts ON pairing_attempts.request_id = inbox_requests.id " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
            "WHERE device_identities.role = 'active' " +
            "AND inbox_requests.completed_at IS NULL " +
            "AND pairing_attempts.state IN " +
            "('exchange_pending', 'exchange_failed', 'sas_verification_pending', " +
            "'waiting_for_finish') " +
            "ORDER BY inbox_requests.received_at DESC, inbox_requests.id DESC",
    )
    fun observePendingPairingRequests(): Flow<List<InboxRequestEntity>>

    @Query(
        "SELECT inbox_requests.* FROM inbox_requests " +
            "JOIN secret_upload_requests " +
            "ON secret_upload_requests.request_id = inbox_requests.id " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
            "WHERE device_identities.role = 'active' " +
            "AND inbox_requests.completed_at IS NULL " +
            "AND secret_upload_requests.decision IS NULL " +
            "ORDER BY inbox_requests.received_at DESC, inbox_requests.id DESC",
    )
    fun observePendingSecretUploadRequests(): Flow<List<InboxRequestEntity>>

    @Query(
        "SELECT pairing_attempts.* FROM pairing_attempts " +
            "JOIN inbox_requests ON inbox_requests.id = pairing_attempts.request_id " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
            "WHERE device_identities.role = 'active' " +
            "ORDER BY inbox_requests.received_at DESC, pairing_attempts.request_id DESC",
    )
    fun observePairingAttempts(): Flow<List<PairingAttemptEntity>>

    @Query(
        "SELECT clients.* FROM clients " +
            "JOIN device_identities ON device_identities.id = clients.device_identity_id " +
            "WHERE device_identities.role = 'active' " +
            "ORDER BY clients.name COLLATE NOCASE, clients.client_id",
    )
    fun observeClients(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM secret_use_requests ORDER BY request_id DESC")
    fun observeSecretUseRequests(): Flow<List<SecretUseRequestEntity>>

    @Query("SELECT * FROM git_sign_requests ORDER BY request_id DESC")
    fun observeGitSignRequests(): Flow<List<GitSignRequestEntity>>

    @Query("SELECT * FROM ssh_authentication_requests ORDER BY request_id DESC")
    fun observeSshAuthenticationRequests(): Flow<List<SshAuthenticationRequestEntity>>

    @Query("SELECT * FROM secret_upload_requests ORDER BY request_id DESC")
    fun observeSecretUploadRequests(): Flow<List<SecretUploadRequestEntity>>

    @Query(
        "SELECT inbox_requests.* FROM inbox_requests " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
            "WHERE inbox_requests.id = :id AND device_identities.role = 'active'",
    )
    fun observeRequest(id: String): Flow<InboxRequestEntity?>

    @Query(
        "SELECT pairing_attempts.* FROM pairing_attempts " +
            "JOIN inbox_requests ON inbox_requests.id = pairing_attempts.request_id " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
            "WHERE pairing_attempts.request_id = :requestId " +
            "AND device_identities.role = 'active'",
    )
    fun observePairingAttempt(requestId: String): Flow<PairingAttemptEntity?>

    @Query("SELECT * FROM secret_use_requests WHERE request_id = :requestId")
    fun observeSecretUseRequest(requestId: String): Flow<SecretUseRequestEntity?>

    @Query("SELECT * FROM git_sign_requests WHERE request_id = :requestId")
    fun observeGitSignRequest(requestId: String): Flow<GitSignRequestEntity?>

    @Query("SELECT * FROM ssh_authentication_requests WHERE request_id = :requestId")
    fun observeSshAuthenticationRequest(requestId: String): Flow<SshAuthenticationRequestEntity?>

    @Query("SELECT * FROM secret_upload_requests WHERE request_id = :requestId")
    fun observeSecretUploadRequest(requestId: String): Flow<SecretUploadRequestEntity?>

    @Query(
        "SELECT * FROM secret_upload_environment_variables WHERE request_id = :requestId ORDER BY name",
    )
    fun observeSecretUploadEnvironmentVariables(
        requestId: String,
    ): Flow<List<SecretUploadEnvironmentVariableEntity>>

    @Query(
        "SELECT clients.* FROM clients " +
            "JOIN device_identities ON device_identities.id = clients.device_identity_id " +
            "WHERE clients.client_id = :clientId AND device_identities.role = 'active'",
    )
    fun observeClient(clientId: String): Flow<ClientEntity?>

    @Query("SELECT * FROM inbox_requests WHERE id = :id")
    suspend fun getRequestById(id: String): InboxRequestEntity?

    @Query(
        """
        UPDATE inbox_requests
        SET state = 'action_required'
        WHERE state = 'reviewing'
        """,
    )
    suspend fun recoverInterruptedAiReviews(): Int

    @Query(
        """
        UPDATE inbox_requests
        SET state = 'action_required'
        WHERE id = :requestId AND request_json = :requestJson AND state = 'reviewing'
        """,
    )
    suspend fun recoverInterruptedAiReview(
        requestId: String,
        requestJson: String,
    ): Int

    @Query("SELECT EXISTS(SELECT 1 FROM device_identities WHERE id = :id AND role = 'active')")
    suspend fun isActiveIdentity(id: String): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM inbox_requests " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
            "WHERE inbox_requests.id = :requestId " +
            "AND inbox_requests.device_identity_id = :deviceIdentityId " +
            "AND inbox_requests.completed_at IS NULL " +
            "AND device_identities.role = 'active')",
    )
    suspend fun canAdvanceRequest(requestId: String, deviceIdentityId: String): Boolean

    @Query(
        "SELECT pairing_attempts.* FROM pairing_attempts " +
            "JOIN inbox_requests ON inbox_requests.id = pairing_attempts.request_id " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
            "WHERE pairing_attempts.request_id = :requestId " +
            "AND device_identities.role = 'active'",
    )
    suspend fun getPairingAttempt(requestId: String): PairingAttemptEntity?

    @Query(
        "SELECT clients.* FROM clients " +
            "JOIN device_identities ON device_identities.id = clients.device_identity_id " +
            "WHERE clients.client_id = :clientId AND device_identities.role = 'active'",
    )
    suspend fun getClient(clientId: String): ClientEntity?

    @Query("SELECT * FROM clients WHERE client_id = :clientId")
    suspend fun getClientById(clientId: String): ClientEntity?

    @Query("SELECT * FROM secrets WHERE id IN (:secretIds)")
    suspend fun getAuthorizationSecrets(secretIds: List<String>): List<SecretEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM secrets WHERE name = :name)")
    suspend fun authorizationSecretExists(name: String): Boolean

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
    suspend fun getSecretUseRequest(requestId: String): SecretUseRequestEntity?

    @Query("SELECT * FROM git_sign_requests WHERE request_id = :requestId")
    suspend fun getGitSignRequest(requestId: String): GitSignRequestEntity?

    @Query("SELECT * FROM ssh_authentication_requests WHERE request_id = :requestId")
    suspend fun getSshAuthenticationRequest(requestId: String): SshAuthenticationRequestEntity?

    @Query("SELECT * FROM secret_upload_requests WHERE request_id = :requestId")
    suspend fun getSecretUploadRequest(requestId: String): SecretUploadRequestEntity?

    @Query(
        "SELECT * FROM secret_upload_environment_variables WHERE request_id = :requestId ORDER BY name",
    )
    suspend fun getSecretUploadEnvironmentVariables(
        requestId: String,
    ): List<SecretUploadEnvironmentVariableEntity>

    @Query("SELECT * FROM secret_upload_ssh_keys WHERE request_id = :requestId")
    suspend fun getSecretUploadSshKey(requestId: String): SecretUploadSshKeyEntity?

    @Query(
        "SELECT pairing_attempts.* FROM pairing_attempts " +
            "JOIN inbox_requests ON inbox_requests.id = pairing_attempts.request_id " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
            "WHERE device_identities.role = 'active' " +
            "ORDER BY inbox_requests.received_at, pairing_attempts.request_id",
    )
    suspend fun getPairingAttempts(): List<PairingAttemptEntity>

    @Query(
        "SELECT clients.* FROM clients " +
            "JOIN device_identities ON device_identities.id = clients.device_identity_id " +
            "WHERE device_identities.role = 'active' " +
            "ORDER BY clients.paired_at, clients.client_id",
    )
    suspend fun getClients(): List<ClientEntity>

    @Query(
        "SELECT * FROM client_psks WHERE client_id = :clientId AND slot = :slot",
    )
    suspend fun getClientPsk(clientId: String, slot: String): ClientPskEntity?

    @Query("SELECT * FROM request_psks WHERE request_id = :requestId")
    suspend fun getRequestPsk(requestId: String): RequestPskEntity?

    @Query(
        """
        DELETE FROM request_psks
        WHERE request_id = :requestId
          AND EXISTS (
            SELECT 1 FROM inbox_requests
            WHERE id = :requestId AND exchange_ended_at IS NOT NULL
          )
        """,
    )
    suspend fun deleteEndedRequestPsk(requestId: String): Int

    @Query(
        """
        DELETE FROM request_psks
        WHERE request_id IN (
            SELECT id FROM inbox_requests WHERE exchange_ended_at IS NOT NULL
        )
        """,
    )
    suspend fun deleteEndedRequestPsks(): Int

    @Query(
        "SELECT inbox_requests.* FROM inbox_requests " +
            "JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id " +
            "WHERE response_json IS NOT NULL AND response_outbox_finished = 0 " +
            "AND exchange_ended_at IS NULL AND device_identities.role = 'active' " +
            "ORDER BY received_at, inbox_requests.id",
    )
    suspend fun getUnfinishedResponseOutboxes(): List<InboxRequestEntity>

    @Query(
        """
        SELECT inbox_requests.* FROM inbox_requests
        JOIN device_identities ON device_identities.id = inbox_requests.device_identity_id
        WHERE device_identities.role = 'active'
          AND exchange_ended_at IS NULL
        ORDER BY received_at, inbox_requests.id
        """,
    )
    suspend fun getOpenExchanges(): List<InboxRequestEntity>

    @Insert
    suspend fun insertRequest(request: InboxRequestEntity)

    @Insert
    suspend fun insertPairingAttempt(attempt: PairingAttemptEntity)

    @Insert
    suspend fun insertClient(client: ClientEntity)

    @Insert
    suspend fun insertClientPsk(psk: ClientPskEntity)

    @Insert
    suspend fun insertRequestPsk(psk: RequestPskEntity)

    @Insert
    suspend fun insertSecretUseRequestRow(request: SecretUseRequestEntity)

    @Insert
    suspend fun insertGitSignRequestRow(request: GitSignRequestEntity)

    @Insert
    suspend fun insertSshAuthenticationRequestRow(request: SshAuthenticationRequestEntity)

    @Insert
    suspend fun insertSecretUploadRequestRow(request: SecretUploadRequestEntity)

    @Insert
    suspend fun insertSecretUploadEnvironmentVariables(
        variables: List<SecretUploadEnvironmentVariableEntity>,
    )

    @Insert
    suspend fun insertSecretUploadSshKey(key: SecretUploadSshKeyEntity)

    @Upsert
    suspend fun upsertClientPsk(psk: ClientPskEntity)

    @Update
    suspend fun updateRequest(request: InboxRequestEntity): Int

    @Transaction
    suspend fun updateEndedRequest(request: InboxRequestEntity) {
        check(request.exchangeEndedAt != null)
        check(updateRequest(request) == 1)
        deleteEndedRequestPsk(request.id)
        trimCompletedHistory()
    }

    @Update
    suspend fun updatePairingAttempt(attempt: PairingAttemptEntity): Int

    @Update
    suspend fun updateClient(client: ClientEntity): Int

    @Update
    suspend fun updateSecretUseRequestRow(request: SecretUseRequestEntity): Int

    @Update
    suspend fun updateGitSignRequestRow(request: GitSignRequestEntity): Int

    @Update
    suspend fun updateSshAuthenticationRequestRow(request: SshAuthenticationRequestEntity): Int

    @Update
    suspend fun updateSecretUploadRequestRow(request: SecretUploadRequestEntity): Int

    @Update
    suspend fun updateSecretUploadEnvironmentVariable(
        variable: SecretUploadEnvironmentVariableEntity,
    ): Int

    @Query("DELETE FROM client_psks WHERE client_id = :clientId AND slot = :slot")
    suspend fun deleteClientPsk(clientId: String, slot: String): Int

    @Query("DELETE FROM client_psks WHERE slot = 'previous' AND stored_at < :storedBefore")
    suspend fun deleteExpiredPreviousClientPsks(storedBefore: Long): Int

    @Query("DELETE FROM client_psks WHERE client_id = :clientId")
    suspend fun deleteClientPsks(clientId: String): Int

    @Query("DELETE FROM clients WHERE client_id = :clientId")
    suspend fun deleteClient(clientId: String): Int

    @Query("DELETE FROM temporary_access_grants WHERE client_id = :clientId")
    suspend fun deleteTemporaryAccessGrantsForClient(clientId: String): Int

    @Query("DELETE FROM secret_upload_environment_variables WHERE request_id = :requestId")
    suspend fun deleteSecretUploadEnvironmentVariables(requestId: String): Int

    @Query("DELETE FROM secret_upload_ssh_keys WHERE request_id = :requestId")
    suspend fun deleteSecretUploadSshKey(requestId: String): Int

    @Query(
        """
        DELETE FROM secret_upload_environment_variables
        WHERE request_id IN (
            SELECT request_id FROM secret_upload_requests WHERE decision IS NOT NULL
        )
        """,
    )
    suspend fun discardDecidedSecretUploadEnvironmentValues(): Int

    @Query(
        """
        DELETE FROM secret_upload_ssh_keys
        WHERE request_id IN (
            SELECT request_id FROM secret_upload_requests WHERE decision IS NOT NULL
        )
        """,
    )
    suspend fun discardDecidedSecretUploadSshKeys(): Int

    @Transaction
    suspend fun discardDecidedSecretUploadValues(): Int =
        discardDecidedSecretUploadEnvironmentValues() + discardDecidedSecretUploadSshKeys()

    @Transaction
    suspend fun revokeClient(client: ClientEntity) {
        check(updateClient(client) == 1)
        deleteClientPsks(client.clientId)
        deleteTemporaryAccessGrantsForClient(client.clientId)
    }

    @Transaction
    suspend fun rejectPairing(request: InboxRequestEntity, attempt: PairingAttemptEntity) {
        check(request.id == attempt.requestId)
        check(request.clientId == attempt.clientId)
        check(attempt.state == "rejected")
        check(attempt.hasNoPendingPsk())
        check(canAdvanceRequest(request.id, request.deviceIdentityId))
        check(updateRequest(request) == 1)
        check(updatePairingAttempt(attempt) == 1)
        trimCompletedHistory()
    }

    @Query(
        """
        UPDATE inbox_requests SET response_outbox_finished = 1
        WHERE id = :requestId AND response_outbox_finished = 0
        """,
    )
    suspend fun markResponseOutboxFinished(requestId: String): Int

    @Query(
        """
        UPDATE inbox_requests SET listed = 0
        WHERE listed = 1
          AND completed_at IS NOT NULL
          AND EXISTS (
            SELECT 1 FROM device_identities
            WHERE device_identities.id = inbox_requests.device_identity_id
              AND device_identities.role = 'active'
          )
          AND (
            kind NOT IN ('secret_use', 'git_sign', 'ssh_authenticate')
            OR id NOT IN (
              SELECT active_requests.id FROM inbox_requests AS active_requests
              JOIN device_identities
                ON device_identities.id = active_requests.device_identity_id
              WHERE active_requests.listed = 1
                AND active_requests.completed_at IS NOT NULL
                AND active_requests.kind IN ('secret_use', 'git_sign', 'ssh_authenticate')
                AND device_identities.role = 'active'
              ORDER BY active_requests.received_at DESC, active_requests.id DESC
              LIMIT 100
            )
          )
        """,
    )
    suspend fun trimCompletedHistory(): Int

    @Query(
        """
        DELETE FROM inbox_requests
        WHERE listed = 0
          AND completed_at IS NOT NULL
          AND exchange_ended_at IS NOT NULL
          AND exchange_ended_at <= :endedBefore
          AND NOT EXISTS (
            SELECT 1 FROM inbox_requests AS child
            WHERE child.parent_request_id = inbox_requests.id
          )
          AND NOT EXISTS (
            SELECT 1 FROM pairing_attempts AS attempt
            WHERE attempt.request_id = inbox_requests.id
              AND attempt.desired_relay_client_state IS NOT NULL
              AND (
                attempt.relay_client_state != attempt.desired_relay_client_state
              )
          )
        """,
    )
    suspend fun deleteSettledHiddenRequests(endedBefore: Long): Int

    @Query(
        """
        SELECT inbox_requests.* FROM inbox_requests
        JOIN device_identities
          ON device_identities.id = inbox_requests.device_identity_id
        LEFT JOIN pairing_attempts
          ON pairing_attempts.request_id = inbox_requests.id
        LEFT JOIN secret_use_requests
          ON secret_use_requests.request_id = inbox_requests.id
        LEFT JOIN git_sign_requests
          ON git_sign_requests.request_id = inbox_requests.id
        LEFT JOIN ssh_authentication_requests
          ON ssh_authentication_requests.request_id = inbox_requests.id
        LEFT JOIN secret_upload_requests
          ON secret_upload_requests.request_id = inbox_requests.id
        WHERE inbox_requests.listed = 1
          AND inbox_requests.state = 'action_required'
          AND device_identities.role = 'active'
        ORDER BY inbox_requests.received_at DESC, inbox_requests.id DESC
        """,
    )
    fun observeActionRequiredRequests(): Flow<List<InboxRequestEntity>>

    @Transaction
    suspend fun insertPairingRequest(
        request: InboxRequestEntity,
        attempt: PairingAttemptEntity,
    ) {
        check(attempt.requestId == request.id)
        check(attempt.clientId == request.clientId)
        check(isActiveIdentity(request.deviceIdentityId))
        insertRequest(request)
        insertPairingAttempt(attempt)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun insertSecretUseRequest(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        client: ClientEntity,
        requestPsk: RequestPskEntity,
        currentClientPsk: ClientPskEntity?,
        previousClientPsk: ClientPskEntity?,
    ) {
        check(secretUseRequest.requestId == request.id)
        check(client.clientId == request.clientId)
        check(client.deviceIdentityId == request.deviceIdentityId)
        check(isActiveIdentity(request.deviceIdentityId))
        check(updateClient(client) == 1)
        insertRequest(request)
        insertSecretUseRequestRow(secretUseRequest)
        storeAcceptedPsks(request, requestPsk, currentClientPsk, previousClientPsk)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun insertGitSignRequest(
        request: InboxRequestEntity,
        gitSignRequest: GitSignRequestEntity,
        client: ClientEntity,
        requestPsk: RequestPskEntity,
        currentClientPsk: ClientPskEntity?,
        previousClientPsk: ClientPskEntity?,
    ) {
        check(gitSignRequest.requestId == request.id)
        check(client.clientId == request.clientId)
        check(client.deviceIdentityId == request.deviceIdentityId)
        check(isActiveIdentity(request.deviceIdentityId))
        check(updateClient(client) == 1)
        insertRequest(request)
        insertGitSignRequestRow(gitSignRequest)
        storeAcceptedPsks(request, requestPsk, currentClientPsk, previousClientPsk)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun insertSshAuthenticationRequest(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
        client: ClientEntity,
        requestPsk: RequestPskEntity,
        currentClientPsk: ClientPskEntity?,
        previousClientPsk: ClientPskEntity?,
    ) {
        check(authentication.requestId == request.id)
        check(client.clientId == request.clientId)
        check(client.deviceIdentityId == request.deviceIdentityId)
        check(isActiveIdentity(request.deviceIdentityId))
        check(updateClient(client) == 1)
        insertRequest(request)
        insertSshAuthenticationRequestRow(authentication)
        storeAcceptedPsks(request, requestPsk, currentClientPsk, previousClientPsk)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun insertSecretListRequest(
        request: InboxRequestEntity,
        client: ClientEntity,
        requestPsk: RequestPskEntity,
        currentClientPsk: ClientPskEntity?,
        previousClientPsk: ClientPskEntity?,
    ) {
        check(client.clientId == request.clientId)
        check(client.deviceIdentityId == request.deviceIdentityId)
        check(isActiveIdentity(request.deviceIdentityId))
        check(updateClient(client) == 1)
        insertRequest(request)
        storeAcceptedPsks(request, requestPsk, currentClientPsk, previousClientPsk)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun insertSecretUploadRequest(
        request: InboxRequestEntity,
        secretUpload: SecretUploadRequestEntity,
        client: ClientEntity,
        environmentVariables: List<SecretUploadEnvironmentVariableEntity>,
        sshKey: SecretUploadSshKeyEntity?,
        requestPsk: RequestPskEntity,
        currentClientPsk: ClientPskEntity?,
        previousClientPsk: ClientPskEntity?,
    ) {
        check(secretUpload.requestId == request.id)
        check(client.clientId == request.clientId)
        check(client.deviceIdentityId == request.deviceIdentityId)
        check(isActiveIdentity(request.deviceIdentityId))
        check(environmentVariables.all { it.requestId == request.id })
        check(sshKey == null || sshKey.requestId == request.id)
        check(updateClient(client) == 1)
        insertRequest(request)
        insertSecretUploadRequestRow(secretUpload)
        if (environmentVariables.isNotEmpty()) {
            insertSecretUploadEnvironmentVariables(environmentVariables)
        }
        sshKey?.let { insertSecretUploadSshKey(it) }
        storeAcceptedPsks(request, requestPsk, currentClientPsk, previousClientPsk)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun insertHiddenPairedRequest(
        request: InboxRequestEntity,
        client: ClientEntity?,
        requestPsk: RequestPskEntity,
        currentClientPsk: ClientPskEntity?,
        previousClientPsk: ClientPskEntity?,
    ) {
        if (client != null) {
            check(client.clientId == request.clientId)
            check(client.deviceIdentityId == request.deviceIdentityId)
            check(updateClient(client) == 1)
        }
        check(isActiveIdentity(request.deviceIdentityId))
        insertRequest(request)
        storeAcceptedPsks(request, requestPsk, currentClientPsk, previousClientPsk)
    }

    @Transaction
    suspend fun insertPairingRemoval(
        request: InboxRequestEntity,
        requestPsk: RequestPskEntity,
        client: ClientEntity,
    ) {
        check(requestPsk.requestId == request.id)
        check(request.clientId == client.clientId)
        check(client.deviceIdentityId == request.deviceIdentityId)
        check(isActiveIdentity(request.deviceIdentityId))
        insertRequest(request)
        insertRequestPsk(requestPsk)
        check(updateClient(client) == 1)
        deleteClientPsks(client.clientId)
        deleteTemporaryAccessGrantsForClient(client.clientId)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun storeAcceptedPsks(
        request: InboxRequestEntity,
        requestPsk: RequestPskEntity,
        currentClientPsk: ClientPskEntity?,
        previousClientPsk: ClientPskEntity?,
    ) {
        check(requestPsk.requestId == request.id)
        insertRequestPsk(requestPsk)
        if (currentClientPsk != null || previousClientPsk != null) {
            checkNotNull(currentClientPsk)
            checkNotNull(previousClientPsk)
            check(currentClientPsk.clientId == request.clientId)
            check(previousClientPsk.clientId == request.clientId)
            check(currentClientPsk.slot == "current")
            check(previousClientPsk.slot == "previous")
            deleteClientPsk(previousClientPsk.clientId, previousClientPsk.slot)
            upsertClientPsk(previousClientPsk)
            upsertClientPsk(currentClientPsk)
        }
    }

    @Transaction
    suspend fun recordInitialCompletion(
        request: InboxRequestEntity,
        attempt: PairingAttemptEntity,
    ) {
        check(request.id == attempt.requestId)
        check(request.clientId == attempt.clientId)
        check(request.exchangeEndedAt != null)
        check(attempt.state == "sas_verification_pending")
        check(attempt.hasCompletePendingPsk())
        check(canAdvanceRequest(request.id, request.deviceIdentityId))
        check(updateRequest(request) == 1)
        check(updatePairingAttempt(attempt) == 1)
    }

    @Transaction
    suspend fun updatePairingRequest(
        request: InboxRequestEntity,
        attempt: PairingAttemptEntity,
    ) {
        check(canAdvanceRequest(request.id, request.deviceIdentityId))
        check(updateRequest(request) == 1)
        check(updatePairingAttempt(attempt) == 1)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun updateSecretUseRequest(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
    ) {
        check(canAdvanceRequest(request.id, request.deviceIdentityId))
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
    ): ConditionalRequestUpdate {
        if (!canAdvanceRequest(request.id, request.deviceIdentityId)) {
            return ConditionalRequestUpdate.UNAVAILABLE
        }
        if (!authorizationMatches(authorization, clientId, operation, now)) {
            val currentRequest = getRequestById(request.id)
                ?: return ConditionalRequestUpdate.UNAVAILABLE
            val currentSecretUseRequest = getSecretUseRequest(request.id)
                ?: return ConditionalRequestUpdate.UNAVAILABLE
            check(
                updateRequest(
                    currentRequest.copy(
                        state = "action_required",
                        responseJson = null,
                        completedAt = null,
                    ),
                ) == 1,
            )
            check(
                updateSecretUseRequestRow(
                    currentSecretUseRequest.copy(
                        decision = null,
                        decisionSource = null,
                        completionResult = null,
                        completionReason = null,
                        completionMessage = null,
                        decidedAt = null,
                    ),
                ) == 1,
            )
            trimCompletedHistory()
            return ConditionalRequestUpdate.ACTION_REQUIRED
        }
        check(updateRequest(request) == 1)
        check(updateSecretUseRequestRow(secretUseRequest) == 1)
        trimCompletedHistory()
        return ConditionalRequestUpdate.APPLIED
    }

    @Transaction
    suspend fun updateGitSignRequest(
        request: InboxRequestEntity,
        gitSignRequest: GitSignRequestEntity,
    ) {
        check(canAdvanceRequest(request.id, request.deviceIdentityId))
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
        expectedState: String,
        now: Long,
    ): ConditionalRequestUpdate {
        if (!canAdvanceRequest(request.id, request.deviceIdentityId)) {
            return ConditionalRequestUpdate.UNAVAILABLE
        }
        val currentRequest = getRequestById(request.id)
            ?: return ConditionalRequestUpdate.UNAVAILABLE
        val currentGitSignRequest = getGitSignRequest(request.id)
            ?: return ConditionalRequestUpdate.UNAVAILABLE
        if (
            currentRequest.state != expectedState ||
            currentGitSignRequest.decision != null
        ) {
            return ConditionalRequestUpdate.UNAVAILABLE
        }
        if (!authorizationMatches(authorization, clientId, operation, now)) {
            check(
                updateRequest(
                    currentRequest.copy(
                        state = "action_required",
                        responseJson = null,
                        completedAt = null,
                    ),
                ) == 1,
            )
            check(
                updateGitSignRequestRow(
                    currentGitSignRequest.copy(
                        decision = null,
                        completionResult = null,
                        completionReason = null,
                        completionMessage = null,
                        decidedAt = null,
                    ),
                ) == 1,
            )
            trimCompletedHistory()
            return ConditionalRequestUpdate.ACTION_REQUIRED
        }
        check(updateRequest(request) == 1)
        check(updateGitSignRequestRow(gitSignRequest) == 1)
        trimCompletedHistory()
        return ConditionalRequestUpdate.APPLIED
    }


    @Transaction
    suspend fun updateSshAuthenticationRequest(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
    ) {
        check(canAdvanceRequest(request.id, request.deviceIdentityId))
        check(updateRequest(request) == 1)
        check(updateSshAuthenticationRequestRow(authentication) == 1)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun updateSshAuthenticationRequestIfAuthorized(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
        authorization: AuthorizationCommitment,
        clientId: String,
        operation: String,
        expectedState: String,
        now: Long,
    ): ConditionalRequestUpdate {
        if (!canAdvanceRequest(request.id, request.deviceIdentityId)) {
            return ConditionalRequestUpdate.UNAVAILABLE
        }
        val currentRequest = getRequestById(request.id)
            ?: return ConditionalRequestUpdate.UNAVAILABLE
        val currentAuthentication = getSshAuthenticationRequest(request.id)
            ?: return ConditionalRequestUpdate.UNAVAILABLE
        if (
            currentRequest.state != expectedState ||
            currentAuthentication.decision != null
        ) {
            return ConditionalRequestUpdate.UNAVAILABLE
        }
        if (!authorizationMatches(authorization, clientId, operation, now)) {
            check(
                updateRequest(
                    currentRequest.copy(
                        state = "action_required",
                        responseJson = null,
                        completedAt = null,
                    ),
                ) == 1,
            )
            check(
                updateSshAuthenticationRequestRow(
                    currentAuthentication.copy(
                        decision = null,
                        completionResult = null,
                        completionReason = null,
                        completionMessage = null,
                        decidedAt = null,
                    ),
                ) == 1,
            )
            trimCompletedHistory()
            return ConditionalRequestUpdate.ACTION_REQUIRED
        }
        check(updateRequest(request) == 1)
        check(updateSshAuthenticationRequestRow(authentication) == 1)
        trimCompletedHistory()
        return ConditionalRequestUpdate.APPLIED
    }

    @Transaction
    suspend fun authorizationMatches(
        authorization: AuthorizationCommitment,
        clientId: String,
        operation: String,
        now: Long,
    ): Boolean {
        val client = getClient(clientId) ?: return false
        if (
            client.relayClientState != "active" ||
            client.desiredRelayClientState?.let { it != "active" } == true
        ) {
            return false
        }
        authorization.instructions?.let { expected ->
            if (
                getAuthorizationDeviceInstructions(expected.deviceIdentityId) !=
                expected.deviceInstructions ||
                client.deviceIdentityId != expected.deviceIdentityId ||
                expected.clientId != clientId ||
                client.name != expected.clientName ||
                client.instructions != expected.clientInstructions
            ) {
                return false
            }
        }
        for (name in authorization.expectedAbsentSecretNames) {
            if (authorizationSecretExists(name)) return false
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
    suspend fun updateSecretListRequest(request: InboxRequestEntity) {
        check(canAdvanceRequest(request.id, request.deviceIdentityId))
        check(updateRequest(request) == 1)
        trimCompletedHistory()
    }

    @Transaction
    suspend fun updateSecretUploadRequest(
        request: InboxRequestEntity,
        secretUpload: SecretUploadRequestEntity,
        discardUploadedValues: Boolean = false,
    ) {
        check(canAdvanceRequest(request.id, request.deviceIdentityId))
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
        attempt: PairingAttemptEntity,
        client: ClientEntity?,
        clientPsk: ClientPskEntity?,
        finishRequest: InboxRequestEntity,
        requestPsk: RequestPskEntity,
    ) {
        check(rootRequest.id == attempt.requestId)
        check(rootRequest.clientId == attempt.clientId)
        check(attempt.state == "completed")
        check(attempt.desiredRelayClientState == null)
        check(attempt.hasNoPendingPsk())
        check((client == null) == (clientPsk == null))
        if (client == null) {
            check(attempt.relayClientState == "revoked")
        } else {
            val promotedPsk = checkNotNull(clientPsk)
            check(client.clientId == attempt.clientId)
            check(client.deviceIdentityId == rootRequest.deviceIdentityId)
            check(promotedPsk.clientId == client.clientId)
            check(promotedPsk.slot == "current")
        }
        check(finishRequest.clientId == attempt.clientId)
        check(finishRequest.deviceIdentityId == rootRequest.deviceIdentityId)
        check(requestPsk.requestId == finishRequest.id)
        check(canAdvanceRequest(rootRequest.id, rootRequest.deviceIdentityId))
        check(isActiveIdentity(finishRequest.deviceIdentityId))
        check(updateRequest(rootRequest) == 1)
        check(updatePairingAttempt(attempt) == 1)
        if (client != null && clientPsk != null) {
            insertClient(client)
            insertClientPsk(clientPsk)
        }
        insertRequest(finishRequest)
        insertRequestPsk(requestPsk)
        trimCompletedHistory()
    }
}
