package dev.agentknock.storage.secret

import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.auditDataOf
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.protocol.SshSignatureAlgorithm
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray

internal class SecretRepository(
    private val dao: SecretDao,
    private val keyManager: VaultKeyManager,
    private val encryption: AesGcmEncryption,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val sshKeys: SshKeyCodec = SshKeyCodec(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val material = SecretMaterialStore(
        keyManager = keyManager,
        encryption = encryption,
        sshKeys = sshKeys,
        cryptographyDispatcher = cryptographyDispatcher,
    )
    private val resolver = SecretResolver(
        dao = dao,
        material = material,
        sshKeys = sshKeys,
        cryptographyDispatcher = cryptographyDispatcher,
    )
    private val uploads = SecretUploads(
        dao = dao,
        material = material,
        newId = newId,
        currentTimeMillis = currentTimeMillis,
    )
    fun observeSecrets(): Flow<List<SecretSummary>> = combine(
        dao.observeSecrets(),
        observeTemporaryAccessGrants(),
    ) { rows, grants ->
        val grantsBySecret = grants.groupBy(TemporaryAccessGrant::secretId)
        rows.mapNotNull { row ->
            val type = SecretType.fromStoredNameOrNull(row.type) ?: return@mapNotNull null
            SecretSummary(
                id = row.id,
                name = row.name,
                description = row.description,
                type = type,
                environmentVariableCount = row.environmentVariableCount,
                sshKey = row.sshAlgorithm?.let { algorithm ->
                    val publicKey = checkNotNull(row.sshPublicKey)
                    val comment = checkNotNull(row.sshComment)
                    sshKeyMetadata(
                        storedAlgorithm = algorithm,
                        publicKey = publicKey,
                        comment = comment,
                        privateKeyAvailable = row.sshEncryptionKeyId?.let {
                            keyManager.keyAvailable(it)
                        } ?: false,
                    )
                },
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                temporaryAccessGrants = grantsBySecret[row.id].orEmpty(),
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
        if (secret == null) return@combine null
        val type = SecretType.fromStoredNameOrNull(secret.type) ?: return@combine null
        val availability = variables
            .map(EnvironmentVariableMetadataRow::encryptionKeyId)
            .distinct()
            .associateWith { keyId -> keyManager.keyAvailable(keyId) }
        SecretDetails(
            id = secret.id,
            name = secret.name,
            description = secret.description,
            type = type,
            environmentVariables = variables.map { variable ->
                EnvironmentVariableMetadata(
                    id = variable.id,
                    secretId = variable.secretId,
                    name = variable.name,
                    sensitive = variable.sensitive,
                    valueAvailable = availability.getValue(variable.encryptionKeyId),
                    valueUpdatedAt = variable.valueUpdatedAt,
                )
            },
            sshKey = sshKey?.let { key ->
                sshKeyMetadata(
                    storedAlgorithm = key.algorithm,
                    publicKey = key.publicKey,
                    comment = key.comment,
                    privateKeyAvailable = keyManager.keyAvailable(key.encryptionKeyId),
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

    private fun sshKeyMetadata(
        storedAlgorithm: String,
        publicKey: ByteArray,
        comment: String,
        privateKeyAvailable: Boolean,
    ): SshKeyMetadata? {
        val algorithm = SshKeyAlgorithm.fromStoredName(storedAlgorithm) ?: return null
        val rendered = sshKeys.publicKey(algorithm, publicKey, comment)
        return SshKeyMetadata(
            algorithm = algorithm,
            bits = sshKeys.bitLength(rendered),
            publicKey = rendered.line,
            fingerprint = rendered.fingerprint,
            fingerprintHex = rendered.fingerprintHex,
            comment = rendered.comment,
            privateKeyAvailable = privateKeyAvailable,
        )
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
                val override = overrides[secret.id]
                SecretApprovalPolicy(
                    secretId = secret.id,
                    secretName = secret.name,
                    mode = override?.approvalMode?.toSecretApprovalMode()
                        ?: secret.approvalMode.toSecretApprovalMode(),
                    instructions = secret.instructions,
                    revision = secret.revision,
                    temporaryAccessExpiresAt = grants[secret.id]?.expiresAt,
                )
            }
        }
    }

    suspend fun saveApprovalMode(id: String, mode: SecretApprovalMode): SaveSecretResult {
        return writeTransaction.execute {
            val secret = dao.getSecret(id) ?: return@execute SaveSecretResult.NOT_FOUND
            if (secret.approvalMode == mode.storedName) return@execute SaveSecretResult.SAVED
            val now = currentTimeMillis()
            if (!dao.setSecretApprovalMode(id, mode.storedName, now)) {
                return@execute SaveSecretResult.NOT_FOUND
            }
            audit.record(
                AuditRecord(
                    type = AuditEventType.SECRET_APPROVAL_MODE_CHANGED,
                    outcome = AuditOutcome.CHANGED,
                    subject = secret.name,
                    detail = mode.auditName(),
                    data = secret.copy(
                        approvalMode = mode.storedName,
                        updatedAt = now,
                    ).auditData() + auditDataOf(
                        "previous_approval_mode" to secret.approvalMode,
                    ),
                ),
            )
            SaveSecretResult.SAVED
        }
    }

    suspend fun saveInstructions(id: String, instructions: String): SaveSecretResult {
        val normalized = instructions.trim()
        return writeTransaction.execute {
            val secret = dao.getSecret(id) ?: return@execute SaveSecretResult.NOT_FOUND
            if (secret.instructions == normalized) return@execute SaveSecretResult.SAVED
            val now = currentTimeMillis()
            if (!dao.setSecretInstructions(id, normalized, now)) {
                return@execute SaveSecretResult.NOT_FOUND
            }
            audit.record(
                AuditRecord(
                    type = AuditEventType.SECRET_INSTRUCTIONS_CHANGED,
                    outcome = AuditOutcome.CHANGED,
                    subject = secret.name,
                    data = secret.copy(
                        instructions = normalized,
                        updatedAt = now,
                        revision = secret.revision + 1,
                    ).auditData() + auditDataOf(
                        "previous_instructions" to secret.instructions,
                    ),
                ),
            )
            SaveSecretResult.SAVED
        }
    }

    suspend fun setClientApprovalOverride(
        secretId: String,
        clientId: String,
        mode: SecretApprovalMode?,
    ): SaveSecretResult {
        return writeTransaction.execute {
            val secret = dao.getSecret(secretId) ?: return@execute SaveSecretResult.NOT_FOUND
            val currentOverride = dao.getClientApprovalOverrides(
                clientId = clientId,
                secretIds = listOf(secretId),
            ).singleOrNull()
            if (currentOverride?.approvalMode == mode?.storedName) {
                return@execute SaveSecretResult.SAVED
            }
            val now = currentTimeMillis()
            val defaultMode = secret.approvalMode.toSecretApprovalMode()
            dao.replaceClientApprovalOverride(
                secretId = secretId,
                clientId = clientId,
                approvalMode = mode?.storedName,
                effectiveModeChanged =
                    (currentOverride?.approvalMode?.toSecretApprovalMode() ?: defaultMode) !=
                    (mode ?: defaultMode),
                updatedAt = now,
            )
            audit.record(
                AuditRecord(
                    type = AuditEventType.CLIENT_APPROVAL_OVERRIDE_CHANGED,
                    outcome = AuditOutcome.CHANGED,
                    subject = secret.name,
                    detail = mode?.auditName() ?: "Use default",
                    clientId = clientId,
                    clientName = dao.getClientName(clientId),
                    data = secret.auditData() + auditDataOf(
                        "previous_client_approval_mode" to currentOverride?.approvalMode,
                        "client_approval_mode" to mode?.storedName,
                        "previous_effective_approval_mode" to
                            (currentOverride?.approvalMode ?: defaultMode.storedName),
                        "effective_approval_mode" to (mode ?: defaultMode).storedName,
                    ),
                ),
            )
            SaveSecretResult.SAVED
        }
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
            policy.mode == SecretApprovalMode.ASK_ME || policy.mode == SecretApprovalMode.ASK_AI
        }) { "Temporary access is not enabled for this client and secret" }
        return writeTransaction.execute {
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
            if (!inserted) return@execute false
            val clientName = dao.getClientName(clientId)
            audit.append(
                records = distinctPolicies.map { policy ->
                    AuditRecord(
                        type = AuditEventType.TEMPORARY_ACCESS_ALLOWED,
                        outcome = AuditOutcome.APPROVED,
                        decisionSource = AuditDecisionSource.USER,
                        subject = policy.secretName,
                        detail = operation.auditName(),
                        expiresAt = expiresAt,
                        clientId = clientId,
                        clientName = clientName,
                        data = auditDataOf(
                            "secret_id" to policy.secretId,
                            "secret_name" to policy.secretName,
                            "approval_mode" to policy.mode.storedName,
                            "secret_revision" to policy.revision,
                            "operation" to operation.storedName,
                        ),
                    )
                },
                occurredAt = now,
            )
            true
        }
    }

    suspend fun endTemporaryAccess(
        secretId: String,
        clientId: String,
        operation: TemporaryAccessOperation,
    ): Boolean {
        return writeTransaction.execute {
            val secret = dao.getSecret(secretId)
            val grant = dao.getTemporaryAccessGrant(secretId, clientId, operation.storedName)
            val deleted = dao.deleteTemporaryAccessGrant(
                secretId,
                clientId,
                operation.storedName,
            ) > 0
            if (deleted) {
                audit.record(
                    AuditRecord(
                        type = AuditEventType.TEMPORARY_ACCESS_ENDED,
                        outcome = AuditOutcome.CHANGED,
                        subject = secret?.name,
                        detail = operation.auditName(),
                        clientId = clientId,
                        clientName = dao.getClientName(clientId),
                        expiresAt = grant?.expiresAt,
                        data = auditDataOf(
                            "secret_id" to secretId,
                            "secret_name" to secret?.name,
                            "secret_type" to secret?.type,
                            "operation" to operation.storedName,
                            "scheduled_expiration" to grant?.expiresAt,
                        ),
                    ),
                )
            }
            deleted
        }
    }

    suspend fun generateSshKey(
        algorithm: SshKeyAlgorithm,
        comment: String,
    ): SshPrivateKey = withContext(cryptographyDispatcher) {
        when (algorithm) {
            SshKeyAlgorithm.ED25519 -> sshKeys.generateEd25519(comment.trim())
            SshKeyAlgorithm.RSA -> sshKeys.generateRsa(comment.trim())
        }
    }

    suspend fun importSshKey(value: String): SshPrivateKey =
        withContext(cryptographyDispatcher) { sshKeys.importOpenSshPrivateKey(value) }

    suspend fun createEnvironmentSecret(
        name: String,
        description: String,
        variables: List<EnvironmentVariableInput> = emptyList(),
    ): CreateSecretResult {
        validateSecretName(name)
        variables.forEach { variable ->
            validateEnvironmentVariableName(variable.name)
            validateEnvironmentVariableValue(variable.value)
        }
        require(variables.map { it.name }.distinct().size == variables.size) {
            "Environment variable names must be unique"
        }
        val id = newId()
        val now = currentTimeMillis()
        val encryptedVariables = variables.map { variable ->
            val variableId = newId()
            EnvironmentVariableEntity(
                id = variableId,
                secretId = id,
                name = variable.name,
                sensitive = variable.sensitive,
                encryptedValue = material.encryptEnvironmentValue(
                    variableId,
                    id,
                    variable.name,
                    variable.sensitive,
                    variable.value,
                ),
                valueUpdatedAt = now,
            )
        }
        return writeTransaction.execute {
            if (dao.secretNameInUse(name, excludingId = "")) {
                return@execute CreateSecretResult.NameInUse
            }
            val secret = SecretEntity(
                id = id,
                name = name,
                description = description,
                type = ENVIRONMENT_SECRET_TYPE,
                createdAt = now,
                updatedAt = now,
            )
            dao.insertEnvironmentSecret(
                secret = secret,
                variables = encryptedVariables,
            )
            audit.record(
                AuditRecord(
                    type = AuditEventType.SECRET_CREATED,
                    outcome = AuditOutcome.CHANGED,
                    subject = name,
                    data = secret.auditData() + auditDataOf(
                        "environment_variables" to encryptedVariables.auditData(),
                    ),
                ),
            )
            CreateSecretResult.Created(id)
        }
    }

    suspend fun createSshSecret(
        name: String,
        description: String,
        privateKey: SshPrivateKey,
    ): CreateSecretResult {
        validateSecretName(name)
        material.validateSshPrivateKey(privateKey)
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
        val encryptedKey = material.encryptedSshKey(id, privateKey)
        return writeTransaction.execute {
            if (dao.secretNameInUse(name, excludingId = "")) {
                return@execute CreateSecretResult.NameInUse
            }
            dao.insertSshSecret(secret, encryptedKey)
            audit.record(
                AuditRecord(
                    type = AuditEventType.SSH_KEY_CREATED,
                    outcome = AuditOutcome.CHANGED,
                    subject = name,
                    data = secret.auditData() + privateKey.auditData(),
                ),
            )
            CreateSecretResult.Created(id)
        }
    }

    suspend fun replaceSshKey(id: String, privateKey: SshPrivateKey): SaveSshSecretResult {
        material.validateSshPrivateKey(privateKey)
        val secret = dao.getSecret(id) ?: return SaveSshSecretResult.NotFound
        if (SecretType.fromStoredNameOrNull(secret.type) != SecretType.SSH) {
            return SaveSshSecretResult.WrongType
        }
        val previousKey = dao.getSshKey(id) ?: return SaveSshSecretResult.NotFound
        val now = currentTimeMillis()
        val encryptedKey = material.encryptedSshKey(id, privateKey)
        return writeTransaction.execute {
            if (
                !dao.updateSshKeyIfCurrent(
                    encryptedKey,
                    expectedSecretRevision = secret.revision,
                    secretUpdatedAt = now,
                )
            ) return@execute SaveSshSecretResult.NotFound
            audit.record(
                AuditRecord(
                    type = AuditEventType.SSH_KEY_REPLACED,
                    outcome = AuditOutcome.CHANGED,
                    subject = secret.name,
                    data = secret.copy(
                        updatedAt = now,
                        revision = secret.revision + 1,
                    ).auditData() + privateKey.auditData() + previousKey.auditData("previous_"),
                ),
            )
            SaveSshSecretResult.Saved(id)
        }
    }

    suspend fun saveSshComment(id: String, comment: String): SaveSshSecretResult {
        val trimmed = comment.trim()
        val secret = dao.getSecret(id) ?: return SaveSshSecretResult.NotFound
        if (SecretType.fromStoredNameOrNull(secret.type) != SecretType.SSH) {
            return SaveSshSecretResult.WrongType
        }
        val key = dao.getSshKey(id) ?: return SaveSshSecretResult.NotFound
        runCatching { sshKeys.publicKey(key.algorithm, key.publicKey, trimmed) }
            .getOrElse { throw IllegalArgumentException(it.message, it) }
        if (key.comment == trimmed) return SaveSshSecretResult.Saved(id)
        val now = currentTimeMillis()
        return writeTransaction.execute {
            if (!dao.updateSshKeyComment(id, trimmed, secretUpdatedAt = now)) {
                return@execute SaveSshSecretResult.NotFound
            }
            audit.record(
                AuditRecord(
                    type = AuditEventType.SSH_PUBLIC_KEY_COMMENT_UPDATED,
                    outcome = AuditOutcome.CHANGED,
                    subject = secret.name,
                    data = secret.copy(updatedAt = now).auditData() +
                        key.auditData() + auditDataOf(
                            "previous_comment" to key.comment,
                            "comment" to trimmed,
                        ),
                ),
            )
            SaveSshSecretResult.Saved(id)
        }
    }

    suspend fun saveSecret(id: String, name: String, description: String): SaveSecretResult {
        validateSecretName(name)
        return writeTransaction.execute {
            val existing = dao.getSecret(id) ?: return@execute SaveSecretResult.NOT_FOUND
            if (dao.secretNameInUse(name, excludingId = id)) {
                return@execute SaveSecretResult.NAME_IN_USE
            }
            val now = currentTimeMillis()
            if (!dao.updateSecretMetadata(id, name, description, now)) {
                return@execute SaveSecretResult.NOT_FOUND
            }
            audit.record(
                AuditRecord(
                    type = AuditEventType.SECRET_UPDATED,
                    outcome = AuditOutcome.CHANGED,
                    subject = name,
                    detail = existing.name.takeUnless { it == name },
                    data = existing.copy(
                        name = name,
                        description = description,
                        updatedAt = now,
                        revision = existing.revision + if (existing.name == name) 0 else 1,
                    ).auditData() + auditDataOf(
                        "previous_name" to existing.name,
                        "previous_description" to existing.description,
                    ),
                ),
            )
            SaveSecretResult.SAVED
        }
    }

    suspend fun deleteSecret(id: String): Boolean {
        return writeTransaction.execute {
            val secret = dao.getSecret(id) ?: return@execute false
            val variables = dao.getEnvironmentVariables().filter { it.secretId == id }
            val sshKey = dao.getSshKey(id)
            dao.deleteSecret(secret)
            audit.record(
                AuditRecord(
                    type = AuditEventType.SECRET_DELETED,
                    outcome = AuditOutcome.CHANGED,
                    subject = secret.name,
                    data = secret.auditData() + auditDataOf(
                        "environment_variables" to variables.auditData(),
                        "ssh_key" to sshKey?.auditDataObject(),
                    ),
                ),
            )
            true
        }
    }

    suspend fun createEnvironmentVariable(
        secretId: String,
        name: String,
        value: String,
        sensitive: Boolean,
        nonSensitiveCreationAuthorized: Boolean,
    ): CreateEnvironmentVariableResult {
        validateEnvironmentVariableName(name)
        validateEnvironmentVariableValue(value)
        val owner = dao.getSecret(secretId)
        if (owner == null) {
            return CreateEnvironmentVariableResult.SecretNotFound
        }
        require(owner.secretType == SecretType.ENVIRONMENT) {
            "Environment variables can only belong to an environment secret"
        }
        if (dao.environmentVariableNameInUse(secretId, name, excludingId = "")) {
            return CreateEnvironmentVariableResult.NameInUse
        }
        if (!sensitive && !nonSensitiveCreationAuthorized) {
            return CreateEnvironmentVariableResult.AuthenticationRequired
        }

        val id = newId()
        val encrypted = material.encryptEnvironmentValue(id, secretId, name, sensitive, value)
        val now = currentTimeMillis()
        return writeTransaction.execute {
            if (dao.environmentVariableNameInUse(secretId, name, excludingId = "")) {
                return@execute CreateEnvironmentVariableResult.NameInUse
            }
            val inserted = dao.insertEnvironmentVariableIfCurrent(
                EnvironmentVariableEntity(
                    id = id,
                    secretId = secretId,
                    name = name,
                    sensitive = sensitive,
                    encryptedValue = encrypted,
                    valueUpdatedAt = now,
                ),
                expectedSecretRevision = owner.revision,
                secretUpdatedAt = now,
            )
            if (!inserted) return@execute CreateEnvironmentVariableResult.SecretNotFound
            audit.record(
                AuditRecord(
                    type = AuditEventType.ENVIRONMENT_VARIABLE_ADDED,
                    outcome = AuditOutcome.CHANGED,
                    subject = name,
                    detail = owner.name,
                    data = owner.copy(
                        updatedAt = now,
                        revision = owner.revision + 1,
                    ).auditData() + EnvironmentVariableEntity(
                        id = id,
                        secretId = secretId,
                        name = name,
                        sensitive = sensitive,
                        encryptedValue = encrypted,
                        valueUpdatedAt = now,
                    ).auditData(),
                ),
            )
            CreateEnvironmentVariableResult.Created(id)
        }
    }

    suspend fun readEnvironmentVariableValue(
        id: String,
        sensitiveAccessAuthorized: Boolean,
    ): EnvironmentVariableValue {
        val variable = dao.getEnvironmentVariable(id) ?: return EnvironmentVariableValue.NotFound
        if (variable.sensitive && !sensitiveAccessAuthorized) {
            return EnvironmentVariableValue.AuthenticationRequired(variable.name)
        }
        return material.decryptEnvironmentValue(variable).toEnvironmentVariableValue(variable)
    }

    suspend fun saveEnvironmentVariable(
        id: String,
        name: String,
        sensitive: Boolean,
        replacementValue: String?,
        sensitivityReductionAuthorized: Boolean,
    ): SaveEnvironmentVariableResult {
        validateEnvironmentVariableName(name)
        replacementValue?.let(::validateEnvironmentVariableValue)
        val existing = dao.getEnvironmentVariable(id) ?: return SaveEnvironmentVariableResult.NOT_FOUND
        val owner = dao.getSecret(existing.secretId)
            ?: return SaveEnvironmentVariableResult.NOT_FOUND
        if (owner.secretType != SecretType.ENVIRONMENT) {
            return SaveEnvironmentVariableResult.NOT_FOUND
        }
        if (dao.environmentVariableNameInUse(existing.secretId, name, excludingId = id)) {
            return SaveEnvironmentVariableResult.NAME_IN_USE
        }
        if (existing.sensitive && !sensitive && !sensitivityReductionAuthorized) {
            return SaveEnvironmentVariableResult.AUTHENTICATION_REQUIRED
        }

        val authenticatedFieldsChanged = name != existing.name || sensitive != existing.sensitive
        val encrypted = when {
            replacementValue != null -> material.encryptEnvironmentValue(
                id, existing.secretId, name, sensitive, replacementValue,
            )
            authenticatedFieldsChanged -> when (
                val decrypted = material.decryptEnvironmentValue(existing)
            ) {
                is DecryptionResult.Plaintext -> material.encryptEnvironmentValue(
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
            else -> existing.encryptedValue
        }

        val now = currentTimeMillis()
        val updated = existing.copy(
            name = name,
            sensitive = sensitive,
            encryptedValue = encrypted,
            valueUpdatedAt = if (replacementValue == null) existing.valueUpdatedAt else now,
        )
        return writeTransaction.execute {
            if (dao.environmentVariableNameInUse(existing.secretId, name, excludingId = id)) {
                return@execute SaveEnvironmentVariableResult.NAME_IN_USE
            }
            val rowsUpdated = if (
                dao.updateEnvironmentVariableIfCurrent(
                    updated,
                    owner.revision,
                    secretUpdatedAt = now,
                )
            ) 1 else 0
            if (rowsUpdated != 1) return@execute SaveEnvironmentVariableResult.NOT_FOUND
            audit.record(
                AuditRecord(
                    type = AuditEventType.ENVIRONMENT_VARIABLE_UPDATED,
                    outcome = AuditOutcome.CHANGED,
                    subject = name,
                    detail = owner.name,
                    data = owner.copy(
                        updatedAt = now,
                        revision = owner.revision + 1,
                    ).auditData() + updated.auditData() + auditDataOf(
                        "previous_name" to existing.name,
                        "previous_sensitive" to existing.sensitive,
                        "previous_value_updated_at" to existing.valueUpdatedAt,
                        "value_replaced" to (replacementValue != null),
                    ),
                ),
            )
            SaveEnvironmentVariableResult.SAVED
        }
    }

    suspend fun deleteEnvironmentVariable(id: String): Boolean {
        return writeTransaction.execute {
            val variable = dao.getEnvironmentVariable(id) ?: return@execute false
            val owner = dao.getSecret(variable.secretId) ?: return@execute false
            val now = currentTimeMillis()
            if (
                !dao.deleteEnvironmentVariableIfCurrent(
                    variable,
                    expectedSecretRevision = owner.revision,
                    secretUpdatedAt = now,
                )
            ) return@execute false
            audit.record(
                AuditRecord(
                    type = AuditEventType.ENVIRONMENT_VARIABLE_DELETED,
                    outcome = AuditOutcome.CHANGED,
                    subject = variable.name,
                    detail = owner.name,
                    data = owner.copy(
                        updatedAt = now,
                        revision = owner.revision + 1,
                    ).auditData() + variable.auditData(),
                ),
            )
            true
        }
    }

    suspend fun describeEnvironmentSecretUpload(
        upload: EnvironmentSecretUpload,
    ): EnvironmentSecretUploadResult = uploads.describeEnvironmentSecretUpload(upload)

    suspend fun targetForSecretUpload(
        mode: SecretUploadMode,
        name: String,
    ): SecretUploadTarget? = uploads.targetForSecretUpload(mode, name)

    suspend fun prepareEnvironmentSecretUpload(
        upload: EnvironmentSecretUpload,
        approvedName: String,
        target: SecretUploadTarget? = null,
    ): EnvironmentSecretUploadPreparation =
        uploads.prepareEnvironmentSecretUpload(upload, approvedName, target)

    suspend fun applyPreparedEnvironmentSecretUpload(
        upload: PreparedEnvironmentSecretUpload,
    ): ApplySecretUploadResult =
        uploads.applyPreparedEnvironmentSecretUpload(upload)

    suspend fun describeSshSecretUpload(upload: SshSecretUpload): SshSecretUploadResult =
        uploads.describeSshSecretUpload(upload)

    suspend fun prepareSshSecretUpload(
        upload: SshSecretUpload,
        approvedName: String,
        target: SecretUploadTarget? = null,
    ): SshSecretUploadPreparation = uploads.prepareSshSecretUpload(upload, approvedName, target)

    suspend fun applyPreparedSshSecretUpload(
        upload: PreparedSshSecretUpload,
    ): ApplySecretUploadResult = uploads.applyPreparedSshSecretUpload(upload)

    suspend fun listSecretsForClient(): List<SecretMetadata> =
        resolver.listSecretsForClient()

    suspend fun describeRequestedSecrets(
        names: List<String>,
        environmentSelections: Map<String, EnvironmentVariableSelection> = emptyMap(),
    ): RequestedSecretDescription =
        resolver.resolve(names, environmentSelections, includeValues = false).description

    suspend fun resolveRequestedSecrets(
        names: List<String>,
        environmentSelections: Map<String, EnvironmentVariableSelection> = emptyMap(),
    ): SecretResolution = resolver.resolve(names, environmentSelections, includeValues = true)

    suspend fun signGitMessage(
        secretName: String,
        expectedPublicKey: String,
        message: ByteArray,
    ): SignatureResult<String> = resolver.signGitMessage(secretName, expectedPublicKey, message)

    suspend fun signSshAuthentication(
        secretName: String,
        expectedPublicKey: String,
        message: ByteArray,
        algorithm: SshSignatureAlgorithm,
    ): SignatureResult<ByteArray> = resolver.signSshAuthentication(
        secretName,
        expectedPublicKey,
        message,
        algorithm,
    )

    private fun DecryptionResult.toEnvironmentVariableValue(
        variable: EnvironmentVariableEntity,
    ): EnvironmentVariableValue = when (this) {
        is DecryptionResult.Plaintext -> try {
            EnvironmentVariableValue.Available(
                name = variable.name,
                value = value.decodeToString(throwOnInvalidSequence = true),
                sensitive = variable.sensitive,
            )
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

    private companion object {
        const val TEMPORARY_ACCESS_REFRESH_MILLIS = 30_000L
        val ENVIRONMENT_VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }

    private fun SecretEntity.auditData(): Map<String, JsonElement> = auditDataOf(
        "secret_id" to id,
        "secret_name" to name,
        "description" to description,
        "secret_type" to type,
        "approval_mode" to approvalMode,
        "instructions" to instructions,
        "revision" to revision,
        "created_at" to createdAt,
        "updated_at" to updatedAt,
    )

    private fun EnvironmentVariableEntity.auditData(): Map<String, JsonElement> = auditDataOf(
        "variable_id" to id,
        "secret_id" to secretId,
        "variable_name" to name,
        "sensitive" to sensitive,
        "value_updated_at" to valueUpdatedAt,
    )

    private fun List<EnvironmentVariableEntity>.auditData() = buildJsonArray {
        sortedBy(EnvironmentVariableEntity::name).forEach { variable ->
            add(JsonObject(variable.auditData()))
        }
    }

    private fun SshKeyEntity.auditData(prefix: String = ""): Map<String, JsonElement> {
        val algorithm = checkNotNull(SshKeyAlgorithm.fromStoredName(algorithm))
        val public = sshKeys.publicKey(algorithm, publicKey, comment)
        return public.auditData(prefix)
    }

    private fun SshKeyEntity.auditDataObject() = JsonObject(auditData())

    private fun SshPrivateKey.auditData(): Map<String, JsonElement> =
        sshKeys.publicKey(algorithm, publicKey, comment).auditData()

    private fun SshPublicKey.auditData(prefix: String = ""): Map<String, JsonElement> =
        auditDataOf(
            "${prefix}algorithm" to algorithm.storedName,
            "${prefix}bits" to sshKeys.bitLength(this),
            "${prefix}public_key" to line,
            "${prefix}fingerprint" to fingerprint,
            "${prefix}fingerprint_hex" to fingerprintHex,
            "${prefix}comment" to comment,
        )
}

private fun TemporaryAccessOperation.auditName(): String = when (this) {
    TemporaryAccessOperation.INVOCATION -> "secret values"
    TemporaryAccessOperation.GIT_SIGN -> "Git signing"
    TemporaryAccessOperation.SSH_AUTHENTICATE -> "SSH authentication"
}

private fun SecretApprovalMode.auditName(): String = when (this) {
    SecretApprovalMode.APPROVE -> "Approve"
    SecretApprovalMode.ASK_AI -> "Ask AI"
    SecretApprovalMode.ASK_ME -> "Ask me"
    SecretApprovalMode.DENY -> "Deny"
}
