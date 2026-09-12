package dev.agentknock.relay

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayFrameCodecTest {
    private val codec = RelayFrameCodec()

    @Test(expected = IllegalStateException::class)
    fun `rejects a malformed optional inactive kind`() {
        codec.decode(
            """{"type":"inactive","client_id":"client","request_id":"request","kind":{}}"""
        )
    }

    @Test
    fun `encodes every device frame with the websocket contract`() {
        val payload = Json.parseToJsonElement("""{"result":"APPROVED"}""")

        assertJsonEquals(
            """{"type":"message","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","kind":"response","payload":{"result":"APPROVED"}}""",
            codec.encode(RelayDeviceFrame.Response(CLIENT_ID, REQUEST_ID, payload)),
        )
        assertJsonEquals(
            """{"type":"ack","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","kind":"request"}""",
            codec.encode(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    REQUEST_ID,
                    RelayMessageKind.REQUEST,
                )
            ),
        )
        assertJsonEquals(
            """{"type":"resume","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID"}""",
            codec.encode(RelayDeviceFrame.Resume(CLIENT_ID, REQUEST_ID)),
        )
        assertJsonEquals(
            """{"type":"set_client_state","client_id":"$CLIENT_ID","state":"active"}""",
            codec.encode(RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.ACTIVE)),
        )
    }

    @Test
    fun `decodes relay message and state frames`() {
        assertEquals(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = REQUEST_ID,
                kind = RelayMessageKind.REQUEST,
                payload = Json.parseToJsonElement("""{"method":"List"}"""),
                addressId = ADDRESS_ID,
            ),
            codec.decode(
                """{"type":"message","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","kind":"request","payload":{"method":"List"},"address_id":"$ADDRESS_ID","future":true}"""
            ),
        )
        assertEquals(
            RelayDeviceEvent.State(
                clientId = CLIENT_ID,
                requestId = REQUEST_ID,
                exchange = RelayExchangeState.CLOSING,
                response = RelayMessageState.DISCARDED,
            ),
            codec.decode(
                """{"type":"state","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","exchange":"closing","request":"delivered","response":"discarded","completion":"accepted"}"""
            ),
        )
    }

    @Test
    fun `decodes relay control and terminal frames`() {
        assertEquals(
            RelayDeviceEvent.PushRegistration(RelayPushRegistrationState.MISSING),
            codec.decode("""{"type":"push_registration","state":"missing"}"""),
        )
        assertEquals(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            codec.decode("""{"type":"client_state","client_id":"$CLIENT_ID","state":"active"}"""),
        )
        assertEquals(
            RelayDeviceEvent.Inactive(CLIENT_ID, REQUEST_ID, null),
            codec.decode(
                """{"type":"inactive","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID"}"""
            ),
        )
        assertEquals(RelayDeviceEvent.CaughtUp, codec.decode("""{"type":"caught_up"}"""))
        assertEquals(
            RelayDeviceEvent.Error(
                code = "BUSY",
                message = "retry",
                retryable = true,
                scope = RelayDeviceErrorScope.Unscoped,
                retryAfterMillis = 250,
            ),
            codec.decode(
                """{"type":"error","error":"BUSY","message":"retry","retryable":true,"retry_after_ms":250}"""
            ),
        )
    }

    @Test
    fun `decodes a complete exchange error scope`() {
        assertEquals(
            RelayDeviceEvent.Error(
                code = "REQUEST_ID_CONFLICT",
                message = "conflict",
                retryable = false,
                scope =
                    RelayDeviceErrorScope.Exchange(
                        clientId = CLIENT_ID,
                        requestId = REQUEST_ID,
                        kind = RelayMessageKind.RESPONSE,
                    ),
            ),
            codec.decode(
                """{"type":"error","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","kind":"response","error":"REQUEST_ID_CONFLICT","message":"conflict","retryable":false}"""
            ),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects a partial exchange error scope`() {
        codec.decode(
            """{"type":"error","client_id":"$CLIENT_ID","error":"BUSY","message":"retry","retryable":true}"""
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects a negative retry delay`() {
        codec.decode(
            """{"type":"error","error":"BUSY","message":"retry","retryable":true,"retry_after_ms":-1}"""
        )
    }

    @Test
    fun `clamps an overflowing retry delay`() {
        val event =
            codec.decode(
                """{"type":"error","error":"BUSY","message":"retry","retryable":true,"retry_after_ms":999999999999999999999999999999}"""
            )

        assertEquals(Long.MAX_VALUE, (event as RelayDeviceEvent.Error).retryAfterMillis)
    }

    private fun assertJsonEquals(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
    }

    private companion object {
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
        const val ADDRESS_ID = "9e6f33bf47382846903dffa0962ea313"
    }
}
