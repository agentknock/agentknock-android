package dev.agentknock.relay

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class RelaySubscriptionClientTest {
    @Test
    fun `gets subscription status for the authenticated device`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("""{"active":false}""").build())
            val client = client(server)

            assertEquals(
                RelayEndpointResult.Success(RelaySubscriptionStatus(active = false)),
                client.status(DEVICE_ID, DEVICE_TOKEN),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/device/$DEVICE_ID/subscription/status", request.target)
            assertEquals("Bearer $DEVICE_TOKEN", request.headers["Authorization"])
            assertEquals("{}", checkNotNull(request.body).utf8())
        }
    }

    @Test
    fun `redeems a subscription token for the authenticated device`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("""{"active":true}""").build())
            val client = client(server)

            assertEquals(
                RelayEndpointResult.Success(RelaySubscriptionStatus(active = true)),
                client.redeem(DEVICE_ID, DEVICE_TOKEN, REDEMPTION_TOKEN),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/device/$DEVICE_ID/subscription/update", request.target)
            assertEquals("Bearer $DEVICE_TOKEN", request.headers["Authorization"])
            val body = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            assertEquals("redemption", body.getValue("source").jsonPrimitive.content)
            assertEquals(
                REDEMPTION_TOKEN,
                body.getValue("redemption_token").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `submits a Google Play purchase for the authenticated device`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("""{"active":true}""").build())
            val client = client(server)

            assertEquals(
                RelayEndpointResult.Success(RelaySubscriptionStatus(active = true)),
                client.updateFromGooglePlay(DEVICE_ID, DEVICE_TOKEN, PURCHASE_TOKEN),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/device/$DEVICE_ID/subscription/update", request.target)
            assertEquals("Bearer $DEVICE_TOKEN", request.headers["Authorization"])
            val body = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            assertEquals("google_play", body.getValue("source").jsonPrimitive.content)
            assertEquals(PURCHASE_TOKEN, body.getValue("purchase_token").jsonPrimitive.content)
        }
    }

    @Test
    fun `rejects a successful response without subscription status`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())

            assertEquals(
                RelayEndpointResult.InvalidResponse,
                client(server).status(DEVICE_ID, DEVICE_TOKEN),
            )
        }
    }

    private fun client(server: MockWebServer) =
        HttpRelaySubscriptionClient(
            transport =
                RelayHttpTransport(
                    client = OkHttpClient(),
                    relayUrl = server.url("/").toString(),
                )
        )

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val REDEMPTION_TOKEN = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDI"
        const val PURCHASE_TOKEN = "google-play-purchase-token"
    }
}
