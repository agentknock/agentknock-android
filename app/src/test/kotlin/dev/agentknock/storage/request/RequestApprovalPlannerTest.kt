package dev.agentknock.storage.request

import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
import dev.agentknock.storage.approval.ApprovalPolicyEvaluator
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestApprovalPlannerTest {
    @Test
    fun approvedSourceDistinguishesPolicyTemporaryAiMixedAndNonSensitive() {
        val policy = policy("policy", SecretApprovalMode.APPROVE)
        val temporary = policy(
            "temporary",
            SecretApprovalMode.ASK_ME,
            temporaryAccessExpiresAt = 8_000L,
        )
        val ai = policy("ai", SecretApprovalMode.ASK_AI)

        assertEquals(
            DECISION_SOURCE_POLICY,
            approvedPlan(listOf(policy)).decisionSource,
        )
        assertEquals(
            DECISION_SOURCE_TEMPORARY_ACCESS,
            approvedPlan(listOf(temporary)).decisionSource,
        )
        assertEquals(
            DECISION_SOURCE_AI,
            approvedPlan(listOf(ai), AiReviewDecision.APPROVE).decisionSource,
        )
        val mixed = approvedPlan(
            listOf(temporary, ai),
            AiReviewDecision.APPROVE,
        )
        assertEquals(DECISION_SOURCE_MIXED, mixed.decisionSource)
        assertTrue(mixed.aiApprovalUsed)
        assertEquals(
            DECISION_SOURCE_NON_SENSITIVE,
            planAutomaticApproval(
                outcome = AutomaticApprovalOutcome.APPROVED,
                evaluation = null,
                aiDecision = null,
                nonSensitive = true,
            ).decisionSource,
        )
    }

    @Test
    fun deniedAndActionRequiredPlansDoNotInventAReason() {
        val policyDenial = planAutomaticApproval(
            outcome = AutomaticApprovalOutcome.DENIED,
            evaluation = null,
            aiDecision = null,
            denialSource = AutomaticApprovalDenialSource.POLICY,
        )
        assertEquals(ApprovalDecision.DENIED, policyDenial.decision)
        assertEquals(DECISION_SOURCE_POLICY, policyDenial.decisionSource)

        val aiDenial = planAutomaticApproval(
            outcome = AutomaticApprovalOutcome.DENIED,
            evaluation = null,
            aiDecision = AiReviewDecision.DENY,
            denialSource = AutomaticApprovalDenialSource.AI,
        )
        assertEquals(DECISION_SOURCE_AI, aiDenial.decisionSource)

        val unrelatedFailure = planAutomaticApproval(
            outcome = AutomaticApprovalOutcome.DENIED,
            evaluation = null,
            aiDecision = null,
        )
        assertNull(unrelatedFailure.decisionSource)

        val actionRequired = planAutomaticApproval(
            outcome = AutomaticApprovalOutcome.ACTION_REQUIRED,
            evaluation = null,
            aiDecision = null,
        )
        assertNull(actionRequired.decision)
        assertNull(actionRequired.decisionSource)
    }

    @Test
    fun temporaryPlanCanGrantOneOrManyEligibleSecrets() {
        val temporary = policy("temporary", SecretApprovalMode.ASK_ME)
        val ai = policy("ai", SecretApprovalMode.ASK_AI)
        val policies = listOf(temporary, ai)
        val stored = evaluation(policies).copy(
            aiReview = AiReview(decision = AiReviewDecision.ASK_USER),
        )

        val plan = checkNotNull(planTemporaryAccess(policies, stored, now = 1_000L))

        assertEquals(policies, plan.policies)
        assertEquals(1_000L + TEMPORARY_ACCESS_DURATION_MILLIS, plan.expiresAt)
        assertTrue(plan.evaluation.secrets.all { it.temporaryAccessExpiresAt == plan.expiresAt })
        assertEquals(DECISION_SOURCE_TEMPORARY_ACCESS, plan.decisionSource)
    }

    @Test
    fun temporaryPlanReportsMixedWhenOtherSecretsKeepOneTimeOrAiApproval() {
        val temporary = policy("temporary", SecretApprovalMode.ASK_ME)
        val ai = policy("ai", SecretApprovalMode.ASK_AI)
        val policies = listOf(temporary, ai)
        val stored = evaluation(policies).copy(
            aiReview = AiReview(decision = AiReviewDecision.APPROVE),
        )

        val plan = checkNotNull(planTemporaryAccess(policies, stored, now = 2_000L))

        assertEquals(listOf(temporary), plan.policies)
        assertEquals(DECISION_SOURCE_MIXED, plan.decisionSource)
        assertEquals(plan.expiresAt, plan.evaluation.secrets[0].temporaryAccessExpiresAt)
        assertNull(plan.evaluation.secrets[1].temporaryAccessExpiresAt)
    }

    @Test
    fun failedAiReviewCanEscalateToTemporaryAccess() {
        val policy = policy("ai", SecretApprovalMode.ASK_AI)
        val stored = evaluation(listOf(policy)).copy(
            aiReview = AiReview(failure = AiReviewFailure.UNAVAILABLE),
        )

        val plan = checkNotNull(planTemporaryAccess(listOf(policy), stored, now = 2_000L))

        assertEquals(listOf(policy), plan.policies)
        assertEquals(DECISION_SOURCE_TEMPORARY_ACCESS, plan.decisionSource)
    }

    @Test
    fun temporaryPlanRejectsChangedOrIneligiblePolicyState() {
        val original = policy("secret", SecretApprovalMode.ASK_ME)
        val stored = evaluation(listOf(original))
        assertNull(
            planTemporaryAccess(
                policies = listOf(original.copy(revision = original.revision + 1)),
                storedEvaluation = stored,
                now = 3_000L,
            ),
        )
        assertNull(
            planTemporaryAccess(
                policies = listOf(policy("secret", SecretApprovalMode.APPROVE)),
                storedEvaluation = evaluation(
                    listOf(policy("secret", SecretApprovalMode.APPROVE)),
                ),
                now = 3_000L,
            ),
        )
    }

    private fun approvedPlan(
        policies: List<SecretApprovalPolicy>,
        aiDecision: AiReviewDecision? = null,
    ): AutomaticApprovalPlan = planAutomaticApproval(
        outcome = AutomaticApprovalOutcome.APPROVED,
        evaluation = evaluation(policies),
        aiDecision = aiDecision,
    )

    private fun evaluation(policies: List<SecretApprovalPolicy>) =
        ApprovalPolicyEvaluator.evaluate(policies.map { it.toRequestedSecretApproval() })

    private fun policy(
        id: String,
        mode: SecretApprovalMode,
        temporaryAccessExpiresAt: Long? = null,
    ) = SecretApprovalPolicy(
        secretId = id,
        secretName = id,
        mode = mode,
        instructions = "",
        revision = 1L,
        temporaryAccessExpiresAt = temporaryAccessExpiresAt,
    )
}
