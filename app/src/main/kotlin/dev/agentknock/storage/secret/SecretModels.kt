package dev.agentknock.storage.secret

import dev.agentknock.protocol.SecretUploadMode
import kotlinx.serialization.Serializable

internal enum class SecretType(val storedName: String) {
    ENVIRONMENT("environment"),
    SSH("ssh");

    companion object {
        fun fromStoredName(value: String): SecretType =
            requireNotNull(fromStoredNameOrNull(value)) {
                "Unknown secret type: $value"
            }

        fun fromStoredNameOrNull(value: String): SecretType? = entries.singleOrNull {
            it.storedName == value
        }
    }
}

internal val ENVIRONMENT_SECRET_TYPE = SecretType.ENVIRONMENT.storedName
internal val SSH_SECRET_TYPE = SecretType.SSH.storedName
internal const val ED25519_PRIVATE_KEY_FORMAT = "ed25519_seed"
internal const val RSA_PRIVATE_KEY_FORMAT = "rsa_pkcs8"

internal fun SshKeyAlgorithm.canonicalPrivateKeyFormat(): String =
    when (this) {
        SshKeyAlgorithm.ED25519 -> ED25519_PRIVATE_KEY_FORMAT
        SshKeyAlgorithm.RSA -> RSA_PRIVATE_KEY_FORMAT
    }

internal enum class SecretApprovalMode(val storedName: String) {
    DENY("deny"),
    ASK_ME("ask_me"),
    ASK_AI("ask_ai"),
    APPROVE("approve"),
}

internal enum class TemporaryAccessOperation(val storedName: String) {
    INVOCATION("invocation"),
    GIT_SIGN("git_sign"),
    SSH_AUTHENTICATE("ssh_authenticate"),
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
    val instructions: String,
    val revision: Long,
    val temporaryAccessExpiresAt: Long? = null,
)

internal data class SecretSummary(
    val id: String,
    val name: String,
    val description: String,
    val type: SecretType,
    val environmentVariableCount: Int,
    val sshKey: SshKeyMetadata?,
    val createdAt: Long,
    val updatedAt: Long,
    val temporaryAccessGrants: List<TemporaryAccessGrant> = emptyList(),
)

internal data class SecretDetails(
    val id: String,
    val name: String,
    val description: String,
    val type: SecretType,
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
    val algorithm: SshKeyAlgorithm,
    val bits: Int,
    val publicKey: String,
    val fingerprint: String,
    val fingerprintHex: String,
    val comment: String,
    val privateKeyAvailable: Boolean,
)

internal data class EnvironmentVariableMetadata(
    val id: String,
    val secretId: String,
    val name: String,
    val sensitive: Boolean,
    val valueAvailable: Boolean,
    val valueUpdatedAt: Long,
)

internal data class EnvironmentVariableInput(
    val name: String,
    val value: String,
    val sensitive: Boolean,
)

internal sealed interface EnvironmentVariableValue {
    data class Available(
        val name: String,
        val value: String,
        val sensitive: Boolean,
    ) : EnvironmentVariableValue

    data class AuthenticationRequired(val name: String) : EnvironmentVariableValue

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

    data object AuthenticationRequired : CreateEnvironmentVariableResult
}

internal enum class SaveEnvironmentVariableResult {
    SAVED,
    NAME_IN_USE,
    NOT_FOUND,
    AUTHENTICATION_REQUIRED,
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
    val privateKey: SshPrivateKey,
)

internal data class EnvironmentSecretUploadSummary(
    val target: SecretUploadTarget?,
    val addedVariables: List<String>,
    val changedVariables: List<String>,
    val unchangedVariables: List<String>,
    val removedVariables: List<String>,
    val variableSensitivity: Map<String, Boolean>,
)

internal sealed interface EnvironmentSecretUploadResult {
    data class Valid(val summary: EnvironmentSecretUploadSummary) : EnvironmentSecretUploadResult

    data class Invalid(
        val message: String,
        val target: SecretUploadTarget? = null,
    ) : EnvironmentSecretUploadResult
}

internal sealed interface ApplySecretUploadResult {
    data class Applied(val secretId: String) : ApplySecretUploadResult

    data class Invalid(val message: String) : ApplySecretUploadResult
}

internal data class SshSecretUploadSummary(
    val target: SecretUploadTarget?,
    val publicKey: String,
    val fingerprint: String,
    val previousPublicKey: String?,
    val previousFingerprint: String?,
    val keyChanged: Boolean,
)

internal sealed interface SshSecretUploadResult {
    data class Valid(val summary: SshSecretUploadSummary) : SshSecretUploadResult

    data class Invalid(
        val message: String,
        val target: SecretUploadTarget? = null,
    ) : SshSecretUploadResult
}

internal data class SecretUploadTarget(
    val secretId: String,
    val revision: Long,
)

@Serializable
internal data class SecretMetadata(
    val name: String,
    val description: String,
    val type: String,
    val environmentVariableNames: List<String> = emptyList(),
    val environmentVariableRename: Map<String, String> = emptyMap(),
    val environmentVariableStdin: String? = null,
    val sshPublicKey: String? = null,
)

internal data class RequestedSecretDescription(
    val secrets: List<SecretMetadata>,
    val reviewMetadata: List<SecretReviewMetadata>,
    val missingSecrets: List<String>,
    val containsSensitiveMaterial: Boolean,
)

internal fun SecretReviewMetadata.containsSelectedSensitiveEnvironmentValue(): Boolean =
    type == ENVIRONMENT_SECRET_TYPE &&
        environmentVariables.any { variable ->
            variable.sensitive &&
                variable.destination != EnvironmentVariableReviewDestination.Omitted
        }

internal data class SecretReviewMetadata(
    val id: String,
    val revision: Long = 1,
    val name: String,
    val type: String,
    val environmentVariables: List<EnvironmentVariableReviewMetadata>,
)

internal sealed interface EnvironmentVariableReviewDestination {
    data class Environment(val name: String) : EnvironmentVariableReviewDestination

    data object Omitted : EnvironmentVariableReviewDestination

    data object StandardInput : EnvironmentVariableReviewDestination
}

internal data class EnvironmentVariableReviewMetadata(
    val name: String,
    val sensitive: Boolean,
    val destination: EnvironmentVariableReviewDestination,
)

internal data class EnvironmentVariableSelection(
    val only: Set<String>? = null,
    val omit: Set<String> = emptySet(),
    val rename: Map<String, String> = emptyMap(),
    val stdin: String? = null,
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

    data class MissingEnvironmentVariables(
        val secretName: String,
        val names: List<String>,
    ) : RequestedSecretsResult

    data class EnvironmentOptionsForSshSecret(val secretName: String) : RequestedSecretsResult

    data object MultipleSshKeys : RequestedSecretsResult

    data object UnsupportedSecretType : RequestedSecretsResult

    data object SecretUnavailable : RequestedSecretsResult

    data object SecretCorrupted : RequestedSecretsResult

    data object UnsupportedEncryption : RequestedSecretsResult
}

internal sealed interface SignatureResult<out T> {
    data class Signed<T>(val signature: T) : SignatureResult<T>

    data object NotFound : SignatureResult<Nothing>

    data object WrongType : SignatureResult<Nothing>

    data object KeyChanged : SignatureResult<Nothing>

    data object SecretUnavailable : SignatureResult<Nothing>

    data object SecretCorrupted : SignatureResult<Nothing>

    data object UnsupportedEncryption : SignatureResult<Nothing>
}
