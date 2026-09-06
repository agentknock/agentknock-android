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
    val nonSensitiveEnvironmentValues: Map<String, Map<String, String>>,
)

private data class ParsedSshKey(
    val entity: SshKeyEntity,
    val algorithm: SshKeyAlgorithm,
)

private class ParsedSecretSnapshot(snapshot: SecretSnapshot) {
    val secrets: List<SecretEntity> = snapshot.secrets
    val secretsByName: Map<String, SecretEntity> = snapshot.secretsByName
    val variablesBySecret: Map<String, List<EnvironmentVariableEntity>> =
        snapshot.variablesBySecret
    val sshKeysBySecret: Map<String, ParsedSshKey> = snapshot.sshKeys.mapNotNull { key ->
        SshKeyAlgorithm.fromStoredName(key.algorithm)?.let { algorithm ->
            key.secretId to ParsedSshKey(key, algorithm)
        }
    }.toMap()
    private val secretTypesById: Map<String, SecretType> = snapshot.secrets.mapNotNull { secret ->
        SecretType.fromStoredNameOrNull(secret.type)?.let { type -> secret.id to type }
    }.toMap()

    fun typeOf(secret: SecretEntity): SecretType? = secretTypesById[secret.id]
}

/** Resolves one immutable database snapshot into review facts and, when requested, values. */
internal class SecretResolver(
    private val dao: SecretDao,
    private val material: SecretMaterialStore,
    private val sshKeys: SshKeyCodec,
    private val cryptographyDispatcher: CoroutineDispatcher,
) {
    suspend fun listSecretsForClient(): List<SecretMetadata> {
        val snapshot = ParsedSecretSnapshot(dao.getSecretSnapshot())
        return snapshot.secrets.mapNotNull { secret ->
            when (snapshot.typeOf(secret)) {
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
                        sshPublicKey = material.publicKey(key.entity, key.algorithm).line,
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
        val snapshot = ParsedSecretSnapshot(dao.getSecretSnapshot())
        val selection = select(snapshot, names, environmentSelections)
        val description = selection.description()
        val values = selection.values(includeValues)
        return SecretResolution(
            description = description,
            values = values,
            nonSensitiveEnvironmentValues = if (
                includeValues && values is RequestedSecretsResult.Available
            ) {
                selection.nonSensitiveEnvironmentValues(values.secrets)
            } else {
                emptyMap()
            },
        )
    }

    suspend fun signGitMessage(
        secretName: String,
        expectedPublicKey: String,
        message: ByteArray,
    ): SignatureResult<String> = sign(secretName, expectedPublicKey) { key ->
        sshKeys.signGitSignature(key, message)
    }

    suspend fun signSshAuthentication(
        secretName: String,
        expectedPublicKey: String,
        message: ByteArray,
        algorithm: SshSignatureAlgorithm,
    ): SignatureResult<ByteArray> = sign(secretName, expectedPublicKey) { key ->
        sshKeys.signSshAuthentication(key, message, algorithm)
    }

    private suspend fun <T> sign(
        secretName: String,
        expectedPublicKey: String,
        signature: (SshPrivateKey) -> T,
    ): SignatureResult<T> {
        val snapshot = ParsedSecretSnapshot(dao.getSecretSnapshot())
        val secret = snapshot.secretsByName[secretName] ?: return SignatureResult.NotFound
        if (snapshot.typeOf(secret) != SecretType.SSH) {
            return SignatureResult.WrongType
        }
        val key = snapshot.sshKeysBySecret[secret.id] ?: return SignatureResult.SecretCorrupted
        val currentPublic = runCatching { material.publicKey(key.entity, key.algorithm) }
            .getOrElse { return SignatureResult.SecretCorrupted }
        val expectedPublic = runCatching { sshKeys.importOpenSshPublicKey(expectedPublicKey) }
            .getOrElse { return SignatureResult.SecretCorrupted }
        if (!MessageDigest.isEqual(currentPublic.blob(), expectedPublic.blob())) {
            return SignatureResult.KeyChanged
        }
        val plaintext = when (val decrypted = material.decryptSshKey(key.entity, key.algorithm)) {
            is DecryptionResult.Plaintext -> decrypted.value
            DecryptionResult.KeyUnavailable -> return SignatureResult.SecretUnavailable
            DecryptionResult.AuthenticationFailed -> return SignatureResult.SecretCorrupted
            DecryptionResult.UnsupportedFormat -> {
                return SignatureResult.UnsupportedEncryption
            }
        }
        val privateKey = runCatching {
            material.storedPrivateKey(key.entity, key.algorithm, plaintext)
        }
            .getOrElse { return SignatureResult.SecretCorrupted }
        return runCatchingNonCancellation {
            withContext(cryptographyDispatcher) { SignatureResult.Signed(signature(privateKey)) }
        }.getOrElse { SignatureResult.SecretCorrupted }
    }

    private fun select(
        snapshot: ParsedSecretSnapshot,
        names: List<String>,
        environmentSelections: Map<String, EnvironmentVariableSelection>,
    ): Selection = Selection(snapshot, names.distinct(), environmentSelections)

    private inner class Selection(
        private val snapshot: ParsedSecretSnapshot,
        private val requestedNames: List<String>,
        private val selections: Map<String, EnvironmentVariableSelection>,
    ) {
        private val secretByName = requestedNames.mapNotNull(snapshot.secretsByName::get)
            .associateBy(SecretEntity::name)

        private val selectedVariablesBySecretId = secretByName.values.associate { secret ->
            secret.id to snapshot.variablesBySecret[secret.id].orEmpty().filter { variable ->
                val selection = selections[secret.name]
                when {
                    selection?.only != null -> variable.name in selection.only
                    selection != null -> variable.name !in selection.omit
                    else -> true
                }
            }
        }

        private fun selectedVariables(secret: SecretEntity): List<EnvironmentVariableEntity> =
            selectedVariablesBySecretId.getValue(secret.id)

        fun description(): RequestedSecretDescription = RequestedSecretDescription(
            secrets = requestedNames.mapNotNull { name ->
                secretByName[name]?.metadata(selectedVariables(secretByName.getValue(name)))
            },
            reviewMetadata = requestedNames.mapNotNull { name ->
                secretByName[name]?.reviewMetadata(selectedVariables(secretByName.getValue(name)))
            },
            missingSecrets = requestedNames.filterNot(secretByName::containsKey),
            containsSensitiveMaterial = secretByName.values.any { secret ->
                snapshot.typeOf(secret) == SecretType.ENVIRONMENT &&
                    selectedVariables(secret).any(EnvironmentVariableEntity::sensitive)
            },
        )

        suspend fun values(includeValues: Boolean): RequestedSecretsResult {
            if (!includeValues) return RequestedSecretsResult.Available(emptyMap())
            if (requestedNames.isEmpty()) return RequestedSecretsResult.MissingSecrets(emptyList())
            val missing = requestedNames.filterNot(secretByName::containsKey)
            if (missing.isNotEmpty()) return RequestedSecretsResult.MissingSecrets(missing)
            val typed = secretByName.values.associateWith {
                snapshot.typeOf(it)
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
                        val key = snapshot.sshKeysBySecret[secret.id]
                            ?: return RequestedSecretsResult.SecretCorrupted
                        val publicKey = runCatching {
                            material.publicKey(key.entity, key.algorithm)
                        }
                            .getOrElse { return RequestedSecretsResult.SecretCorrupted }
                        SecretValues.Ssh(secret.description, publicKey.line)
                    }
                }
            }
            return RequestedSecretsResult.Available(values)
        }

        private fun SecretEntity.metadata(
            variables: List<EnvironmentVariableEntity>,
        ): SecretMetadata? = when (snapshot.typeOf(this)) {
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
                    sshPublicKey = material.publicKey(key.entity, key.algorithm).line,
                )
            }
            null -> null
        }

        private fun SecretEntity.reviewMetadata(
            selected: List<EnvironmentVariableEntity>,
        ): SecretReviewMetadata? {
            val selectedNames = selected.mapTo(hashSetOf(), EnvironmentVariableEntity::name)
            return when (snapshot.typeOf(this)) {
                SecretType.ENVIRONMENT -> SecretReviewMetadata(
                    id = id,
                    revision = revision,
                    name = name,
                    type = SecretType.ENVIRONMENT.storedName,
                    environmentVariables = snapshot.variablesBySecret[id].orEmpty()
                        .sortedBy(EnvironmentVariableEntity::name)
                        .map {
                            EnvironmentVariableReviewMetadata(
                                name = it.name,
                                sensitive = it.sensitive,
                                destination = destination(it, it.name in selectedNames),
                            )
                        },
                )
                SecretType.SSH -> snapshot.sshKeysBySecret[id]?.let {
                    SecretReviewMetadata(
                        id = id,
                        revision = revision,
                        name = name,
                        type = SecretType.SSH.storedName,
                        environmentVariables = emptyList(),
                    )
                }
                null -> null
            }
        }

        suspend fun nonSensitiveEnvironmentValues(
            available: Map<String, SecretValues>,
        ): Map<String, Map<String, String>> = requestedNames.mapNotNull { name ->
            val secret = secretByName[name] ?: return@mapNotNull null
            if (snapshot.typeOf(secret) != SecretType.ENVIRONMENT) return@mapNotNull null
            val delivered = (available[name] as? SecretValues.Environment)?.environment.orEmpty()
            val values = snapshot.variablesBySecret[secret.id].orEmpty()
                .filterNot(EnvironmentVariableEntity::sensitive)
                .mapNotNull { variable ->
                    delivered[variable.name]?.let { variable.name to it }
                        ?: optionalEnvironmentValue(variable)?.let { variable.name to it }
                }
                .toMap(linkedMapOf())
            name to values
        }.toMap(linkedMapOf())

        private suspend fun optionalEnvironmentValue(
            variable: EnvironmentVariableEntity,
        ): String? {
            val decrypted = runCatchingNonCancellation {
                material.decryptEnvironmentValue(variable)
            }.getOrNull() ?: return null
            return when (decrypted) {
                is DecryptionResult.Plaintext -> runCatching {
                    decrypted.value.decodeToString(throwOnInvalidSequence = true)
                }.getOrNull()
                DecryptionResult.KeyUnavailable,
                DecryptionResult.AuthenticationFailed,
                DecryptionResult.UnsupportedFormat,
                -> null
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
}
