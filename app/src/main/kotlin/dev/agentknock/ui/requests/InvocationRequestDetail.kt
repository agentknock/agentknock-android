package dev.agentknock.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.ApprovalCompletionResult
import dev.agentknock.storage.request.ApprovalDecision
import dev.agentknock.storage.request.SecretUseRequestDetails
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.DetailPage
import dev.agentknock.ui.components.DetailValue
import dev.agentknock.ui.components.Disclosure
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.Notice
import dev.agentknock.ui.components.NoticeTone
import dev.agentknock.ui.components.StatusLine

@Composable
internal fun InvocationRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAllowTemporarily: () -> Unit,
    modifier: Modifier,
) {
    val secretUse = (request.content as InboxRequestContent.SecretUse).details
    var confirmTemporaryAccess by remember(request.id) { mutableStateOf(false) }
    val aiReviewInFlight = !request.userDecisionAvailable
    val temporarySecretNames = secretUse.approvalEvaluation.temporaryGrantSecretNames(
        aiReviewInFlight,
    )
    DetailPage(
        title = "Secret use",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = secretUse.state to secretUse.completionResult,
        bottomContent = if (
            secretUse.state == ApprovalRequestState.APPROVAL_PENDING &&
            request.userDecisionAvailable
        ) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    RequestDecisionButtons(
                        approveLabel = "Approve once",
                        approveEnabled = secretUse.missingSecrets.isEmpty(),
                        temporaryAccessAvailable = temporarySecretNames.isNotEmpty() &&
                            secretUse.missingSecrets.isEmpty(),
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
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Security,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val secretCount = secretUse.secrets.size
                    val secretLabel = if (secretCount == 1) "secret" else "secrets"
                    val headline = when {
                        secretUse.state == ApprovalRequestState.COMPLETED &&
                            secretUse.completionResult == ApprovalCompletionResult.APPROVED ->
                            "${secretUse.clientName} used $secretCount $secretLabel"
                        secretUse.state == ApprovalRequestState.COMPLETED ->
                            "${secretUse.clientName} requested $secretCount $secretLabel"
                        else ->
                            "${secretUse.clientName} requests $secretCount $secretLabel"
                    }
                    Text(headline, style = MaterialTheme.typography.titleLarge)
                    StatusLine(
                        if (aiReviewInFlight) "AI review in progress" else secretUse.statusLabel(),
                        secretUse.isError(),
                        attention = secretUse.state == ApprovalRequestState.APPROVAL_PENDING &&
                            request.userDecisionAvailable,
                        subdued = aiReviewInFlight ||
                            secretUse.decision == ApprovalDecision.DENIED ||
                            secretUse.completionResult == ApprovalCompletionResult.DENIED ||
                            secretUse.completionResult == ApprovalCompletionResult.ABORTED,
                    )
                    Text(
                        "Received ${formatTimestamp(request.receivedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (secretUse.secretDetails.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Requested secrets", style = MaterialTheme.typography.titleMedium)
                InformationSurface(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    secretUse.secretDetails.forEachIndexed { index, secret ->
                        SecretSummary(secret, secretUse.environmentVariables[secret.name])
                        if (index != secretUse.secretDetails.lastIndex) HorizontalDivider()
                    }
                }
            }
        }

        val renderedCommand = renderShellCommand(secretUse.command, secretUse.arguments)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Command", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(renderedCommand, fontFamily = FontFamily.Monospace)
                }
                if (renderedCommand.any { it.code > 0x7e }) {
                    Text(
                        "This command contains non-ASCII characters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (
            secretUse.missingSecrets.isNotEmpty() &&
            secretUse.completionResult != ApprovalCompletionResult.DENIED
        ) {
            Notice(
                "Secrets are unavailable",
                secretUse.missingSecrets.joinToString(),
                NoticeTone.DANGER,
            )
        }

        if (secretUse.state == ApprovalRequestState.APPROVAL_PENDING) {
            val evaluation = secretUse.approvalEvaluation
            if (
                evaluation?.aiReview != null ||
                evaluation?.secrets?.any { it.action == ApprovalAction.ASK_AI } == true
            ) {
                AiReviewNotice(evaluation.aiReview, aiReviewInFlight)
            }
        }

        if (secretUse.state != ApprovalRequestState.APPROVAL_PENDING) {
            SecretUseOutcome(secretUse)
        }

        secretUse.reason?.takeIf(String::isNotBlank)?.let { reason ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        "Why this command says it needs access",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(reason, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Reported by the requesting client; not verified by Agentknock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Disclosure("Technical details") {
            DetailValue("Working directory", secretUse.workingDirectory, true)
            DetailValue("Executable path", secretUse.executablePath, true)
            secretUse.executableHash?.let { DetailValue("Executable hash", it, true) }
            DetailValue("Executable mode", secretUse.executableMode)
            DetailValue("Standard input", secretUse.stdinKind)
            DetailValue("Standard output", secretUse.stdoutKind)
            DetailValue("Standard error", secretUse.stderrKind)
            if (secretUse.launcherChain.isNotEmpty()) {
                DetailValue("Launcher chain", secretUse.launcherChain.joinToString("\n"), true)
            }
            secretUse.platform?.let {
                DetailValue("Platform reported by client", formatPlatformName(it))
            }
            secretUse.architecture?.let { DetailValue("Architecture reported by client", it) }
            secretUse.hostname?.takeIf { it != secretUse.clientName }
                ?.let { DetailValue("Hostname reported by client", it) }
            secretUse.osVersion?.let { DetailValue("OS version", it) }
            secretUse.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            secretUse.machineId?.let {
                DetailValue("Machine ID reported by client", it, true)
            }
            DetailValue("Client ID", secretUse.clientId, true)
            DetailValue("Request ID", request.id, true)
        }
    }
    if (confirmTemporaryAccess) {
        TemporaryAccessConfirmation(
            clientName = secretUse.clientName,
            secretNames = temporarySecretNames,
            operation = TemporaryAccessOperation.INVOCATION,
            approvesOtherUsesOnce = secretUse.approvalEvaluation
                ?.secrets
                ?.any {
                    it.secretName !in temporarySecretNames &&
                        it.action != ApprovalAction.APPROVE
                } == true,
            onConfirm = {
                confirmTemporaryAccess = false
                onAllowTemporarily()
            },
            onDismiss = { confirmTemporaryAccess = false },
        )
    }
}

@Composable
private fun SecretUseOutcome(secretUse: SecretUseRequestDetails) {
    val temporaryAccessScopes = secretUse.approvalEvaluation.temporaryAccessHistory()
    val aiReview = secretUse.approvalEvaluation?.aiReview
    val outcome = when (secretUse.state) {
        ApprovalRequestState.WAITING_FOR_COMPLETION -> OutcomeNotice(
            title = if (secretUse.decision == ApprovalDecision.APPROVED) {
                "Approved"
            } else {
                "Denied"
            },
            detail = "Waiting for the client to finish.",
            tone = if (secretUse.decision == ApprovalDecision.APPROVED) {
                NoticeTone.SUCCESS
            } else {
                NoticeTone.SUBDUED
            },
        )
        ApprovalRequestState.COMPLETED -> when (secretUse.completionResult) {
            ApprovalCompletionResult.APPROVED -> OutcomeNotice(
                "Delivered",
                "The client received the secret values.",
                NoticeTone.SUCCESS,
            )
            ApprovalCompletionResult.DENIED -> if (
                secretUse.completionReason == "INVALID_REQUEST"
            ) {
                OutcomeNotice(
                    "Request rejected",
                    secretUse.completionMessage ?: "The request was invalid.",
                    NoticeTone.DANGER,
                )
            } else {
                OutcomeNotice(
                    "Denied",
                    "No requested values were released.",
                    NoticeTone.SUBDUED,
                )
            }
            ApprovalCompletionResult.ABORTED -> OutcomeNotice(
                "Aborted",
                secretUse.completionMessage ?: "The client stopped this request.",
                NoticeTone.SUBDUED,
            )
            null -> OutcomeNotice("Completed", "The request is complete.")
        }
        ApprovalRequestState.VERIFICATION_FAILED -> OutcomeNotice(
            "Could not verify request",
            secretUse.error ?: "The cryptographic message was invalid.",
            NoticeTone.DANGER,
        )
        ApprovalRequestState.APPROVAL_PENDING -> return
    }
    Notice(outcome.title, outcome.detail, outcome.tone)
    if (
        shouldShowDecisionHistory(
            verificationFailed = secretUse.state == ApprovalRequestState.VERIFICATION_FAILED,
            completionReason = secretUse.completionReason,
        )
    ) {
        ApprovalDecisionHistory(
            decision = secretUse.decision,
            decisionSource = secretUse.decisionSource,
            aiReview = aiReview,
            temporaryAccessScopes = temporaryAccessScopes,
        )
    }
}

@Composable
private fun ApprovalDecisionHistory(
    decision: ApprovalDecision?,
    decisionSource: String?,
    aiReview: AiReview?,
    temporaryAccessScopes: String,
) {
    val approved = decision == ApprovalDecision.APPROVED
    when (decisionSource) {
        "rule" -> Notice(
            if (approved) "Approved by previous access" else "Denied by previous access",
            "A previously saved approval made this decision.",
            if (approved) NoticeTone.SUCCESS else NoticeTone.SUBDUED,
        )
        "policy" -> Notice(
            if (approved) "Approved automatically" else "Denied automatically",
            "Approval settings made this decision.",
            if (approved) NoticeTone.SUCCESS else NoticeTone.SUBDUED,
        )
        "ai" -> HistoricalAiReview(aiReview, decision)
        "temporary_access" -> {
            Notice(
                "Temporary access allowed",
                if (temporaryAccessScopes.isEmpty()) {
                    "A current temporary approval allowed this use."
                } else {
                    "$temporaryAccessScopes."
                },
                NoticeTone.SUCCESS,
            )
            HistoricalAiReview(aiReview, decision, "You allowed temporary access.")
        }
        "mixed" -> {
            Notice(
                "Multiple approvals used",
                if (temporaryAccessScopes.isEmpty()) {
                    "Temporary access allowed part of this use."
                } else {
                    "Temporary access allowed part of this use: $temporaryAccessScopes."
                },
                NoticeTone.SUCCESS,
            )
            HistoricalAiReview(aiReview, decision)
        }
        "non_sensitive" -> Notice(
            "No approval needed",
            "This request used only non-sensitive data.",
            NoticeTone.NEUTRAL,
        )
        else -> HistoricalAiReview(aiReview, decision)
    }
}

private fun ApprovalEvaluation?.temporaryAccessHistory(): String = this?.secrets
    ?.mapNotNull { secret ->
        secret.temporaryAccessExpiresAt?.let { expiresAt ->
            "${secret.secretName} until ${formatTimestamp(expiresAt)}"
        }
    }
    ?.joinToString("; ")
    .orEmpty()

private data class OutcomeNotice(
    val title: String,
    val detail: String,
    val tone: NoticeTone = NoticeTone.NEUTRAL,
)

@Composable
private fun SecretSummary(
    secret: SecretMetadata,
    environmentVariables: Map<String, String?>?,
) {
    Column(
        Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(secret.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (secret.type == "ssh") "SSH key" else "Environment variables",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (secret.description.isNotBlank()) {
            Text(
                secret.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (secret.type == "ssh") {
            Text(
                "The public key can be provided; private key operations remain protected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            secret.environmentVariableNames.forEach { name ->
                val deliveredName = secret.environmentVariableRename[name] ?: name
                val sentToStdin = secret.environmentVariableStdin == name
                EnvironmentVariableFact(
                    name = when {
                        sentToStdin -> "$name → standard input"
                        deliveredName != name -> "$name → $deliveredName"
                        else -> name
                    },
                    value = environmentVariables?.get(if (sentToStdin) name else deliveredName),
                )
            }
        }
    }
}

@Composable
private fun EnvironmentVariableFact(name: String, value: String?) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = with(density) { maxWidth.roundToPx() }
        val gap = with(density) { 12.dp.roundToPx() }
        val fitsOnOneLine = value == null || (
            '\n' !in value && '\r' !in value &&
                textMeasurer.measure(name, style = style, maxLines = 1).size.width +
                textMeasurer.measure(value, style = style, maxLines = 1).size.width + gap <=
                availableWidth
            )
        if (fitsOnOneLine) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = style, modifier = Modifier.weight(1f))
                if (value != null) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            value,
                            style = style,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    HiddenSensitiveValue(style)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = style)
                SelectionContainer {
                    Text(checkNotNull(value), style = style)
                }
            }
        }
    }
}

@Composable
private fun HiddenSensitiveValue(style: TextStyle) {
    Text(
        "••••••••",
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Sensitive value hidden"
        },
    )
}

private fun SecretUseRequestDetails.statusLabel(): String = secretUseStatusLabel(
    state,
    completionResult,
    completionReason,
)

private fun SecretUseRequestDetails.isError(): Boolean =
    state == ApprovalRequestState.VERIFICATION_FAILED || completionReason == "INVALID_REQUEST"
