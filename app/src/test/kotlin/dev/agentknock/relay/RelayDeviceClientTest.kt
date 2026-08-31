package dev.agentknock.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class RelayDeviceClientTest {
    @Test
    fun `bounds queued relay events and terminates on overflow`() = runTest {
        var overflowed = false
        val buffer = RelayEventBuffer(capacity = 1)

        assertTrue(buffer.offer(RelayDeviceEvent.CaughtUp) { overflowed = true })
        assertFalse(buffer.offer(RelayDeviceEvent.CaughtUp) { overflowed = true })

        assertTrue(overflowed)
        assertEquals(
            RelayDeviceEvent.Failed("Relay event backlog exceeded its safe limit."),
            buffer.events.receive(),
        )
        assertTrue(buffer.events.receiveCatching().isClosed)
    }

    @Test
    fun `closes the relay event stream after its terminal event`() = runTest {
        val buffer = RelayEventBuffer(capacity = 1)
        buffer.finish(RelayDeviceEvent.Closed(1000, "done")) {
            error("Terminal event should fit")
        }

        assertEquals(RelayDeviceEvent.Closed(1000, "done"), buffer.events.receive())
        assertTrue(buffer.events.receiveCatching().isClosed)
    }

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
    fun `a malformed websocket rejection cannot strand connection setup`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(401)
                    .body("""{"error":7,"message":{"unexpected":true}}""")
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
            )

            assertEquals(
                RelayDeviceConnectionResult.Rejected(401, null, null),
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                },
            )
        }
    }

    @Test
    fun `classifies transient websocket upgrade failures as unavailable`() = runTest {
        MockWebServer().use { server ->
            server.start()
            listOf(408, 425, 500, 599).forEach { status ->
                server.enqueue(MockResponse.Builder().code(status).build())
            }
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
            )

            listOf(408, 425, 500, 599).forEach { status ->
                assertEquals(
                    RelayDeviceConnectionResult.Unavailable(
                        message = "Relay is temporarily unavailable (HTTP $status).",
                    ),
                    withContext(Dispatchers.IO) {
                        withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                    },
                )
            }
        }
    }

    @Test
    fun `preserves transient relay message`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(429)
                    .addHeader("Retry-After", "17")
                    .body("""{"error":"RATE_LIMITED","message":"try again later"}""")
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
            )

            assertEquals(
                RelayDeviceConnectionResult.Unavailable(
                    message = "try again later",
                ),
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                },
            )
        }
    }

    @Test
    fun `classifies a network failure before upgrade as unavailable`() = runTest {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val client = WebSocketRelayDeviceClient(
            client = OkHttpClient(),
            relayUrl = "http://127.0.0.1:$unusedPort",
        )

        val result = withContext(Dispatchers.IO) {
            withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
        }

        assertTrue(result is RelayDeviceConnectionResult.Unavailable)
        assertTrue((result as RelayDeviceConnectionResult.Unavailable).message?.isNotBlank() == true)
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

    @Test
    fun `completes a client initiated close handshake`() = runTest {
        MockWebServer().use { server ->
            val closeCode = CompletableDeferred<Int>()
            val closeReason = CompletableDeferred<String>()
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .webSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                closeCode.complete(code)
                                closeReason.complete(reason)
                                webSocket.close(code, reason)
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
            withContext(Dispatchers.IO) {
                connection.close()
            }
            assertEquals(1000, closeCode.await())
            assertEquals("client disconnect", closeReason.await())
        }
    }

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
