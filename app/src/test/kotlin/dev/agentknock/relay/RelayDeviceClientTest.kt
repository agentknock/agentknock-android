package dev.agentknock.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDeviceClientTest {
    @Test
    fun `reports a rejected websocket upgrade`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(401)
                    .body("""{"error":"UNAUTHORIZED","message":"bad token"}""")
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
            )

            assertEquals(
                RelayDeviceConnectionResult.Rejected(
                    status = 401,
                    code = "UNAUTHORIZED",
                    message = "bad token",
                ),
                client.connect(DEVICE_ID, DEVICE_TOKEN),
            )
        }
    }

    @Test
    fun `connects to the authenticated device websocket and exchanges frames`() = runTest {
        MockWebServer().use { server ->
            val received = CompletableDeferred<String>()
            val serverSocket = CompletableDeferred<WebSocket>()
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .webSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSocket.complete(webSocket)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                received.complete(text)
                            }
                        },
                    )
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
            )

            val result = client.connect(DEVICE_ID, DEVICE_TOKEN)

            assertTrue(result is RelayDeviceConnectionResult.Connected)
            val connection = (result as RelayDeviceConnectionResult.Connected).connection
            val request = server.takeRequest()
            assertEquals("/v1/device/$DEVICE_ID", request.target)
            assertEquals("Bearer $DEVICE_TOKEN", request.headers["Authorization"])

            assertTrue(
                connection.send(
                    RelayDeviceFrame.Resume(CLIENT_ID, REQUEST_ID),
                ),
            )
            assertEquals(
                """{"type":"resume","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID"}""",
                received.await(),
            )

            val socket = serverSocket.await()
            socket.send(
                """{"type":"client_state","client_id":"$CLIENT_ID","state":"active"}""",
            )
            assertEquals(
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
                connection.events.receive(),
            )
            assertTrue(socket.close(1000, "test complete"))
            assertEquals(
                RelayDeviceEvent.Closed(1000, "test complete"),
                connection.events.receive(),
            )
        }
    }

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
