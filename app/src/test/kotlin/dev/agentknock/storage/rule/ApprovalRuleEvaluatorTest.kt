package dev.agentknock.storage.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalRuleEvaluatorTest {
    @Test
    fun `a deny for one secret denies a request containing additional secrets`() {
        val evaluation = ApprovalRuleEvaluator.evaluate(
            rules = listOf(rule("deny-gh", ApprovalRuleAction.DENY, setOf("gh"))),
            request = request("gh", "aws"),
            now = NOW,
        )

        assertEquals(ApprovalRuleAction.DENY, evaluation.action)
        assertEquals(
            listOf(ApprovalRuleAction.DENY, ApprovalRuleAction.ASK_ME),
            evaluation.secrets.map(SecretRuleEvaluation::action),
        )
    }

    @Test
    fun `every requested secret must be approved`() {
        val evaluation = ApprovalRuleEvaluator.evaluate(
            rules = listOf(rule("approve-gh", ApprovalRuleAction.APPROVE, setOf("gh"))),
            request = request("gh", "aws"),
            now = NOW,
        )

        assertEquals(ApprovalRuleAction.ASK_ME, evaluation.action)
    }

    @Test
    fun `approvals compose across independently targeted secrets`() {
        val evaluation = ApprovalRuleEvaluator.evaluate(
            rules = listOf(
                rule("approve-gh", ApprovalRuleAction.APPROVE, setOf("gh")),
                rule("approve-aws", ApprovalRuleAction.APPROVE, setOf("aws")),
            ),
            request = request("gh", "aws"),
            now = NOW,
        )

        assertEquals(ApprovalRuleAction.APPROVE, evaluation.action)
    }

    @Test
    fun `one rule can independently approve each selected secret`() {
        val evaluation = ApprovalRuleEvaluator.evaluate(
            rules = listOf(rule("approve-both", ApprovalRuleAction.APPROVE, setOf("gh", "aws"))),
            request = request("aws", "gh"),
            now = NOW,
        )

        assertEquals(ApprovalRuleAction.APPROVE, evaluation.action)
        assertTrue(evaluation.secrets.all { it.decisiveRuleIds == setOf("approve-both") })
    }

    @Test
    fun `the lowest action wins independent of rule order`() {
        val approve = rule("approve", ApprovalRuleAction.APPROVE, setOf("gh"))
        val askAi = rule("ai", ApprovalRuleAction.ASK_AI, setOf("gh"))
        val ask = rule("ask", ApprovalRuleAction.ASK_ME, setOf("gh"))
        val deny = rule("deny", ApprovalRuleAction.DENY, setOf("gh"))

        listOf(
            listOf(approve, askAi, ask, deny),
            listOf(deny, ask, askAi, approve),
            listOf(askAi, approve, deny, ask),
        ).forEach { rules ->
            assertEquals(
                ApprovalRuleAction.DENY,
                ApprovalRuleEvaluator.evaluate(rules, request("gh"), NOW).action,
            )
        }
    }

    @Test
    fun `ask me takes precedence over ask AI and ask AI over approve`() {
        val approve = rule("approve", ApprovalRuleAction.APPROVE, setOf("gh"))
        val askAi = rule("ai", ApprovalRuleAction.ASK_AI, setOf("gh"))
        val ask = rule("ask", ApprovalRuleAction.ASK_ME, setOf("gh"))

        assertEquals(
            ApprovalRuleAction.ASK_AI,
            ApprovalRuleEvaluator.evaluate(listOf(approve, askAi), request("gh"), NOW).action,
        )
        assertEquals(
            ApprovalRuleAction.ASK_ME,
            ApprovalRuleEvaluator.evaluate(listOf(approve, askAi, ask), request("gh"), NOW).action,
        )
    }

    @Test
    fun `prefix matching uses complete ordered tokens`() {
        val prefix = rule(
            id = "prefix",
            action = ApprovalRuleAction.APPROVE,
            secrets = setOf("gh"),
            command = listOf("gh", "issue"),
            commandMatch = CommandMatch.PREFIX,
        )

        assertEquals(
            ApprovalRuleAction.APPROVE,
            ApprovalRuleEvaluator.evaluate(
                listOf(prefix),
                request("gh", command = listOf("gh", "issue", "view", "234")),
                NOW,
            ).action,
        )
        assertEquals(
            ApprovalRuleAction.ASK_ME,
            ApprovalRuleEvaluator.evaluate(
                listOf(prefix),
                request("gh", command = listOf("gh", "issues")),
                NOW,
            ).action,
        )
        assertEquals(
            ApprovalRuleAction.ASK_ME,
            ApprovalRuleEvaluator.evaluate(
                listOf(prefix),
                request("gh", command = listOf("/usr/bin/gh", "issue")),
                NOW,
            ).action,
        )
    }

    @Test
    fun `exact matching rejects additional arguments`() {
        val exact = rule(
            id = "exact",
            action = ApprovalRuleAction.APPROVE,
            secrets = setOf("gh"),
            command = listOf("gh", "issue"),
            commandMatch = CommandMatch.EXACT,
        )

        assertEquals(
            ApprovalRuleAction.ASK_ME,
            ApprovalRuleEvaluator.evaluate(
                listOf(exact),
                request("gh", command = listOf("gh", "issue", "view")),
                NOW,
            ).action,
        )
    }

    @Test
    fun `a rule for another client does not match`() {
        val otherClient = rule("other", ApprovalRuleAction.APPROVE, setOf("gh"))
            .copy(clientId = "another-client")

        assertEquals(
            ApprovalRuleAction.ASK_ME,
            ApprovalRuleEvaluator.evaluate(listOf(otherClient), request("gh"), NOW).action,
        )
    }

    @Test
    fun `captured executable and directory must match exactly`() {
        val captured = rule(
            id = "captured",
            action = ApprovalRuleAction.APPROVE,
            secrets = setOf("gh"),
            executablePath = "/usr/bin/gh",
            executableHash = "hash",
            workingDirectory = "/work/project",
        )

        assertEquals(
            ApprovalRuleAction.APPROVE,
            ApprovalRuleEvaluator.evaluate(listOf(captured), request("gh"), NOW).action,
        )
        assertEquals(
            ApprovalRuleAction.ASK_ME,
            ApprovalRuleEvaluator.evaluate(
                listOf(captured),
                request("gh", executableHash = "other"),
                NOW,
            ).action,
        )
        assertEquals(
            ApprovalRuleAction.ASK_ME,
            ApprovalRuleEvaluator.evaluate(
                listOf(captured),
                request("gh", workingDirectory = "/work/other"),
                NOW,
            ).action,
        )
    }

    @Test
    fun `paused and expired rules do not participate`() {
        val paused = rule("paused", ApprovalRuleAction.APPROVE, setOf("gh")).copy(enabled = false)
        val expired = rule("expired", ApprovalRuleAction.APPROVE, setOf("gh")).copy(expiresAt = NOW)
        val evaluation = ApprovalRuleEvaluator.evaluate(listOf(paused, expired), request("gh"), NOW)

        assertEquals(ApprovalRuleAction.ASK_ME, evaluation.action)
        assertTrue(evaluation.matchedRuleIds.isEmpty())
    }

    private fun rule(
        id: String,
        action: ApprovalRuleAction,
        secrets: Set<String>,
        command: List<String> = listOf("gh", "issue"),
        commandMatch: CommandMatch = CommandMatch.PREFIX,
        executablePath: String? = null,
        executableHash: String? = null,
        workingDirectory: String? = null,
    ) = ApprovalRule(
        id = id,
        name = id,
        action = action,
        clientId = "client",
        secretIds = secrets,
        command = command,
        commandMatch = commandMatch,
        executablePath = executablePath,
        executableHash = executableHash,
        workingDirectory = workingDirectory,
        sourceRequestId = null,
        enabled = true,
        createdAt = NOW,
        updatedAt = NOW,
        expiresAt = null,
        lastMatchedAt = null,
        matchCount = 0,
    )

    private fun request(
        vararg secrets: String,
        command: List<String> = listOf("gh", "issue", "view", "234"),
        executableHash: String? = "hash",
        workingDirectory: String = "/work/project",
    ) = ApprovalRuleRequest(
        clientId = "client",
        secrets = secrets.map { RequestedSecretIdentity(it, it) },
        command = command,
        executablePath = "/usr/bin/gh",
        executableHash = executableHash,
        workingDirectory = workingDirectory,
    )

    private companion object {
        const val NOW = 1_000L
    }
}
