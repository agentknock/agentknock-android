package dev.agentknock.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.protocol.relayRequestTimestamp
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.ApprovalDecision
import dev.agentknock.storage.request.ApprovalCompletionResult
import dev.agentknock.storage.request.SshAuthenticationRequestDetails
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.ClientIdentity
import dev.agentknock.ui.components.DetailPage
import dev.agentknock.ui.components.DetailValue
import dev.agentknock.ui.components.Disclosure
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.Notice
import dev.agentknock.ui.components.NoticeTone
import dev.agentknock.ui.components.SecretIdentities
import dev.agentknock.ui.components.StatusLine

@Composable
internal fun SshAuthenticationRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAllowTemporarily: () -> Unit,
    modifier: Modifier,
) {
    val authentication = (request.content as InboxRequestContent.SshAuthentication).details
    val pending = authentication.state == ApprovalRequestState.APPROVAL_PENDING
    val aiReviewRequested = authentication.approvalEvaluation?.secrets
        ?.any { it.action == ApprovalAction.ASK_AI } == true
    val aiReviewInFlight = request.state == InboxRequestState.REVIEWING && aiReviewRequested
    val temporarySecretNames = authentication.approvalEvaluation.temporaryGrantSecretNames(
        aiReviewInFlight,
    )
    var confirmTemporaryAccess by remember(request.id) { mutableStateOf(false) }
    DetailPage(
        title = "SSH authentication",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = authentication.state to authentication.completionResult,
        bottomContent = if (pending && request.userDecisionAvailable) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    RequestDecisionButtons(
                        approveLabel = "Authenticate once",
                        approveEnabled = true,
                        temporaryAccessAvailable = temporarySecretNames.isNotEmpty(),
                        onDeny = onDeny,
                        onApprove = onApprove,
                        onAllowTemporarily = { confirmTemporaryAccess = true },
                    )
                }
            }
        } else {
            null
        },
    ) {
        if (!pending) SshAuthenticationOutcome(authentication)
        InformationSurface {
            ClientIdentity(authentication.clientName)
            StatusLine(
                if (aiReviewInFlight) {
                    "AI review in progress"
                } else {
                    authentication.statusLabel()
                },
                error = authentication.state ==
                    ApprovalRequestState.VERIFICATION_FAILED,
                attention = pending && request.userDecisionAvailable,
                subdued = aiReviewInFlight ||
                    authentication.decision == ApprovalDecision.DENIED ||
                    authentication.completionResult ==
                    ApprovalCompletionResult.DENIED ||
                    authentication.completionResult ==
                    ApprovalCompletionResult.ABORTED,
            )
            SecretIdentities(listOf(authentication.secretName))
            InformationRow(
                "Requested",
                formatTimestamp(relayRequestTimestamp(request.id) ?: request.receivedAt),
            )
        }

        if (
            pending && (
                authentication.approvalEvaluation?.aiReview != null ||
                    authentication.approvalEvaluation?.secrets
                        ?.any { it.action == ApprovalAction.ASK_AI } == true
                )
        ) {
            AiReviewNotice(authentication.approvalEvaluation.aiReview, aiReviewInFlight)
        }
        InformationSurface {
            InformationRow("Remote account", authentication.username)
            InformationRow(
                "Method",
                when (authentication.method.wireName) {
                    "publickey" -> "Public-key authentication"
                    else -> "Host-bound public-key authentication"
                },
            )
            InformationRow("Signature", authentication.algorithm.wireName)
            authentication.hostKeyAlgorithm?.let { InformationRow("Host key", it) }
            authentication.hostKeyFingerprint?.let {
                InformationRow("Host fingerprint", it, monospace = true)
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Triggered by", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(
                        renderShellCommand(authentication.command, authentication.arguments),
                        fontFamily = FontFamily.Monospace,
                    )
                }
                authentication.reason?.takeIf(String::isNotBlank)?.let {
                    HorizontalDivider()
                    Text(
                        "Why this command says it needs the key",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Reported by the requesting client; not verified by Agentknock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (pending) {
            Text(
                "Agentknock signs the SSH authentication request on this device. " +
                    "The private key is never sent to the client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Disclosure("Technical details") {
            authentication.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            DetailValue("Client ID", authentication.clientId, true)
            DetailValue("Invocation request ID", authentication.invocationRequestId, true)
            DetailValue("Authentication request ID", request.id, true)
        }
    }
    if (confirmTemporaryAccess) {
        TemporaryAccessConfirmation(
            clientName = authentication.clientName,
            secretNames = temporarySecretNames,
            operation = TemporaryAccessOperation.SSH_AUTHENTICATE,
            approvesOtherUsesOnce = false,
            onConfirm = {
                confirmTemporaryAccess = false
                onAllowTemporarily()
            },
            onDismiss = { confirmTemporaryAccess = false },
        )
    }
}

@Composable
private fun SshAuthenticationOutcome(authentication: SshAuthenticationRequestDetails) {
    val temporaryAccessUntil = authentication.approvalEvaluation?.secrets
        ?.mapNotNull { it.temporaryAccessExpiresAt }
        ?.maxOrNull()
    val aiReview = authentication.approvalEvaluation?.aiReview
    when {
        authentication.state == ApprovalRequestState.VERIFICATION_FAILED -> Notice(
            "Authentication could not be confirmed",
            authentication.error ?: "The client confirmation was invalid.",
            NoticeTone.DANGER,
        )
        authentication.completionResult == ApprovalCompletionResult.APPROVED -> Notice(
            "Authentication signed",
            "The SSH signature was delivered to the client.",
            NoticeTone.SUCCESS,
        )
        authentication.completionResult == ApprovalCompletionResult.DENIED -> Notice(
            if (authentication.completionReason == "INVALID_REQUEST") {
                "Invalid request"
            } else {
                "Authentication denied"
            },
            authentication.completionMessage ?: "No SSH signature was created.",
            NoticeTone.SUBDUED,
        )
        authentication.completionResult == ApprovalCompletionResult.ABORTED -> Notice(
            "Request ended",
            authentication.completionMessage ?: "The client ended the SSH authentication request.",
            NoticeTone.NEUTRAL,
        )
        authentication.state == ApprovalRequestState.COMPLETED &&
            authentication.error != null -> Notice(
                "Request ended",
                authentication.error,
                NoticeTone.NEUTRAL,
            )
        authentication.state == ApprovalRequestState.WAITING_FOR_COMPLETION &&
            authentication.decision == ApprovalDecision.APPROVED -> Notice(
            "Authentication signed",
            "Waiting for the client to confirm receipt.",
            NoticeTone.SUCCESS,
        )
        authentication.state == ApprovalRequestState.WAITING_FOR_COMPLETION -> Notice(
            "Authentication denied",
            authentication.completionMessage ?: "Waiting for the client to confirm the denial.",
            NoticeTone.SUBDUED,
        )
    }
    if (
        shouldShowDecisionHistory(
            verificationFailed = authentication.state ==
                ApprovalRequestState.VERIFICATION_FAILED,
            completionReason = authentication.completionReason,
        )
    ) {
        temporaryAccessUntil?.let {
            Notice(
                "Temporary SSH access",
                "SSH authentication from this client is allowed through ${formatTimestamp(it)}.",
                NoticeTone.SUCCESS,
            )
        }
        HistoricalAiReview(
            review = aiReview,
            decision = authentication.decision,
            humanResolution = when {
                aiReview?.decision != AiReviewDecision.ASK_USER -> null
                authentication.decision == ApprovalDecision.DENIED -> "You denied it."
                temporaryAccessUntil != null ->
                    "You authenticated it and allowed temporary access."
                else -> "You authenticated it once."
            },
        )
    }
}

private fun SshAuthenticationRequestDetails.statusLabel(): String =
    sshAuthenticationStatusLabel(state, completionResult, completionReason)
