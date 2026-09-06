package dev.agentknock.storage.approval

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalEvaluationTest {
    @Test
    fun `manual approval suppresses invocation AI review for every target`() {
        val evaluation = ApprovalEvaluation(
            listOf(
                secret("manual", ApprovalAction.ASK_ME),
                secret("reviewed", ApprovalAction.ASK_AI),
            ),
        )

        assertEquals(false, evaluation.requiresInvocationAiReview())
        assertEquals(false, evaluation.isFullyApproved(AiReviewDecision.APPROVE))
    }

    @Test
    fun `denial suppresses AI review`() {
        val evaluation = ApprovalEvaluation(
            listOf(
                secret("blocked", ApprovalAction.DENY),
                secret("reviewed", ApprovalAction.ASK_AI),
            ),
        )

        assertEquals(false, evaluation.requiresInvocationAiReview())
        assertEquals(false, evaluation.isFullyApproved(AiReviewDecision.APPROVE))
    }

    @Test
    fun `invocation AI review accepts approve and AI targets together`() {
        val evaluation = ApprovalEvaluation(
            listOf(
                secret("automatic", ApprovalAction.APPROVE),
                secret("reviewed", ApprovalAction.ASK_AI),
            ),
        )

        assertEquals(true, evaluation.requiresInvocationAiReview())
    }

    private fun secret(name: String, action: ApprovalAction) = SecretApprovalEvaluation(
        secretId = "$name-id",
        secretName = name,
        action = action,
    )
}
