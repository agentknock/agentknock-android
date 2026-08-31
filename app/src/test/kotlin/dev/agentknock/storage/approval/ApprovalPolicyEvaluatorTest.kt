package dev.agentknock.storage.approval

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalPolicyEvaluatorTest {
    @Test
    fun `each requested secret keeps its effective approval mode`() {
        val evaluation = ApprovalPolicyEvaluator.evaluate(
            listOf(
                secret("github", ApprovalAction.APPROVE),
                secret("production", ApprovalAction.ASK_AI),
            ),
        )

        assertEquals(
            listOf(ApprovalAction.APPROVE, ApprovalAction.ASK_AI),
            evaluation.secrets.map(SecretApprovalEvaluation::action),
        )
    }

    @Test
    fun `manual scope does not suppress AI review for another secret`() {
        val evaluation = ApprovalPolicyEvaluator.evaluate(
            listOf(
                secret("manual", ApprovalAction.ASK_ME),
                secret("reviewed", ApprovalAction.ASK_AI),
            ),
        )

        assertEquals(true, evaluation.requiresAiReview())
        assertEquals(false, evaluation.isFullyApproved(AiReviewDecision.APPROVE))
    }

    @Test
    fun `denial suppresses AI review`() {
        val evaluation = ApprovalPolicyEvaluator.evaluate(
            listOf(
                secret("blocked", ApprovalAction.DENY),
                secret("reviewed", ApprovalAction.ASK_AI),
            ),
        )

        assertEquals(false, evaluation.requiresAiReview())
        assertEquals(false, evaluation.isFullyApproved(AiReviewDecision.APPROVE))
    }

    @Test
    fun `temporary access metadata remains attached to its secret`() {
        val evaluation = ApprovalPolicyEvaluator.evaluate(
            listOf(
                RequestedSecretApproval(
                    id = "github-id",
                    name = "github",
                    defaultAction = ApprovalAction.APPROVE,
                    temporaryAccessEligible = true,
                    temporaryAccessExpiresAt = 1234,
                    revision = 5678,
                ),
            ),
        )

        assertEquals(true, evaluation.isFullyApproved(null))
        assertEquals(true, evaluation.secrets.single().temporaryAccessEligible)
        assertEquals(1234L, evaluation.secrets.single().temporaryAccessExpiresAt)
        assertEquals(5678L, evaluation.secrets.single().revision)
    }

    private fun secret(name: String, action: ApprovalAction) = RequestedSecretApproval(
        id = "$name-id",
        name = name,
        defaultAction = action,
    )
}
