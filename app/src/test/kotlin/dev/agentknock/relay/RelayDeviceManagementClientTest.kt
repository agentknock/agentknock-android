package dev.agentknock.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayDeviceManagementClientTest {
    @Test
    fun `changes pairing admission for the authenticated device`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body("""{"pairing_enabled":false}""").build(),
            )
            val client = client(server)

            assertEquals(
                RelayEndpointResult.Success(Unit),
                client.setPairingEnabled(DEVICE_ID, DEVICE_TOKEN, enabled = false),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/device/$DEVICE_ID/pairing", request.target)
            assertEquals("Bearer $DEVICE_TOKEN", request.headers["Authorization"])
            val body = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            assertEquals(false, body.getValue("enabled").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `deletes the authenticated relay device`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(200).body("""{"deleted":true}""").build(),
            )
            val client = client(server)

            assertEquals(
                RelayEndpointResult.Success(Unit),
                client.deleteDevice(DEVICE_ID, DEVICE_TOKEN),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/device/$DEVICE_ID/delete", request.target)
            assertEquals("Bearer $DEVICE_TOKEN", request.headers["Authorization"])
            assertEquals("{}", checkNotNull(request.body).utf8())
        }
    }

    @Test
    fun `rejects a successful response that did not apply the requested change`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body("""{"pairing_enabled":true}""").build(),
            )

            assertEquals(
                RelayEndpointResult.InvalidResponse,
                client(server).setPairingEnabled(DEVICE_ID, DEVICE_TOKEN, enabled = false),
            )
        }
    }

    private fun client(server: MockWebServer) = HttpRelayDeviceManagementClient(
        client = OkHttpClient(),
        relayUrl = server.url("/").toString(),
        dispatcher = UnconfinedTestDispatcher(),
    )

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
