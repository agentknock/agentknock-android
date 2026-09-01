package dev.agentknock.ui

import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestStatus
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.request.SecretUploadRequestState
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.ui.requests.shouldShowDecisionHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowPresentationTest {
    @Test
    fun `each top-level section receives only its own records`() {
        val requests = listOf(
            summary(1, InboxRequestKind.PAIRING, pairing = PairingState.EXCHANGE_PENDING),
            summary(2, InboxRequestKind.PAIRING, pairing = PairingState.COMPLETED),
            summary(3, InboxRequestKind.PAIRING, pairing = PairingState.EXCHANGE_PENDING),
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
                secretUse = ApprovalRequestState.COMPLETED,
            ),
            summary(7, InboxRequestKind.GIT_SIGN),
        )

        assertEquals(listOf("6", "7"), requests.requestHistory().map { it.id })
        assertEquals(listOf("1", "3"), requests.pendingPairings().map { it.id })
        assertEquals(listOf("4"), requests.pendingSecretUploads().map { it.id })
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
                secretUse = ApprovalRequestState.APPROVAL_PENDING,
            ),
            summary(
                4,
                InboxRequestKind.SECRET_USE,
                state = InboxRequestState.COMPLETED,
                secretUse = ApprovalRequestState.COMPLETED,
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
        secretUse: ApprovalRequestState? = null,
    ) = InboxRequestSummary(
        id = id.toString(),
        kind = kind,
        state = state,
        status = when (kind) {
            InboxRequestKind.PAIRING -> InboxRequestStatus.Pairing(checkNotNull(pairing))
            InboxRequestKind.SECRET_UPLOAD -> InboxRequestStatus.SecretUpload(checkNotNull(upload))
            InboxRequestKind.SECRET_USE,
            InboxRequestKind.GIT_SIGN,
            InboxRequestKind.SSH_AUTHENTICATE,
            -> InboxRequestStatus.Approval(
                state = secretUse ?: if (state == InboxRequestState.ACTION_REQUIRED) {
                    ApprovalRequestState.APPROVAL_PENDING
                } else {
                    ApprovalRequestState.COMPLETED
                },
                decision = null,
                completionResult = null,
                completionReason = null,
            )
        },
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
