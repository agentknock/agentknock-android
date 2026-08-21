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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal data class SecretSummary(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val environmentVariableCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class SecretDetails(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val environmentVariables: List<EnvironmentVariableMetadata>,
    val createdAt: Long,
    val updatedAt: Long,
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

@Serializable
internal data class SecretMetadata(
    val name: String,
    val description: String,
    val environmentVariableNames: List<String>,
)

internal data class RequestedSecretDescription(
    val secrets: List<SecretMetadata>,
    val missingSecrets: List<String>,
)

internal data class SecretValues(
    val description: String,
    val environment: Map<String, String>,
)

internal sealed interface RequestedSecretsResult {
    data class Available(val secrets: Map<String, SecretValues>) : RequestedSecretsResult

    data class MissingSecrets(val names: List<String>) : RequestedSecretsResult

    data class ConflictingVariable(val name: String) : RequestedSecretsResult

    data object SecretUnavailable : RequestedSecretsResult

    data object SecretCorrupted : RequestedSecretsResult

    data object UnsupportedEncryption : RequestedSecretsResult
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
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RequestedSecretSource {
    fun observeSecrets(): Flow<List<SecretSummary>> = dao.observeSecrets().map { rows ->
        rows.map { row ->
            SecretSummary(
                id = row.id,
                name = row.name,
                description = row.description,
                type = row.type,
                environmentVariableCount = row.environmentVariableCount,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
            )
        }
    }

    fun observeSecret(id: String): Flow<SecretDetails?> = combine(
        dao.observeSecret(id),
        dao.observeEnvironmentVariables(id),
    ) { secret, variables ->
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
                createdAt = secret.createdAt,
                updatedAt = secret.updatedAt,
            )
        }
    }

    suspend fun createSecret(name: String, description: String): CreateSecretResult {
        validateSecretName(name)
        if (dao.secretNameInUse(name, excludingId = "")) return CreateSecretResult.NameInUse
        val id = newId()
        val now = currentTimeMillis()
        dao.insertSecret(
            SecretEntity(
                id = id,
                name = name,
                description = description,
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

    suspend fun saveSecret(id: String, name: String, description: String): SaveSecretResult {
        validateSecretName(name)
        val existing = dao.getSecret(id) ?: return SaveSecretResult.NOT_FOUND
        if (dao.secretNameInUse(name, excludingId = id)) return SaveSecretResult.NAME_IN_USE
        dao.updateSecret(
            existing.copy(
                name = name,
                description = description,
                updatedAt = currentTimeMillis(),
            ),
        )
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
        dao.updateEnvironmentVariable(
            existing.copy(
                name = name,
                sensitive = sensitive,
                notes = notes,
                encryptionFormat = encrypted.formatVersion,
                encryptionKeyId = encrypted.keyId,
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
                updatedAt = now,
                valueUpdatedAt = if (replacementValue == null) existing.valueUpdatedAt else now,
            ),
        )
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

    override suspend fun listSecretsForClient(): List<SecretMetadata> {
        val variablesBySecret = dao.getEnvironmentVariables()
            .groupBy(EnvironmentVariableEntity::secretId)
        return dao.getSecrets().map { secret ->
            SecretMetadata(
                    name = secret.name,
                    description = secret.description,
                environmentVariableNames = variablesBySecret[secret.id]
                    .orEmpty()
                    .map(EnvironmentVariableEntity::name),
            )
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
        return RequestedSecretDescription(
            secrets = requestedNames.mapNotNull { name ->
                secretByName[name]?.let { secret ->
                    SecretMetadata(
                        name = secret.name,
                        description = secret.description,
                        environmentVariableNames = variablesBySecret[secret.id]
                            .orEmpty()
                            .map(EnvironmentVariableEntity::name)
                            .sorted(),
                    )
                }
            },
            missingSecrets = requestedNames.filterNot(secretByName::containsKey),
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
        return RequestedSecretsResult.Available(
            requestedNames.associateWith { name ->
                val secret = secretByName.getValue(name)
                SecretValues(
                    description = secret.description,
                    environment = secretEnvironments.getValue(secret.id),
                )
            },
        )
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
        val ENVIRONMENT_VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val ENVIRONMENT_SECRET_TYPE = "environment"
    }
}
