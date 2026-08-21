package dev.agentknock.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PairedRequestProtocolTest {
    private val protocol = PairedRequestProtocol()

    @Test
    fun `reads the protected request method`() {
        assertEquals(
            "SecretList",
            protocol.method("""{"method":"SecretList","extra":true}""".encodeToByteArray()),
        )
    }

    @Test
    fun `encodes a stable generic invalid request response`() {
        assertArrayEquals(
            """{"error":"INVALID_REQUEST","message":"The request could not be understood."}"""
                .encodeToByteArray(),
            protocol.errorResponse(PairedRequestErrorCode.INVALID_REQUEST),
        )
    }
}
