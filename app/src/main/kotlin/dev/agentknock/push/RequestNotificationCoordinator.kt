package dev.agentknock.push

import dev.agentknock.storage.request.RequestNotification
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes notification state with the request state that produced it. */
internal class RequestNotificationCoordinator(
    private val scope: CoroutineScope,
    private val currentRequests: suspend () -> List<RequestNotification>,
    private val displayRequests: suspend (List<RequestNotification>) -> Unit,
    private val displayWake: suspend () -> Unit,
    private val clear: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val state = AtomicReference(State(generation = 0, paused = false))

    suspend fun refresh() {
        val admittedGeneration = admit() ?: return
        mutex.withLock {
            if (!isCurrent(admittedGeneration)) return
            val requests = currentRequests()
            if (isCurrent(admittedGeneration)) displayRequests(requests)
        }
    }

    fun showWake() {
        val admittedGeneration = admit() ?: return
        scope.launch {
            mutex.withLock {
                if (isCurrent(admittedGeneration)) displayWake()
            }
        }
    }

    /**
     * Runs an action originating from one of our notifications while it is still current.
     *
     * Factory reset pauses this coordinator before clearing storage. Sharing the same mutex makes
     * the pause wait for an admitted action and rejects new actions until storage is coherent.
     */
    suspend fun performAction(action: suspend () -> Unit): Boolean {
        val admittedGeneration = admit() ?: return false
        return mutex.withLock {
            if (!isCurrent(admittedGeneration)) return@withLock false
            action()
            if (isCurrent(admittedGeneration)) {
                displayRequests(currentRequests())
            }
            true
        }
    }

    suspend fun pauseAndClear() {
        state.updateAndGet { State(generation = it.generation + 1, paused = true) }
        mutex.withLock {
            state.updateAndGet {
                if (it.paused) it else State(generation = it.generation + 1, paused = true)
            }
            clear()
        }
    }

    suspend fun resume(refresh: Boolean) {
        mutex.withLock {
            val resumedState = state.updateAndGet { it.copy(paused = false) }
            if (refresh) {
                val requests = currentRequests()
                if (isCurrent(resumedState.generation)) displayRequests(requests)
            }
        }
    }

    private fun admit(): Long? = state.get().let { current ->
        current.generation.takeUnless { current.paused }
    }

    private fun isCurrent(admittedGeneration: Long): Boolean =
        state.get().let { !it.paused && it.generation == admittedGeneration }

    private data class State(
        val generation: Long,
        val paused: Boolean,
    )
}
