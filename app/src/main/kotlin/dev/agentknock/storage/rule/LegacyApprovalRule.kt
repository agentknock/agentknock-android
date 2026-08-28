package dev.agentknock.storage.rule

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Retained in the Room model so upgrades preserve the schema of published
 * internal builds. Command rules are no longer evaluated or exposed by the app.
 */
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
