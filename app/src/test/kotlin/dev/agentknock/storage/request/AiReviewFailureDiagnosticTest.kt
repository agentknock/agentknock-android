package dev.agentknock.storage.request

import dev.agentknock.storage.approval.AiReview
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReviewFailureDiagnosticTest {
    @Test
    fun auditRetainsCodeLocationsButNeverExceptionMessagesOrApprovalStateDiagnostics() {
        val cause = SocketTimeoutException("secret-token and response body")
        val failure = IOException("https://relay/device/private-device-id", cause)
        failure.addSuppressed(IOException("another secret"))
        val review = unavailableAiReview(failure)
        val diagnostic = review.failureDiagnostic!!

        assertTrue(diagnostic.contains("java.io.IOException"))
        assertTrue(diagnostic.contains("java.net.SocketTimeoutException"))
        assertTrue(diagnostic.contains("AiReviewFailureDiagnosticTest.kt:"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("private-device-id"))
        assertEquals(JsonPrimitive(diagnostic), review.auditData()["ai_failure_diagnostic"])
        val encoded = Json { encodeDefaults = true }.encodeToString(review)
        assertFalse(encoded.contains("Diagnostic"))
        assertEquals(AiReview(failure = review.failure), Json.decodeFromString<AiReview>(encoded))
        assertEquals("AI review was unavailable.", review.auditFailureDetail())
    }

    @Test
    fun cyclicCausesCannotHangAuditRecording() {
        val first = IOException("private message")
        val second = IOException("private cause", first)
        first.initCause(second)
        val diagnostic = unavailableAiReview(first).failureDiagnostic!!
        assertTrue(diagnostic.endsWith("[cause chain truncated]"))
        assertFalse(diagnostic.contains("private"))
    }
}
