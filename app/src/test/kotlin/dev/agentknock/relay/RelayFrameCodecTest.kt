package dev.agentknock.relay

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayFrameCodecTest {
    private val codec = RelayFrameCodec()

    @Test
    fun `encodes every device frame with the websocket contract`() {
        val payload = Json.parseToJsonElement("""{"result":"APPROVED"}""")

        assertEquals(
            """{"type":"message","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","kind":"response","payload":{"result":"APPROVED"}}""",
            codec.encode(RelayDeviceFrame.Message(CLIENT_ID, REQUEST_ID, payload)),
        )
        assertEquals(
            """{"type":"ack","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","kind":"request"}""",
            codec.encode(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
            ),
        )
        assertEquals(
            """{"type":"resume","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID"}""",
            codec.encode(RelayDeviceFrame.Resume(CLIENT_ID, REQUEST_ID)),
        )
        assertEquals(
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
                """{"type":"message","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","kind":"request","payload":{"method":"List"},"address_id":"$ADDRESS_ID","future":true}""",
            ),
        )
        assertEquals(
            RelayDeviceEvent.State(
                clientId = CLIENT_ID,
                requestId = REQUEST_ID,
                exchange = RelayExchangeState.CLOSING,
                request = RelayMessageState.DELIVERED,
                response = RelayMessageState.DISCARDED,
                completion = RelayMessageState.ACCEPTED,
            ),
            codec.decode(
                """{"type":"state","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","exchange":"closing","request":"delivered","response":"discarded","completion":"accepted"}""",
            ),
        )
    }

    @Test
    fun `decodes relay control and terminal frames`() {
        assertEquals(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            codec.decode(
                """{"type":"client_state","client_id":"$CLIENT_ID","state":"active"}""",
            ),
        )
        assertEquals(
            RelayDeviceEvent.Inactive(CLIENT_ID, REQUEST_ID, null),
            codec.decode(
                """{"type":"inactive","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID"}""",
            ),
        )
        assertEquals(RelayDeviceEvent.CaughtUp, codec.decode("""{"type":"caught_up"}"""))
        val error = codec.decode(
            """{"type":"error","error":"BUSY","message":"retry","retryable":true,"retry_after_ms":250}""",
        )
        assertTrue(error is RelayDeviceEvent.Error && error.retryAfterMillis == 250L)
    }

    private companion object {
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
        const val ADDRESS_ID = "9e6f33bf47382846903dffa0962ea313"
    }
}
