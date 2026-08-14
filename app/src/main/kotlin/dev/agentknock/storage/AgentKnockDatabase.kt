package dev.agentknock.storage

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import dev.agentknock.storage.crypto.LocalEncryptionDao
import dev.agentknock.storage.crypto.LocalEncryptionKeyEntity
import dev.agentknock.storage.crypto.LocalEncryptionStateEntity
import dev.agentknock.storage.profile.EnvironmentVariableEntity
import dev.agentknock.storage.profile.ProfileDao
import dev.agentknock.storage.profile.ProfileEntity
import dev.agentknock.storage.request.InboxRequestEntity
import dev.agentknock.storage.request.PairingEntity
import dev.agentknock.storage.request.PairingSecretEntity
import dev.agentknock.storage.request.RequestDao
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
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class AgentKnockDatabase : RoomDatabase() {
    abstract fun localEncryptionDao(): LocalEncryptionDao

    abstract fun profileDao(): ProfileDao

    abstract fun vaultDao(): VaultDao

    abstract fun requestDao(): RequestDao

    companion object {
        const val NAME = "agentknock.db"

        fun create(context: Context): AgentKnockDatabase =
            Room.databaseBuilder(context, AgentKnockDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
