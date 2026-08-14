package dev.agentknock.relay

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayClaimClientTest {
    @Test
    fun `sends the phone claim contract`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("{\"claimed\":true}")
                    .build(),
            )
            val client = HttpRelayClaimClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            val result = client.claim(ROUTE_ID, AUTHENTICATION_TOKEN)

            assertEquals(RelayClaimResult.Claimed, result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/route/$ROUTE_ID/claim", request.target)
            assertFalse(request.headers.names().any { it.equals("Authorization", true) })
            val body = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            assertEquals(
                AUTHENTICATION_TOKEN,
                body.getValue("authentication_token").jsonPrimitive.content,
            )
            assertTrue(
                body.getValue("attestation").jsonObject
                    .getValue("development").jsonPrimitive.boolean,
            )
        }
    }

    @Test
    fun `maps a claim conflict to an unavailable address`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .body("{\"error\":\"route_already_claimed\",\"message\":\"claimed\"}")
                    .build(),
            )
            val client = HttpRelayClaimClient(
                client = OkHttpClient(),
                relayUrl = server.url("/").toString(),
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            assertEquals(
                RelayClaimResult.AddressUnavailable,
                client.claim(ROUTE_ID, AUTHENTICATION_TOKEN),
            )
        }
    }

    private companion object {
        const val ROUTE_ID = "0b7d7963604cba911e9c03e727688b89"
        const val AUTHENTICATION_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
