package dev.agentknock.storage.profile

import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import dev.agentknock.storage.audit.AuditCategory
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.NoOpAuditSink
import dev.agentknock.protocol.ProfileUploadMode
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal data class ProfileSummary(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val environmentVariableCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class ProfileDetails(
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
    val profileId: String,
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

internal sealed interface CreateProfileResult {
    data class Created(val id: String) : CreateProfileResult

    data object NameInUse : CreateProfileResult
}

internal enum class SaveProfileResult {
    SAVED,
    NAME_IN_USE,
    NOT_FOUND,
}

internal sealed interface CreateEnvironmentVariableResult {
    data class Created(val id: String) : CreateEnvironmentVariableResult

    data object NameInUse : CreateEnvironmentVariableResult

    data object ProfileNotFound : CreateEnvironmentVariableResult
}

internal enum class SaveEnvironmentVariableResult {
    SAVED,
    NAME_IN_USE,
    NOT_FOUND,
    VALUE_UNAVAILABLE,
    VALUE_CORRUPTED,
    UNSUPPORTED_FORMAT,
}

internal data class EnvironmentProfileProposal(
    val mode: ProfileUploadMode,
    val name: String,
    val descriptionProvided: Boolean,
    val description: String?,
    val variables: Map<String, String>,
)

internal data class EnvironmentProfileProposalSummary(
    val existingProfileId: String?,
    val addedVariables: List<String>,
    val changedVariables: List<String>,
    val unchangedVariables: List<String>,
    val removedVariables: List<String>,
)

internal sealed interface EnvironmentProfileProposalResult {
    data class Valid(val summary: EnvironmentProfileProposalSummary) :
        EnvironmentProfileProposalResult
    data class Invalid(val message: String) : EnvironmentProfileProposalResult
}

internal sealed interface ApplyEnvironmentProfileProposalResult {
    data class Applied(val profileId: String) : ApplyEnvironmentProfileProposalResult
    data class Invalid(val message: String) : ApplyEnvironmentProfileProposalResult
}

@Serializable
internal data class CredentialProfileMetadata(
    val name: String,
    val description: String,
    val environmentVariableNames: List<String>,
)

internal data class CredentialProfileDescription(
    val profiles: List<CredentialProfileMetadata>,
    val missingProfiles: List<String>,
)

internal data class CredentialProfileValues(
    val description: String,
    val environment: Map<String, String>,
)

internal sealed interface CredentialProfilesResult {
    data class Available(val profiles: Map<String, CredentialProfileValues>) : CredentialProfilesResult

    data class MissingProfiles(val names: List<String>) : CredentialProfilesResult

    data class ConflictingVariable(val name: String) : CredentialProfilesResult

    data object SecretUnavailable : CredentialProfilesResult

    data object SecretCorrupted : CredentialProfilesResult

    data object UnsupportedEncryption : CredentialProfilesResult
}

internal interface CredentialProfileSource {
    suspend fun listCredentialProfiles(): List<CredentialProfileMetadata>

    suspend fun describeCredentialProfiles(names: List<String>): CredentialProfileDescription

    suspend fun credentialProfiles(names: List<String>): CredentialProfilesResult
}

internal class ProfileRepository(
    private val dao: ProfileDao,
    private val keyManager: LocalEncryptionKeyManager,
    private val encryption: AesGcmEncryption,
    private val audit: AuditSink = NoOpAuditSink,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialProfileSource {
    fun observeProfiles(): Flow<List<ProfileSummary>> = dao.observeProfiles().map { rows ->
        rows.map { row ->
            ProfileSummary(
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

    fun observeProfile(id: String): Flow<ProfileDetails?> = combine(
        dao.observeProfile(id),
        dao.observeEnvironmentVariables(id),
    ) { profile, variables ->
        profile?.let {
            val availability = variables
                .map(EnvironmentVariableMetadataRow::encryptionKeyId)
                .distinct()
                .associateWith { keyId -> keyManager.keyAvailable(keyId) }
            ProfileDetails(
                id = profile.id,
                name = profile.name,
                description = profile.description,
                type = profile.type,
                environmentVariables = variables.map { variable ->
                    EnvironmentVariableMetadata(
                        id = variable.id,
                        profileId = variable.profileId,
                        name = variable.name,
                        sensitive = variable.sensitive,
                        notes = variable.notes,
                        valueAvailable = availability.getValue(variable.encryptionKeyId),
                        createdAt = variable.createdAt,
                        updatedAt = variable.updatedAt,
                        valueUpdatedAt = variable.valueUpdatedAt,
                    )
                },
                createdAt = profile.createdAt,
                updatedAt = profile.updatedAt,
            )
        }
    }

    suspend fun createProfile(name: String, description: String): CreateProfileResult {
        validateProfileName(name)
        if (dao.profileNameInUse(name, excludingId = "")) return CreateProfileResult.NameInUse
        val id = newId()
        val now = currentTimeMillis()
        dao.insertProfile(
            ProfileEntity(
                id = id,
                name = name,
                description = description,
                createdAt = now,
                updatedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.PROFILE,
                title = "Profile created",
                detail = name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return CreateProfileResult.Created(id)
    }

    suspend fun saveProfile(id: String, name: String, description: String): SaveProfileResult {
        validateProfileName(name)
        val existing = dao.getProfile(id) ?: return SaveProfileResult.NOT_FOUND
        if (dao.profileNameInUse(name, excludingId = id)) return SaveProfileResult.NAME_IN_USE
        dao.updateProfile(
            existing.copy(
                name = name,
                description = description,
                updatedAt = currentTimeMillis(),
            ),
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.PROFILE,
                title = "Profile updated",
                detail = name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return SaveProfileResult.SAVED
    }

    suspend fun deleteProfile(id: String): Boolean {
        val profile = dao.getProfile(id) ?: return false
        dao.deleteProfile(profile)
        audit.record(
            AuditRecord(
                category = AuditCategory.PROFILE,
                title = "Profile deleted",
                detail = profile.name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return true
    }

    suspend fun createEnvironmentVariable(
        profileId: String,
        name: String,
        value: String,
        sensitive: Boolean,
        notes: String,
    ): CreateEnvironmentVariableResult {
        validateEnvironmentVariableName(name)
        validateEnvironmentVariableValue(value)
        if (dao.getProfile(profileId) == null) {
            return CreateEnvironmentVariableResult.ProfileNotFound
        }
        if (dao.environmentVariableNameInUse(profileId, name, excludingId = "")) {
            return CreateEnvironmentVariableResult.NameInUse
        }

        val id = newId()
        val encrypted = encrypt(id, profileId, name, sensitive, value)
        val now = currentTimeMillis()
        dao.insertEnvironmentVariable(
            EnvironmentVariableEntity(
                id = id,
                profileId = profileId,
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
                category = AuditCategory.PROFILE,
                title = "Variable added",
                detail = "$name in ${dao.getProfile(profileId)?.name.orEmpty()}",
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
        if (dao.environmentVariableNameInUse(existing.profileId, name, excludingId = id)) {
            return SaveEnvironmentVariableResult.NAME_IN_USE
        }

        val authenticatedFieldsChanged = name != existing.name || sensitive != existing.sensitive
        val encrypted = when {
            replacementValue != null -> encrypt(id, existing.profileId, name, sensitive, replacementValue)
            authenticatedFieldsChanged -> when (val decrypted = decrypt(existing)) {
                is DecryptionResult.Plaintext -> encrypt(
                    id,
                    existing.profileId,
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
                category = AuditCategory.PROFILE,
                title = "Variable updated",
                detail = name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return SaveEnvironmentVariableResult.SAVED
    }

    suspend fun deleteEnvironmentVariable(id: String): Boolean {
        val variable = dao.getEnvironmentVariable(id) ?: return false
        dao.deleteEnvironmentVariable(variable, profileUpdatedAt = currentTimeMillis())
        audit.record(
            AuditRecord(
                category = AuditCategory.PROFILE,
                title = "Variable deleted",
                detail = variable.name,
                outcome = AuditOutcome.CHANGED,
            ),
        )
        return true
    }

    suspend fun describeEnvironmentProfileProposal(
        proposal: EnvironmentProfileProposal,
    ): EnvironmentProfileProposalResult {
        val invalid = validateProposalInput(proposal)
        if (invalid != null) return EnvironmentProfileProposalResult.Invalid(invalid)
        val existing = dao.getProfilesByName(listOf(proposal.name)).singleOrNull()
        when (proposal.mode) {
            ProfileUploadMode.CREATE -> if (existing != null) {
                return EnvironmentProfileProposalResult.Invalid(
                    "A Profile named ${proposal.name} already exists.",
                )
            }
            ProfileUploadMode.REPLACE,
            ProfileUploadMode.UPDATE,
            -> {
                if (existing == null) {
                    return EnvironmentProfileProposalResult.Invalid(
                        "The target Profile ${proposal.name} does not exist.",
                    )
                }
                if (existing.type != ENVIRONMENT_PROFILE_TYPE) {
                    return EnvironmentProfileProposalResult.Invalid(
                        "The target Profile has a different type.",
                    )
                }
            }
        }
        val existingVariables = existing?.let {
            dao.getEnvironmentVariablesForProfiles(listOf(it.id)).associateBy { variable ->
                variable.name
            }
        }.orEmpty()
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        proposal.variables.toSortedMap().forEach { (name, value) ->
            val current = existingVariables[name]
            if (current == null) {
                added += name
            } else {
                val same = (decrypt(current) as? DecryptionResult.Plaintext)?.value
                    ?.decodeToString(throwOnInvalidSequence = false) == value
                if (same) unchanged += name else changed += name
            }
        }
        val removed = if (proposal.mode == ProfileUploadMode.REPLACE) {
            existingVariables.keys.minus(proposal.variables.keys).sorted()
        } else {
            emptyList()
        }
        return EnvironmentProfileProposalResult.Valid(
            EnvironmentProfileProposalSummary(
                existingProfileId = existing?.id,
                addedVariables = added,
                changedVariables = changed,
                unchangedVariables = unchanged,
                removedVariables = removed,
            ),
        )
    }

    suspend fun applyEnvironmentProfileProposal(
        proposal: EnvironmentProfileProposal,
        acceptedName: String,
    ): ApplyEnvironmentProfileProposalResult {
        val validation = describeEnvironmentProfileProposal(proposal)
        if (validation is EnvironmentProfileProposalResult.Invalid) {
            return ApplyEnvironmentProfileProposalResult.Invalid(validation.message)
        }
        validateProfileName(acceptedName)
        val summary = (validation as EnvironmentProfileProposalResult.Valid).summary
        val existing = summary.existingProfileId?.let { id -> dao.getProfile(id) }
        if (
            proposal.mode == ProfileUploadMode.CREATE &&
            dao.profileNameInUse(acceptedName, excludingId = "")
        ) {
            return ApplyEnvironmentProfileProposalResult.Invalid(
                "A Profile named $acceptedName already exists.",
            )
        }
        val now = currentTimeMillis()
        val profileId = existing?.id ?: newId()
        val profile = ProfileEntity(
            id = profileId,
            name = if (proposal.mode == ProfileUploadMode.CREATE) acceptedName else proposal.name,
            description = when {
                proposal.descriptionProvided -> proposal.description.orEmpty()
                proposal.mode == ProfileUploadMode.UPDATE -> existing?.description.orEmpty()
                else -> ""
            },
            type = ENVIRONMENT_PROFILE_TYPE,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        val existingVariables = existing?.let {
            dao.getEnvironmentVariablesForProfiles(listOf(it.id)).associateBy { variable ->
                variable.name
            }
        }.orEmpty()
        val variables = proposal.variables.map { (name, value) ->
            val current = existingVariables[name]
            val id = current?.id ?: newId()
            val sensitive = current?.sensitive ?: true
            val encrypted = encrypt(id, profileId, name, sensitive, value)
            EnvironmentVariableEntity(
                id = id,
                profileId = profileId,
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
        dao.applyEnvironmentProfile(
            profile = profile,
            variables = variables,
            replaceVariables = proposal.mode != ProfileUploadMode.UPDATE,
        )
        return ApplyEnvironmentProfileProposalResult.Applied(profileId)
    }

    override suspend fun listCredentialProfiles(): List<CredentialProfileMetadata> {
        val variablesByProfile = dao.getEnvironmentVariables()
            .groupBy(EnvironmentVariableEntity::profileId)
        return dao.getProfiles().map { profile ->
            CredentialProfileMetadata(
                    name = profile.name,
                    description = profile.description,
                environmentVariableNames = variablesByProfile[profile.id]
                    .orEmpty()
                    .map(EnvironmentVariableEntity::name),
            )
        }
    }

    override suspend fun describeCredentialProfiles(
        names: List<String>,
    ): CredentialProfileDescription {
        val requestedNames = names.distinct()
        val profiles = if (requestedNames.isEmpty()) {
            emptyList()
        } else {
            dao.getProfilesByName(requestedNames)
        }
        val profileByName = profiles.associateBy(ProfileEntity::name)
        val variables = if (profiles.isEmpty()) {
            emptyList()
        } else {
            dao.getEnvironmentVariablesForProfiles(profiles.map(ProfileEntity::id))
        }
        val variablesByProfile = variables.groupBy(EnvironmentVariableEntity::profileId)
        return CredentialProfileDescription(
            profiles = requestedNames.mapNotNull { name ->
                profileByName[name]?.let { profile ->
                    CredentialProfileMetadata(
                        name = profile.name,
                        description = profile.description,
                        environmentVariableNames = variablesByProfile[profile.id]
                            .orEmpty()
                            .map(EnvironmentVariableEntity::name)
                            .sorted(),
                    )
                }
            },
            missingProfiles = requestedNames.filterNot(profileByName::containsKey),
        )
    }

    override suspend fun credentialProfiles(names: List<String>): CredentialProfilesResult {
        val requestedNames = names.distinct()
        if (requestedNames.isEmpty()) {
            return CredentialProfilesResult.MissingProfiles(emptyList())
        }
        val profiles = dao.getProfilesByName(requestedNames)
        val profileByName = profiles.associateBy(ProfileEntity::name)
        val missing = requestedNames.filterNot(profileByName::containsKey)
        if (missing.isNotEmpty()) return CredentialProfilesResult.MissingProfiles(missing)

        val variables = dao.getEnvironmentVariablesForProfiles(profiles.map(ProfileEntity::id))
        val combinedEnvironment = sortedMapOf<String, String>()
        val profileEnvironments = profiles.associate { profile ->
            profile.id to sortedMapOf<String, String>()
        }
        for (variable in variables) {
            val value = when (val decrypted = decrypt(variable)) {
                is DecryptionResult.Plaintext -> try {
                    decrypted.value.decodeToString(throwOnInvalidSequence = true)
                } catch (_: IllegalArgumentException) {
                    return CredentialProfilesResult.SecretCorrupted
                }
                DecryptionResult.KeyUnavailable -> {
                    return CredentialProfilesResult.SecretUnavailable
                }
                DecryptionResult.AuthenticationFailed -> {
                    return CredentialProfilesResult.SecretCorrupted
                }
                DecryptionResult.UnsupportedFormat -> {
                    return CredentialProfilesResult.UnsupportedEncryption
                }
            }
            profileEnvironments.getValue(variable.profileId)[variable.name] = value
            val previous = combinedEnvironment.putIfAbsent(variable.name, value)
            if (previous != null && previous != value) {
                return CredentialProfilesResult.ConflictingVariable(variable.name)
            }
        }
        return CredentialProfilesResult.Available(
            requestedNames.associateWith { name ->
                val profile = profileByName.getValue(name)
                CredentialProfileValues(
                    description = profile.description,
                    environment = profileEnvironments.getValue(profile.id),
                )
            },
        )
    }

    private suspend fun encrypt(
        id: String,
        profileId: String,
        name: String,
        sensitive: Boolean,
        value: String,
    ): EncryptedValue {
        val key = keyManager.activeKey()
        return withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = location(id, profileId, name, sensitive),
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
                    profileId = variable.profileId,
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
        profileId: String,
        name: String,
        sensitive: Boolean,
    ) = EncryptionLocation(
        recordType = "environment_variable",
        recordId = id,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("profile_id", profileId),
            EncryptionBinding("name", name),
            EncryptionBinding("sensitive", sensitive.toString()),
        ),
    )

    private fun validateProfileName(name: String) {
        require(name.isNotBlank()) { "A profile must have a name" }
        require(name == name.trim()) { "A profile name cannot start or end with whitespace" }
    }

    private fun validateEnvironmentVariableName(name: String) {
        require(ENVIRONMENT_VARIABLE_NAME.matches(name)) {
            "An environment variable name must be a portable shell identifier"
        }
    }

    private fun validateEnvironmentVariableValue(value: String) {
        require('\u0000' !in value) { "An environment variable value cannot contain a null byte" }
    }

    private fun validateProposalInput(proposal: EnvironmentProfileProposal): String? =
        runCatching {
            validateProfileName(proposal.name)
            proposal.variables.forEach { (name, value) ->
                validateEnvironmentVariableName(name)
                validateEnvironmentVariableValue(value)
            }
        }.exceptionOrNull()?.message

    private companion object {
        val ENVIRONMENT_VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val ENVIRONMENT_PROFILE_TYPE = "environment"
    }
}
