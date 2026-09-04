package dev.agentknock.presentation

import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
import dev.agentknock.storage.request.ApprovalDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalPresentationTest {
    @Test fun pendingEscalation_isNotPresentedAsAnAiApproval() {
        val review = AiReview(AiReviewDecision.ASK_USER, "Confirm the production change.")
        assertEquals("AI asked you to decide", approvalSummary(review, null, null))
        assertEquals("You approved", approvalSummary(review, ApprovalDecision.APPROVED, "user"))
        assertEquals("You denied", approvalSummary(review, ApprovalDecision.DENIED, "user"))
    }

    @Test fun aiResultsAndFailures_remainDistinct() {
        assertEquals("AI approved", aiReviewLabel(AiReview(AiReviewDecision.APPROVE)))
        assertEquals("AI denied", aiReviewLabel(AiReview(AiReviewDecision.DENY)))
        assertEquals("AI subscription required", aiReviewLabel(AiReview(failure = AiReviewFailure.SUBSCRIPTION_REQUIRED)))
        assertEquals("AI review unavailable", aiReviewLabel(AiReview(failure = AiReviewFailure.UNAVAILABLE)))
        assertNull(approvalSummary(null, null, null))
    }

    @Test fun deterministicAndTemporaryDecisions_doNotImplyAiReview() {
        assertEquals("Approval settings", approvalSummary(null, ApprovalDecision.APPROVED, "policy"))
        assertEquals("Temporary access", approvalSummary(null, ApprovalDecision.APPROVED, "temporary_access"))
        assertEquals("No approval needed", approvalSummary(null, ApprovalDecision.APPROVED, "non_sensitive"))
    }
}
