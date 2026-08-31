package dev.agentknock.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.describeGitSigningContent
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.protocol.GitSignChangeStatus
import dev.agentknock.protocol.GitSignHead
import dev.agentknock.protocol.GitSignRepository
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.request.ApprovalCompletionResult
import dev.agentknock.storage.request.GitSignRequestDetails
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.ApprovalDecision
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.ClientIdentity
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.SecretIdentities
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun GitSignRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAllowTemporarily: () -> Unit,
    modifier: Modifier,
) {
    val signing = (request.content as InboxRequestContent.GitSign).details
    val pending = signing.state == ApprovalRequestState.APPROVAL_PENDING
    var confirmTemporaryAccess by remember(request.id) { mutableStateOf(false) }
    val aiReviewInFlight = !request.userDecisionAvailable
    val temporarySecretNames = signing.approvalEvaluation.temporaryGrantSecretNames(
        aiReviewInFlight,
    )
    val signingContent = describeGitSigningContent(signing.message)
    val title = signingContent.requestTitle
    val exactContent = signing.message.displayForApproval()
    val gitMessage = signingContent.message
    DetailPage(
        title = title,
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = signing.state to signing.completionResult,
        bottomContent = if (pending && request.userDecisionAvailable) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    RequestDecisionButtons(
                        approveLabel = "Sign once",
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
                if (aiReviewInFlight) "AI review in progress" else signing.statusLabel(),
                error = signing.state == ApprovalRequestState.VERIFICATION_FAILED,
                attention = pending && request.userDecisionAvailable,
                subdued = aiReviewInFlight ||
                    signing.decision == ApprovalDecision.DENIED ||
                    signing.completionResult == ApprovalCompletionResult.DENIED ||
                    signing.completionResult == ApprovalCompletionResult.ABORTED,
            )
            ClientIdentity(signing.clientName)
            SecretIdentities(listOf(signing.secretName))
            InformationRow("Received", formatTimestamp(request.receivedAt))
        }

        if (
            pending && (
                signing.approvalEvaluation?.aiReview != null ||
                    signing.approvalEvaluation?.secrets
                        ?.any { it.action == ApprovalAction.ASK_AI } == true
                )
        ) {
            AiReviewNotice(signing.approvalEvaluation.aiReview, aiReviewInFlight)
        }

        if (!pending) {
            GitSignOutcome(signing)
        }

        signing.repository?.takeIf(GitSignRepository::hasVisibleContext)?.let { repository ->
            GitRepositoryContext(repository)
        }

        gitMessage?.let { message ->
            Surface(
                color = if (pending) {
                    MaterialTheme.agentknockColors.attentionContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                contentColor = if (pending) {
                    MaterialTheme.agentknockColors.onAttentionContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        checkNotNull(signingContent.messageLabel),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    SelectionContainer {
                        Text(message, style = MaterialTheme.typography.titleMedium)
                    }
                }
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
                        renderShellCommand(signing.command, signing.arguments),
                        fontFamily = FontFamily.Monospace,
                    )
                }
                signing.reason?.takeIf(String::isNotBlank)?.let {
                    HorizontalDivider()
                    Text(
                        "Why this command says it needs a signature",
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

        Disclosure(
            title = if (gitMessage == null) "Content to sign" else "Exact content to sign",
            initiallyExpanded = gitMessage == null,
        ) {
            if (gitMessage != null) {
                Text(
                    "The complete Git object that will be signed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(exactContent, fontFamily = FontFamily.Monospace)
            }
        }

        if (pending) {
            Text(
                "Git signing uses the private key on this device. The private key is never sent to the client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Disclosure("Technical details") {
            signing.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            signing.repository?.worktree?.let {
                DetailValue("Worktree reported by client", it, true)
            }
            DetailValue("Client ID", signing.clientId, true)
            DetailValue("Invocation request ID", signing.invocationRequestId, true)
            DetailValue("Signing request ID", request.id, true)
        }
    }
    if (confirmTemporaryAccess) {
        TemporaryAccessConfirmation(
            clientName = signing.clientName,
            secretNames = temporarySecretNames,
            operation = TemporaryAccessOperation.GIT_SIGN,
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
private fun GitRepositoryContext(repository: GitSignRepository) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Repository", style = MaterialTheme.typography.labelLarge)
            (repository.remote ?: repository.worktree)?.let {
                SelectionContainer {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
            }
            repository.head?.let { head ->
                Text(
                    when (head) {
                        is GitSignHead.Branch -> buildString {
                            append("Branch ")
                            append(head.name)
                            head.upstream?.let {
                                append(" · upstream ")
                                append(it)
                            }
                        }
                        GitSignHead.Detached -> "Detached HEAD"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            repository.changedPathCount?.let { count ->
                Text(
                    if (count == 1L) "1 changed file" else "$count changed files",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            repository.changedPaths?.takeIf { it.isNotEmpty() }?.forEach { path ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        when (path.status) {
                            GitSignChangeStatus.ADDED -> "A"
                            GitSignChangeStatus.DELETED -> "D"
                            GitSignChangeStatus.MODIFIED -> "M"
                            GitSignChangeStatus.TYPE_CHANGED -> "T"
                        },
                        modifier = Modifier.width(16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                    SelectionContainer {
                        Text(path.path, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

private fun GitSignRepository.hasVisibleContext(): Boolean =
    remote != null || worktree != null || head != null ||
        changedPathCount != null || changedPaths != null

@Composable
private fun GitSignOutcome(signing: GitSignRequestDetails) {
    val temporaryAccessUntil = signing.approvalEvaluation?.secrets
        ?.mapNotNull { it.temporaryAccessExpiresAt }
        ?.maxOrNull()
    val aiReview = signing.approvalEvaluation?.aiReview
    when {
        signing.state == ApprovalRequestState.VERIFICATION_FAILED -> Notice(
            "Signature could not be confirmed",
            signing.error ?: "The client confirmation was invalid.",
            NoticeTone.DANGER,
        )
        signing.completionResult == ApprovalCompletionResult.APPROVED -> Notice(
            "Content signed",
            "The signature was delivered to the client.",
            NoticeTone.SUCCESS,
        )
        signing.completionResult == ApprovalCompletionResult.DENIED -> Notice(
            if (signing.completionReason == "INVALID_REQUEST") {
                "Invalid request"
            } else {
                "Signature denied"
            },
            signing.completionMessage ?: "No signature was created.",
            NoticeTone.SUBDUED,
        )
        signing.completionResult == ApprovalCompletionResult.ABORTED -> Notice(
            "Request ended",
            signing.completionMessage ?: "The client ended the Git signing request.",
            NoticeTone.NEUTRAL,
        )
        signing.state == ApprovalRequestState.WAITING_FOR_COMPLETION &&
            signing.decision == ApprovalDecision.APPROVED -> Notice(
            "Signature sent",
            "Waiting for the client to confirm receipt.",
            NoticeTone.SUCCESS,
        )
        signing.state == ApprovalRequestState.WAITING_FOR_COMPLETION -> Notice(
            "Signature denied",
            signing.completionMessage ?: "Waiting for the client to confirm the denial.",
            NoticeTone.SUBDUED,
        )
    }
    if (
        shouldShowDecisionHistory(
            verificationFailed = signing.state == ApprovalRequestState.VERIFICATION_FAILED,
            completionReason = signing.completionReason,
        )
    ) {
        temporaryAccessUntil?.let {
            Notice(
                "Temporary signing access",
                "Future Git signing from this client is allowed through ${formatTimestamp(it)}.",
                NoticeTone.SUCCESS,
            )
        }
        HistoricalAiReview(
            review = aiReview,
            decision = signing.decision,
            humanResolution = when {
                aiReview?.decision != AiReviewDecision.ASK_USER -> null
                signing.decision == ApprovalDecision.DENIED -> "You denied it."
                temporaryAccessUntil != null -> "You signed it and allowed temporary access."
                else -> "You signed it once."
            },
        )
    }
}

private fun ByteArray.displayForApproval(): String {
    val text = runCatching { decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    if (
        text != null && text.all { character ->
            character == '\n' || character == '\r' || character == '\t' ||
                !character.isISOControl()
        }
    ) {
        return text
    }
    return toList().chunked(16).joinToString("\n") { row ->
        row.joinToString(" ") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private fun GitSignRequestDetails.statusLabel(): String = gitSignStatusLabel(
    state,
    completionResult,
    completionReason,
)
