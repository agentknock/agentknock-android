package dev.agentknock.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedRequestProtocolTest {
    private val protocol = PairedRequestProtocol()

    @Test
    fun `encodes a stable generic invalid request response`() {
        val response =
            Json.parseToJsonElement(
                    protocol.errorResponse(PairedRequestErrorCode.INVALID_REQUEST).decodeToString()
                )
                .jsonObject

        assertEquals("INVALID_REQUEST", response.getValue("error").jsonPrimitive.content)
        assertTrue(response.getValue("message").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `encodes a stable response too large error`() {
        val response =
            Json.parseToJsonElement(
                    protocol
                        .errorResponse(PairedRequestErrorCode.RESPONSE_TOO_LARGE)
                        .decodeToString()
                )
                .jsonObject

        assertEquals("RESPONSE_TOO_LARGE", response.getValue("error").jsonPrimitive.content)
        assertTrue(response.getValue("message").jsonPrimitive.content.isNotBlank())
    }
}
