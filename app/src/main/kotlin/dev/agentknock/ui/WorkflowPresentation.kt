package dev.agentknock.ui

import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.request.SecretUploadRequestState

internal fun List<InboxRequestSummary>.secretUseHistory(): List<InboxRequestSummary> =
    filter { it.kind == InboxRequestKind.SECRET_USE }

internal fun List<InboxRequestSummary>.pendingPairings(): List<InboxRequestSummary> =
    filter { summary ->
        summary.kind == InboxRequestKind.PAIRING && summary.pairingState in pendingPairingStates
    }

internal fun List<InboxRequestSummary>.pendingSecretUploads(): List<InboxRequestSummary> =
    filter { summary ->
        summary.kind == InboxRequestKind.SECRET_UPLOAD &&
            summary.secretUploadState == SecretUploadRequestState.REVIEW_PENDING
    }

internal fun List<InboxRequestSummary>.actionRequiredCount(kind: InboxRequestKind): Int =
    count { it.kind == kind && it.state == InboxRequestState.ACTION_REQUIRED }

private val pendingPairingStates = setOf(
    PairingState.RECEIVING,
    PairingState.SAS_VERIFICATION_PENDING,
    PairingState.RELAY_ACTIVATION_PENDING,
    PairingState.WAITING_FOR_FINISH,
    PairingState.VERIFICATION_FAILED,
)
