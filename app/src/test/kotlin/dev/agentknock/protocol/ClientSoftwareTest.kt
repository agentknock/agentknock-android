package dev.agentknock.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSoftwareTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes application and library identity from an application message`() {
        val plaintext =
            """{${testClientSoftwareFields("2.3.4", "0.1.0")},"method":"Example"}"""
                .encodeToByteArray()

        assertEquals(testClientSoftware("2.3.4", "0.1.0"), json.decodeClientSoftware(plaintext))
    }

    @Test
    fun `requires both software identities with string fields`() {
        val invalidMessages = listOf(
            """{"app_info":{"name":"agentknock","version":"0.1.0"}}""",
            """{"lib_info":{"name":"agentknock","version":"0.1.0"}}""",
            """{"app_info":{"name":"agentknock","version":1},"lib_info":{"name":"agentknock","version":"0.1.0"}}""",
        )

        invalidMessages.forEach { message ->
            assertTrue(runCatching { json.decodeClientSoftware(message.encodeToByteArray()) }.isFailure)
        }
    }
}
