package dev.agentknock.storage.request

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLifecycleTest {
    @Test
    fun uploadCompletesOnlyAfterDecisionAndTransportInEitherOrder() {
        val decisionFirst = secretUploadLifecycle("approved", transportFinished = false)
        assertEquals(InboxRequestState.WAITING, decisionFirst.state)
        assertFalse(decisionFirst.completed)

        val transportAfterDecision = secretUploadLifecycle("approved", transportFinished = true)
        assertEquals(InboxRequestState.COMPLETED, transportAfterDecision.state)
        assertTrue(transportAfterDecision.completed)

        val transportFirst = secretUploadLifecycle(null, transportFinished = true)
        assertEquals(InboxRequestState.ACTION_REQUIRED, transportFirst.state)
        assertFalse(transportFirst.completed)

        val decisionAfterTransport = secretUploadLifecycle("rejected", transportFinished = true)
        assertEquals(InboxRequestState.COMPLETED, decisionAfterTransport.state)
        assertTrue(decisionAfterTransport.completed)
    }

    @Test
    fun aiReviewEscalationAndOperationPresentationComeFromTheBaseState() {
        assertEquals(InboxRequestState.ACTION_REQUIRED, reviewedRequestState(false))
        assertEquals(InboxRequestState.WAITING, reviewedRequestState(true))
        assertEquals(
            ApprovalRequestState.APPROVAL_PENDING,
            approvalRequestState(InboxRequestState.REVIEWING, error = null),
        )
        assertEquals(
            ApprovalRequestState.APPROVAL_PENDING,
            approvalRequestState(InboxRequestState.ACTION_REQUIRED, error = null),
        )
        assertEquals(
            ApprovalRequestState.VERIFICATION_FAILED,
            approvalRequestState(InboxRequestState.COMPLETED, error = "invalid completion"),
        )
    }

    @Test
    fun completionDecoderDistinguishesMalformedWireFromInternalFailure() {
        assertNull(
            decodeWireCompletionOrNull<String> {
                throw SerializationException("malformed completion")
            },
        )

        val internalFailure = runCatching {
            decodeWireCompletionOrNull<String> { error("decoder bug") }
        }
        assertTrue(internalFailure.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun storedRequestKindParsingIsExhaustiveAndFutureValuesMapToUnknown() {
        RequestKind.entries.forEach { kind ->
            assertEquals(kind, kind.storedName.toRequestKind())
        }
        assertEquals(RequestKind.UNKNOWN, "future_method".toRequestKind())
    }
}
