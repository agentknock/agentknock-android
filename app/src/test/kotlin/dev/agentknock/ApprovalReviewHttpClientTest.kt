package dev.agentknock

import java.io.IOException
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApprovalReviewHttpClientTest {
    @Test
    fun `redirects cannot forward a billable review to another endpoint`() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse.Builder().code(status).addHeader("Location", "/other").build()
                )
                server.enqueue(MockResponse.Builder().code(200).build())
                val client = approvalReviewHttpClient(OkHttpClient())
                val request =
                    Request.Builder().url(server.url("/review")).post("{}".toRequestBody()).build()
                client.newCall(request).execute().use { assertEquals(status, it.code) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `a lost response cannot silently repeat a billable review`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            )
            server.enqueue(MockResponse.Builder().code(200).build())
            val client =
                approvalReviewHttpClient(
                    OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
                )
            val request =
                Request.Builder().url(server.url("/review")).post("{}".toRequestBody()).build()
            assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `HTTP 503 with zero retry delay is left to the explicit review retry loop`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
            server.enqueue(MockResponse.Builder().code(200).build())
            val request =
                Request.Builder().url(server.url("/review")).post("{}".toRequestBody()).build()

            approvalReviewHttpClient(OkHttpClient()).newCall(request).execute().use { response ->
                assertEquals(503, response.code)
            }
            assertEquals(1, server.requestCount)
        }
    }
}
