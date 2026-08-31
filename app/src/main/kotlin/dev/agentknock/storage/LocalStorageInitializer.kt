package dev.agentknock.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** A lazily started storage initializer that can be replaced after destructive recovery. */
internal class LocalStorageInitializer(
    private val scope: CoroutineScope,
    private val initialize: suspend () -> Unit,
) {
    private val lock = Any()
    private var current: Deferred<Result<Unit>> = newInitialization()

    fun start() {
        synchronized(lock) { current.start() }
    }

    suspend fun await() {
        synchronized(lock) { current }.await().getOrThrow()
    }

    /** Waits for storage users to leave startup, while allowing recovery from startup failure. */
    suspend fun awaitSettled() {
        synchronized(lock) { current }.await()
    }

    /** Replaces a completed initialization after its underlying storage has been erased. */
    fun restart() {
        val replacement = synchronized(lock) {
            check(current.isCompleted) { "Storage initialization is still running" }
            newInitialization().also { current = it }
        }
        replacement.start()
    }

    private fun newInitialization(): Deferred<Result<Unit>> = scope.async(
        start = CoroutineStart.LAZY,
    ) {
        try {
            Result.success(initialize())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }
}
