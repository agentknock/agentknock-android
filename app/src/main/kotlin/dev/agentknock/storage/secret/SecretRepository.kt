package dev.agentknock.storage.secret

import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.audit.AuditCategory
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.NoOpAuditSink
import dev.agentknock.protocol.SecretUploadMode
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal const val ENVIRONMENT_SECRET_TYPE = "environment"
internal const val SSH_SECRET_TYPE = "ssh"
internal const val SSH_PRIVATE_KEY_FORMAT = "ed25519_seed"

internal enum class SecretApprovalMode(
    val storedName: String,
    val precedence: Int,
) {
    DENY("deny", 0),
    ASK_ME("ask_me", 1),
    TEMPORARY("temporary", 1),
    ASK_AI("ask_ai", 2),
    APPROVE("approve", 3),
}

internal enum class TemporaryAccessOperation(val storedName: String) {
    INVOCATION("invocation"),
    GIT_SIGN("git_sign"),
}

internal fun String.toTemporaryAccessOperation(): TemporaryAccessOperation =
    checkNotNull(TemporaryAccessOperation.entries.find { it.storedName == this }) {
        "Unknown temporary access operation"
    }

internal fun String.toSecretApprovalMode(): SecretApprovalMode =
    checkNotNull(SecretApprovalMode.entries.find { it.storedName == this }) {
        "Unknown secret approval mode"
    }

internal data class SecretClientApprovalOverride(
    val clientId: String,
    val mode: SecretApprovalMode,
)

internal data class TemporaryAccessGrant(
    val secretId: String,
    val secretName: String,
    val clientId: String,
    val operation: TemporaryAccessOperation,
    val expiresAt: Long,
)

internal data class SecretApprovalPolicy(
    val secretId: String,
    val secretName: String,
    val mode: SecretApprovalMode,
    val defaultMode: SecretApprovalMode,
    val overridden: Boolean,
    val instructions: String,
    val revision: Long,
    val temporaryAccessExpiresAt: Long? = null,
)

internal data class SecretSummary(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val environmentVariableCount: Int,
    val sshKey: SshKeyMetadata?,
    val createdAt: Long,
    val updatedAt: Long,
    val temporaryAccessCount: Int = 0,
)

