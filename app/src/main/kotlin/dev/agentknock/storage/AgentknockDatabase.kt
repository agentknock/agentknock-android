package dev.agentknock.storage

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dev.agentknock.storage.audit.AuditDao
import dev.agentknock.storage.audit.AuditEventEntity
import dev.agentknock.storage.audit.auditBody
import dev.agentknock.storage.audit.auditJson
import dev.agentknock.storage.crypto.VaultKeyDao
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.device.DeviceCredentialEntity
import dev.agentknock.storage.device.DeviceIdentityDao
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.request.ClientEntity
import dev.agentknock.storage.request.ClientPskEntity
import dev.agentknock.storage.request.GitSignRequestEntity
import dev.agentknock.storage.request.InboxRequestEntity
import dev.agentknock.storage.request.PairingAttemptEntity
import dev.agentknock.storage.request.RequestDao
import dev.agentknock.storage.request.RequestPskEntity
import dev.agentknock.storage.request.SecretUploadEnvironmentVariableEntity
import dev.agentknock.storage.request.SecretUploadRequestEntity
import dev.agentknock.storage.request.SecretUploadSshKeyEntity
import dev.agentknock.storage.request.SecretUseRequestEntity
import dev.agentknock.storage.request.SshAuthenticationRequestEntity
import dev.agentknock.storage.secret.EnvironmentVariableEntity
import dev.agentknock.storage.secret.SecretClientApprovalOverrideEntity
import dev.agentknock.storage.secret.SecretDao
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.SshKeyEntity
import dev.agentknock.storage.secret.TemporaryAccessGrantEntity
import kotlinx.serialization.json.JsonObject

