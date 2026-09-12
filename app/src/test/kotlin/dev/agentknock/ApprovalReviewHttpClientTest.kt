package dev.agentknock

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApprovalReviewHttpClientTest {
    @Test
    fun `billable review requests are never retried transparently`() {
        val client = approvalReviewHttpClient(OkHttpClient())

        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
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
