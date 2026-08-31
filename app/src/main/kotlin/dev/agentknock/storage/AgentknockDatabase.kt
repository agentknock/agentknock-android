package dev.agentknock.storage

import android.content.Context
import androidx.room3.Database
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
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

    abstract fun resetDao(): LocalDataResetDao

    companion object {
        const val NAME = "agentknock.db"

        fun create(context: Context): AgentknockDatabase =
            Room.databaseBuilder(context, AgentknockDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

/** Owns the exact transaction boundary after which a factory-reset wipe is irreversible. */
@Dao
internal interface LocalDataResetDao {
    @Query("UPDATE inbox_requests SET parent_request_id = NULL")
    suspend fun detachChildRequests()

    @Query("DELETE FROM secret_upload_environment_variables")
    suspend fun clearSecretUploadEnvironmentVariables()

    @Query("DELETE FROM secret_upload_ssh_keys")
    suspend fun clearSecretUploadSshKeys()

    @Query("DELETE FROM secret_upload_requests")
    suspend fun clearSecretUploadRequests()

    @Query("DELETE FROM git_sign_requests")
    suspend fun clearGitSignRequests()

    @Query("DELETE FROM ssh_authentication_requests")
    suspend fun clearSshAuthenticationRequests()

    @Query("DELETE FROM secret_use_requests")
    suspend fun clearSecretUseRequests()

    @Query("DELETE FROM request_psks")
    suspend fun clearRequestPsks()

    @Query("DELETE FROM pairing_attempts")
    suspend fun clearPairingAttempts()

    @Query("DELETE FROM inbox_requests")
    suspend fun clearInboxRequests()

    @Query("DELETE FROM client_psks")
    suspend fun clearClientPsks()

    @Query("DELETE FROM secret_client_approval_overrides")
    suspend fun clearSecretClientApprovalOverrides()

    @Query("DELETE FROM temporary_access_grants")
    suspend fun clearTemporaryAccessGrants()

    @Query("DELETE FROM clients")
    suspend fun clearClients()

    @Query("DELETE FROM device_credentials")
    suspend fun clearDeviceCredentials()

    @Query("DELETE FROM device_identities")
    suspend fun clearDeviceIdentities()

    @Query("DELETE FROM environment_variables")
    suspend fun clearEnvironmentVariables()

    @Query("DELETE FROM ssh_keys")
    suspend fun clearSshKeys()

    @Query("DELETE FROM secrets")
    suspend fun clearSecrets()

    @Query("DELETE FROM vault_keys")
    suspend fun clearVaultKeys()

    @Query("DELETE FROM audit_events")
    suspend fun clearAuditEvents()

    @Transaction
    suspend fun clearAllData() {
        detachChildRequests()
        clearSecretUploadEnvironmentVariables()
        clearSecretUploadSshKeys()
        clearSecretUploadRequests()
        clearGitSignRequests()
        clearSshAuthenticationRequests()
        clearSecretUseRequests()
        clearRequestPsks()
        clearPairingAttempts()
        clearInboxRequests()
        clearClientPsks()
        clearSecretClientApprovalOverrides()
        clearTemporaryAccessGrants()
        clearClients()
        clearDeviceCredentials()
        clearDeviceIdentities()
        clearEnvironmentVariables()
        clearSshKeys()
        clearSecrets()
        clearVaultKeys()
        clearAuditEvents()
    }
}
