package dev.agentknock.storage.rule

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Entity(
    tableName = "approval_rules",
    indices = [
        Index(value = ["client_id"]),
        Index(value = ["enabled"]),
        Index(value = ["expires_at"]),
    ],
)
internal data class ApprovalRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "action")
    val action: String,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "secret_ids_json")
    val secretIdsJson: String,
    @ColumnInfo(name = "command_json")
    val commandJson: String,
    @ColumnInfo(name = "command_match")
    val commandMatch: String,
    @ColumnInfo(name = "executable_path")
    val executablePath: String?,
    @ColumnInfo(name = "executable_hash")
    val executableHash: String?,
    @ColumnInfo(name = "working_directory")
    val workingDirectory: String?,
    @ColumnInfo(name = "source_request_id")
    val sourceRequestId: Long?,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long?,
    @ColumnInfo(name = "last_matched_at")
    val lastMatchedAt: Long?,
    @ColumnInfo(name = "match_count")
    val matchCount: Long,
)

@Dao
internal interface ApprovalRuleDao {
    @Query(
        """
        SELECT * FROM approval_rules
        ORDER BY enabled DESC,
                 CASE WHEN expires_at IS NULL THEN 1 ELSE 0 END,
                 expires_at,
                 name COLLATE NOCASE,
                 id
        """,
    )
    fun observeRules(): Flow<List<ApprovalRuleEntity>>

    @Query("SELECT * FROM approval_rules WHERE id = :id")
    fun observeRule(id: String): Flow<ApprovalRuleEntity?>

    @Query("SELECT * FROM approval_rules WHERE id = :id")
    suspend fun getRule(id: String): ApprovalRuleEntity?

    @Query("SELECT * FROM approval_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<ApprovalRuleEntity>

    @Insert
    suspend fun insertRule(rule: ApprovalRuleEntity)

    @Update
    suspend fun updateRule(rule: ApprovalRuleEntity): Int

    @Query("DELETE FROM approval_rules WHERE id = :id")
    suspend fun deleteRule(id: String): Int

    @Query(
        """
        UPDATE approval_rules
        SET last_matched_at = :matchedAt,
            match_count = match_count + 1
        WHERE id = :id
        """,
    )
    suspend fun recordMatch(id: String, matchedAt: Long): Int

    @Transaction
    suspend fun recordMatches(ids: Set<String>, matchedAt: Long) {
        ids.forEach { id -> recordMatch(id, matchedAt) }
    }
}

@Serializable
internal enum class ApprovalRuleAction(
    val storedName: String,
    val precedence: Int,
) {
    DENY("deny", 0),
    ASK_ME("ask_me", 1),
    ASK_AI("ask_ai", 2),
    APPROVE("approve", 3),
}

internal fun String.toApprovalRuleAction(): ApprovalRuleAction =
    checkNotNull(ApprovalRuleAction.entries.find { it.storedName == this }) {
        "Unknown approval rule action"
    }

internal enum class CommandMatch(val storedName: String) {
    EXACT("exact"),
    PREFIX("prefix"),
}

internal fun String.toCommandMatch(): CommandMatch =
    checkNotNull(CommandMatch.entries.find { it.storedName == this }) {
        "Unknown command match mode"
    }

internal data class ApprovalRule(
    val id: String,
    val name: String,
    val action: ApprovalRuleAction,
    val clientId: String,
    val secretIds: Set<String>,
    val command: List<String>,
    val commandMatch: CommandMatch,
    val executablePath: String?,
    val executableHash: String?,
    val workingDirectory: String?,
    val sourceRequestId: Long?,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long?,
    val lastMatchedAt: Long?,
    val matchCount: Long,
) {
    fun isExpired(now: Long): Boolean = expiresAt?.let { now >= it } == true
}

internal data class RequestedSecretIdentity(
    val id: String,
    val name: String,
)

internal data class ApprovalRuleRequest(
    val clientId: String,
    val secrets: List<RequestedSecretIdentity>,
    val command: List<String>,
    val executablePath: String,
    val executableHash: String?,
    val workingDirectory: String,
)

@Serializable
internal data class SecretRuleEvaluation(
    val secretId: String,
    val secretName: String,
    val action: ApprovalRuleAction,
    val matchedRuleIds: Set<String>,
    val decisiveRuleIds: Set<String>,
)

@Serializable
internal data class ApprovalRuleEvaluation(
    val action: ApprovalRuleAction,
    val secrets: List<SecretRuleEvaluation>,
    val invalidRuleData: Boolean = false,
) {
    val matchedRuleIds: Set<String>
        get() = secrets.flatMapTo(linkedSetOf()) { it.matchedRuleIds }

    val decisiveRuleIds: Set<String>
        get() = secrets
            .filter { it.action == action }
            .flatMapTo(linkedSetOf()) { it.decisiveRuleIds }
}

internal object ApprovalRuleEvaluator {
    fun evaluate(
        rules: List<ApprovalRule>,
        request: ApprovalRuleRequest,
        now: Long,
    ): ApprovalRuleEvaluation {
        val eligibleRules = rules.filter { rule ->
            rule.enabled && !rule.isExpired(now) && rule.matchesRequest(request)
        }
        val secretEvaluations = request.secrets.map { secret ->
            val matching = eligibleRules.filter { secret.id in it.secretIds }
            val action = matching.minByOrNull { it.action.precedence }?.action
                ?: ApprovalRuleAction.ASK_ME
            SecretRuleEvaluation(
                secretId = secret.id,
                secretName = secret.name,
                action = action,
                matchedRuleIds = matching.mapTo(linkedSetOf(), ApprovalRule::id),
                decisiveRuleIds = matching
                    .filter { it.action == action }
                    .mapTo(linkedSetOf(), ApprovalRule::id),
            )
        }
        return ApprovalRuleEvaluation(
            action = secretEvaluations.minByOrNull { it.action.precedence }?.action
                ?: ApprovalRuleAction.ASK_ME,
            secrets = secretEvaluations,
        )
    }

    private fun ApprovalRule.matchesRequest(request: ApprovalRuleRequest): Boolean {
        if (clientId != request.clientId) return false
        if (command.isEmpty() || request.command.isEmpty()) return false
        val commandMatches = when (commandMatch) {
            CommandMatch.EXACT -> command == request.command
            CommandMatch.PREFIX -> request.command.size >= command.size &&
                request.command.subList(0, command.size) == command
        }
        if (!commandMatches) return false
        if (executableHash != null) {
            if (request.executableHash != executableHash) return false
            if (request.executablePath != executablePath) return false
        }
        if (workingDirectory != null && request.workingDirectory != workingDirectory) return false
        return true
    }
}
