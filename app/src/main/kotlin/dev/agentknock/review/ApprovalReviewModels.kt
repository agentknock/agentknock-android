package dev.agentknock.review

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ApprovalReviewRequest(
    val instructions: ApprovalReviewInstructions,
    val facts: ApprovalReviewFacts,
    val evidence: ApprovalReviewEvidence,
    @SerialName("parent_facts") val parentFacts: ApprovalReviewParentFacts? = null,
    @SerialName("parent_evidence") val parentEvidence: ApprovalReviewEvidence? = null,
)

@Serializable
internal data class ApprovalReviewInstructions(
    val general: String,
    val client: String,
    val secrets: Map<String, String>,
)

@Serializable
internal data class ApprovalReviewFacts(
    val client: String,
    val operation: ApprovalReviewOperation,
    val secret: String? = null,
    val secrets: Map<String, ApprovalReviewSecretFacts>? = null,
)

@Serializable
internal data class ApprovalReviewParentFacts(
    val operation: ApprovalReviewOperation,
    @SerialName("elapsed_seconds") val elapsedSeconds: Long,
    val secrets: Map<String, ApprovalReviewSecretFacts>,
)

@Serializable
internal enum class ApprovalReviewOperation {
    @SerialName("invocation") INVOCATION,
    @SerialName("git_sign") GIT_SIGN,
    @SerialName("ssh_authenticate") SSH_AUTHENTICATE,
}

@Serializable internal sealed interface ApprovalReviewSecretFacts

@Serializable
@SerialName("environment")
internal data class ApprovalReviewEnvironmentSecretFacts(
    val variables: Map<String, ApprovalReviewEnvironmentVariableFacts>
) : ApprovalReviewSecretFacts

@Serializable
internal data class ApprovalReviewEnvironmentVariableFacts(
    val delivery: ApprovalReviewEnvironmentDelivery,
    val target: String? = null,
    val value: String? = null,
) {
    init {
        require((delivery == ApprovalReviewEnvironmentDelivery.ENVIRONMENT) == (target != null)) {
            "Environment delivery requires exactly one target"
        }
    }
}

@Serializable
internal enum class ApprovalReviewEnvironmentDelivery {
    @SerialName("environment") ENVIRONMENT,
    @SerialName("standard_input") STANDARD_INPUT,
    @SerialName("omitted") OMITTED,
}

@Serializable
@SerialName("ssh")
internal data object ApprovalReviewSshSecretFacts : ApprovalReviewSecretFacts

@Serializable
internal data class ApprovalReviewEvidence(
    val reason: String? = null,
    val command: ApprovalReviewCommandEvidence? = null,
    @SerialName("signed_content") val signedContent: String? = null,
    val repository: ApprovalReviewGitRepositoryEvidence? = null,
    @SerialName("ssh_authentication")
    val sshAuthentication: ApprovalReviewSshAuthenticationEvidence? = null,
)

@Serializable
internal data class ApprovalReviewSshAuthenticationEvidence(
    val username: String,
    val method: String,
    val algorithm: String,
    @SerialName("host_key_algorithm") val hostKeyAlgorithm: String? = null,
    @SerialName("host_key_fingerprint") val hostKeyFingerprint: String? = null,
)

@Serializable
internal data class ApprovalReviewCommandEvidence(
    val argv: List<String>,
    @SerialName("working_directory") val workingDirectory: String,
    @SerialName("resolved_executable") val resolvedExecutable: String,
    @SerialName("launcher_chain") val launcherChain: List<String>,
    @SerialName("script_contents") val scriptContents: String? = null,
)

@Serializable
internal data class ApprovalReviewGitRepositoryEvidence(
    val remote: String? = null,
    val worktree: String? = null,
    val head: ApprovalReviewGitHeadEvidence? = null,
    @SerialName("changed_path_count") val changedPathCount: Long? = null,
    @SerialName("changed_paths")
    val changedPaths: List<ApprovalReviewGitChangedPathEvidence>? = null,
)

@Serializable
internal data class ApprovalReviewGitHeadEvidence(
    val type: String,
    val name: String? = null,
    val upstream: String? = null,
)

@Serializable
internal data class ApprovalReviewGitChangedPathEvidence(
    val status: String,
    val path: String,
)
