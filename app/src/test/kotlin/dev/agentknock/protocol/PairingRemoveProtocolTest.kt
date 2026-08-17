package dev.agentknock.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingRemoveProtocolTest {
    private val protocol = PairingRemoveProtocol()

    @Test
    fun `decodes removal request and completion`() {
        assertEquals(
            "0.2.0",
            protocol.decodeRequest(
                """{"cli_version":"0.2.0","method":"PairingRemove"}"""
                    .encodeToByteArray(),
            ),
        )
        assertEquals("{}", protocol.response().decodeToString())
        assertEquals(
            "0.2.0",
            protocol.decodeCompletion(
                """{"cli_version":"0.2.0"}""".encodeToByteArray(),
            ),
        )
    }
}
