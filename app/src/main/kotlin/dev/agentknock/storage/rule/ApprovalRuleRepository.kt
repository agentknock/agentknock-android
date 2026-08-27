package dev.agentknock.storage.rule

import dev.agentknock.storage.audit.AuditCategory
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.NoOpAuditSink
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class ApprovalRuleInput(
    val name: String,
    val action: ApprovalRuleAction,
    val clientId: String,
    val secretIds: Set<String>,
    val command: List<String>,
    val commandMatch: CommandMatch,
    val executablePath: String? = null,
    val executableHash: String? = null,
    val workingDirectory: String? = null,
    val sourceRequestId: Long? = null,
    val expiresAt: Long? = null,
)

internal sealed interface SaveApprovalRuleResult {
    data class Saved(val id: String) : SaveApprovalRuleResult
    data object NotFound : SaveApprovalRuleResult
    data class Invalid(val message: String) : SaveApprovalRuleResult
}

internal class ApprovalRuleRepository(
    private val dao: ApprovalRuleDao,
    private val audit: AuditSink = NoOpAuditSink,
    private val json: Json = Json,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    fun observeRules(): Flow<List<ApprovalRule>> = dao.observeRules().map { entities ->
        entities.mapNotNull { entity -> runCatching { entity.toModel() }.getOrNull() }
    }

    fun observeRule(id: String): Flow<ApprovalRule?> = dao.observeRule(id).map { entity ->
        entity?.let { runCatching { it.toModel() }.getOrNull() }
    }

    suspend fun getRule(id: String): ApprovalRule? =
        dao.getRule(id)?.let { runCatching { it.toModel() }.getOrNull() }

    suspend fun getRules(ids: Set<String>): List<ApprovalRule> = ids.mapNotNull { id ->
        getRule(id)
    }

    suspend fun create(
        input: ApprovalRuleInput,
        enabled: Boolean = true,
    ): SaveApprovalRuleResult {
        validate(input)?.let { return SaveApprovalRuleResult.Invalid(it) }
        val now = currentTimeMillis()
        val id = newId()
        dao.insertRule(input.toEntity(id = id, enabled = enabled, createdAt = now, updatedAt = now))
        audit.record(
            AuditRecord(
                category = AuditCategory.RULE,
                title = "Approval rule created",
                detail = input.name,
                outcome = AuditOutcome.CHANGED,
                clientId = input.clientId,
            ),
        )
        return SaveApprovalRuleResult.Saved(id)
    }

    suspend fun save(id: String, input: ApprovalRuleInput): SaveApprovalRuleResult {
        validate(input)?.let { return SaveApprovalRuleResult.Invalid(it) }
        val existing = dao.getRule(id) ?: return SaveApprovalRuleResult.NotFound
        val updated = input.toEntity(
            id = id,
            enabled = existing.enabled,
            createdAt = existing.createdAt,
            updatedAt = currentTimeMillis(),
            lastMatchedAt = existing.lastMatchedAt,
            matchCount = existing.matchCount,
        )
        check(dao.updateRule(updated) == 1)
        audit.record(
            AuditRecord(
                category = AuditCategory.RULE,
                title = "Approval rule updated",
                detail = input.name,
                outcome = AuditOutcome.CHANGED,
                clientId = input.clientId,
            ),
        )
        return SaveApprovalRuleResult.Saved(id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean {
        val existing = dao.getRule(id) ?: return false
        if (existing.enabled == enabled) return true
        check(
            dao.updateRule(
                existing.copy(enabled = enabled, updatedAt = currentTimeMillis()),
            ) == 1,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.RULE,
                title = if (enabled) "Approval rule enabled" else "Approval rule paused",
                detail = existing.name,
                outcome = AuditOutcome.CHANGED,
                clientId = existing.clientId,
            ),
        )
        return true
    }

    suspend fun delete(id: String): Boolean {
        val existing = dao.getRule(id) ?: return false
        if (dao.deleteRule(id) != 1) return false
        audit.record(
            AuditRecord(
                category = AuditCategory.RULE,
                title = "Approval rule deleted",
                detail = existing.name,
                outcome = AuditOutcome.CHANGED,
                clientId = existing.clientId,
            ),
        )
        return true
    }

    suspend fun evaluate(request: ApprovalRuleRequest): ApprovalRuleEvaluation {
        val entities = dao.getEnabledRules()
        val rules = entities.mapNotNull { entity -> runCatching { entity.toModel() }.getOrNull() }
        if (rules.size != entities.size) {
            return ApprovalRuleEvaluation(
                action = ApprovalRuleAction.ASK_ME,
                secrets = request.secrets.map { secret ->
                    SecretRuleEvaluation(
                        secretId = secret.id,
                        secretName = secret.name,
                        action = ApprovalRuleAction.ASK_ME,
                        matchedRuleIds = emptySet(),
                        decisiveRuleIds = emptySet(),
                    )
                },
                invalidRuleData = true,
            )
        }
        return ApprovalRuleEvaluator.evaluate(rules, request, currentTimeMillis())
    }

    suspend fun recordMatches(evaluation: ApprovalRuleEvaluation) {
        if (evaluation.matchedRuleIds.isNotEmpty()) {
            dao.recordMatches(evaluation.matchedRuleIds, currentTimeMillis())
        }
    }

    private fun validate(input: ApprovalRuleInput): String? = when {
        input.name.isBlank() -> "Enter a rule name."
        input.clientId.isBlank() -> "Select a client."
        input.secretIds.isEmpty() -> "Select at least one secret."
        input.command.isEmpty() || input.command.first().isEmpty() -> "Enter a command."
        input.executableHash != null && input.executablePath.isNullOrBlank() ->
            "An executable identity requires its resolved path."
        input.executablePath != null && input.executableHash.isNullOrBlank() ->
            "An executable identity requires its content hash."
        input.workingDirectory?.isBlank() == true -> "The working directory is empty."
        input.expiresAt?.let { it <= currentTimeMillis() } == true ->
            "The rule must expire in the future."
        else -> null
    }

    private fun ApprovalRuleInput.toEntity(
        id: String,
        enabled: Boolean,
        createdAt: Long,
        updatedAt: Long,
        lastMatchedAt: Long? = null,
        matchCount: Long = 0,
    ) = ApprovalRuleEntity(
        id = id,
        name = name.trim(),
        action = action.storedName,
        clientId = clientId,
        secretIdsJson = json.encodeToString(secretIds.sorted()),
        commandJson = json.encodeToString(command),
        commandMatch = commandMatch.storedName,
        executablePath = executablePath,
        executableHash = executableHash,
        workingDirectory = workingDirectory,
        sourceRequestId = sourceRequestId,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt,
        lastMatchedAt = lastMatchedAt,
        matchCount = matchCount,
    )

    private fun ApprovalRuleEntity.toModel() = ApprovalRule(
        id = id,
        name = name,
        action = action.toApprovalRuleAction(),
        clientId = clientId,
        secretIds = json.decodeFromString<List<String>>(secretIdsJson).toSet(),
        command = json.decodeFromString(
            ListSerializer(String.serializer()),
            commandJson,
        ),
        commandMatch = commandMatch.toCommandMatch(),
        executablePath = executablePath,
        executableHash = executableHash,
        workingDirectory = workingDirectory,
        sourceRequestId = sourceRequestId,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt,
        lastMatchedAt = lastMatchedAt,
        matchCount = matchCount,
    )
}