@Database(
    entities = [
        VaultKeyEntity::class,
        SecretEntity::class,
        SecretClientApprovalOverrideEntity::class,
        TemporaryAccessGrantEntity::class,
        EnvironmentVariableEntity::class,
        SshKeyEntity::class,
        DeviceIdentityEntity::class,
        DeviceCredentialEntity::class,
        InboxRequestEntity::class,
        PairingAttemptEntity::class,
        ClientEntity::class,
        ClientPskEntity::class,
        RequestPskEntity::class,
        SecretUseRequestEntity::class,
        GitSignRequestEntity::class,
        SshAuthenticationRequestEntity::class,
        SecretUploadRequestEntity::class,
        SecretUploadEnvironmentVariableEntity::class,
        SecretUploadSshKeyEntity::class,
        AuditEventEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class AgentknockDatabase : RoomDatabase() {
    abstract fun vaultKeyDao(): VaultKeyDao

    abstract fun secretDao(): SecretDao

    abstract fun deviceIdentityDao(): DeviceIdentityDao

    abstract fun requestDao(): RequestDao

    abstract fun auditDao(): AuditDao

    companion object {
        const val NAME = "agentknock.db"

        fun create(context: Context): AgentknockDatabase =
            Room.databaseBuilder(context, AgentknockDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

internal val MIGRATION_1_2 = Migration(1, 2) { connection ->
    connection.execSQL("ALTER TABLE inbox_requests ADD COLUMN failure_kind TEXT")
}

internal val MIGRATION_2_3 = Migration(2, 3) { connection ->
    connection.execSQL("UPDATE secrets SET approval_mode = 'ask_me' WHERE approval_mode = 'temporary'")
    connection.execSQL(
        "UPDATE secret_client_approval_overrides SET approval_mode = 'ask_me' " +
            "WHERE approval_mode = 'temporary'",
    )
    removeEnvironmentVariableNotes(connection)
    migrateAuditEvents(connection)
}

private fun removeEnvironmentVariableNotes(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE environment_variables_new (" +
            "id TEXT NOT NULL, secret_id TEXT NOT NULL, name TEXT NOT NULL, " +
            "sensitive INTEGER NOT NULL, value_updated_at INTEGER NOT NULL, " +
            "encryption_format INTEGER NOT NULL, encryption_key_id TEXT NOT NULL, " +
            "nonce BLOB NOT NULL, ciphertext BLOB NOT NULL, PRIMARY KEY(id), " +
            "FOREIGN KEY(secret_id) REFERENCES secrets(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
            "FOREIGN KEY(encryption_key_id) REFERENCES vault_keys(id) " +
            "ON UPDATE NO ACTION ON DELETE RESTRICT)",
    )
    connection.execSQL(
        "INSERT INTO environment_variables_new " +
            "(id, secret_id, name, sensitive, value_updated_at, encryption_format, " +
            "encryption_key_id, nonce, ciphertext) " +
            "SELECT id, secret_id, name, sensitive, value_updated_at, encryption_format, " +
            "encryption_key_id, nonce, ciphertext FROM environment_variables",
    )
    connection.execSQL("DROP TABLE environment_variables")
    connection.execSQL(
        "ALTER TABLE environment_variables_new RENAME TO environment_variables",
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX index_environment_variables_secret_id_name " +
            "ON environment_variables (secret_id, name)",
    )
    connection.execSQL(
        "CREATE INDEX index_environment_variables_encryption_key_id " +
            "ON environment_variables (encryption_key_id)",
    )
}

private fun migrateAuditEvents(connection: SQLiteConnection) {
    val legacyEvents = buildList {
        connection.prepare(
            "SELECT id, occurred_at, event_type, subject, context, detail, outcome, " +
                "decision_source, expires_at, client_id, client_name, relay_request_id " +
                "FROM audit_events ORDER BY id",
        ).use { statement ->
            while (statement.step()) {
                add(
                    LegacyAuditEvent(
                        id = statement.getLong(0),
                        occurredAt = statement.getLong(1),
                        eventType = statement.getText(2),
                        subject = statement.textOrNull(3),
                        context = statement.textOrNull(4),
                        detail = statement.textOrNull(5),
                        outcome = statement.getText(6),
                        decisionSource = statement.textOrNull(7),
                        expiresAt = statement.longOrNull(8),
                        clientId = statement.textOrNull(9),
                        clientName = statement.textOrNull(10),
                        relayRequestId = statement.textOrNull(11),
                    ),
                )
            }
        }
    }
    connection.execSQL(
        "CREATE TABLE audit_events_new (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "occurred_at INTEGER NOT NULL, event_type TEXT NOT NULL, outcome TEXT NOT NULL, " +
            "decision_source TEXT, client_id TEXT, relay_request_id TEXT, body_json TEXT NOT NULL)",
    )
    connection.prepare(
        "INSERT INTO audit_events_new " +
            "(id, occurred_at, event_type, outcome, decision_source, client_id, " +
            "relay_request_id, body_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    ).use { insert ->
        legacyEvents.forEach { event ->
            insert.bindLong(1, event.id)
            insert.bindLong(2, event.occurredAt)
            insert.bindText(3, event.eventType)
            insert.bindText(4, event.outcome)
            insert.bindNullableText(5, event.decisionSource)
            insert.bindNullableText(6, event.clientId)
            insert.bindNullableText(7, event.relayRequestId)
            insert.bindText(
                8,
                auditJson.encodeToString(
                    JsonObject.serializer(),
                    auditBody(
                        event.subject,
                        event.context,
                        event.detail,
                        event.expiresAt,
                        event.clientName,
                    ),
                ),
            )
            check(!insert.step())
            insert.reset()
            insert.clearBindings()
        }
    }
    connection.execSQL("DROP TABLE audit_events")
    connection.execSQL("ALTER TABLE audit_events_new RENAME TO audit_events")
    connection.execSQL(
        "CREATE INDEX index_audit_events_occurred_at ON audit_events (occurred_at)",
    )
}

private data class LegacyAuditEvent(
    val id: Long,
    val occurredAt: Long,
    val eventType: String,
    val subject: String?,
    val context: String?,
    val detail: String?,
    val outcome: String,
    val decisionSource: String?,
    val expiresAt: Long?,
    val clientId: String?,
    val clientName: String?,
    val relayRequestId: String?,
)

private fun androidx.sqlite.SQLiteStatement.textOrNull(index: Int): String? =
    if (isNull(index)) null else getText(index)

private fun androidx.sqlite.SQLiteStatement.longOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun androidx.sqlite.SQLiteStatement.bindNullableText(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}
