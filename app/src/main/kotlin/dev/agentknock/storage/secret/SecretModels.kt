package dev.agentknock.storage.secret

import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.protocol.SshSignatureAlgorithm
import kotlinx.serialization.Serializable

internal enum class SecretType(val storedName: String) {
    ENVIRONMENT("environment"),
    SSH("ssh"),
    ;

    companion object {
        fun fromStoredName(value: String): SecretType = requireNotNull(fromStoredNameOrNull(value)) {
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

internal fun SshKeyAlgorithm.canonicalPrivateKeyFormat(): String = when (this) {
    SshKeyAlgorithm.ED25519 -> ED25519_PRIVATE_KEY_FORMAT
    SshKeyAlgorithm.RSA -> RSA_PRIVATE_KEY_FORMAT
}

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
    val type: SecretType,
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
    val publicKey: String,
    val fingerprint: String,
    val comment: String,
    val privateKeyAvailable: Boolean,
)

internal data class EnvironmentVariableMetadata(
    val id: String,
    val secretId: String,
    val name: String,
    val sensitive: Boolean,
    val notes: String,
    val valueAvailable: Boolean,
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
    data class Valid(val summary: EnvironmentSecretUploadSummary) :
        EnvironmentSecretUploadResult
    data class Invalid(
        val message: String,
        val target: SecretUploadTarget? = null,
    ) : EnvironmentSecretUploadResult
}

internal sealed interface ApplyEnvironmentSecretUploadResult {
    data class Applied(val secretId: String) : ApplyEnvironmentSecretUploadResult
    data class Invalid(val message: String) : ApplyEnvironmentSecretUploadResult
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

internal sealed interface ApplySshSecretUploadResult {
    data class Applied(val secretId: String) : ApplySshSecretUploadResult

    data class Invalid(val message: String) : ApplySshSecretUploadResult
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

internal data class SecretReviewMetadata(
    val id: String,
    val revision: Long = 1,
    val name: String,
    val type: String,
    val environmentVariables: List<EnvironmentVariableReviewMetadata>,
    val environmentVariableDestinations: Map<String, EnvironmentVariableReviewDestination> =
        emptyMap(),
)

internal sealed interface EnvironmentVariableReviewDestination {
    data class Environment(val name: String) : EnvironmentVariableReviewDestination

    data object Omitted : EnvironmentVariableReviewDestination

    data object StandardInput : EnvironmentVariableReviewDestination
}

internal data class EnvironmentVariableReviewMetadata(
    val name: String,
    val sensitive: Boolean,
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

internal sealed interface GitSignatureResult {
    data class Signed(val signature: String) : GitSignatureResult

    data object NotFound : GitSignatureResult

    data object WrongType : GitSignatureResult

    data object KeyChanged : GitSignatureResult

    data object SecretUnavailable : GitSignatureResult

    data object SecretCorrupted : GitSignatureResult

    data object UnsupportedEncryption : GitSignatureResult
}

internal sealed interface SshAuthenticationSignatureResult {
    data class Signed(val signature: ByteArray) : SshAuthenticationSignatureResult

    data object NotFound : SshAuthenticationSignatureResult
    data object WrongType : SshAuthenticationSignatureResult
    data object KeyChanged : SshAuthenticationSignatureResult
    data object SecretUnavailable : SshAuthenticationSignatureResult
    data object SecretCorrupted : SshAuthenticationSignatureResult
    data object UnsupportedEncryption : SshAuthenticationSignatureResult
}
