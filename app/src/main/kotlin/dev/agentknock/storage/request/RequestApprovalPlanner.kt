package dev.agentknock.storage.request

import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretApprovalPolicy

internal enum class AutomaticApprovalOutcome {
    APPROVED,
    DENIED,
    ACTION_REQUIRED,
}

internal enum class AutomaticApprovalDenialSource {
    POLICY,
    AI,
}

internal data class AutomaticApprovalPlan(
    val decision: ApprovalDecision?,
    val decisionSource: String?,
    val aiApprovalUsed: Boolean,
)

internal data class TemporaryAccessPlan(
    val policies: List<SecretApprovalPolicy>,
    val expiresAt: Long,
    val evaluation: ApprovalEvaluation,
    val decisionSource: String,
)

/**
 * Derives only the approval outcome metadata shared by invocation and signing operations. Producing
 * a response, validating request contents, and deciding why an unrelated failure was denied remain
 * responsibilities of each operation handler.
 */
internal fun planAutomaticApproval(
    outcome: AutomaticApprovalOutcome,
    evaluation: ApprovalEvaluation?,
    aiDecision: AiReviewDecision?,
    nonSensitive: Boolean = false,
    denialSource: AutomaticApprovalDenialSource? = null,
): AutomaticApprovalPlan {
    require(outcome == AutomaticApprovalOutcome.APPROVED || !nonSensitive) {
        "Only an approved request can be classified as non-sensitive"
    }
    require(outcome == AutomaticApprovalOutcome.DENIED || denialSource == null) {
        "Only a denied request can have a denial source"
    }
    val temporaryAccessUsed =
        evaluation?.secrets?.any { it.temporaryAccessExpiresAt != null } == true
    val aiApprovalUsed =
        aiDecision == AiReviewDecision.APPROVE &&
            evaluation?.secrets?.any { it.action == ApprovalAction.ASK_AI } == true
    val decision =
        when (outcome) {
            AutomaticApprovalOutcome.APPROVED -> ApprovalDecision.APPROVED
            AutomaticApprovalOutcome.DENIED -> ApprovalDecision.DENIED
            AutomaticApprovalOutcome.ACTION_REQUIRED -> null
        }
    val decisionSource =
        when (outcome) {
            AutomaticApprovalOutcome.APPROVED ->
                when {
                    nonSensitive -> DECISION_SOURCE_NON_SENSITIVE
                    temporaryAccessUsed && aiApprovalUsed -> DECISION_SOURCE_MIXED
                    temporaryAccessUsed -> DECISION_SOURCE_TEMPORARY_ACCESS
                    aiApprovalUsed -> DECISION_SOURCE_AI
                    else -> DECISION_SOURCE_POLICY
                }
            AutomaticApprovalOutcome.DENIED ->
                when (denialSource) {
                    AutomaticApprovalDenialSource.POLICY -> DECISION_SOURCE_POLICY
                    AutomaticApprovalDenialSource.AI -> DECISION_SOURCE_AI
                    null -> null
                }
            AutomaticApprovalOutcome.ACTION_REQUIRED -> null
        }
    return AutomaticApprovalPlan(
        decision = decision,
        decisionSource = decisionSource,
        aiApprovalUsed = aiApprovalUsed,
    )
}

/**
 * Plans the grants represented by a user's "allow temporarily" decision. Callers still persist the
 * grants and request decision together so current policy revisions can be checked atomically.
 */
internal fun planTemporaryAccess(
    policies: List<SecretApprovalPolicy>,
    storedEvaluation: ApprovalEvaluation,
    now: Long,
): TemporaryAccessPlan? {
    val currentEvaluation = ApprovalEvaluation(policies.map(SecretApprovalPolicy::evaluate))
    if (!storedEvaluation.hasSameSecretPolicies(currentEvaluation)) return null

    val evaluationsById = storedEvaluation.secrets.associateBy { it.secretId }
    val aiCanEscalateToTemporaryAccess =
        storedEvaluation.aiReview?.decision == AiReviewDecision.ASK_USER ||
            storedEvaluation.aiReview?.failure != null ||
            storedEvaluation.aiReview == null
    val grantablePolicies = policies.filter { policy ->
        val evaluation = evaluationsById[policy.secretId]
        evaluation?.temporaryAccessExpiresAt == null &&
            when (policy.mode) {
                SecretApprovalMode.ASK_ME -> evaluation?.action == ApprovalAction.ASK_ME
                SecretApprovalMode.ASK_AI ->
                    evaluation?.action == ApprovalAction.ASK_AI && aiCanEscalateToTemporaryAccess
                else -> false
            }
    }
    if (grantablePolicies.isEmpty()) return null

    val expiresAt = now + TEMPORARY_ACCESS_DURATION_MILLIS
    val grantedIds = grantablePolicies.map(SecretApprovalPolicy::secretId).toSet()
    val alsoApprovedByAi =
        storedEvaluation.aiReview?.decision == AiReviewDecision.APPROVE &&
            storedEvaluation.secrets.any { evaluation ->
                evaluation.action == ApprovalAction.ASK_AI && evaluation.secretId !in grantedIds
            }
    val alsoApprovedOnce =
        storedEvaluation.secrets.any { evaluation ->
            evaluation.action == ApprovalAction.ASK_ME && evaluation.secretId !in grantedIds
        }
    return TemporaryAccessPlan(
        policies = grantablePolicies,
        expiresAt = expiresAt,
        evaluation =
            storedEvaluation.copy(
                secrets =
                    storedEvaluation.secrets.map { evaluation ->
                        if (evaluation.secretId in grantedIds) {
                            evaluation.copy(temporaryAccessExpiresAt = expiresAt)
                        } else {
                            evaluation
                        }
                    }
            ),
        decisionSource =
            if (alsoApprovedByAi || alsoApprovedOnce) {
                DECISION_SOURCE_MIXED
            } else {
                DECISION_SOURCE_TEMPORARY_ACCESS
            },
    )
}
