package dev.agentknock.storage

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import dev.agentknock.storage.crypto.LocalEncryptionDao
import dev.agentknock.storage.audit.AuditDao
import dev.agentknock.storage.audit.AuditEventEntity
import dev.agentknock.storage.crypto.LocalEncryptionKeyEntity
import dev.agentknock.storage.crypto.LocalEncryptionStateEntity
import dev.agentknock.storage.profile.EnvironmentVariableEntity
import dev.agentknock.storage.profile.ProfileDao
import dev.agentknock.storage.profile.ProfileEntity
import dev.agentknock.storage.request.InboxRequestEntity
import dev.agentknock.storage.request.CredentialRequestEntity
import dev.agentknock.storage.request.PairingEntity
import dev.agentknock.storage.request.PairingSecretEntity
import dev.agentknock.storage.request.ProfileListRequestEntity
import dev.agentknock.storage.request.ProfileUploadRequestEntity
import dev.agentknock.storage.request.ProfileUploadVariableEntity
import dev.agentknock.storage.request.RequestDao
import dev.agentknock.storage.request.RequestSecretEntity
import dev.agentknock.storage.vault.VaultDao
import dev.agentknock.storage.vault.VaultIdentityEntity
import dev.agentknock.storage.vault.VaultSecretEntity

@Database(
    entities = [
        LocalEncryptionKeyEntity::class,
        LocalEncryptionStateEntity::class,
        ProfileEntity::class,
        EnvironmentVariableEntity::class,
        VaultIdentityEntity::class,
        VaultSecretEntity::class,
        InboxRequestEntity::class,
        PairingEntity::class,
        PairingSecretEntity::class,
        RequestSecretEntity::class,
        CredentialRequestEntity::class,
        ProfileListRequestEntity::class,
        ProfileUploadRequestEntity::class,
        ProfileUploadVariableEntity::class,
        AuditEventEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class AgentknockDatabase : RoomDatabase() {
    abstract fun localEncryptionDao(): LocalEncryptionDao

    abstract fun profileDao(): ProfileDao

    abstract fun vaultDao(): VaultDao

    abstract fun requestDao(): RequestDao

    abstract fun auditDao(): AuditDao

    companion object {
        const val NAME = "agentknock.db"

        fun create(context: Context): AgentknockDatabase =
            Room.databaseBuilder(context, AgentknockDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
