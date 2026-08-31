package dev.agentknock.storage.secret

import dev.agentknock.protocol.SshSignatureAlgorithm
import dev.agentknock.storage.runCatchingNonCancellation
import dev.agentknock.storage.crypto.DecryptionResult
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal data class SecretResolution(
    val description: RequestedSecretDescription,
    val values: RequestedSecretsResult,
)

/** Resolves one immutable database snapshot into review facts and, when requested, values. */
internal class SecretResolver(
    private val dao: SecretDao,
    private val material: SecretMaterialStore,
    private val sshKeys: SshKeyCodec,
    private val cryptographyDispatcher: CoroutineDispatcher,
) {
    suspend fun listSecretsForClient(): List<SecretMetadata> {
        val snapshot = dao.getSecretSnapshot()
        return snapshot.secrets.mapNotNull { secret ->
            when (runCatching { secret.secretType }.getOrNull()) {
                SecretType.ENVIRONMENT -> SecretMetadata(
                    name = secret.name,
                    description = secret.description,
                    type = SecretType.ENVIRONMENT.storedName,
                    environmentVariableNames = snapshot.variablesBySecret[secret.id]
                        .orEmpty()
                        .map(EnvironmentVariableEntity::name),
                )
                SecretType.SSH -> snapshot.sshKeysBySecret[secret.id]?.let { key ->
                    SecretMetadata(
                        name = secret.name,
                        description = secret.description,
                        type = SecretType.SSH.storedName,
                        sshPublicKey = material.publicKey(key).line,
                    )
                }
                null -> null
            }
        }
    }

    suspend fun resolve(
        names: List<String>,
        environmentSelections: Map<String, EnvironmentVariableSelection> = emptyMap(),
        includeValues: Boolean,
    ): SecretResolution {
        val snapshot = dao.getSecretSnapshot()
        val selection = select(snapshot, names, environmentSelections)
        val description = selection.description()
        return SecretResolution(
            description = description,
            values = selection.values(includeValues),
        )
    }

    suspend fun signGitMessage(
        secretName: String,
        expectedPublicKey: String,
        message: ByteArray,
    ): GitSignatureResult {
        val loaded = loadSigningKey(secretName, expectedPublicKey)
        val key = when (loaded) {
            is SigningKeyLoadResult.Available -> loaded.key
            SigningKeyLoadResult.NotFound -> return GitSignatureResult.NotFound
            SigningKeyLoadResult.WrongType -> return GitSignatureResult.WrongType
            SigningKeyLoadResult.KeyChanged -> return GitSignatureResult.KeyChanged
            SigningKeyLoadResult.Unavailable -> return GitSignatureResult.SecretUnavailable
            SigningKeyLoadResult.Corrupted -> return GitSignatureResult.SecretCorrupted
            SigningKeyLoadResult.UnsupportedEncryption -> {
                return GitSignatureResult.UnsupportedEncryption
            }
        }
        val signature = runCatchingNonCancellation {
            withContext(cryptographyDispatcher) { sshKeys.signGitSignature(key, message) }
        }.getOrElse { return GitSignatureResult.SecretCorrupted }
        return GitSignatureResult.Signed(signature)
    }

    suspend fun signSshAuthentication(
        secretName: String,
        expectedPublicKey: String,
        message: ByteArray,
        algorithm: SshSignatureAlgorithm,
    ): SshAuthenticationSignatureResult {
        val loaded = loadSigningKey(secretName, expectedPublicKey)
        val key = when (loaded) {
            is SigningKeyLoadResult.Available -> loaded.key
            SigningKeyLoadResult.NotFound -> return SshAuthenticationSignatureResult.NotFound
            SigningKeyLoadResult.WrongType -> return SshAuthenticationSignatureResult.WrongType
            SigningKeyLoadResult.KeyChanged -> return SshAuthenticationSignatureResult.KeyChanged
            SigningKeyLoadResult.Unavailable -> {
                return SshAuthenticationSignatureResult.SecretUnavailable
            }
            SigningKeyLoadResult.Corrupted -> {
                return SshAuthenticationSignatureResult.SecretCorrupted
            }
            SigningKeyLoadResult.UnsupportedEncryption -> {
                return SshAuthenticationSignatureResult.UnsupportedEncryption
            }
        }
        val signature = runCatchingNonCancellation {
            withContext(cryptographyDispatcher) {
                sshKeys.signSshAuthentication(key, message, algorithm)
            }
        }.getOrElse { return SshAuthenticationSignatureResult.SecretCorrupted }
        return SshAuthenticationSignatureResult.Signed(signature)
    }

    private suspend fun loadSigningKey(
        secretName: String,
        expectedPublicKey: String,
    ): SigningKeyLoadResult {
        val snapshot = dao.getSecretSnapshot()
        val secret = snapshot.secretsByName[secretName] ?: return SigningKeyLoadResult.NotFound
        if (runCatching { secret.secretType }.getOrNull() != SecretType.SSH) {
            return SigningKeyLoadResult.WrongType
        }
        val row = snapshot.sshKeysBySecret[secret.id] ?: return SigningKeyLoadResult.Corrupted
        val currentPublic = runCatching { material.publicKey(row) }
            .getOrElse { return SigningKeyLoadResult.Corrupted }
        val expectedPublic = runCatching { sshKeys.importOpenSshPublicKey(expectedPublicKey) }
            .getOrElse { return SigningKeyLoadResult.Corrupted }
        if (!MessageDigest.isEqual(currentPublic.blob(), expectedPublic.blob())) {
            return SigningKeyLoadResult.KeyChanged
        }
        val plaintext = when (val decrypted = material.decryptSshKey(row)) {
            is DecryptionResult.Plaintext -> decrypted.value
            DecryptionResult.KeyUnavailable -> return SigningKeyLoadResult.Unavailable
            DecryptionResult.AuthenticationFailed -> return SigningKeyLoadResult.Corrupted
            DecryptionResult.UnsupportedFormat -> {
                return SigningKeyLoadResult.UnsupportedEncryption
            }
        }
        val key = runCatching { material.storedPrivateKey(row, plaintext) }
            .getOrElse { return SigningKeyLoadResult.Corrupted }
        return SigningKeyLoadResult.Available(key)
    }

    private fun select(
        snapshot: SecretSnapshot,
        names: List<String>,
        environmentSelections: Map<String, EnvironmentVariableSelection>,
    ): Selection = Selection(snapshot, names.distinct(), environmentSelections)

    private inner class Selection(
        private val snapshot: SecretSnapshot,
        private val requestedNames: List<String>,
        private val selections: Map<String, EnvironmentVariableSelection>,
    ) {
        private val secretByName = requestedNames.mapNotNull(snapshot.secretsByName::get)
            .associateBy(SecretEntity::name)

        private fun selectedVariables(secret: SecretEntity): List<EnvironmentVariableEntity> =
            snapshot.variablesBySecret[secret.id].orEmpty().filter { variable ->
                val selection = selections[secret.name]
                when {
                    selection?.only != null -> variable.name in selection.only
                    selection != null -> variable.name !in selection.omit
                    else -> true
                }
            }

        fun description(): RequestedSecretDescription = RequestedSecretDescription(
            secrets = requestedNames.mapNotNull { name ->
                secretByName[name]?.metadata(selectedVariables(secretByName.getValue(name)))
            },
            reviewMetadata = requestedNames.mapNotNull { name ->
                secretByName[name]?.reviewMetadata(selectedVariables(secretByName.getValue(name)))
            },
            missingSecrets = requestedNames.filterNot(secretByName::containsKey),
            containsSensitiveMaterial = secretByName.values.any { secret ->
                runCatching { secret.secretType }.getOrNull() == SecretType.ENVIRONMENT &&
                    selectedVariables(secret).any(EnvironmentVariableEntity::sensitive)
            },
        )

        suspend fun values(includeValues: Boolean): RequestedSecretsResult {
            if (!includeValues) return RequestedSecretsResult.Available(emptyMap())
            if (requestedNames.isEmpty()) return RequestedSecretsResult.MissingSecrets(emptyList())
            val missing = requestedNames.filterNot(secretByName::containsKey)
            if (missing.isNotEmpty()) return RequestedSecretsResult.MissingSecrets(missing)
            val typed = secretByName.values.associateWith {
                runCatching { it.secretType }.getOrNull()
                    ?: return RequestedSecretsResult.UnsupportedSecretType
            }
            if (typed.values.count { it == SecretType.SSH } > 1) {
                return RequestedSecretsResult.MultipleSshKeys
            }
            typed.entries.firstOrNull { (secret, type) ->
                type == SecretType.SSH && selections[secret.name] != null
            }?.let { return RequestedSecretsResult.EnvironmentOptionsForSshSecret(it.key.name) }

            for ((secret, type) in typed) {
                if (type != SecretType.ENVIRONMENT) continue
                val selection = selections[secret.name] ?: continue
                val available = snapshot.variablesBySecret[secret.id].orEmpty()
                    .mapTo(mutableSetOf(), EnvironmentVariableEntity::name)
                val required = selection.only.orEmpty() + selection.rename.keys +
                    listOfNotNull(selection.stdin)
                val missingVariables = (required - available).sorted()
                if (missingVariables.isNotEmpty()) {
                    return RequestedSecretsResult.MissingEnvironmentVariables(
                        secret.name,
                        missingVariables,
                    )
                }
            }

            val combined = mutableMapOf<String, String>()
            val environments = typed.keys.associate { it.id to sortedMapOf<String, String>() }
            for ((secret, type) in typed) {
                if (type != SecretType.ENVIRONMENT) continue
                val selection = selections[secret.name]
                for (variable in selectedVariables(secret)) {
                    val value = when (val decrypted = material.decryptEnvironmentValue(variable)) {
                        is DecryptionResult.Plaintext -> runCatching {
                            decrypted.value.decodeToString(throwOnInvalidSequence = true)
                        }.getOrElse { return RequestedSecretsResult.SecretCorrupted }
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
                    environments.getValue(secret.id)[variable.name] = value
                    if (selection?.stdin == variable.name) continue
                    val deliveredName = selection?.rename?.get(variable.name) ?: variable.name
                    val previous = combined.putIfAbsent(deliveredName, value)
                    if (previous != null && previous != value) {
                        return RequestedSecretsResult.ConflictingVariable(deliveredName)
                    }
                }
            }

            val values = linkedMapOf<String, SecretValues>()
            for (name in requestedNames) {
                val secret = secretByName.getValue(name)
                values[name] = when (typed.getValue(secret)) {
                    SecretType.ENVIRONMENT -> SecretValues.Environment(
                        secret.description,
                        environments.getValue(secret.id),
                    )
                    SecretType.SSH -> {
                        val row = snapshot.sshKeysBySecret[secret.id]
                            ?: return RequestedSecretsResult.SecretCorrupted
                        val publicKey = runCatching { material.publicKey(row) }
                            .getOrElse { return RequestedSecretsResult.SecretCorrupted }
                        SecretValues.Ssh(secret.description, publicKey.line)
                    }
                }
            }
            return RequestedSecretsResult.Available(values)
        }

        private fun SecretEntity.metadata(
            variables: List<EnvironmentVariableEntity>,
        ): SecretMetadata? = when (runCatching { secretType }.getOrNull()) {
            SecretType.ENVIRONMENT -> SecretMetadata(
                name = name,
                description = description,
                type = SecretType.ENVIRONMENT.storedName,
                environmentVariableNames = variables.map(EnvironmentVariableEntity::name).sorted(),
                environmentVariableRename = selections[name]?.rename.orEmpty(),
                environmentVariableStdin = selections[name]?.stdin,
            )
            SecretType.SSH -> snapshot.sshKeysBySecret[id]?.let { key ->
                SecretMetadata(
                    name = name,
                    description = description,
                    type = SecretType.SSH.storedName,
                    sshPublicKey = material.publicKey(key).line,
                )
            }
            null -> null
        }

        private fun SecretEntity.reviewMetadata(
            selected: List<EnvironmentVariableEntity>,
        ): SecretReviewMetadata? {
            val selectedNames = selected.mapTo(hashSetOf(), EnvironmentVariableEntity::name)
            return when (runCatching { secretType }.getOrNull()) {
                SecretType.ENVIRONMENT -> SecretReviewMetadata(
                    id = id,
                    revision = revision,
                    name = name,
                    description = description,
                    type = SecretType.ENVIRONMENT.storedName,
                    instructions = instructions,
                    environmentVariables = selected.sortedBy(EnvironmentVariableEntity::name).map {
                        EnvironmentVariableReviewMetadata(
                            name = it.name,
                            sensitive = it.sensitive,
                            notes = it.notes,
                            createdAt = it.createdAt,
                            updatedAt = it.updatedAt,
                            valueUpdatedAt = it.valueUpdatedAt,
                        )
                    },
                    environmentVariableDestinations = snapshot.variablesBySecret[id].orEmpty()
                        .sortedBy(EnvironmentVariableEntity::name)
                        .associate { variable ->
                            variable.name to destination(variable, variable.name in selectedNames)
                        },
                    sshKey = null,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
                SecretType.SSH -> snapshot.sshKeysBySecret[id]?.let { key ->
                    val public = material.publicKey(key)
                    SecretReviewMetadata(
                        id = id,
                        revision = revision,
                        name = name,
                        description = description,
                        type = SecretType.SSH.storedName,
                        instructions = instructions,
                        environmentVariables = emptyList(),
                        sshKey = SshKeyReviewMetadata(
                            algorithm = public.algorithm.storedName,
                            publicKey = public.line,
                            fingerprint = public.fingerprint,
                            comment = public.comment,
                            materialUpdatedAt = key.materialUpdatedAt,
                        ),
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    )
                }
                null -> null
            }
        }

        private fun SecretEntity.destination(
            variable: EnvironmentVariableEntity,
            selected: Boolean,
        ): EnvironmentVariableReviewDestination = when {
            !selected -> EnvironmentVariableReviewDestination.Omitted
            selections[name]?.stdin == variable.name -> {
                EnvironmentVariableReviewDestination.StandardInput
            }
            else -> EnvironmentVariableReviewDestination.Environment(
                selections[name]?.rename?.get(variable.name) ?: variable.name,
            )
        }
    }

    private sealed interface SigningKeyLoadResult {
        data class Available(val key: SshPrivateKey) : SigningKeyLoadResult
        data object NotFound : SigningKeyLoadResult
        data object WrongType : SigningKeyLoadResult
        data object KeyChanged : SigningKeyLoadResult
        data object Unavailable : SigningKeyLoadResult
        data object Corrupted : SigningKeyLoadResult
        data object UnsupportedEncryption : SigningKeyLoadResult
    }
}
