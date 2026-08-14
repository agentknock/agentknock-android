package dev.agentknock.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayInboxClientTest {
    @Test
    fun `reads authenticated pending messages without losing field presence`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """{"messages":[{"request_id":"$REQUEST_ID","request":null}],"has_more":false}""",
                ).build(),
            )
            val client = client(server, UnconfinedTestDispatcher(testScheduler))

            val result = client.pending(ROUTE_ID, TOKEN)

            assertTrue(result is RelayInboxResult.Success)
            val batch = (result as RelayInboxResult.Success).value
            assertEquals(1, batch.messages.size)
            assertTrue(batch.messages.single().hasRequest)
            assertEquals(JsonNull, batch.messages.single().request)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/route/$ROUTE_ID/pending", request.target)
            assertEquals("Bearer $TOKEN", request.headers["Authorization"])
            assertEquals("{}", checkNotNull(request.body).utf8())
        }
    }

    @Test
    fun `sends response and delivery acknowledgements in one update`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("{\"updated\":1}").build())
            val client = client(server, UnconfinedTestDispatcher(testScheduler))
            val response = Json.parseToJsonElement(
                """{"pairing_id":"ffeeddccbbaa99887766554433221100","route_key":"key"}""",
            )

            val result = client.update(
                routeId = ROUTE_ID,
                authenticationToken = TOKEN,
                updates = listOf(
                    RelayUpdate(
                        requestId = REQUEST_ID,
                        requestDelivered = true,
                        response = response,
                        completionDelivered = true,
                    ),
                ),
            )

            assertEquals(RelayInboxResult.Success(1), result)
            val request = server.takeRequest()
            val update = Json.parseToJsonElement(checkNotNull(request.body).utf8())
                .jsonObject.getValue("updates").jsonArray.single().jsonObject
            assertEquals(REQUEST_ID, update.getValue("request_id").jsonPrimitive.content)
            assertTrue(update.getValue("request_delivered").jsonPrimitive.content.toBoolean())
            assertTrue(update.getValue("completion_delivered").jsonPrimitive.content.toBoolean())
            assertEquals(response, update.getValue("response"))
        }
    }

    private fun client(
        server: MockWebServer,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = HttpRelayInboxClient(
        client = OkHttpClient(),
        relayUrl = server.url("/").toString(),
        dispatcher = dispatcher,
    )

    private companion object {
        const val ROUTE_ID = "0b7d7963604cba911e9c03e727688b89"
        const val REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        const val TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
