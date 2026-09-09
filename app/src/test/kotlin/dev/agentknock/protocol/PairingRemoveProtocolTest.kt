package dev.agentknock.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingRemoveProtocolTest {
    private val protocol = PairingRemoveProtocol()

    @Test
    fun `decodes removal request and completion`() {
        assertEquals(
            testClientSoftware("0.2.0", "0.1.0"),
            protocol.decodeRequest(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")},"method":"PairingRemove"}"""
                    .encodeToByteArray()
            ),
        )
        assertEquals("{}", protocol.response().decodeToString())
        assertEquals(
            testClientSoftware("0.2.0", "0.1.0"),
            protocol.decodeCompletion(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")}}""".encodeToByteArray()
            ),
        )
    }
}
