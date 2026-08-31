package dev.agentknock

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApprovalReviewHttpClientTest {
    @Test
    fun `billable review requests are never retried transparently`() {
        val client = approvalReviewHttpClient(OkHttpClient())

        assertFalse(client.retryOnConnectionFailure)
        assertEquals(45_000, client.readTimeoutMillis)
        assertEquals(60_000, client.callTimeoutMillis)
    }
}
