package dev.agentknock.storage.request

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun rejectedRequestIsHandledOnlyAfterDurablePersistence() = runTest {
        assertTrue(persistRejectedRequest {})
        assertFalse(persistRejectedRequest { error("injected database failure") })

        val cancellation = runCatching {
            persistRejectedRequest { throw CancellationException("cancelled") }
        }
        assertTrue(cancellation.exceptionOrNull() is CancellationException)
    }
}