internal data class SecretDetails(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val environmentVariables: List<EnvironmentVariableMetadata>,
    val sshKey: SshKeyMetadata?,
    val approvalMode: SecretApprovalMode,
    val instructions: String,
    val clientApprovalOverrides: List<SecretClientApprovalOverride>,
    val temporaryAccessGrants: List<TemporaryAccessGrant>,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class SshKeyMetadata(
    val algorithm: String,
    val publicKey: String,
    val fingerprint: String,
    val comment: String,
    val privateKeyAvailable: Boolean,
    val materialUpdatedAt: Long,
)

internal data class EnvironmentVariableMetadata(
    val id: String,
    val secretId: String,
    val name: String,
    val sensitive: Boolean,
    val notes: String,
    val valueAvailable: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val valueUpdatedAt: Long,
)

internal sealed interface EnvironmentVariableValue {
    data class Available(val value: String) : EnvironmentVariableValue

    data object Unavailable : EnvironmentVariableValue

    data object Corrupted : EnvironmentVariableValue

    data object UnsupportedFormat : EnvironmentVariableValue

    data object NotFound : EnvironmentVariableValue
}

internal sealed interface CreateSecretResult {
    data class Created(val id: String) : CreateSecretResult

    data object NameInUse : CreateSecretResult
}

internal sealed interface SaveSshSecretResult {
    data class Saved(val id: String) : SaveSshSecretResult

    data object NameInUse : SaveSshSecretResult

    data object NotFound : SaveSshSecretResult

    data object WrongType : SaveSshSecretResult
}

internal enum class SaveSecretResult {
    SAVED,
    NAME_IN_USE,
    NOT_FOUND,
}

internal sealed interface CreateEnvironmentVariableResult {
    data class Created(val id: String) : CreateEnvironmentVariableResult

    data object NameInUse : CreateEnvironmentVariableResult

    data object SecretNotFound : CreateEnvironmentVariableResult
}

internal enum class SaveEnvironmentVariableResult {
    SAVED,
    NAME_IN_USE,
    NOT_FOUND,
    VALUE_UNAVAILABLE,
    VALUE_CORRUPTED,
    UNSUPPORTED_FORMAT,
}

internal data class EnvironmentSecretUpload(
    val mode: SecretUploadMode,
    val name: String,
    val descriptionProvided: Boolean,
    val description: String?,
    val variables: Map<String, String>,
    val variableSensitivity: Map<String, Boolean> = emptyMap(),
)

internal data class SshSecretUpload(
    val mode: SecretUploadMode,
    val name: String,
    val descriptionProvided: Boolean,
    val description: String?,
    val privateKey: SshPrivateKey?,
)

internal data class EnvironmentSecretUploadSummary(
    val existingSecretId: String?,
    val addedVariables: List<String>,
    val changedVariables: List<String>,
    val unchangedVariables: List<String>,
    val removedVariables: List<String>,
    val variableSensitivity: Map<String, Boolean>,
)

internal sealed interface EnvironmentSecretUploadResult {
    data class Valid(val summary: EnvironmentSecretUploadSummary) :
        EnvironmentSecretUploadResult
    data class Invalid(val message: String) : EnvironmentSecretUploadResult
}

internal sealed interface ApplyEnvironmentSecretUploadResult {
    data class Applied(val secretId: String) : ApplyEnvironmentSecretUploadResult
    data class Invalid(val message: String) : ApplyEnvironmentSecretUploadResult
}

internal data class SshSecretUploadSummary(
    val existingSecretId: String?,
    val publicKey: String?,
    val fingerprint: String?,
    val previousPublicKey: String?,
    val previousFingerprint: String?,
    val keyChanged: Boolean,
)

internal sealed interface SshSecretUploadResult {
    data class Valid(val summary: SshSecretUploadSummary) : SshSecretUploadResult

    data class Invalid(val message: String) : SshSecretUploadResult
}

internal sealed interface ApplySshSecretUploadResult {
    data class Applied(val secretId: String) : ApplySshSecretUploadResult

    data class Invalid(val message: String) : ApplySshSecretUploadResult
}

@Serializable
internal data class SecretMetadata(
    val name: String,
    val description: String,
    val type: String = ENVIRONMENT_SECRET_TYPE,
    val environmentVariableNames: List<String> = emptyList(),
    val sshPublicKey: String? = null,
)

internal data class RequestedSecretDescription(
    val secrets: List<SecretMetadata>,
    val reviewMetadata: List<SecretReviewMetadata>,
    val missingSecrets: List<String>,
    val containsSensitiveMaterial: Boolean,
)

internal data class SecretReviewMetadata(
    val id: String,
    val revision: Long = 1,
    val name: String,
    val description: String,
    val type: String,
    val instructions: String,
    val environmentVariables: List<EnvironmentVariableReviewMetadata>,
    val sshKey: SshKeyReviewMetadata?,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class EnvironmentVariableReviewMetadata(
    val name: String,
    val sensitive: Boolean,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val valueUpdatedAt: Long,
)

internal data class SshKeyReviewMetadata(
    val algorithm: String,
    val publicKey: String,
    val fingerprint: String,
    val comment: String,
    val materialUpdatedAt: Long,
)

internal data class SecretIdentity(
    val id: String,
    val name: String,
)

internal sealed interface SecretValues {
    val description: String

    data class Environment(
        override val description: String,
        val environment: Map<String, String>,
    ) : SecretValues

    data class Ssh(
        override val description: String,
        val publicKey: String,
    ) : SecretValues
}

internal sealed interface RequestedSecretsResult {
    data class Available(val secrets: Map<String, SecretValues>) : RequestedSecretsResult

    data class MissingSecrets(val names: List<String>) : RequestedSecretsResult

    data class ConflictingVariable(val name: String) : RequestedSecretsResult

    data object MultipleSshKeys : RequestedSecretsResult

    data object UnsupportedSecretType : RequestedSecretsResult

    data object SecretUnavailable : RequestedSecretsResult

    data object SecretCorrupted : RequestedSecretsResult

    data object UnsupportedEncryption : RequestedSecretsResult
}

internal sealed interface GitSignatureResult {
    data class Signed(val signature: String) : GitSignatureResult

    data object NotFound : GitSignatureResult

    data object WrongType : GitSignatureResult

    data object KeyChanged : GitSignatureResult

    data object SecretUnavailable : GitSignatureResult

    data object SecretCorrupted : GitSignatureResult

    data object UnsupportedEncryption : GitSignatureResult
}

internal interface RequestedSecretSource {
    suspend fun listSecretsForClient(): List<SecretMetadata>

    suspend fun describeRequestedSecrets(names: List<String>): RequestedSecretDescription

    suspend fun requestedSecrets(names: List<String>): RequestedSecretsResult
}

internal class SecretRepository(
    private val dao: SecretDao,
    private val keyManager: VaultKeyManager,
    private val encryption: AesGcmEncryption,
    private val audit: AuditSink = NoOpAuditSink,
    private val sshKeys: SshKeyCodec = SshKeyCodec(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RequestedSecretSource {
    fun observeSecrets(): Flow<List<SecretSummary>> = combine(
        dao.observeSecrets(),
        observeTemporaryAccessGrants(),
    ) { rows, grants ->
        val grantCounts = grants.groupingBy(TemporaryAccessGrant::secretId).eachCount()
        rows.map { row ->
            SecretSummary(
                id = row.id,
                name = row.name,
                description = row.description,
                type = row.type,
                environmentVariableCount = row.environmentVariableCount,
                sshKey = row.sshAlgorithm?.let { algorithm ->
                    val publicKey = checkNotNull(row.sshPublicKey)
                    val comment = checkNotNull(row.sshComment)
                    val rendered = sshKeys.publicKey(algorithm, publicKey, comment)
                    SshKeyMetadata(
                        algorithm = rendered.algorithm.storedName,
                        publicKey = rendered.line,
                        fingerprint = rendered.fingerprint,
                        comment = rendered.comment,
                        privateKeyAvailable = if (row.sshEncryptionKeyId == null) {
                            false
                        } else {
                            keyManager.keyAvailable(row.sshEncryptionKeyId)
                        },
                        materialUpdatedAt = checkNotNull(row.sshMaterialUpdatedAt),
                    )
                },
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                temporaryAccessCount = grantCounts[row.id] ?: 0,
            )
        }
    }

    fun observeSecret(id: String): Flow<SecretDetails?> = combine(
        dao.observeSecret(id),
        dao.observeEnvironmentVariables(id),
        dao.observeSshKey(id),
        dao.observeClientApprovalOverrides(id),
        observeTemporaryAccessGrants(),
    ) { secret, variables, sshKey, overrides, grants ->
        secret?.let {
            val availability = variables
                .map(EnvironmentVariableMetadataRow::encryptionKeyId)
                .distinct()
                .associateWith { keyId -> keyManager.keyAvailable(keyId) }
            SecretDetails(
                id = secret.id,
                name = secret.name,
                description = secret.description,
                type = secret.type,
                environmentVariables = variables.map { variable ->
                    EnvironmentVariableMetadata(
                        id = variable.id,
                        secretId = variable.secretId,
                        name = variable.name,
                        sensitive = variable.sensitive,
                        notes = variable.notes,
                        valueAvailable = availability.getValue(variable.encryptionKeyId),
                        createdAt = variable.createdAt,
                        updatedAt = variable.updatedAt,
                        valueUpdatedAt = variable.valueUpdatedAt,
                    )
                },
                sshKey = sshKey?.let { key ->
                    val rendered = sshKeys.publicKey(key.algorithm, key.publicKey, key.comment)
                    SshKeyMetadata(
                        algorithm = rendered.algorithm.storedName,
                        publicKey = rendered.line,
                        fingerprint = rendered.fingerprint,
                        comment = rendered.comment,
                        privateKeyAvailable = keyManager.keyAvailable(key.encryptionKeyId),
                        materialUpdatedAt = key.materialUpdatedAt,
                    )
                },
                approvalMode = secret.approvalMode.toSecretApprovalMode(),
                instructions = secret.instructions,
                clientApprovalOverrides = overrides.map { override ->
                    SecretClientApprovalOverride(
                        clientId = override.clientId,
                        mode = override.approvalMode.toSecretApprovalMode(),
                    )
                },
                temporaryAccessGrants = grants.filter { it.secretId == secret.id },
                createdAt = secret.createdAt,
                updatedAt = secret.updatedAt,
            )
        }
    }

    fun observeTemporaryAccessGrants(): Flow<List<TemporaryAccessGrant>> = combine(
        dao.observeTemporaryAccessGrants(),
        currentTimeFlow(),
    ) { rows, now ->
        rows.filter { it.expiresAt > now }.map { row ->
            TemporaryAccessGrant(
                secretId = row.secretId,
                secretName = row.secretName,
                clientId = row.clientId,
                operation = row.operation.toTemporaryAccessOperation(),
                expiresAt = row.expiresAt,
            )
        }
    }

    suspend fun identitiesForNames(names: List<String>): List<SecretIdentity> {
        val byName = dao.getSecretsByName(names.distinct()).associateBy(SecretEntity::name)
        return names.distinct().mapNotNull { name ->
            byName[name]?.let { secret -> SecretIdentity(secret.id, secret.name) }
        }
    }

    suspend fun approvalPoliciesForNames(
        names: List<String>,
        clientId: String,
        operation: TemporaryAccessOperation,
    ): List<SecretApprovalPolicy> {
        val requestedNames = names.distinct()
        val secrets = dao.getSecretsByName(requestedNames)
        val overrides = dao.getClientApprovalOverrides(
            clientId = clientId,
            secretIds = secrets.map(SecretEntity::id),
        ).associateBy(SecretClientApprovalOverrideEntity::secretId)
        val now = currentTimeMillis()
        val grants = if (secrets.isEmpty()) {
            emptyMap()
        } else {
            dao.getActiveTemporaryAccessGrants(
                clientId = clientId,
                secretIds = secrets.map(SecretEntity::id),
                operation = operation.storedName,
                now = now,
            ).associateBy(TemporaryAccessGrantEntity::secretId)
        }
        val byName = secrets.associateBy(SecretEntity::name)
        return requestedNames.mapNotNull { name ->
            byName[name]?.let { secret ->
                val defaultMode = secret.approvalMode.toSecretApprovalMode()
                val override = overrides[secret.id]
                SecretApprovalPolicy(
                    secretId = secret.id,
                    secretName = secret.name,
                    mode = override?.approvalMode?.toSecretApprovalMode() ?: defaultMode,
                    defaultMode = defaultMode,
                    overridden = override != null,
                    instructions = secret.instructions,
                    revision = secret.revision,
                    temporaryAccessExpiresAt = grants[secret.id]?.expiresAt,
                )
            }
        }
    }

    suspend fun saveApprovalMode(id: String, mode: SecretApprovalMode): SaveSecretResult {
        val secret = dao.getSecret(id) ?: return SaveSecretResult.NOT_FOUND
        if (secret.approvalMode == mode.storedName) return SaveSecretResult.SAVED
        if (!dao.setSecretApprovalMode(id, mode.storedName, currentTimeMillis())) {
            return SaveSecretResult.NOT_FOUND
        }
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "Secret approval mode changed",
                detail = secret.name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return SaveSecretResult.SAVED
    }

    suspend fun saveInstructions(id: String, instructions: String): SaveSecretResult {
        val secret = dao.getSecret(id) ?: return SaveSecretResult.NOT_FOUND
        val normalized = instructions.trim()
        if (secret.instructions == normalized) return SaveSecretResult.SAVED
        if (dao.updateSecretInstructions(id, normalized, currentTimeMillis()) != 1) {
            return SaveSecretResult.NOT_FOUND
        }
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "Secret instructions changed",
                detail = secret.name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return SaveSecretResult.SAVED
    }

    suspend fun setClientApprovalOverride(
        secretId: String,
        clientId: String,
        mode: SecretApprovalMode?,
    ): SaveSecretResult {
        val secret = dao.getSecret(secretId) ?: return SaveSecretResult.NOT_FOUND
        if (mode == null) {
            dao.deleteClientApprovalOverrideAndTemporaryAccess(secretId, clientId)
        } else {
            dao.upsertClientApprovalOverrideAndDeleteTemporaryAccess(
                SecretClientApprovalOverrideEntity(
                    secretId = secretId,
                    clientId = clientId,
                    approvalMode = mode.storedName,
                ),
            )
        }
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "Client approval override changed",
                detail = secret.name,
                outcome = AuditOutcome.CHANGED,
                clientId = clientId,
            ),
        )
        return SaveSecretResult.SAVED
    }

    suspend fun allowTemporaryAccess(
        policies: List<SecretApprovalPolicy>,
        clientId: String,
        operation: TemporaryAccessOperation,
        expiresAt: Long,
    ): Boolean {
        val now = currentTimeMillis()
        require(expiresAt > now) { "Temporary access must expire in the future" }
        val distinctPolicies = policies.distinctBy(SecretApprovalPolicy::secretId)
        if (distinctPolicies.isEmpty()) return false
        if (distinctPolicies.any { it.temporaryAccessExpiresAt != null }) return false
        require(distinctPolicies.all { policy ->
            policy.mode == SecretApprovalMode.TEMPORARY || policy.mode == SecretApprovalMode.ASK_AI
        }) { "Temporary access is not enabled for this client and secret" }
        val inserted = dao.upsertTemporaryAccessGrantsIfCurrent(
            grants = distinctPolicies.map { policy ->
                TemporaryAccessGrantEntity(
                    secretId = policy.secretId,
                    clientId = clientId,
                    operation = operation.storedName,
                    expiresAt = expiresAt,
                )
            },
            expectedRevisions = distinctPolicies.associate { it.secretId to it.revision },
            expectedApprovalModes = distinctPolicies.associate {
                it.secretId to it.mode.storedName
            },
            now = now,
        )
        if (!inserted) return false
        distinctPolicies.forEach { policy ->
            runCatching {
                audit.record(
                    AuditRecord(
                        category = AuditCategory.SECRET_USE,
                        title = "Temporary access allowed",
                        detail = "${policy.secretName} · ${operation.auditName()} · expires " +
                            Instant.ofEpochMilli(expiresAt),
                        outcome = AuditOutcome.APPROVED,
                        clientId = clientId,
                    ),
                )
            }
        }
        return true
    }

    suspend fun endTemporaryAccess(
        secretId: String,
        clientId: String,
        operation: TemporaryAccessOperation,
    ): Boolean {
        val secret = dao.getSecret(secretId)
        val deleted = dao.deleteTemporaryAccessGrant(secretId, clientId, operation.storedName) > 0
        if (deleted) {
            audit.record(
                AuditRecord(
                    category = AuditCategory.SECRET_USE,
                    title = "Temporary access ended",
                    detail = "${secret?.name.orEmpty()} · ${operation.auditName()}",
                    outcome = AuditOutcome.CHANGED,
                    clientId = clientId,
                ),
            )
        }
        return deleted
    }

    suspend fun clearRestoredTemporaryAccess() {
        dao.deleteAllTemporaryAccessGrants()
    }

    suspend fun generateSshKey(comment: String): SshPrivateKey =
        withContext(cryptographyDispatcher) { sshKeys.generateEd25519(comment.trim()) }

    suspend fun importSshKey(value: String): SshPrivateKey =
        withContext(cryptographyDispatcher) { sshKeys.importOpenSshPrivateKey(value) }

    suspend fun createEnvironmentSecret(name: String, description: String): CreateSecretResult {
        validateSecretName(name)
        if (dao.secretNameInUse(name, excludingId = "")) return CreateSecretResult.NameInUse
        val id = newId()
        val now = currentTimeMillis()
        dao.insertSecret(
            SecretEntity(
                id = id,
                name = name,
                description = description,
                type = ENVIRONMENT_SECRET_TYPE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "Secret created",
                detail = name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return CreateSecretResult.Created(id)
    }

    suspend fun createSshSecret(
        name: String,
        description: String,
        privateKey: SshPrivateKey,
    ): CreateSecretResult {
        validateSecretName(name)
        validateSshPrivateKey(privateKey)
        if (dao.secretNameInUse(name, excludingId = "")) return CreateSecretResult.NameInUse
        val id = newId()
        val now = currentTimeMillis()
        val secret = SecretEntity(
            id = id,
            name = name,
            description = description,
            type = SSH_SECRET_TYPE,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertSshSecret(secret, sshKeyEntity(id, privateKey, now))
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "SSH key created",
                detail = name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return CreateSecretResult.Created(id)
    }

    suspend fun replaceSshKey(id: String, privateKey: SshPrivateKey): SaveSshSecretResult {
        validateSshPrivateKey(privateKey)
        val secret = dao.getSecret(id) ?: return SaveSshSecretResult.NotFound
        if (secret.type != SSH_SECRET_TYPE) return SaveSshSecretResult.WrongType
        dao.getSshKey(id) ?: return SaveSshSecretResult.NotFound
        val now = currentTimeMillis()
        dao.updateSshKey(
            sshKeyEntity(id, privateKey, now).copy(materialUpdatedAt = now),
            secretUpdatedAt = now,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "SSH key replaced",
                detail = secret.name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return SaveSshSecretResult.Saved(id)
    }

    suspend fun saveSshComment(id: String, comment: String): SaveSshSecretResult {
        val trimmed = comment.trim()
        val secret = dao.getSecret(id) ?: return SaveSshSecretResult.NotFound
        if (secret.type != SSH_SECRET_TYPE) return SaveSshSecretResult.WrongType
        val key = dao.getSshKey(id) ?: return SaveSshSecretResult.NotFound
        runCatching { sshKeys.publicKey(key.algorithm, key.publicKey, trimmed) }
            .getOrElse { throw IllegalArgumentException(it.message, it) }
        if (key.comment == trimmed) return SaveSshSecretResult.Saved(id)
        val now = currentTimeMillis()
        if (!dao.updateSshKeyComment(id, trimmed, secretUpdatedAt = now)) {
            return SaveSshSecretResult.NotFound
        }
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "SSH public key comment updated",
                detail = secret.name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return SaveSshSecretResult.Saved(id)
    }

    suspend fun saveSecret(id: String, name: String, description: String): SaveSecretResult {
        validateSecretName(name)
        val existing = dao.getSecret(id) ?: return SaveSecretResult.NOT_FOUND
        if (dao.secretNameInUse(name, excludingId = id)) return SaveSecretResult.NAME_IN_USE
        if (!dao.updateSecretMetadata(id, name, description, currentTimeMillis())) {
            return SaveSecretResult.NOT_FOUND
        }
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "Secret updated",
                detail = name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return SaveSecretResult.SAVED
    }

    suspend fun deleteSecret(id: String): Boolean {
        val secret = dao.getSecret(id) ?: return false
        dao.deleteSecret(secret)
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "Secret deleted",
                detail = secret.name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return true
    }

    suspend fun createEnvironmentVariable(
        secretId: String,
        name: String,
        value: String,
        sensitive: Boolean,
        notes: String,
    ): CreateEnvironmentVariableResult {
        validateEnvironmentVariableName(name)
        validateEnvironmentVariableValue(value)
        if (dao.getSecret(secretId) == null) {
            return CreateEnvironmentVariableResult.SecretNotFound
        }
        if (dao.environmentVariableNameInUse(secretId, name, excludingId = "")) {
            return CreateEnvironmentVariableResult.NameInUse
        }

        val id = newId()
        val encrypted = encrypt(id, secretId, name, sensitive, value)
        val now = currentTimeMillis()
        dao.insertEnvironmentVariable(
            EnvironmentVariableEntity(
                id = id,
                secretId = secretId,
                name = name,
                sensitive = sensitive,
                notes = notes,
                encryptionFormat = encrypted.formatVersion,
                encryptionKeyId = encrypted.keyId,
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
                createdAt = now,
                updatedAt = now,
                valueUpdatedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "Environment variable added",
                detail = "$name in ${dao.getSecret(secretId)?.name.orEmpty()}",
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return CreateEnvironmentVariableResult.Created(id)
    }

    suspend fun readEnvironmentVariableValue(id: String): EnvironmentVariableValue {
        val variable = dao.getEnvironmentVariable(id) ?: return EnvironmentVariableValue.NotFound
        return decrypt(variable).toEnvironmentVariableValue()
    }

    suspend fun saveEnvironmentVariable(
        id: String,
        name: String,
        sensitive: Boolean,
        notes: String,
        replacementValue: String?,
    ): SaveEnvironmentVariableResult {
        validateEnvironmentVariableName(name)
        replacementValue?.let(::validateEnvironmentVariableValue)
        val existing = dao.getEnvironmentVariable(id) ?: return SaveEnvironmentVariableResult.NOT_FOUND
        if (dao.environmentVariableNameInUse(existing.secretId, name, excludingId = id)) {
            return SaveEnvironmentVariableResult.NAME_IN_USE
        }

        val authenticatedFieldsChanged = name != existing.name || sensitive != existing.sensitive
        val encrypted = when {
            replacementValue != null -> encrypt(id, existing.secretId, name, sensitive, replacementValue)
            authenticatedFieldsChanged -> when (val decrypted = decrypt(existing)) {
                is DecryptionResult.Plaintext -> encrypt(
                    id,
                    existing.secretId,
                    name,
                    sensitive,
                    decrypted.value.decodeToString(throwOnInvalidSequence = true),
                )
                DecryptionResult.KeyUnavailable ->
                    return SaveEnvironmentVariableResult.VALUE_UNAVAILABLE
                DecryptionResult.AuthenticationFailed ->
                    return SaveEnvironmentVariableResult.VALUE_CORRUPTED
                DecryptionResult.UnsupportedFormat ->
                    return SaveEnvironmentVariableResult.UNSUPPORTED_FORMAT
            }
            else -> EncryptedValue(
                formatVersion = existing.encryptionFormat,
                keyId = existing.encryptionKeyId,
                nonce = existing.nonce,
                ciphertext = existing.ciphertext,
            )
        }

        val now = currentTimeMillis()
        val updated = existing.copy(
                name = name,
                sensitive = sensitive,
                notes = notes,
                encryptionFormat = encrypted.formatVersion,
                encryptionKeyId = encrypted.keyId,
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
                updatedAt = now,
                valueUpdatedAt = if (replacementValue == null) existing.valueUpdatedAt else now,
            )
        val rowsUpdated = if (!authenticatedFieldsChanged && replacementValue == null) {
            dao.updateEnvironmentVariableNotes(
                variableId = id,
                secretId = existing.secretId,
                notes = notes,
                updatedAt = now,
            )
        } else {
            dao.updateEnvironmentVariable(updated)
        }
        if (rowsUpdated != 1) return SaveEnvironmentVariableResult.NOT_FOUND
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "Environment variable updated",
                detail = name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return SaveEnvironmentVariableResult.SAVED
    }

    suspend fun deleteEnvironmentVariable(id: String): Boolean {
        val variable = dao.getEnvironmentVariable(id) ?: return false
        dao.deleteEnvironmentVariable(variable, secretUpdatedAt = currentTimeMillis())
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET,
                title = "Environment variable deleted",
                detail = variable.name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return true
    }

    suspend fun describeEnvironmentSecretUpload(
        upload: EnvironmentSecretUpload,
    ): EnvironmentSecretUploadResult {
        val invalid = validateUploadInput(upload)
        if (invalid != null) return EnvironmentSecretUploadResult.Invalid(invalid)
        val existing = dao.getSecretsByName(listOf(upload.name)).singleOrNull()
        when (upload.mode) {
            SecretUploadMode.CREATE -> if (existing != null) {
                return EnvironmentSecretUploadResult.Invalid(
                    "A secret named ${upload.name} already exists.",
                )
            }
            SecretUploadMode.REPLACE,
            SecretUploadMode.UPDATE,
            -> {
                if (existing == null) {
                    return EnvironmentSecretUploadResult.Invalid(
                        "The target secret ${upload.name} does not exist.",
                    )
                }
                if (existing.type != ENVIRONMENT_SECRET_TYPE) {
                    return EnvironmentSecretUploadResult.Invalid(
                        "The target secret has a different type.",
                    )
                }
            }
        }
        val existingVariables = existing?.let {
            dao.getEnvironmentVariablesForSecrets(listOf(it.id)).associateBy { variable ->
                variable.name
            }
        }.orEmpty()
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        upload.variables.toSortedMap().forEach { (name, value) ->
            val current = existingVariables[name]
            if (current == null) {
                added += name
            } else {
                val same = (decrypt(current) as? DecryptionResult.Plaintext)?.value
                    ?.decodeToString(throwOnInvalidSequence = false) == value
                if (same) unchanged += name else changed += name
            }
        }
        if (upload.mode == SecretUploadMode.UPDATE) {
            unchanged += existingVariables.keys.minus(upload.variables.keys).sorted()
        }
        val removed = if (upload.mode == SecretUploadMode.REPLACE) {
            existingVariables.keys.minus(upload.variables.keys).sorted()
        } else {
            emptyList()
        }
        return EnvironmentSecretUploadResult.Valid(
            EnvironmentSecretUploadSummary(
                existingSecretId = existing?.id,
                addedVariables = added,
                changedVariables = changed,
                unchangedVariables = unchanged,
                removedVariables = removed,
                variableSensitivity = upload.variables.keys.associateWith { name ->
                    existingVariables[name]?.sensitive ?: true
                },
            ),
        )
    }

    suspend fun applyEnvironmentSecretUpload(
        upload: EnvironmentSecretUpload,
        approvedName: String,
    ): ApplyEnvironmentSecretUploadResult {
        val validation = describeEnvironmentSecretUpload(upload)
        if (validation is EnvironmentSecretUploadResult.Invalid) {
            return ApplyEnvironmentSecretUploadResult.Invalid(validation.message)
        }
        validateSecretName(approvedName)
        val summary = (validation as EnvironmentSecretUploadResult.Valid).summary
        val existing = summary.existingSecretId?.let { id -> dao.getSecret(id) }
        if (
            upload.mode == SecretUploadMode.CREATE &&
            dao.secretNameInUse(approvedName, excludingId = "")
        ) {
            return ApplyEnvironmentSecretUploadResult.Invalid(
                "A secret named $approvedName already exists.",
            )
        }
        val now = currentTimeMillis()
        val secretId = existing?.id ?: newId()
        val secret = SecretEntity(
            id = secretId,
            name = if (upload.mode == SecretUploadMode.CREATE) approvedName else upload.name,
            description = when {
                upload.descriptionProvided -> upload.description.orEmpty()
                upload.mode == SecretUploadMode.UPDATE -> existing?.description.orEmpty()
                else -> ""
            },
            type = ENVIRONMENT_SECRET_TYPE,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            revision = existing?.revision?.plus(1) ?: 1,
            approvalMode = existing?.approvalMode ?: SecretApprovalMode.TEMPORARY.storedName,
            instructions = existing?.instructions.orEmpty(),
        )
        val existingVariables = existing?.let {
            dao.getEnvironmentVariablesForSecrets(listOf(it.id)).associateBy { variable ->
                variable.name
            }
        }.orEmpty()
        val variables = upload.variables.map { (name, value) ->
            val current = existingVariables[name]
            val id = current?.id ?: newId()
            val sensitive = upload.variableSensitivity[name] ?: current?.sensitive ?: true
            val encrypted = encrypt(id, secretId, name, sensitive, value)
            EnvironmentVariableEntity(
                id = id,
                secretId = secretId,
                name = name,
                sensitive = sensitive,
                notes = current?.notes.orEmpty(),
                encryptionFormat = encrypted.formatVersion,
                encryptionKeyId = encrypted.keyId,
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
                createdAt = current?.createdAt ?: now,
                updatedAt = now,
                valueUpdatedAt = now,
            )
        }
        dao.applyEnvironmentSecret(
            secret = secret,
            variables = variables,
            replaceVariables = upload.mode != SecretUploadMode.UPDATE,
        )
        return ApplyEnvironmentSecretUploadResult.Applied(secretId)
    }

    suspend fun describeSshSecretUpload(upload: SshSecretUpload): SshSecretUploadResult {
        val invalid = runCatching {
            validateSecretName(upload.name)
            upload.privateKey?.let(::validateSshPrivateKey)
            require(
                upload.mode == SecretUploadMode.UPDATE || upload.privateKey != null,
            ) { "An SSH key is required for this upload mode." }
            require(upload.descriptionProvided || upload.privateKey != null) {
                "The SSH key update contains no changes."
            }
        }.exceptionOrNull()?.message
        if (invalid != null) return SshSecretUploadResult.Invalid(invalid)

        val existing = dao.getSecretsByName(listOf(upload.name)).singleOrNull()
        when (upload.mode) {
            SecretUploadMode.CREATE -> if (existing != null) {
                return SshSecretUploadResult.Invalid(
                    "A secret named ${upload.name} already exists.",
                )
            }
            SecretUploadMode.REPLACE,
            SecretUploadMode.UPDATE,
            -> {
                if (existing == null) {
                    return SshSecretUploadResult.Invalid(
                        "The target secret ${upload.name} does not exist.",
                    )
                }
                if (existing.type != SSH_SECRET_TYPE) {
                    return SshSecretUploadResult.Invalid(
                        "The target secret has a different type.",
                    )
                }
            }
        }
        val existingKey = existing?.let { dao.getSshKey(it.id) }
        if (existing != null && existingKey == null) {
            return SshSecretUploadResult.Invalid("The target SSH key is incomplete.")
        }
        val proposedPublic = upload.privateKey?.let(::publicKey)
        val previousPublic = existingKey?.let(::publicKey)
        return SshSecretUploadResult.Valid(
            SshSecretUploadSummary(
                existingSecretId = existing?.id,
                publicKey = proposedPublic?.line,
                fingerprint = proposedPublic?.fingerprint,
                previousPublicKey = previousPublic?.line,
                previousFingerprint = previousPublic?.fingerprint,
                keyChanged = proposedPublic != null && (
                    previousPublic == null ||
                        proposedPublic.algorithm != previousPublic.algorithm ||
                        !proposedPublic.publicKey.contentEquals(previousPublic.publicKey)
                    ),
            ),
        )
    }

    suspend fun applySshSecretUpload(
        upload: SshSecretUpload,
        approvedName: String,
    ): ApplySshSecretUploadResult {
        val validation = describeSshSecretUpload(upload)
        if (validation is SshSecretUploadResult.Invalid) {
            return ApplySshSecretUploadResult.Invalid(validation.message)
        }
        validateSecretName(approvedName)
        val summary = (validation as SshSecretUploadResult.Valid).summary
        val existing = summary.existingSecretId?.let { dao.getSecret(it) }
        if (
            upload.mode == SecretUploadMode.CREATE &&
            dao.secretNameInUse(approvedName, excludingId = "")
        ) {
            return ApplySshSecretUploadResult.Invalid(
                "A secret named $approvedName already exists.",
            )
        }
        val now = currentTimeMillis()
        val secretId = existing?.id ?: newId()
        val secret = SecretEntity(
            id = secretId,
            name = if (upload.mode == SecretUploadMode.CREATE) approvedName else upload.name,
            description = when {
                upload.descriptionProvided -> upload.description.orEmpty()
                upload.mode == SecretUploadMode.UPDATE -> existing?.description.orEmpty()
                else -> ""
            },
            type = SSH_SECRET_TYPE,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            revision = existing?.revision?.plus(1) ?: 1,
            approvalMode = existing?.approvalMode ?: SecretApprovalMode.TEMPORARY.storedName,
            instructions = existing?.instructions.orEmpty(),
        )
        val currentKey = existing?.let { dao.getSshKey(it.id) }
        val key = upload.privateKey?.let {
            sshKeyEntity(
                secretId,
                it,
                if (summary.keyChanged) now else currentKey?.materialUpdatedAt ?: now,
            )
        } ?: currentKey
            ?: return ApplySshSecretUploadResult.Invalid("The SSH key is missing.")
        dao.applySshSecret(secret, key)
        return ApplySshSecretUploadResult.Applied(secretId)
    }

    override suspend fun listSecretsForClient(): List<SecretMetadata> {
        val variablesBySecret = dao.getEnvironmentVariables()
            .groupBy(EnvironmentVariableEntity::secretId)
        val secrets = dao.getSecrets()
        val sshKeysBySecret = dao.getSshKeysForSecrets(secrets.map(SecretEntity::id))
            .associateBy(SshKeyEntity::secretId)
        return secrets.mapNotNull { secret ->
            when (secret.type) {
                ENVIRONMENT_SECRET_TYPE -> SecretMetadata(
                    name = secret.name,
                    description = secret.description,
                    type = ENVIRONMENT_SECRET_TYPE,
                    environmentVariableNames = variablesBySecret[secret.id]
                        .orEmpty()
                        .map(EnvironmentVariableEntity::name),
                )
                SSH_SECRET_TYPE -> sshKeysBySecret[secret.id]?.let { key ->
                    SecretMetadata(
                        name = secret.name,
                        description = secret.description,
                        type = SSH_SECRET_TYPE,
                        sshPublicKey = publicKey(key).line,
                    )
                }
                else -> null
            }
        }
    }

    override suspend fun describeRequestedSecrets(
        names: List<String>,
    ): RequestedSecretDescription {
        val requestedNames = names.distinct()
        val secrets = if (requestedNames.isEmpty()) {
            emptyList()
        } else {
            dao.getSecretsByName(requestedNames)
        }
        val secretByName = secrets.associateBy(SecretEntity::name)
        val variables = if (secrets.isEmpty()) {
            emptyList()
        } else {
            dao.getEnvironmentVariablesForSecrets(secrets.map(SecretEntity::id))
        }
        val variablesBySecret = variables.groupBy(EnvironmentVariableEntity::secretId)
        val sshKeysBySecret = if (secrets.isEmpty()) {
            emptyMap()
        } else {
            dao.getSshKeysForSecrets(secrets.map(SecretEntity::id))
                .associateBy(SshKeyEntity::secretId)
        }
        return RequestedSecretDescription(
            secrets = requestedNames.mapNotNull { name ->
                secretByName[name]?.let { secret ->
                    when (secret.type) {
                        ENVIRONMENT_SECRET_TYPE -> SecretMetadata(
                            name = secret.name,
                            description = secret.description,
                            type = ENVIRONMENT_SECRET_TYPE,
                            environmentVariableNames = variablesBySecret[secret.id]
                                .orEmpty()
                                .map(EnvironmentVariableEntity::name)
                                .sorted(),
                        )
                        SSH_SECRET_TYPE -> sshKeysBySecret[secret.id]?.let { key ->
                            SecretMetadata(
                                name = secret.name,
                                description = secret.description,
                                type = SSH_SECRET_TYPE,
                                sshPublicKey = publicKey(key).line,
                            )
                        }
                        else -> null
                    }
                }
            },
            reviewMetadata = requestedNames.mapNotNull { name ->
                secretByName[name]?.let { secret ->
                    when (secret.type) {
                        ENVIRONMENT_SECRET_TYPE -> SecretReviewMetadata(
                            id = secret.id,
                            revision = secret.revision,
                            name = secret.name,
                            description = secret.description,
                            type = ENVIRONMENT_SECRET_TYPE,
                            instructions = secret.instructions,
                            environmentVariables = variablesBySecret[secret.id]
                                .orEmpty()
                                .sortedBy(EnvironmentVariableEntity::name)
                                .map { variable ->
                                    EnvironmentVariableReviewMetadata(
                                        name = variable.name,
                                        sensitive = variable.sensitive,
                                        notes = variable.notes,
                                        createdAt = variable.createdAt,
                                        updatedAt = variable.updatedAt,
                                        valueUpdatedAt = variable.valueUpdatedAt,
                                    )
                                },
                            sshKey = null,
                            createdAt = secret.createdAt,
                            updatedAt = secret.updatedAt,
                        )
                        SSH_SECRET_TYPE -> sshKeysBySecret[secret.id]?.let { key ->
                            val publicKey = publicKey(key)
                            SecretReviewMetadata(
                                id = secret.id,
                                revision = secret.revision,
                                name = secret.name,
                                description = secret.description,
                                type = SSH_SECRET_TYPE,
                                instructions = secret.instructions,
                                environmentVariables = emptyList(),
                                sshKey = SshKeyReviewMetadata(
                                    algorithm = publicKey.algorithm.storedName,
                                    publicKey = publicKey.line,
                                    fingerprint = publicKey.fingerprint,
                                    comment = publicKey.comment,
                                    materialUpdatedAt = key.materialUpdatedAt,
                                ),
                                createdAt = secret.createdAt,
                                updatedAt = secret.updatedAt,
                            )
                        }
                        else -> null
                    }
                }
            },
            missingSecrets = requestedNames.filterNot(secretByName::containsKey),
            containsSensitiveMaterial = variables.any(EnvironmentVariableEntity::sensitive),
        )
    }

    override suspend fun requestedSecrets(names: List<String>): RequestedSecretsResult {
        val requestedNames = names.distinct()
        if (requestedNames.isEmpty()) {
            return RequestedSecretsResult.MissingSecrets(emptyList())
        }
        val secrets = dao.getSecretsByName(requestedNames)
        val secretByName = secrets.associateBy(SecretEntity::name)
        val missing = requestedNames.filterNot(secretByName::containsKey)
        if (missing.isNotEmpty()) return RequestedSecretsResult.MissingSecrets(missing)

        if (secrets.count { it.type == SSH_SECRET_TYPE } > 1) {
            return RequestedSecretsResult.MultipleSshKeys
        }
        if (secrets.any { it.type != ENVIRONMENT_SECRET_TYPE && it.type != SSH_SECRET_TYPE }) {
            return RequestedSecretsResult.UnsupportedSecretType
        }

        val variables = dao.getEnvironmentVariablesForSecrets(secrets.map(SecretEntity::id))
        val combinedEnvironment = sortedMapOf<String, String>()
        val secretEnvironments = secrets.associate { secret ->
            secret.id to sortedMapOf<String, String>()
        }
        for (variable in variables) {
            val value = when (val decrypted = decrypt(variable)) {
                is DecryptionResult.Plaintext -> try {
                    decrypted.value.decodeToString(throwOnInvalidSequence = true)
                } catch (_: IllegalArgumentException) {
                    return RequestedSecretsResult.SecretCorrupted
                }
                DecryptionResult.KeyUnavailable -> {
                    return RequestedSecretsResult.SecretUnavailable
                }
                DecryptionResult.AuthenticationFailed -> {
                    return RequestedSecretsResult.SecretCorrupted
                }
                DecryptionResult.UnsupportedFormat -> {
                    return RequestedSecretsResult.UnsupportedEncryption
                }
            }
            secretEnvironments.getValue(variable.secretId)[variable.name] = value
            val previous = combinedEnvironment.putIfAbsent(variable.name, value)
            if (previous != null && previous != value) {
                return RequestedSecretsResult.ConflictingVariable(variable.name)
            }
        }
        val sshKeysBySecret = dao.getSshKeysForSecrets(
            secrets.filter { it.type == SSH_SECRET_TYPE }.map(SecretEntity::id),
        ).associateBy(SshKeyEntity::secretId)
        val values = linkedMapOf<String, SecretValues>()
        for (name in requestedNames) {
            val secret = secretByName.getValue(name)
            values[name] = when (secret.type) {
                ENVIRONMENT_SECRET_TYPE -> SecretValues.Environment(
                    description = secret.description,
                    environment = secretEnvironments.getValue(secret.id),
                )
                SSH_SECRET_TYPE -> {
                    val row = sshKeysBySecret[secret.id]
                        ?: return RequestedSecretsResult.SecretCorrupted
                    val rendered = runCatching { publicKey(row) }
                        .getOrElse { return RequestedSecretsResult.SecretCorrupted }
                    SecretValues.Ssh(
                        description = secret.description,
                        publicKey = rendered.line,
                    )
                }
                else -> return RequestedSecretsResult.UnsupportedSecretType
            }
        }
        return RequestedSecretsResult.Available(values)
    }

    suspend fun signGitMessage(
        secretName: String,
        expectedPublicKey: String,
        message: ByteArray,
    ): GitSignatureResult {
        val secret = dao.getSecretsByName(listOf(secretName)).singleOrNull()
            ?: return GitSignatureResult.NotFound
        if (secret.type != SSH_SECRET_TYPE) return GitSignatureResult.WrongType
        val row = dao.getSshKey(secret.id) ?: return GitSignatureResult.SecretCorrupted
        val currentPublic = runCatching { publicKey(row) }
            .getOrElse { return GitSignatureResult.SecretCorrupted }
        val expectedPublic = runCatching { sshKeys.importOpenSshPublicKey(expectedPublicKey) }
            .getOrElse { return GitSignatureResult.SecretCorrupted }
        if (!MessageDigest.isEqual(currentPublic.blob(), expectedPublic.blob())) {
            return GitSignatureResult.KeyChanged
        }
        val privateKey = when (val decrypted = decryptSshKey(row)) {
            is DecryptionResult.Plaintext -> decrypted.value
            DecryptionResult.KeyUnavailable -> return GitSignatureResult.SecretUnavailable
            DecryptionResult.AuthenticationFailed -> return GitSignatureResult.SecretCorrupted
            DecryptionResult.UnsupportedFormat -> return GitSignatureResult.UnsupportedEncryption
        }
        val key = runCatching {
            sshKeys.fromStored(row.algorithm, privateKey, row.publicKey, row.comment)
        }.getOrElse { return GitSignatureResult.SecretCorrupted }
        val signature = runCatching {
            withContext(cryptographyDispatcher) {
                sshKeys.signGitSignature(key, message)
            }
        }.getOrElse { return GitSignatureResult.SecretCorrupted }
        return GitSignatureResult.Signed(signature)
    }

    private suspend fun encrypt(
        id: String,
        secretId: String,
        name: String,
        sensitive: Boolean,
        value: String,
    ): EncryptedValue {
        val key = keyManager.activeKey(VaultKeyPurpose.SECRET_VALUES)
        return withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = location(id, secretId, name, sensitive),
                plaintext = value.encodeToByteArray(),
            )
        }
    }

    private suspend fun decrypt(variable: EnvironmentVariableEntity): DecryptionResult =
        withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = EncryptedValue(
                    formatVersion = variable.encryptionFormat,
                    keyId = variable.encryptionKeyId,
                    nonce = variable.nonce,
                    ciphertext = variable.ciphertext,
                ),
                location = location(
                    id = variable.id,
                    secretId = variable.secretId,
                    name = variable.name,
                    sensitive = variable.sensitive,
                ),
            )
        }

    private suspend fun sshKeyEntity(
        secretId: String,
        privateKey: SshPrivateKey,
        materialUpdatedAt: Long,
    ): SshKeyEntity {
        val key = keyManager.activeKey(VaultKeyPurpose.SECRET_VALUES)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = sshKeyLocation(
                    secretId = secretId,
                    algorithm = privateKey.algorithm.storedName,
                    publicKey = privateKey.publicKey,
                ),
                plaintext = privateKey.privateKey,
            )
        }
        return SshKeyEntity(
            secretId = secretId,
            algorithm = privateKey.algorithm.storedName,
            publicKey = privateKey.publicKey.copyOf(),
            comment = privateKey.comment,
            privateKeyFormat = SSH_PRIVATE_KEY_FORMAT,
            encryptionFormat = encrypted.formatVersion,
            encryptionKeyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            materialUpdatedAt = materialUpdatedAt,
        )
    }

    private suspend fun decryptSshKey(key: SshKeyEntity): DecryptionResult {
        if (key.privateKeyFormat != SSH_PRIVATE_KEY_FORMAT) {
            return DecryptionResult.UnsupportedFormat
        }
        return withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = EncryptedValue(
                    formatVersion = key.encryptionFormat,
                    keyId = key.encryptionKeyId,
                    nonce = key.nonce,
                    ciphertext = key.ciphertext,
                ),
                location = sshKeyLocation(key.secretId, key.algorithm, key.publicKey),
            )
        }
    }

    private fun publicKey(key: SshKeyEntity): SshPublicKey =
        sshKeys.publicKey(key.algorithm, key.publicKey, key.comment)

    private fun publicKey(key: SshPrivateKey): SshPublicKey =
        SshPublicKey(key.algorithm, key.publicKey, key.comment)

    private fun validateSshPrivateKey(key: SshPrivateKey) {
        sshKeys.fromStored(
            key.algorithm.storedName,
            key.privateKey,
            key.publicKey,
            key.comment,
        )
    }

    private fun sshKeyLocation(
        secretId: String,
        algorithm: String,
        publicKey: ByteArray,
    ) = EncryptionLocation(
        recordType = "ssh_key",
        recordId = secretId,
        fieldName = "private_key",
        bindings = listOf(
            EncryptionBinding("algorithm", algorithm),
            EncryptionBinding("private_key_format", SSH_PRIVATE_KEY_FORMAT),
            EncryptionBinding("public_key", Base64.getEncoder().encodeToString(publicKey)),
        ),
    )

    private fun DecryptionResult.toEnvironmentVariableValue(): EnvironmentVariableValue = when (this) {
        is DecryptionResult.Plaintext -> try {
            EnvironmentVariableValue.Available(value.decodeToString(throwOnInvalidSequence = true))
        } catch (_: IllegalArgumentException) {
            EnvironmentVariableValue.Corrupted
        }
        DecryptionResult.KeyUnavailable -> EnvironmentVariableValue.Unavailable
        DecryptionResult.AuthenticationFailed -> EnvironmentVariableValue.Corrupted
        DecryptionResult.UnsupportedFormat -> EnvironmentVariableValue.UnsupportedFormat
    }

    private fun currentTimeFlow(): Flow<Long> = flow {
        while (true) {
            emit(currentTimeMillis())
            delay(TEMPORARY_ACCESS_REFRESH_MILLIS)
        }
    }

    private fun location(
        id: String,
        secretId: String,
        name: String,
        sensitive: Boolean,
    ) = EncryptionLocation(
        recordType = "environment_variable",
        recordId = id,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("secret_id", secretId),
            EncryptionBinding("name", name),
            EncryptionBinding("sensitive", sensitive.toString()),
        ),
    )

    private fun validateSecretName(name: String) {
        require(name.isNotBlank()) { "A secret must have a name" }
        require(name == name.trim()) { "A secret name cannot start or end with whitespace" }
    }

    private fun validateEnvironmentVariableName(name: String) {
        require(ENVIRONMENT_VARIABLE_NAME.matches(name)) {
            "An environment variable name must be a portable shell identifier"
        }
    }

    private fun validateEnvironmentVariableValue(value: String) {
        require('\u0000' !in value) { "An environment variable value cannot contain a null byte" }
    }

    private fun validateUploadInput(upload: EnvironmentSecretUpload): String? =
        runCatching {
            validateSecretName(upload.name)
            upload.variables.forEach { (name, value) ->
                validateEnvironmentVariableName(name)
                validateEnvironmentVariableValue(value)
            }
        }.exceptionOrNull()?.message

    private companion object {
        const val TEMPORARY_ACCESS_REFRESH_MILLIS = 30_000L
        val ENVIRONMENT_VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

private fun TemporaryAccessOperation.auditName(): String = when (this) {
    TemporaryAccessOperation.INVOCATION -> "secret values"
    TemporaryAccessOperation.GIT_SIGN -> "Git signing"
}
