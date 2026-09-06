package dev.agentknock.presentation

import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
import dev.agentknock.storage.request.ApprovalDecision

internal fun aiReviewLabel(review: AiReview): String = when {
    review.decision == AiReviewDecision.ASK_USER -> "AI asked you to decide"
    review.decision == AiReviewDecision.APPROVE -> "AI approved"
    review.decision == AiReviewDecision.DENY -> "AI denied"
    review.failure == AiReviewFailure.SUBSCRIPTION_REQUIRED -> "AI review inactive · Ask me"
    else -> "AI review unavailable"
}

internal fun approvalSummary(
    review: AiReview?,
    decision: ApprovalDecision?,
    source: String?,
): String? = when (source) {
    "user" -> when (decision) {
        ApprovalDecision.APPROVED -> "You approved"
        ApprovalDecision.DENIED -> "You denied"
        null -> null
    }
    "policy", "rule" -> "Approval settings"
    "non_sensitive" -> "No approval needed"
    "temporary_access" -> "Temporary access"
    "mixed" -> "Combined approvals"
    else -> review?.let(::aiReviewLabel)
}
