package dev.agentknock.storage.secret

import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.storage.crypto.DecryptionResult

/** Plans uploads against a pinned target and applies them with an optimistic revision check. */
internal class SecretUploads(
    private val dao: SecretDao,
    private val material: SecretMaterialStore,
    private val newId: () -> String,
    private val currentTimeMillis: () -> Long,
) {
    suspend fun describeEnvironmentSecretUpload(
        upload: EnvironmentSecretUpload,
    ): EnvironmentSecretUploadResult {
        val invalid = validateUploadInput(upload)
        if (invalid != null) return EnvironmentSecretUploadResult.Invalid(invalid)
        val snapshot = dao.getSecretSnapshot()
        val existing = snapshot.secretsByName[upload.name]
        when (upload.mode) {
            SecretUploadMode.CREATE -> if (existing != null) {
                return EnvironmentSecretUploadResult.Invalid(
                    "A secret named ${upload.name} already exists.",
                    SecretUploadTarget(existing.id, existing.revision),
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
                        SecretUploadTarget(existing.id, existing.revision),
                    )
                }
            }
        }
        val existingVariables = existing?.let { secret ->
            snapshot.variablesBySecret[secret.id].orEmpty().associateBy(EnvironmentVariableEntity::name)
        }.orEmpty()
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        upload.variables.toSortedMap().forEach { (name, value) ->
            val current = existingVariables[name]
            if (current == null) {
                added += name
            } else {
                val same = (material.decryptEnvironmentValue(current) as? DecryptionResult.Plaintext)?.value
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
                target = existing?.let { SecretUploadTarget(it.id, it.revision) },
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

    suspend fun targetForSecretUpload(
        mode: SecretUploadMode,
        name: String,
    ): SecretUploadTarget? = if (mode == SecretUploadMode.CREATE) {
        null
    } else {
        dao.getSecretSnapshot().secretsByName[name]?.let {
            SecretUploadTarget(it.id, it.revision)
        }
    }

    suspend fun applyEnvironmentSecretUpload(
        upload: EnvironmentSecretUpload,
        approvedName: String,
        target: SecretUploadTarget?,
    ): ApplyEnvironmentSecretUploadResult {
        validateUploadInput(upload)?.let { return ApplyEnvironmentSecretUploadResult.Invalid(it) }
        validateSecretName(approvedName)
        val snapshot = dao.getSecretSnapshot()
        val existing = when (upload.mode) {
            SecretUploadMode.CREATE -> {
                if (target != null) return ApplyEnvironmentSecretUploadResult.Invalid(
                    "The upload target changed before it was approved.",
                )
                null
            }
            SecretUploadMode.REPLACE,
            SecretUploadMode.UPDATE,
            -> {
                val expected = target ?: return ApplyEnvironmentSecretUploadResult.Invalid(
                    "The target secret no longer exists.",
                )
                snapshot.secrets.singleOrNull { it.id == expected.secretId }?.takeIf {
                    it.name == upload.name && it.revision == expected.revision &&
                        it.secretType == SecretType.ENVIRONMENT
                } ?: return ApplyEnvironmentSecretUploadResult.Invalid(
                    "The target secret changed before the upload was approved.",
                )
            }
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
        val existingVariables = existing?.let { secret ->
            snapshot.variablesBySecret[secret.id].orEmpty().associateBy(EnvironmentVariableEntity::name)
        }.orEmpty()
        val variables = upload.variables.map { (name, value) ->
            val current = existingVariables[name]
            val id = current?.id ?: newId()
            val sensitive = upload.variableSensitivity[name] ?: current?.sensitive ?: true
            val encrypted = material.encryptEnvironmentValue(id, secretId, name, sensitive, value)
            EnvironmentVariableEntity(
                id = id,
                secretId = secretId,
                name = name,
                sensitive = sensitive,
                notes = current?.notes.orEmpty(),
                encryptedValue = encrypted,
                createdAt = current?.createdAt ?: now,
                updatedAt = now,
                valueUpdatedAt = now,
            )
        }
        return applyPreparedEnvironmentUpload(
            upload = upload,
            approvedName = approvedName,
            target = target,
            secret = secret,
            variables = variables,
        )
    }

    private suspend fun applyPreparedEnvironmentUpload(
        upload: EnvironmentSecretUpload,
        approvedName: String,
        target: SecretUploadTarget?,
        secret: SecretEntity,
        variables: List<EnvironmentVariableEntity>,
    ): ApplyEnvironmentSecretUploadResult {
        val applied = dao.applyEnvironmentUploadIfCurrent(
            target = target,
            expectedName = upload.name,
            secret = secret,
            variables = variables,
            replaceVariables = upload.mode != SecretUploadMode.UPDATE,
            preserveCurrentDescription =
                upload.mode == SecretUploadMode.UPDATE && !upload.descriptionProvided,
        )
        if (!applied) {
            return ApplyEnvironmentSecretUploadResult.Invalid(
                if (upload.mode == SecretUploadMode.CREATE) {
                    "A secret named $approvedName already exists."
                } else {
                    "The target secret changed before the upload was approved."
                },
            )
        }
        return ApplyEnvironmentSecretUploadResult.Applied(secret.id)
    }

    suspend fun describeSshSecretUpload(upload: SshSecretUpload): SshSecretUploadResult {
        val invalid = runCatching {
            validateSecretName(upload.name)
            material.validateSshPrivateKey(upload.privateKey)
        }.exceptionOrNull()?.message
        if (invalid != null) return SshSecretUploadResult.Invalid(invalid)

        val snapshot = dao.getSecretSnapshot()
        val existing = snapshot.secretsByName[upload.name]
        when (upload.mode) {
            SecretUploadMode.CREATE -> if (existing != null) {
                return SshSecretUploadResult.Invalid(
                    "A secret named ${upload.name} already exists.",
                    SecretUploadTarget(existing.id, existing.revision),
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
                        SecretUploadTarget(existing.id, existing.revision),
                    )
                }
            }
        }
        val existingKey = existing?.let { snapshot.sshKeysBySecret[it.id] }
        if (existing != null && existingKey == null) {
            return SshSecretUploadResult.Invalid("The target SSH key is incomplete.")
        }
        val proposedPublic = material.publicKey(upload.privateKey)
        val previousPublic = existingKey?.let(material::publicKey)
        return SshSecretUploadResult.Valid(
            SshSecretUploadSummary(
                target = existing?.let { SecretUploadTarget(it.id, it.revision) },
                publicKey = proposedPublic.line,
                fingerprint = proposedPublic.fingerprint,
                previousPublicKey = previousPublic?.line,
                previousFingerprint = previousPublic?.fingerprint,
                keyChanged = previousPublic == null ||
                    proposedPublic.algorithm != previousPublic.algorithm ||
                    !proposedPublic.publicKey.contentEquals(previousPublic.publicKey),
            ),
        )
    }

    suspend fun applySshSecretUpload(
        upload: SshSecretUpload,
        approvedName: String,
        target: SecretUploadTarget?,
    ): ApplySshSecretUploadResult {
        val validation = runCatching {
            validateSecretName(upload.name)
            material.validateSshPrivateKey(upload.privateKey)
        }.exceptionOrNull()?.message
        if (validation != null) return ApplySshSecretUploadResult.Invalid(validation)
        validateSecretName(approvedName)
        val snapshot = dao.getSecretSnapshot()
        val existing = when (upload.mode) {
            SecretUploadMode.CREATE -> {
                if (target != null) return ApplySshSecretUploadResult.Invalid(
                    "The upload target changed before it was approved.",
                )
                null
            }
            SecretUploadMode.REPLACE,
            SecretUploadMode.UPDATE,
            -> {
                val expected = target ?: return ApplySshSecretUploadResult.Invalid(
                    "The target secret no longer exists.",
                )
                snapshot.secrets.singleOrNull { it.id == expected.secretId }?.takeIf {
                    it.name == upload.name && it.revision == expected.revision &&
                        it.secretType == SecretType.SSH
                } ?: return ApplySshSecretUploadResult.Invalid(
                    "The target secret changed before the upload was approved.",
                )
            }
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
        val currentKey = existing?.let { snapshot.sshKeysBySecret[it.id] }
        if (existing != null && currentKey == null) {
            return ApplySshSecretUploadResult.Invalid("The target SSH key is incomplete.")
        }
        val key = upload.privateKey.let {
            val proposedPublic = material.publicKey(it)
            val previousPublic = currentKey?.let(material::publicKey)
            val keyChanged = previousPublic == null ||
                proposedPublic.algorithm != previousPublic.algorithm ||
                !proposedPublic.publicKey.contentEquals(previousPublic.publicKey)
            material.encryptedSshKey(
                secretId,
                it,
                if (keyChanged) now else checkNotNull(currentKey).materialUpdatedAt,
            )
        }
        val applied = dao.applySshUploadIfCurrent(
            target = target,
            expectedName = upload.name,
            secret = secret,
            key = key,
            preserveCurrentDescription =
                upload.mode == SecretUploadMode.UPDATE && !upload.descriptionProvided,
        )
        if (!applied) {
            return ApplySshSecretUploadResult.Invalid(
                if (upload.mode == SecretUploadMode.CREATE) {
                    "A secret named $approvedName already exists."
                } else {
                    "The target secret changed before the upload was approved."
                },
            )
        }
        return ApplySshSecretUploadResult.Applied(secretId)
    }

    private fun validateSecretName(name: String) {
        require(name.isNotBlank()) { "A secret must have a name" }
        require(name == name.trim()) { "A secret name cannot start or end with whitespace" }
    }

    private fun validateUploadInput(upload: EnvironmentSecretUpload): String? =
        runCatching {
            validateSecretName(upload.name)
            require(upload.variables.isNotEmpty()) {
                "An environment upload must contain a variable"
            }
            upload.variables.forEach { (name, value) ->
                require(ENVIRONMENT_VARIABLE_NAME.matches(name)) {
                    "An environment variable name must be a portable shell identifier"
                }
                require('\u0000' !in value) {
                    "An environment variable value cannot contain a null byte"
                }
            }
        }.exceptionOrNull()?.message

    private companion object {
        val ENVIRONMENT_VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
