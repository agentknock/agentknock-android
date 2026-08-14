package dev.agentknock.storage.profile

import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
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
    val environmentVariableCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class ProfileDetails(
    val id: String,
    val name: String,
    val description: String,
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

internal sealed interface CredentialEnvironmentResult {
    data class Available(val environment: Map<String, String>) : CredentialEnvironmentResult

    data class MissingProfiles(val names: List<String>) : CredentialEnvironmentResult

    data class ConflictingVariable(val name: String) : CredentialEnvironmentResult

    data object SecretUnavailable : CredentialEnvironmentResult

    data object SecretCorrupted : CredentialEnvironmentResult

    data object UnsupportedEncryption : CredentialEnvironmentResult
}

internal interface CredentialProfileSource {
    suspend fun listCredentialProfiles(): List<CredentialProfileMetadata>

    suspend fun describeCredentialProfiles(names: List<String>): CredentialProfileDescription

    suspend fun credentialEnvironment(names: List<String>): CredentialEnvironmentResult
}

internal class ProfileRepository(
    private val dao: ProfileDao,
    private val keyManager: LocalEncryptionKeyManager,
    private val encryption: AesGcmEncryption,
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
        return SaveProfileResult.SAVED
    }

    suspend fun deleteProfile(id: String): Boolean {
        val profile = dao.getProfile(id) ?: return false
        dao.deleteProfile(profile)
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
        return SaveEnvironmentVariableResult.SAVED
    }

    suspend fun deleteEnvironmentVariable(id: String): Boolean {
        val variable = dao.getEnvironmentVariable(id) ?: return false
        dao.deleteEnvironmentVariable(variable, profileUpdatedAt = currentTimeMillis())
        return true
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

    override suspend fun credentialEnvironment(names: List<String>): CredentialEnvironmentResult {
        val requestedNames = names.distinct()
        if (requestedNames.isEmpty()) {
            return CredentialEnvironmentResult.MissingProfiles(emptyList())
        }
        val profiles = dao.getProfilesByName(requestedNames)
        val profileByName = profiles.associateBy(ProfileEntity::name)
        val missing = requestedNames.filterNot(profileByName::containsKey)
        if (missing.isNotEmpty()) return CredentialEnvironmentResult.MissingProfiles(missing)

        val variables = dao.getEnvironmentVariablesForProfiles(profiles.map(ProfileEntity::id))
        val environment = sortedMapOf<String, String>()
        for (variable in variables) {
            val value = when (val decrypted = decrypt(variable)) {
                is DecryptionResult.Plaintext -> try {
                    decrypted.value.decodeToString(throwOnInvalidSequence = true)
                } catch (_: IllegalArgumentException) {
                    return CredentialEnvironmentResult.SecretCorrupted
                }
                DecryptionResult.KeyUnavailable -> {
                    return CredentialEnvironmentResult.SecretUnavailable
                }
                DecryptionResult.AuthenticationFailed -> {
                    return CredentialEnvironmentResult.SecretCorrupted
                }
                DecryptionResult.UnsupportedFormat -> {
                    return CredentialEnvironmentResult.UnsupportedEncryption
                }
            }
            val previous = environment.putIfAbsent(variable.name, value)
            if (previous != null && previous != value) {
                return CredentialEnvironmentResult.ConflictingVariable(variable.name)
            }
        }
        return CredentialEnvironmentResult.Available(environment)
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

    private companion object {
        val ENVIRONMENT_VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
