package dev.agentknock.ui

import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.request.SecretUploadRequestState
import dev.agentknock.storage.request.SecretUseRequestState
import dev.agentknock.ui.requests.shouldShowDecisionHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowPresentationTest {
    @Test
    fun `each top-level section receives only its own records`() {
        val requests = listOf(
            summary(1, InboxRequestKind.PAIRING, pairing = PairingState.RECEIVING),
            summary(2, InboxRequestKind.PAIRING, pairing = PairingState.ACTIVE),
            summary(3, InboxRequestKind.PAIRING, pairing = PairingState.VERIFICATION_FAILED),
            summary(
                4,
                InboxRequestKind.SECRET_UPLOAD,
                upload = SecretUploadRequestState.REVIEW_PENDING,
            ),
            summary(
                5,
                InboxRequestKind.SECRET_UPLOAD,
                upload = SecretUploadRequestState.APPROVED,
            ),
            summary(
                6,
                InboxRequestKind.SECRET_USE,
                secretUse = SecretUseRequestState.COMPLETED,
            ),
            summary(7, InboxRequestKind.GIT_SIGN),
        )

        assertEquals(listOf(6L, 7L), requests.requestHistory().map { it.id })
        assertEquals(listOf(1L, 3L), requests.pendingPairings().map { it.id })
        assertEquals(listOf(4L), requests.pendingSecretUploads().map { it.id })
    }

    @Test
    fun `attention counts remain scoped to their destination`() {
        val requests = listOf(
            summary(
                1,
                InboxRequestKind.PAIRING,
                state = InboxRequestState.ACTION_REQUIRED,
                pairing = PairingState.SAS_VERIFICATION_PENDING,
            ),
            summary(
                2,
                InboxRequestKind.SECRET_UPLOAD,
                state = InboxRequestState.ACTION_REQUIRED,
                upload = SecretUploadRequestState.REVIEW_PENDING,
            ),
            summary(
                3,
                InboxRequestKind.SECRET_USE,
                state = InboxRequestState.ACTION_REQUIRED,
                secretUse = SecretUseRequestState.APPROVAL_PENDING,
            ),
            summary(
                4,
                InboxRequestKind.SECRET_USE,
                state = InboxRequestState.COMPLETED,
                secretUse = SecretUseRequestState.COMPLETED,
            ),
            summary(
                5,
                InboxRequestKind.GIT_SIGN,
                state = InboxRequestState.ACTION_REQUIRED,
            ),
        )

        assertEquals(1, requests.actionRequiredCount(InboxRequestKind.PAIRING))
        assertEquals(1, requests.actionRequiredCount(InboxRequestKind.SECRET_UPLOAD))
        assertEquals(1, requests.actionRequiredCount(InboxRequestKind.SECRET_USE))
        assertEquals(
            2,
            requests.actionRequiredCount(InboxRequestKind.SECRET_USE, InboxRequestKind.GIT_SIGN),
        )
    }

    @Test
    fun `invalid and unverifiable requests do not show approval history`() {
        assertFalse(
            shouldShowDecisionHistory(
                verificationFailed = false,
                completionReason = "INVALID_REQUEST",
            ),
        )
        assertFalse(
            shouldShowDecisionHistory(
                verificationFailed = true,
                completionReason = null,
            ),
        )
        assertTrue(
            shouldShowDecisionHistory(
                verificationFailed = false,
                completionReason = null,
            ),
        )
    }

    private fun summary(
        id: Long,
        kind: InboxRequestKind,
        state: InboxRequestState = InboxRequestState.COMPLETED,
        pairing: PairingState? = null,
        upload: SecretUploadRequestState? = null,
        secretUse: SecretUseRequestState? = null,
    ) = InboxRequestSummary(
        id = id,
        kind = kind,
        state = state,
        pairingState = pairing,
        secretUseState = secretUse,
        secretUseDecision = null,
        secretUseResult = null,
        secretUseCompletionReason = null,
        secretListState = null,
        secretUploadState = upload,
        title = "Test",
        clientName = "Test client",
        secretNames = emptyList(),
        listSummary = null,
        command = null,
        arguments = emptyList(),
        receivedAt = id,
        completedAt = null,
    )
}
