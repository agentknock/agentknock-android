package dev.agentknock.storage.approval

import kotlinx.serialization.Serializable

@Serializable
internal enum class ApprovalAction {
    DENY,
    ASK_ME,
    ASK_AI,
    APPROVE,
}

@Serializable
internal data class SecretApprovalEvaluation(
    val secretId: String,
    val secretName: String,
    val action: ApprovalAction,
    val temporaryAccessEligible: Boolean = false,
    val temporaryAccessExpiresAt: Long? = null,
    val revision: Long? = null,
)

@Serializable
internal enum class AiReviewDecision {
    APPROVE,
    DENY,
    ASK_USER,
}

@Serializable
internal enum class AiReviewFailure {
    SUBSCRIPTION_REQUIRED,
    RELAY_REJECTED,
    UNAVAILABLE,
    INVALID_RESPONSE,
}

@Serializable
internal data class AiReview(
    val decision: AiReviewDecision? = null,
    val explanation: String? = null,
    val failure: AiReviewFailure? = null,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    // Carried to the audit event only; never persisted as approval state.
    @kotlinx.serialization.Transient val failureDiagnostic: String? = null,
)

@Serializable
internal data class ApprovalEvaluation(
    val secrets: List<SecretApprovalEvaluation>,
    val aiReview: AiReview? = null,
)

internal fun ApprovalEvaluation.requiresAiReview(): Boolean =
    secrets.none { it.action == ApprovalAction.DENY } &&
        secrets.any { it.action == ApprovalAction.ASK_AI }

internal fun ApprovalEvaluation.requiresInvocationAiReview(): Boolean =
    secrets.none {
        it.action == ApprovalAction.DENY || it.action == ApprovalAction.ASK_ME
    } && secrets.any { it.action == ApprovalAction.ASK_AI }

internal fun ApprovalEvaluation.isFullyApproved(aiDecision: AiReviewDecision?): Boolean =
    secrets.all { secret ->
        secret.action == ApprovalAction.APPROVE ||
            (secret.action == ApprovalAction.ASK_AI && aiDecision == AiReviewDecision.APPROVE)
    }
