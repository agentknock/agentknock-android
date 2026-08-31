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
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.SecretUseDecision
import dev.agentknock.storage.request.SshAuthenticationCompletionResult
import dev.agentknock.storage.request.SshAuthenticationRequestDetails
import dev.agentknock.storage.request.SshAuthenticationRequestState
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.ClientIdentity
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.SecretIdentities

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
    val authentication = checkNotNull(request.sshAuthentication)
    val pending = authentication.state == SshAuthenticationRequestState.APPROVAL_PENDING
    val aiReviewInFlight = !request.userDecisionAvailable
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
        InformationSurface {
            StatusLine(
                if (aiReviewInFlight) {
                    "AI review in progress"
                } else {
                    authentication.statusLabel()
                },
                error = authentication.state ==
                    SshAuthenticationRequestState.VERIFICATION_FAILED,
                attention = pending && request.userDecisionAvailable,
                subdued = aiReviewInFlight ||
                    authentication.decision == SecretUseDecision.DENIED ||
                    authentication.completionResult ==
                    SshAuthenticationCompletionResult.DENIED ||
                    authentication.completionResult ==
                    SshAuthenticationCompletionResult.ABORTED,
            )
            ClientIdentity(authentication.clientName)
            SecretIdentities(listOf(authentication.secretName))
            InformationRow("Received", formatTimestamp(request.receivedAt))
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
        if (!pending) SshAuthenticationOutcome(authentication)

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
            color = MaterialTheme.colorScheme.surfaceContainerLow,
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
        authentication.state == SshAuthenticationRequestState.VERIFICATION_FAILED -> Notice(
            "Authentication could not be confirmed",
            authentication.error ?: "The client confirmation was invalid.",
            NoticeTone.DANGER,
        )
        authentication.completionResult == SshAuthenticationCompletionResult.APPROVED -> Notice(
            "Authentication signed",
            "The SSH signature was delivered to the client.",
            NoticeTone.SUCCESS,
        )
        authentication.completionResult == SshAuthenticationCompletionResult.DENIED -> Notice(
            if (authentication.completionReason == "INVALID_REQUEST") {
                "Invalid request"
            } else {
                "Authentication denied"
            },
            authentication.completionMessage ?: "No SSH signature was created.",
            NoticeTone.SUBDUED,
        )
        authentication.completionResult == SshAuthenticationCompletionResult.ABORTED -> Notice(
            "Request ended",
            authentication.completionMessage ?: "The client ended the SSH authentication request.",
            NoticeTone.NEUTRAL,
        )
        authentication.state == SshAuthenticationRequestState.WAITING_FOR_COMPLETION &&
            authentication.decision == SecretUseDecision.APPROVED -> Notice(
            "Authentication signed",
            "Waiting for the client to confirm receipt.",
            NoticeTone.SUCCESS,
        )
        authentication.state == SshAuthenticationRequestState.WAITING_FOR_COMPLETION -> Notice(
            "Authentication denied",
            authentication.completionMessage ?: "Waiting for the client to confirm the denial.",
            NoticeTone.SUBDUED,
        )
    }
    if (
        shouldShowDecisionHistory(
            verificationFailed = authentication.state ==
                SshAuthenticationRequestState.VERIFICATION_FAILED,
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
                authentication.decision == SecretUseDecision.DENIED -> "You denied it."
                temporaryAccessUntil != null ->
                    "You authenticated it and allowed temporary access."
                else -> "You authenticated it once."
            },
        )
    }
}

private fun SshAuthenticationRequestDetails.statusLabel(): String =
    sshAuthenticationStatusLabel(state, completionResult, completionReason)
