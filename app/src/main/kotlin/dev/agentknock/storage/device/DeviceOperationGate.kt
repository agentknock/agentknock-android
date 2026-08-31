package dev.agentknock.storage.device

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes remote device lifecycle changes that must not cross factory reset. */
internal class DeviceOperationGate {
    private val mutex = Mutex()

    suspend fun <T> run(operation: suspend () -> T): T = mutex.withLock {
        operation()
    }
}
