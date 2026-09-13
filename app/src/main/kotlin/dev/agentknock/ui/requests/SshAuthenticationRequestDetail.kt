package dev.agentknock.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.formatParentRequestAge
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.protocol.SshAuthenticationMethod
import dev.agentknock.protocol.relayRequestTimestamp
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.request.ApprovalCompletionResult
import dev.agentknock.storage.request.ApprovalDecision
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.SshAuthenticationRequestDetails
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.DetailPage
import dev.agentknock.ui.components.DetailValue
import dev.agentknock.ui.components.Disclosure
import dev.agentknock.ui.components.Notice
import dev.agentknock.ui.components.NoticeTone
import dev.agentknock.ui.components.rememberDateTimeFormatter

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
    val dates = rememberDateTimeFormatter()
    val authentication = (request.content as InboxRequestContent.SshAuthentication).details
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    val pending = authentication.state == ApprovalRequestState.APPROVAL_PENDING
    val aiReviewRequested =
        authentication.approvalEvaluation?.secrets?.any { it.action == ApprovalAction.ASK_AI } ==
            true
    val aiReviewInFlight = request.state == InboxRequestState.REVIEWING && aiReviewRequested
    val temporarySecretNames =
        authentication.approvalEvaluation.temporaryGrantSecretNames(aiReviewInFlight)
    var confirmTemporaryAccess by remember(request.id) { mutableStateOf(false) }
    val requestedAt = relayRequestTimestamp(request.id) ?: request.receivedAt
    DetailPage(
        title = "SSH authentication",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = authentication.state to authentication.completionResult,
        bottomContent =
            if (actionRequired) {
                {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 3.dp,
                    ) {
                        RequestDecisionButtons(
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
        StatusHeader(
            if (pending) {
                authentication.pendingStatus(actionRequired, aiReviewInFlight)
            } else {
                authentication.outcome()
            },
            dates.timestamp(requestedAt),
        )

        AuthenticationTicket(
            authentication = authentication,
            parentRequestAge =
                formatParentRequestAge(
                    authentication.invocationReceivedAt,
                    request.receivedAt,
                ),
        )

        authentication.scriptContents?.let { ScriptContents(it) }

        if (pending) {
            if (authentication.approvalEvaluation?.aiReview != null || aiReviewRequested) {
                AiReviewNotice(authentication.approvalEvaluation.aiReview, aiReviewInFlight)
            }
        } else if (
            shouldShowDecisionHistory(
                verificationFailed =
                    authentication.state == ApprovalRequestState.VERIFICATION_FAILED,
                completionReason = authentication.completionReason,
            )
        ) {
            SshAuthenticationDecisionHistory(authentication)
        }

        if (pending) {
            Text(
                "Agentknock signs the SSH authentication request on this device. " +
                    "The private key is never sent to the client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Disclosure("Technical details") {
            DetailValue("Requested", dates.timestamp(requestedAt, includeSeconds = true))
            DetailValue("Authentication method", authentication.method.wireName, true)
            DetailValue("Signature algorithm", authentication.algorithm.wireName, true)
            authentication.hostKeyAlgorithm?.let { DetailValue("Host-key algorithm", it, true) }
            authentication.clientSoftware?.let { software ->
                renderSoftware(software.application)?.let { DetailValue("Client software", it) }
                if (software.library != software.application) {
                    renderSoftware(software.library)?.let { DetailValue("Agentknock library", it) }
                }
            }
            DetailValue("Client ID", authentication.clientId, true)
            DetailValue("Invocation request ID", authentication.invocationRequestId, true)
            DetailValue("Authentication request ID", request.id, true)
            authentication.decidedAt?.let {
                DetailValue("Decided", dates.timestamp(it, includeSeconds = true))
            }
            request.completedAt?.let {
                DetailValue("Completed", dates.timestamp(it, includeSeconds = true))
            }
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

/**
 * The request as one unit: who asks with which key, why, the authentication that will be signed,
 * and the command that led here. The sheet holds facts parsed from the signed request itself; the
 * command and reason are client claims and are labelled as such.
 */
@Composable
private fun AuthenticationTicket(
    authentication: SshAuthenticationRequestDetails,
    parentRequestAge: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IdentityColumns(
                authentication.clientName,
                listOf(authentication.secretName),
                secretRole = "SSH key",
            )
            authentication.reason?.takeIf(String::isNotBlank)?.let {
                ReasonQuote(it, authentication.clientName)
            }
            AuthenticationSheet(authentication)
            TriggeringCommand(
                command = authentication.command,
                arguments = authentication.arguments,
                clientName = authentication.clientName,
                parentRequestAge = parentRequestAge,
            )
        }
    }
}

/**
 * What the key will sign, taken from the SSH user-authentication request: the remote account, the
 * server's host key when the method binds one, and the method itself. A host name never appears
 * here because the request does not carry one.
 */
@Composable
private fun AuthenticationSheet(authentication: SshAuthenticationRequestDetails) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                SelectionContainer {
                    Text(authentication.username, style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "Remote account",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            authentication.hostKeyFingerprint?.let { fingerprint ->
                SelectableFact(
                    icon = Icons.Outlined.Fingerprint,
                    label =
                        listOfNotNull("Host key", authentication.hostKeyAlgorithm)
                            .joinToString(" · "),
                    value = fingerprint,
                    monospace = true,
                )
            }
            MethodRow(
                method = authentication.methodLabel(),
                signatureAlgorithm = authentication.algorithm.wireName,
            )
            if (authentication.hostKeyFingerprint == null) {
                Text(
                    "This method does not include the server's host key, so the request " +
                        "does not identify the server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Authentication to sign",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MethodRow(method: String, signatureAlgorithm: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column {
            Text(method, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$signatureAlgorithm signature",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SshAuthenticationRequestDetails.methodLabel(): String =
    when (method) {
        SshAuthenticationMethod.PUBLIC_KEY -> "Public-key authentication"
        SshAuthenticationMethod.HOST_BOUND -> "Host-bound public-key authentication"
    }

private fun SshAuthenticationRequestDetails.pendingStatus(
    actionRequired: Boolean,
    aiReviewInFlight: Boolean,
): StatusSummary =
    when {
        aiReviewInFlight ->
            StatusSummary(
                "AI review in progress",
                NoticeTone.SUBDUED,
                Icons.Outlined.AutoAwesome,
            )
        actionRequired -> StatusSummary(statusLabel(), NoticeTone.ATTENTION)
        else ->
            StatusSummary(
                statusLabel(),
                if (
                    decision == ApprovalDecision.DENIED ||
                        completionResult == ApprovalCompletionResult.DENIED ||
                        completionResult == ApprovalCompletionResult.ABORTED
                ) {
                    NoticeTone.SUBDUED
                } else {
                    NoticeTone.SUCCESS
                },
            )
    }

private fun SshAuthenticationRequestDetails.outcome(): StatusSummary =
    when {
        state == ApprovalRequestState.VERIFICATION_FAILED ->
            StatusSummary(
                "Authentication could not be confirmed",
                NoticeTone.DANGER,
                Icons.Outlined.ErrorOutline,
                error ?: "The client confirmation was invalid.",
            )
        completionResult == ApprovalCompletionResult.APPROVED ->
            StatusSummary(
                "Authentication signed",
                NoticeTone.SUCCESS,
                Icons.Outlined.CheckCircle,
                "The SSH signature was delivered to the client.",
            )
        completionResult == ApprovalCompletionResult.DENIED ->
            if (completionReason == "INVALID_REQUEST") {
                StatusSummary(
                    "Invalid request",
                    NoticeTone.DANGER,
                    Icons.Outlined.ErrorOutline,
                    completionMessage ?: "No SSH signature was created.",
                )
            } else {
                StatusSummary(
                    "Authentication denied",
                    NoticeTone.SUBDUED,
                    Icons.Outlined.Block,
                    completionMessage ?: "No SSH signature was created.",
                )
            }
        completionResult == ApprovalCompletionResult.ABORTED ->
            StatusSummary(
                "Request ended",
                NoticeTone.NEUTRAL,
                Icons.Outlined.Block,
                completionMessage ?: "The client ended the SSH authentication request.",
            )
        state == ApprovalRequestState.COMPLETED && error != null ->
            StatusSummary(
                "Request ended",
                NoticeTone.NEUTRAL,
                null,
                error,
            )
        state == ApprovalRequestState.WAITING_FOR_COMPLETION &&
            decision == ApprovalDecision.APPROVED ->
            StatusSummary(
                "Authentication signed",
                NoticeTone.SUCCESS,
                Icons.Outlined.HourglassTop,
                "Waiting for the client to confirm receipt.",
            )
        state == ApprovalRequestState.WAITING_FOR_COMPLETION ->
            StatusSummary(
                "Authentication denied",
                NoticeTone.SUBDUED,
                Icons.Outlined.HourglassTop,
                completionMessage ?: "Waiting for the client to confirm the denial.",
            )
        else -> StatusSummary(statusLabel())
    }

@Composable
private fun SshAuthenticationDecisionHistory(authentication: SshAuthenticationRequestDetails) {
    val dates = rememberDateTimeFormatter()
    val temporaryAccessUntil =
        authentication.approvalEvaluation
            ?.secrets
            ?.mapNotNull { it.temporaryAccessExpiresAt }
            ?.maxOrNull()
    val aiReview = authentication.approvalEvaluation?.aiReview
    temporaryAccessUntil?.let {
        Notice(
            "Temporary SSH access",
            "SSH authentication from this client is allowed through ${dates.timestamp(it)}.",
            NoticeTone.SUCCESS,
        )
    }
    HistoricalAiReview(
        review = aiReview,
        decision = authentication.decision,
        humanResolution =
            when {
                aiReview?.decision != AiReviewDecision.ASK_USER -> null
                authentication.decision == ApprovalDecision.DENIED -> "You denied it."
                temporaryAccessUntil != null -> "You authenticated it and allowed temporary access."
                else -> "You authenticated it once."
            },
    )
}

private fun SshAuthenticationRequestDetails.statusLabel(): String =
    sshAuthenticationStatusLabel(state, completionResult, completionReason)
