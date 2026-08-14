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

@Database(
    entities = [
        LocalEncryptionKeyEntity::class,
        LocalEncryptionStateEntity::class,
        ProfileEntity::class,
        EnvironmentVariableEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class AgentKnockDatabase : RoomDatabase() {
    abstract fun localEncryptionDao(): LocalEncryptionDao

    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "agentknock.db"

        fun create(context: Context): AgentKnockDatabase =
            Room.databaseBuilder(context, AgentKnockDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
