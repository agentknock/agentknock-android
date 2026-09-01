package dev.agentknock.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
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
import java.time.Instant

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
                    .body(
                        """{"error":"RATE_LIMITED","message":"try again later","retry_after_ms":12345}""",
                    )
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
            )

            assertEquals(
                RelayDeviceConnectionResult.Unavailable(
                    message = "try again later",
                    retryAfterMillis = 17_000,
                ),
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                },
            )
        }
    }

    @Test
    fun `preserves a JSON websocket upgrade retry delay`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(429)
                    .body(
                        """{"error":"RATE_LIMITED","message":"wait","retryable":true,"retry_after_ms":60000}""",
                    )
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
            )

            assertEquals(
                RelayDeviceConnectionResult.Unavailable(
                    message = "wait",
                    retryAfterMillis = 60_000,
                ),
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                },
            )
        }
    }

    @Test
    fun `supports an HTTP-date websocket upgrade retry delay`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .addHeader("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT")
                    .body("""{"error":"OVERLOADED","message":"wait"}""")
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
                currentTimeMillis = {
                    Instant.parse("2015-10-21T07:27:00Z").toEpochMilli()
                },
            )

            assertEquals(
                RelayDeviceConnectionResult.Unavailable("wait", 60_000),
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                },
            )
        }
    }

    @Test
    fun `supports obsolete HTTP-date websocket upgrade retry delays`() = runTest {
        MockWebServer().use { server ->
            server.start()
            listOf(
                "Sunday, 06-Nov-94 08:49:37 GMT",
                "Sun Nov  6 08:49:37 1994",
            ).forEach { retryAfter ->
                server.enqueue(
                    MockResponse.Builder()
                        .code(503)
                        .addHeader("Retry-After", retryAfter)
                        .body("""{"error":"OVERLOADED","message":"wait"}""")
                        .build(),
                )
            }
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
                currentTimeMillis = {
                    Instant.parse("1994-11-06T08:48:37Z").toEpochMilli()
                },
            )

            repeat(2) {
                assertEquals(
                    RelayDeviceConnectionResult.Unavailable("wait", 60_000),
                    withContext(Dispatchers.IO) {
                        withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                    },
                )
            }
        }
    }

    @Test
    fun `RFC850 retry date uses the full fifty-year boundary`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .addHeader("Retry-After", "Friday, 31-Dec-76 23:59:59 GMT")
                    .body("""{"error":"OVERLOADED","message":"wait"}""")
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
                currentTimeMillis = {
                    Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
                },
            )

            assertEquals(
                RelayDeviceConnectionResult.Unavailable("wait", 0),
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                },
            )
        }
    }

    @Test
    fun `RFC850 retry date resolves its century relative to a future current date`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .addHeader("Retry-After", "Friday, 01-Jan-20 00:00:00 GMT")
                    .body("""{"error":"OVERLOADED","message":"wait"}""")
                    .build(),
            )
            val now = Instant.parse("2076-01-01T00:00:00Z")
            val retryAt = Instant.parse("2120-01-01T00:00:00Z")
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
                currentTimeMillis = now::toEpochMilli,
            )

            assertEquals(
                RelayDeviceConnectionResult.Unavailable(
                    "wait",
                    retryAt.toEpochMilli() - now.toEpochMilli(),
                ),
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                },
            )
        }
    }

    @Test
    fun `clamps an overflowing decimal websocket upgrade retry delay`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(429)
                    .addHeader("Retry-After", "999999999999999999999999999999")
                    .body("""{"error":"RATE_LIMITED","message":"wait"}""")
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
            )

            assertEquals(
                RelayDeviceConnectionResult.Unavailable(
                    "wait",
                    (Long.MAX_VALUE / 1_000L) * 1_000L,
                ),
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) { client.connect(DEVICE_ID, DEVICE_TOKEN) }
                },
            )
        }
    }

    @Test
    fun `does not honor negative websocket upgrade retry delays`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(429)
                    .addHeader("Retry-After", "-1")
                    .body(
                        """{"error":"RATE_LIMITED","message":"wait","retry_after_ms":-1}""",
                    )
                    .build(),
            )
            val client = WebSocketRelayDeviceClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
            )

            assertEquals(
                RelayDeviceConnectionResult.Unavailable(message = "wait"),
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

            assertEquals(
                RelayFrameSendResult.Sent,
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
            assertEquals(
                RelayFrameSendResult.Unavailable,
                connection.send(RelayDeviceFrame.Resume(CLIENT_ID, REQUEST_ID)),
            )
        }
    }

    @Test
    fun `rejects an oversized frame without writing it to the websocket`() = runTest {
        MockWebServer().use { server ->
            val received = CompletableDeferred<String>()
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .webSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                received.complete(text)
                            }

                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
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
            val connection = (result as RelayDeviceConnectionResult.Connected).connection

            assertEquals(
                RelayFrameSendResult.FrameTooLarge,
                connection.send(
                    RelayDeviceFrame.Response(
                        clientId = CLIENT_ID,
                        requestId = REQUEST_ID,
                        payload = JsonPrimitive("x".repeat(256 * 1024)),
                    ),
                ),
            )
            assertFalse(received.isCompleted)

            withContext(Dispatchers.IO) {
                connection.close()
            }
        }
    }

    @Test
    fun `an error frame does not terminate a healthy websocket`() = runTest {
        MockWebServer().use { server ->
            val serverSocket = CompletableDeferred<WebSocket>()
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .webSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSocket.complete(webSocket)
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
            val connection = (result as RelayDeviceConnectionResult.Connected).connection
            val socket = serverSocket.await()

            socket.send(
                """{"type":"error","client_id":"$CLIENT_ID","request_id":"$REQUEST_ID","kind":"response","error":"REQUEST_ID_CONFLICT","message":"conflict","retryable":false}""",
            )
            socket.send("""{"type":"caught_up"}""")

            assertEquals(
                RelayDeviceEvent.Error(
                    code = "REQUEST_ID_CONFLICT",
                    message = "conflict",
                    retryable = false,
                    scope = RelayDeviceErrorScope.Exchange(
                        clientId = CLIENT_ID,
                        requestId = REQUEST_ID,
                        kind = RelayMessageKind.RESPONSE,
                    ),
                ),
                connection.events.receive(),
            )
            assertEquals(RelayDeviceEvent.CaughtUp, connection.events.receive())
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
