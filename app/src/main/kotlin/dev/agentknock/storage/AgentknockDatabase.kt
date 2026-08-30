package dev.agentknock.storage

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import dev.agentknock.storage.audit.AuditDao
import dev.agentknock.storage.audit.AuditEventEntity
import dev.agentknock.storage.crypto.VaultKeyDao
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.secret.EnvironmentVariableEntity
import dev.agentknock.storage.secret.SecretDao
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.SecretClientApprovalOverrideEntity
import dev.agentknock.storage.secret.TemporaryAccessGrantEntity
import dev.agentknock.storage.secret.SshKeyEntity
import dev.agentknock.storage.request.InboxRequestEntity
import dev.agentknock.storage.request.SecretUseRequestEntity
import dev.agentknock.storage.request.SshAuthenticationRequestEntity
import dev.agentknock.storage.request.GitSignRequestEntity
import dev.agentknock.storage.request.PairingEntity
import dev.agentknock.storage.request.PairingSecretEntity
import dev.agentknock.storage.request.SecretListRequestEntity
import dev.agentknock.storage.request.SecretUploadRequestEntity
import dev.agentknock.storage.request.SecretUploadEnvironmentVariableEntity
import dev.agentknock.storage.request.SecretUploadSshKeyEntity
import dev.agentknock.storage.request.RequestDao
import dev.agentknock.storage.request.RequestSecretEntity
import dev.agentknock.storage.rule.ApprovalRuleEntity
import dev.agentknock.storage.vault.VaultDao
import dev.agentknock.storage.vault.DeviceIdentityEntity
import dev.agentknock.storage.vault.VaultSecretEntity

@Database(
    entities = [
        VaultKeyEntity::class,
        SecretEntity::class,
        SecretClientApprovalOverrideEntity::class,
        TemporaryAccessGrantEntity::class,
        EnvironmentVariableEntity::class,
        SshKeyEntity::class,
        DeviceIdentityEntity::class,
        VaultSecretEntity::class,
        InboxRequestEntity::class,
        PairingEntity::class,
        PairingSecretEntity::class,
        RequestSecretEntity::class,
        SecretUseRequestEntity::class,
        GitSignRequestEntity::class,
        SshAuthenticationRequestEntity::class,
        SecretListRequestEntity::class,
        SecretUploadRequestEntity::class,
        SecretUploadEnvironmentVariableEntity::class,
        SecretUploadSshKeyEntity::class,
        AuditEventEntity::class,
        ApprovalRuleEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
internal abstract class AgentknockDatabase : RoomDatabase() {
    abstract fun vaultKeyDao(): VaultKeyDao

    abstract fun secretDao(): SecretDao

    abstract fun vaultDao(): VaultDao

    abstract fun requestDao(): RequestDao

    abstract fun auditDao(): AuditDao

    companion object {
        const val NAME = "agentknock.db"

        fun create(context: Context): AgentknockDatabase =
            Room.databaseBuilder(context, AgentknockDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                )
                .build()
    }
}
