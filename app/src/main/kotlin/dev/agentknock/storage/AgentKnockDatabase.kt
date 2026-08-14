package dev.agentknock.storage

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import dev.agentknock.storage.crypto.LocalEncryptionDao
import dev.agentknock.storage.crypto.LocalEncryptionKeyEntity
import dev.agentknock.storage.crypto.LocalEncryptionStateEntity
import dev.agentknock.storage.secret.StoredSecretDao
import dev.agentknock.storage.secret.StoredSecretEntity

@Database(
    entities = [
        LocalEncryptionKeyEntity::class,
        LocalEncryptionStateEntity::class,
        StoredSecretEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class AgentKnockDatabase : RoomDatabase() {
    abstract fun localEncryptionDao(): LocalEncryptionDao

    abstract fun storedSecretDao(): StoredSecretDao

    companion object {
        const val NAME = "agentknock.db"

        fun create(context: Context): AgentKnockDatabase =
            Room.databaseBuilder(context, AgentKnockDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
