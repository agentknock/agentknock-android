package dev.agentknock.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalStorageInitializerTest {
    @Test
    fun `initialization remains lazy until explicitly started or awaited`() = runTest {
        var calls = 0
        val initializer = LocalStorageInitializer(backgroundScope) { calls += 1 }

        runCurrent()
        assertEquals(0, calls)
        initializer.start()
        runCurrent()
        assertEquals(1, calls)
        initializer.await()
    }

    @Test
    fun `settling tolerates failed startup and restart installs a fresh run`() = runTest {
        var calls = 0
        val initializer = LocalStorageInitializer(backgroundScope) {
            calls += 1
            if (calls == 1) error("unusable restored state")
        }

        initializer.start()
        initializer.awaitSettled()
        assertEquals(1, calls)

        initializer.restart()
        initializer.await()
        assertEquals(2, calls)
    }

    @Test
    fun `restart is rejected while initialization is active`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val initializer = LocalStorageInitializer(backgroundScope) {
            started.complete(Unit)
            finish.await()
        }

        val waiting = async { initializer.await() }
        started.await()
        var rejected = false
        try {
            initializer.restart()
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertEquals(true, rejected)
        assertFalse(waiting.isCompleted)
        finish.complete(Unit)
        waiting.await()
    }
}
