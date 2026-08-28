package dev.agentknock.storage.approval

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalPolicyEvaluatorTest {
    @Test
    fun `stored evaluations from before AI review decode without migration`() {
        val evaluation = Json.decodeFromString<ApprovalEvaluation>(
            """{
                "action":"ASK_AI",
                "secrets":[{
                    "secretId":"id",
                    "secretName":"github",
                    "action":"ASK_AI",
                    "matchedRuleIds":["old-rule"],
                    "decisiveRuleIds":["old-rule"]
                }],
                "invalidRuleData":false
            }""".trimIndent(),
        )

        assertEquals(ApprovalAction.ASK_AI, evaluation.action)
        assertEquals(setOf("old-rule"), evaluation.secrets.single().matchedRuleIds)
        assertEquals(setOf("old-rule"), evaluation.secrets.single().decisiveRuleIds)
        assertNull(evaluation.aiReview)
    }

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
    fun `least permissive effective mode decides an atomic request`() {
        val evaluation = ApprovalPolicyEvaluator.evaluate(
            listOf(
                secret("automatic", ApprovalAction.APPROVE),
                secret("reviewed", ApprovalAction.ASK_AI),
                secret("manual", ApprovalAction.ASK_ME),
                secret("blocked", ApprovalAction.DENY),
            ),
        )

        assertEquals(ApprovalAction.DENY, evaluation.action)
    }

    @Test
    fun `empty protected request fails to manual review`() {
        assertEquals(
            ApprovalAction.ASK_ME,
            ApprovalPolicyEvaluator.evaluate(emptyList()).action,
        )
    }

    private fun secret(name: String, action: ApprovalAction) = RequestedSecretApproval(
        id = "$name-id",
        name = name,
        defaultAction = action,
    )
}
