package dev.agentknock.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayHttpTransportTest {
    @Test
    fun `posts JSON with optional bearer authentication`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("response").build())
            val transport = transport(server)

            assertEquals(
                RelayHttpResult.Success("response"),
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
                MockResponse.Builder().code(409)
                    .body("""{"error":"CONFLICT","message":"Already exists"}""")
                    .build(),
            )

            assertEquals(
                RelayHttpResult.Rejected(409, "CONFLICT", "Already exists"),
                transport(server).post("v1/test", "{}"),
            )
        }
    }

    @Test
    fun `preserves rejection status when the error body is invalid`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).body("not json").build())

            val result = transport(server).post("v1/test", "{}") as RelayHttpResult.Rejected

            assertEquals(503, result.status)
            assertNull(result.code)
            assertNull(result.message)
        }
    }

    @Test
    fun `ignores malformed members in a relay error body`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(400)
                    .body("""{"error":7,"message":{"unexpected":true}}""")
                    .build(),
            )

            assertEquals(
                RelayHttpResult.Rejected(400, null, null),
                transport(server).post("v1/test", "{}"),
            )
        }
    }

    private fun transport(server: MockWebServer) = RelayHttpTransport(
        client = OkHttpClient(),
        relayUrl = server.url("/").toString(),
        dispatcher = UnconfinedTestDispatcher(),
    )
}
