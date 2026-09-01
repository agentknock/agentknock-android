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

class RelayPushRegistrationClientTest {
    @Test
    fun `registers the Firebase installation ID for the authenticated device`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"push_registration":"registered"}""")
                    .build(),
            )
            val client = client(server)

            assertEquals(
                RelayEndpointResult.Success(Unit),
                client.register(DEVICE_ID, DEVICE_TOKEN, FIREBASE_INSTALLATION_ID),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/device/$DEVICE_ID/push", request.target)
            assertEquals("Bearer $DEVICE_TOKEN", request.headers["Authorization"])
            val body = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            assertEquals(
                FIREBASE_INSTALLATION_ID,
                body.getValue("fid").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `rejects an unexpected successful registration response`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"push_registration":"pending"}""")
                    .build(),
            )
            val client = client(server)

            assertEquals(
                RelayEndpointResult.InvalidResponse,
                client.register(DEVICE_ID, DEVICE_TOKEN, FIREBASE_INSTALLATION_ID),
            )
        }
    }

    private fun client(server: MockWebServer) = HttpRelayPushRegistrationClient(
        transport = RelayHttpTransport(
            client = OkHttpClient(),
            relayUrl = server.url("/").toString(),
        ),
    )

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val FIREBASE_INSTALLATION_ID = "cR7guAQpRtyz1K4ZL_Cx42"
    }
}
