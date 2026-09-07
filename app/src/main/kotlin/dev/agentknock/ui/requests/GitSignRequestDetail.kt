package dev.agentknock.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.GitSigningContent
import dev.agentknock.presentation.describeGitSigningContent
import dev.agentknock.presentation.formatParentRequestAge
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.protocol.GitSignChangeStatus
import dev.agentknock.protocol.GitSignHead
import dev.agentknock.protocol.GitSignRepository
import dev.agentknock.protocol.relayRequestTimestamp
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.request.ApprovalCompletionResult
import dev.agentknock.storage.request.ApprovalDecision
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.request.GitSignRequestDetails
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.DetailPage
import dev.agentknock.ui.components.DetailValue
import dev.agentknock.ui.components.Disclosure
import dev.agentknock.ui.components.ExactText
import dev.agentknock.ui.components.Notice
import dev.agentknock.ui.components.NoticeTone
import dev.agentknock.ui.components.rememberDateTimeFormatter

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
    val dates = rememberDateTimeFormatter()
    val signing = (request.content as InboxRequestContent.GitSign).details
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    val pending = signing.state == ApprovalRequestState.APPROVAL_PENDING
    var confirmTemporaryAccess by remember(request.id) { mutableStateOf(false) }
    val aiReviewRequested = signing.approvalEvaluation?.secrets
        ?.any { it.action == ApprovalAction.ASK_AI } == true
    val aiReviewInFlight = request.state == InboxRequestState.REVIEWING && aiReviewRequested
    val temporarySecretNames = signing.approvalEvaluation.temporaryGrantSecretNames(
        aiReviewInFlight,
    )
    val signingContent = describeGitSigningContent(signing.message)
    val exactContent = signing.message.displayForApproval()
    val gitMessage = signingContent.message
    val requestedAt = relayRequestTimestamp(request.id) ?: request.receivedAt
    DetailPage(
        title = signingContent.requestTitle,
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = signing.state to signing.completionResult,
        bottomContent = if (actionRequired) {
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
            if (pending) signing.pendingStatus(actionRequired, aiReviewInFlight) else signing.outcome(),
            dates.timestamp(requestedAt),
        )

        SigningTicket(
            clientName = signing.clientName,
            secretName = signing.secretName,
            reason = signing.reason,
            content = signingContent,
            command = signing.command,
            arguments = signing.arguments,
            parentRequestAge = formatParentRequestAge(signing.invocationReceivedAt, request.receivedAt),
        )

        if (pending) {
            if (signing.approvalEvaluation?.aiReview != null || aiReviewRequested) {
                AiReviewNotice(signing.approvalEvaluation.aiReview, aiReviewInFlight)
            }
        } else if (
            shouldShowDecisionHistory(
                verificationFailed = signing.state == ApprovalRequestState.VERIFICATION_FAILED,
                completionReason = signing.completionReason,
            )
        ) {
            GitSignDecisionHistory(signing)
        }

        signing.repository?.takeIf(GitSignRepository::hasVisibleContext)?.let { repository ->
            RepositoryCard(repository, signing.clientName)
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
            ExactText(exactContent)
        }

        if (pending) {
            Text(
                "Git signing uses the private key on this device. The private key is never sent to the client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Disclosure("Technical details") {
            DetailValue(
                "Requested",
                dates.timestamp(requestedAt, includeSeconds = true),
            )
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
            signing.decidedAt?.let { DetailValue("Decided", dates.timestamp(it, includeSeconds = true)) }
            request.completedAt?.let { DetailValue("Completed", dates.timestamp(it, includeSeconds = true)) }
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

/**
 * The request as one unit: who asks with which key, why, the object they want signed, and
 * the command that led here. Repository context is client-reported and shown separately.
 */
@Composable
private fun SigningTicket(
    clientName: String,
    secretName: String,
    reason: String?,
    content: GitSigningContent,
    command: String,
    arguments: List<String>,
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
            IdentityColumns(clientName, listOf(secretName), secretRole = "Signing key")
            reason?.takeIf(String::isNotBlank)?.let { ReasonQuote(it, clientName) }
            content.message?.let { SignedObjectSheet(content, it) }
            TriggeringCommand(command, arguments, clientName, parentRequestAge)
        }
    }
}

/** The message and identities parsed from the bytes to sign, presented as the document it is. */
@Composable
private fun SignedObjectSheet(content: GitSigningContent, message: String) {
    val subject = message.substringBefore('\n')
    val body = message.substringAfter('\n', "").trimStart('\n').takeIf(String::isNotEmpty)
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
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(subject, style = MaterialTheme.typography.titleLarge)
                    body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            content.identities.groupBy({ it.second }, { it.first }).forEach { (identity, roles) ->
                SelectableFact(Icons.Outlined.Person, roles.joinToString(" · "), identity)
            }
            Text(
                content.objectLabel?.let { "$it to sign" } ?: "Content to sign",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RepositoryCard(repository: GitSignRepository, clientName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Repository")
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.AccountTree,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        repository.remote?.let {
                            SelectionContainer {
                                Text(it, style = MaterialTheme.typography.titleMedium)
                            }
                        } ?: repository.worktree?.let {
                            SelectionContainer {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
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
                    }
                }
                repository.changedPaths?.takeIf { it.isNotEmpty() }?.let { paths ->
                    Column(
                        modifier = Modifier.padding(start = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        paths.forEach { path ->
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
                                    Text(
                                        path.path,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Reported by $clientName, not part of the signed content",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun GitSignRepository.hasVisibleContext(): Boolean =
    remote != null || worktree != null || head != null ||
        changedPathCount != null || changedPaths != null

private fun GitSignRequestDetails.pendingStatus(
    actionRequired: Boolean,
    aiReviewInFlight: Boolean,
): StatusSummary = when {
    aiReviewInFlight -> StatusSummary(
        "AI review in progress",
        NoticeTone.SUBDUED,
        Icons.Outlined.AutoAwesome,
    )
    actionRequired -> StatusSummary(statusLabel(), NoticeTone.ATTENTION)
    else -> StatusSummary(
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

private fun GitSignRequestDetails.outcome(): StatusSummary = when {
    state == ApprovalRequestState.VERIFICATION_FAILED -> StatusSummary(
        "Signature could not be confirmed",
        NoticeTone.DANGER,
        Icons.Outlined.ErrorOutline,
        error ?: "The client confirmation was invalid.",
    )
    completionResult == ApprovalCompletionResult.APPROVED -> StatusSummary(
        "Content signed",
        NoticeTone.SUCCESS,
        Icons.Outlined.CheckCircle,
        "The signature was delivered to the client.",
    )
    completionResult == ApprovalCompletionResult.DENIED -> if (completionReason == "INVALID_REQUEST") {
        StatusSummary(
            "Invalid request",
            NoticeTone.DANGER,
            Icons.Outlined.ErrorOutline,
            completionMessage ?: "No signature was created.",
        )
    } else {
        StatusSummary(
            "Signature denied",
            NoticeTone.SUBDUED,
            Icons.Outlined.Block,
            completionMessage ?: "No signature was created.",
        )
    }
    completionResult == ApprovalCompletionResult.ABORTED -> StatusSummary(
        "Request ended",
        NoticeTone.NEUTRAL,
        Icons.Outlined.Block,
        completionMessage ?: "The client ended the Git signing request.",
    )
    state == ApprovalRequestState.COMPLETED && error != null -> StatusSummary(
        "Request ended",
        NoticeTone.NEUTRAL,
        null,
        error,
    )
    state == ApprovalRequestState.WAITING_FOR_COMPLETION &&
        decision == ApprovalDecision.APPROVED -> StatusSummary(
        "Signature sent",
        NoticeTone.SUCCESS,
        Icons.Outlined.HourglassTop,
        "Waiting for the client to confirm receipt.",
    )
    state == ApprovalRequestState.WAITING_FOR_COMPLETION -> StatusSummary(
        "Signature denied",
        NoticeTone.SUBDUED,
        Icons.Outlined.HourglassTop,
        completionMessage ?: "Waiting for the client to confirm the denial.",
    )
    else -> StatusSummary(statusLabel())
}

@Composable
private fun GitSignDecisionHistory(signing: GitSignRequestDetails) {
    val dates = rememberDateTimeFormatter()
    val temporaryAccessUntil = signing.approvalEvaluation?.secrets
        ?.mapNotNull { it.temporaryAccessExpiresAt }
        ?.maxOrNull()
    val aiReview = signing.approvalEvaluation?.aiReview
    temporaryAccessUntil?.let {
        Notice(
            "Temporary signing access",
            "Future Git signing from this client is allowed through ${dates.timestamp(it)}.",
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
