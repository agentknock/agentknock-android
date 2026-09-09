package dev.agentknock.storage

import androidx.room3.RoomDatabase
import androidx.room3.withWriteTransaction

internal interface WriteTransaction {
    suspend fun <T> execute(block: suspend () -> T): T
}

internal class RoomWriteTransaction(private val database: RoomDatabase) : WriteTransaction {
    override suspend fun <T> execute(block: suspend () -> T): T = database.withWriteTransaction {
        block()
    }
}
