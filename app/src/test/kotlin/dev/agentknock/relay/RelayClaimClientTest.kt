package dev.agentknock.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayClaimClientTest {
    @Test
    fun `claims a new device and sets its address`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("{\"claimed\":true,\"device_id\":\"$DEVICE_ID\"}")
                    .build()
            )
            server.enqueue(
                MockResponse.Builder().code(200).body("{\"address_id\":\"$ADDRESS_ID\"}").build()
            )
            val client =
                HttpRelayClaimClient(
                    transport = transport(server),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    attestationProvider = attestationProvider(),
                )

            val result =
                client.claimAndSetAddress(
                    DEVICE_ID,
                    ADDRESS_ID,
                    DEVICE_TOKEN,
                )

            assertEquals(
                RelayEndpointResult.Success(RelayClaimOutcome.CLAIMED),
                result,
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/device/$DEVICE_ID/claim", request.target)
            assertFalse(request.headers.names().any { it.equals("Authorization", true) })
            val body = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            assertEquals(
                DEVICE_TOKEN,
                body.getValue("device_token").jsonPrimitive.content,
            )
            val attestation = body.getValue("attestation").jsonObject
            assertEquals("android_key", attestation.getValue("type").jsonPrimitive.content)
            assertEquals(
                listOf("bGVhZg==", "cm9vdA=="),
                attestation.getValue("certificate_chain").jsonArray.map {
                    it.jsonPrimitive.content
                },
            )
            val addressRequest = server.takeRequest()
            assertEquals("POST", addressRequest.method)
            assertEquals("/v1/device/$DEVICE_ID/address", addressRequest.target)
            assertEquals(
                "Bearer $DEVICE_TOKEN",
                addressRequest.headers["Authorization"],
            )
            val addressBody =
                Json.parseToJsonElement(checkNotNull(addressRequest.body).utf8()).jsonObject
            assertEquals(ADDRESS_ID, addressBody.getValue("address_id").jsonPrimitive.content)
        }
    }

    @Test
    fun `maps a claim conflict to an unavailable address`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("{\"claimed\":true,\"device_id\":\"$DEVICE_ID\"}")
                    .build()
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .body("{\"error\":\"ADDRESS_ALREADY_CLAIMED\",\"message\":\"claimed\"}")
                    .build()
            )
            val client =
                HttpRelayClaimClient(
                    transport = transport(server),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    attestationProvider = attestationProvider(),
                )

            assertEquals(
                RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE),
                client.claimAndSetAddress(
                    DEVICE_ID,
                    ADDRESS_ID,
                    DEVICE_TOKEN,
                ),
            )
        }
    }

    @Test
    fun `rejects a successful claim response for another device`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("{\"claimed\":true,\"device_id\":\"another-device\"}")
                    .build()
            )
            server.enqueue(
                MockResponse.Builder().code(200).body("{\"address_id\":\"$ADDRESS_ID\"}").build()
            )
            val client =
                HttpRelayClaimClient(
                    transport = transport(server),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    attestationProvider = attestationProvider(),
                )

            assertEquals(
                RelayEndpointResult.InvalidResponse,
                client.claimAndSetAddress(
                    DEVICE_ID,
                    ADDRESS_ID,
                    DEVICE_TOKEN,
                ),
            )
        }
    }

    @Test
    fun `sets an existing device address without claiming it`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(200).body("{\"address_id\":\"$ADDRESS_ID\"}").build()
            )
            val client =
                HttpRelayClaimClient(
                    transport = transport(server),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    attestationProvider =
                        DeviceAttestationProvider { _, _ ->
                            error("Attestation provider must not be called")
                        },
                )

            assertEquals(
                RelayEndpointResult.Success(RelayClaimOutcome.CLAIMED),
                client.setAddress(
                    DEVICE_ID,
                    ADDRESS_ID,
                    DEVICE_TOKEN,
                ),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/device/$DEVICE_ID/address", request.target)
            assertEquals("Bearer $DEVICE_TOKEN", request.headers["Authorization"])
            val body = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            assertEquals(ADDRESS_ID, body.getValue("address_id").jsonPrimitive.content)
            assertEquals(1, server.requestCount)
        }
    }

    private fun transport(server: MockWebServer) =
        RelayHttpTransport(
            client = OkHttpClient(),
            relayUrl = server.url("/").toString(),
        )

    private fun attestationProvider() = DeviceAttestationProvider { deviceId, deviceToken ->
        assertEquals(DEVICE_ID, deviceId)
        assertEquals(DEVICE_TOKEN, deviceToken)
        AndroidKeyAttestation(
            type = "android_key",
            certificateChain = listOf("bGVhZg==", "cm9vdA=="),
        )
    }

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val ADDRESS_ID = "9e6f33bf47382846903dffa0962ea313"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
