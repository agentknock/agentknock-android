package dev.agentknock.ui

import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestStatus
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.SecretUploadRequestState

internal fun List<InboxRequestSummary>.requestHistory(): List<InboxRequestSummary> = filter {
    it.kind == InboxRequestKind.SECRET_USE ||
        it.kind == InboxRequestKind.GIT_SIGN ||
        it.kind == InboxRequestKind.SSH_AUTHENTICATE
}

internal fun List<InboxRequestSummary>.pendingPairings(): List<InboxRequestSummary> =
    filter { summary ->
        summary.kind == InboxRequestKind.PAIRING &&
            (summary.status as? InboxRequestStatus.Pairing)?.state?.isPendingPresentation == true
    }

internal fun List<InboxRequestSummary>.pendingSecretUploads(): List<InboxRequestSummary> =
    filter { summary ->
        summary.kind == InboxRequestKind.SECRET_UPLOAD &&
            (summary.status as? InboxRequestStatus.SecretUpload)?.state ==
                SecretUploadRequestState.REVIEW_PENDING
    }

internal fun List<InboxRequestSummary>.actionRequiredCount(vararg kinds: InboxRequestKind): Int =
    count {
        it.kind in kinds && it.state == InboxRequestState.ACTION_REQUIRED
    }
