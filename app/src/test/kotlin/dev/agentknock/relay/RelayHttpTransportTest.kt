package dev.agentknock.relay

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHttpTransportTest {
    @Test
    fun `posts JSON with optional bearer authentication`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("response").build())
            val transport = transport(server)

            assertEquals(
                RelayEndpointResult.Success("response"),
                transport.post("v1/test", "{}", bearerToken = "device-token"),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/test", request.target)
            assertEquals("Bearer device-token", request.headers["Authorization"])
            assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
            assertEquals("{}", checkNotNull(request.body).utf8())
        }
    }

    @Test
    fun `decodes the common relay error body`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .body("""{"error":"CONFLICT","message":"Already exists"}""")
                    .build()
            )

            assertEquals(
                RelayEndpointResult.Rejected(409, "CONFLICT", "Already exists"),
                transport(server).post("v1/test", "{}"),
            )
        }
    }

    @Test
    fun `preserves the longer retry delay from the error body and HTTP header`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val transport = transport(server)
            for ((header, expected) in listOf("2" to 3_000L, "5" to 5_000L)) {
                server.enqueue(
                    MockResponse.Builder()
                        .code(429)
                        .addHeader("Retry-After", header)
                        .body("""{"error":"RATE_LIMITED","retry_after_ms":3000}""")
                        .build()
                )

                val result = transport.post("v1/test", "{}") as RelayEndpointResult.Rejected

                assertEquals(expected, result.retryAfterMillis)
            }
        }
    }

    @Test
    fun `preserves header retry delay even when the error body is not JSON`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(503).addHeader("Retry-After", "7").body("busy").build()
            )

            val result = transport(server).post("v1/test", "{}") as RelayEndpointResult.Rejected

            assertEquals(RelayEndpointResult.Rejected(503, null, null, 7_000L), result)
        }
    }

    @Test
    fun `preserves a zero server delay and ignores invalid retry delays`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val transport = transport(server)
            for ((encoded, expected) in listOf("0" to 0L, "-1" to null, "\"1000\"" to null)) {
                server.enqueue(
                    MockResponse.Builder()
                        .code(429)
                        .addHeader("Retry-After", "invalid")
                        .body("""{"error":"RATE_LIMITED","retry_after_ms":$encoded}""")
                        .build()
                )

                val result = transport.post("v1/test", "{}") as RelayEndpointResult.Rejected

                assertEquals(expected, result.retryAfterMillis)
            }
        }
    }

    @Test
    fun `ignores malformed members in a relay error body`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(400)
                    .body("""{"error":7,"message":{"unexpected":true}}""")
                    .build()
            )

            assertEquals(
                RelayEndpointResult.Rejected(400, null, null),
                transport(server).post("v1/test", "{}"),
            )
        }
    }

    @Test
    fun `maps connection failures to unavailable`() = runTest {
        val server = MockWebServer()
        server.start()
        val transport = transport(server)
        server.close()

        val result = transport.post("v1/test", "{}")

        assertTrue(result is RelayEndpointResult.Unavailable)
    }

    @Test
    fun `cancelling the coroutine cancels the OkHttp call`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().headersDelay(1, TimeUnit.DAYS).body("response").build()
            )
            val observedCall = CompletableDeferred<Call>()
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            observedCall.complete(chain.call())
                            chain.proceed(chain.request())
                        }
                    )
                    .build()
            val transport =
                RelayHttpTransport(
                    client = client,
                    relayUrl = server.url("/").toString(),
                )
            val request = async { transport.post("v1/test", "{}") }
            val call = observedCall.await()

            request.cancelAndJoin()

            assertTrue(request.isCancelled)
            assertTrue(call.isCanceled())
        }
    }

    private fun transport(server: MockWebServer) =
        RelayHttpTransport(
            client = OkHttpClient(),
            relayUrl = server.url("/").toString(),
        )
}
