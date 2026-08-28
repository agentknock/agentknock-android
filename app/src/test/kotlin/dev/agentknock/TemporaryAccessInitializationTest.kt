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
            var clearAllCalls = 0
            val expiryChecks = mutableListOf<Long>()

            initializeTemporaryAccessStorage(
                marker = marker,
                now = 100,
                clearAll = { clearAllCalls += 1 },
                clearExpired = { expiryChecks += it },
            )
            assertTrue(marker.exists())
            assertEquals(1, clearAllCalls)
            assertEquals(listOf(100L), expiryChecks)

            initializeTemporaryAccessStorage(
                marker = marker,
                now = 200,
                clearAll = { clearAllCalls += 1 },
                clearExpired = { expiryChecks += it },
            )
            assertEquals(1, clearAllCalls)
            assertEquals(listOf(100L, 200L), expiryChecks)
        } finally {
            directory.deleteRecursively()
        }
    }
}
