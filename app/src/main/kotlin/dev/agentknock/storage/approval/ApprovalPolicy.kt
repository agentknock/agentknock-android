package dev.agentknock.storage.approval

import kotlinx.serialization.Serializable

@Serializable
internal enum class ApprovalAction(val precedence: Int) {
    DENY(0),
    ASK_ME(1),
    ASK_AI(2),
    APPROVE(3),
}

internal data class RequestedSecretApproval(
    val id: String,
    val name: String,
    val defaultAction: ApprovalAction,
    val temporaryAccessEligible: Boolean = false,
    val temporaryAccessExpiresAt: Long? = null,
    val revision: Long? = null,
)

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
)

@Serializable
internal data class ApprovalEvaluation(
    val action: ApprovalAction,
    val secrets: List<SecretApprovalEvaluation>,
    val aiReview: AiReview? = null,
)

internal object ApprovalPolicyEvaluator {
    fun evaluate(secrets: List<RequestedSecretApproval>): ApprovalEvaluation {
        val secretEvaluations = secrets.map { secret ->
            SecretApprovalEvaluation(
                secretId = secret.id,
                secretName = secret.name,
                action = secret.defaultAction,
                temporaryAccessEligible = secret.temporaryAccessEligible,
                temporaryAccessExpiresAt = secret.temporaryAccessExpiresAt,
                revision = secret.revision,
            )
        }
        return ApprovalEvaluation(
            action = secretEvaluations.minByOrNull { it.action.precedence }?.action
                ?: ApprovalAction.ASK_ME,
            secrets = secretEvaluations,
        )
    }
}

internal fun ApprovalEvaluation.requiresAiReview(): Boolean =
    secrets.none { it.action == ApprovalAction.DENY } &&
        secrets.any { it.action == ApprovalAction.ASK_AI }

internal fun ApprovalEvaluation.isFullyApproved(aiDecision: AiReviewDecision?): Boolean =
    secrets.all { secret ->
        secret.action == ApprovalAction.APPROVE ||
            (secret.action == ApprovalAction.ASK_AI && aiDecision == AiReviewDecision.APPROVE)
    }
