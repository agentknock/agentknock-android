package dev.agentknock

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryAccessInitializationTest {
    @Test
    fun restoredTemporaryAccessIsClearedOnceAndExpiryCleanupAlwaysRuns() = runTest {
        val directory = Files.createTempDirectory("agentknock-temporary-access").toFile()
        try {
            val marker = directory.resolve("initialized")
            val grants = mutableMapOf("restored" to 1_000L)
            suspend fun initialize(now: Long) =
                initializeTemporaryAccessStorage(
                    marker = marker,
                    now = now,
                    clearAll = { grants.clear() },
                    clearExpired = { cutoff -> grants.entries.removeAll { it.value <= cutoff } },
                )

            initialize(100)
            assertTrue(grants.isEmpty())
            grants += mapOf("expired" to 150L, "live" to 300L)
            initialize(200)
            assertEquals(mapOf("live" to 300L), grants)
        } finally {
            directory.deleteRecursively()
        }
    }
}
