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
import dev.agentknock.storage.request.ClientEntity
import dev.agentknock.storage.request.ClientPskEntity
import dev.agentknock.storage.request.PairingAttemptEntity
import dev.agentknock.storage.request.SecretUploadRequestEntity
import dev.agentknock.storage.request.SecretUploadEnvironmentVariableEntity
import dev.agentknock.storage.request.SecretUploadSshKeyEntity
import dev.agentknock.storage.request.RequestDao
import dev.agentknock.storage.request.RequestPskEntity
import dev.agentknock.storage.device.DeviceIdentityDao
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.device.DeviceCredentialEntity

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
    version = 1,
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
                .build()
    }
}
