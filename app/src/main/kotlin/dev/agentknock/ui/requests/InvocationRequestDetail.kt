package dev.agentknock.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Key
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentknock.presentation.approvalSummary
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.presentation.renderShellWord
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.protocol.relayRequestTimestamp
import dev.agentknock.review.ApprovalReviewEnvironmentDelivery
import dev.agentknock.review.ApprovalReviewEnvironmentSecretFacts
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.request.ApprovalCompletionResult
import dev.agentknock.storage.request.ApprovalDecision
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.SecretUseRequestDetails
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.DetailPage
import dev.agentknock.ui.components.DetailValue
import dev.agentknock.ui.components.Disclosure
import dev.agentknock.ui.components.Notice
import dev.agentknock.ui.components.NoticeTone
import dev.agentknock.ui.components.TonalIcon
import dev.agentknock.ui.components.rememberDateTimeFormatter

@Composable
internal fun InvocationRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAllowTemporarily: () -> Unit,
    modifier: Modifier,
    scriptInitiallyExpanded: Boolean = false,
) {
    val dates = rememberDateTimeFormatter()
    val secretUse = (request.content as InboxRequestContent.SecretUse).details
    var confirmTemporaryAccess by remember(request.id) { mutableStateOf(false) }
    val aiReviewRequested =
        secretUse.approvalEvaluation?.secrets?.any { it.action == ApprovalAction.ASK_AI } == true
    val aiReviewInFlight = request.state == InboxRequestState.REVIEWING && aiReviewRequested
    val temporarySecretNames =
        secretUse.approvalEvaluation.temporaryGrantSecretNames(aiReviewInFlight)
    val requestedAt = relayRequestTimestamp(request.id) ?: request.receivedAt
    DetailPage(
        title = "Secret use",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = secretUse.state to secretUse.completionResult,
        bottomContent =
            if (request.state == InboxRequestState.ACTION_REQUIRED) {
                {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 3.dp,
                    ) {
                        RequestDecisionButtons(
                            approveEnabled = secretUse.missingSecrets.isEmpty(),
                            temporaryAccessAvailable =
                                temporarySecretNames.isNotEmpty() &&
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
        val status =
            if (secretUse.state == ApprovalRequestState.APPROVAL_PENDING) {
                when {
                    aiReviewInFlight ->
                        StatusSummary(
                            "AI review in progress",
                            tone = NoticeTone.SUBDUED,
                            icon = Icons.Outlined.AutoAwesome,
                        )
                    secretUse.isError() ->
                        StatusSummary(
                            secretUse.statusLabel(),
                            tone = NoticeTone.DANGER,
                            icon = Icons.Outlined.ErrorOutline,
                        )
                    request.state == InboxRequestState.ACTION_REQUIRED ->
                        StatusSummary(
                            secretUse.statusLabel(),
                            tone = NoticeTone.ATTENTION,
                        )
                    else ->
                        StatusSummary(
                            secretUse.statusLabel(),
                            tone =
                                if (
                                    secretUse.decision == ApprovalDecision.DENIED ||
                                        secretUse.completionResult ==
                                            ApprovalCompletionResult.DENIED ||
                                        secretUse.completionResult ==
                                            ApprovalCompletionResult.ABORTED
                                ) {
                                    NoticeTone.SUBDUED
                                } else {
                                    NoticeTone.SUCCESS
                                },
                        )
                }
            } else {
                secretUse.outcome()
            }
        StatusHeader(status, dates.timestamp(requestedAt))

        RequestTicket(
            clientName = secretUse.clientName,
            secretNames = secretUse.secrets,
            command = secretUse.command,
            arguments = secretUse.arguments,
            reason = secretUse.reason,
        )

        secretUse.scriptContents?.let {
            ScriptContents(it, scriptInitiallyExpanded)
        }

        if (secretUse.state == ApprovalRequestState.APPROVAL_PENDING) {
            val evaluation = secretUse.approvalEvaluation
            if (evaluation?.aiReview != null || aiReviewRequested) {
                AiReviewNotice(evaluation.aiReview, aiReviewInFlight)
            }
        } else if (
            shouldShowDecisionHistory(
                verificationFailed = secretUse.state == ApprovalRequestState.VERIFICATION_FAILED,
                completionReason = secretUse.completionReason,
            )
        ) {
            ApprovalDecisionHistory(
                decision = secretUse.decision,
                decisionSource = secretUse.decisionSource,
                aiReview = secretUse.approvalEvaluation?.aiReview,
                temporaryAccessScopes =
                    secretUse.approvalEvaluation.temporaryAccessHistory(dates::timestamp),
            )
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

        if (secretUse.secretDetails.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(
                    if (secretUse.secretDetails.size == 1) "Requested secret"
                    else "Requested secrets"
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        secretUse.secretDetails.forEachIndexed { index, secret ->
                            SecretSummary(secret, secretUse.environmentVariables[secret.name])
                            if (index != secretUse.secretDetails.lastIndex) {
                                HorizontalDivider(Modifier.padding(start = 72.dp))
                            }
                        }
                    }
                }
            }
        }

        Disclosure("Technical details") {
            DetailValue(
                "Requested",
                dates.timestamp(requestedAt, includeSeconds = true),
            )
            Text(
                "Process information reported by the client",
                style = MaterialTheme.typography.labelLarge,
            )
            DetailValue("Working directory", secretUse.workingDirectory, true)
            DetailValue("Execution mode", secretUse.executableMode)
            DetailValue("Standard input", secretUse.stdinKind)
            if (secretUse.launcherChain.isNotEmpty()) {
                DetailValue("Launcher chain", secretUse.launcherChain.joinToString(" → "), true)
            }
            DetailValue("Executable path", secretUse.executablePath, true)
            secretUse.executableHash?.let { DetailValue("Executable hash", it, true) }
            DetailValue("Standard output", secretUse.stdoutKind)
            DetailValue("Standard error", secretUse.stderrKind)
            secretUse.platform?.let {
                DetailValue("Platform reported by client", formatPlatformName(it))
            }
            secretUse.architecture?.let { DetailValue("Architecture reported by client", it) }
            secretUse.hostname
                ?.takeIf { it != secretUse.clientName }
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
            HorizontalDivider()
            DetailValue("Client ID", secretUse.clientId, true)
            DetailValue("Request ID", request.id, true)
            secretUse.decidedAt?.let {
                DetailValue("Decided", dates.timestamp(it, includeSeconds = true))
            }
            request.completedAt?.let {
                DetailValue("Completed", dates.timestamp(it, includeSeconds = true))
            }
        }
    }
    if (confirmTemporaryAccess) {
        TemporaryAccessConfirmation(
            clientName = secretUse.clientName,
            secretNames = temporarySecretNames,
            operation = TemporaryAccessOperation.INVOCATION,
            approvesOtherUsesOnce =
                secretUse.approvalEvaluation?.secrets?.any {
                    it.secretName !in temporarySecretNames && it.action != ApprovalAction.APPROVE
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
private fun RequestTicket(
    clientName: String,
    secretNames: List<String>,
    command: String,
    arguments: List<String>,
    reason: String?,
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
            IdentityColumns(clientName, secretNames)
            reason?.takeIf(String::isNotBlank)?.let { ReasonQuote(it, clientName) }
            CommandBlock(command, arguments)
        }
    }
}

/** Commands longer than this, or with more arguments, are listed one argument per line. */
private const val INLINE_COMMAND_MAX_LENGTH = 72
private const val INLINE_COMMAND_MAX_ARGUMENTS = 6

@Composable
private fun CommandBlock(command: String, arguments: List<String>) {
    val renderedCommand = renderShellCommand(command, arguments)
    val listed =
        renderedCommand.length > INLINE_COMMAND_MAX_LENGTH ||
            arguments.size > INLINE_COMMAND_MAX_ARGUMENTS
    val style =
        MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = if (listed) 15.sp else 16.sp,
            lineHeight = if (listed) 22.sp else 24.sp,
            textIndent = if (listed) TextIndent(restLine = 16.sp) else TextIndent.None,
        )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer {
                Text(
                    annotatedCommand(
                        command = command,
                        arguments = arguments,
                        listed = listed,
                        mutedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        optionColor = MaterialTheme.colorScheme.tertiary,
                    ),
                    style = style,
                )
            }
            if (renderedCommand.any { it.code > 0x7e }) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        "This command contains non-ASCII characters.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            Text(
                if (listed) "Command · ${arguments.size} arguments, one per line" else "Command",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shell line continuation, so a copied multi-line listing still pastes as one command. */
internal const val COMMAND_LINE_CONTINUATION = " \\\n"

/**
 * The shell-rendered executable word split after its last slash: directory (may be empty) and name.
 */
internal fun renderedExecutable(command: String): Pair<String, String> {
    val executable = renderShellWord(command)
    val directoryLength = executable.lastIndexOf('/') + 1
    return executable.substring(0, directoryLength) to executable.substring(directoryLength)
}

/**
 * The same words as [renderShellCommand]. When [listed], each argument starts a new line and the
 * previous line ends with a backslash continuation, so selecting and copying the block yields an
 * equivalent shell command. The executable's directory and the continuation marks are muted so the
 * program name and arguments stand out; option-like words are tinted. Nothing is removed or
 * reordered unless [includeDirectory] is false, which lets a summary show the directory separately.
 */
internal fun annotatedCommand(
    command: String,
    arguments: List<String>,
    listed: Boolean,
    mutedColor: Color,
    optionColor: Color,
    includeDirectory: Boolean = true,
): AnnotatedString = buildAnnotatedString {
    val (directory, name) = renderedExecutable(command)
    if (includeDirectory && directory.isNotEmpty()) {
        withStyle(SpanStyle(color = mutedColor)) { append(directory) }
    }
    append(name)
    arguments.forEach { argument ->
        if (listed) {
            withStyle(SpanStyle(color = mutedColor)) {
                append(COMMAND_LINE_CONTINUATION, 0, COMMAND_LINE_CONTINUATION.length - 1)
            }
            append(COMMAND_LINE_CONTINUATION.last())
        } else {
            append(' ')
        }
        val word = renderShellWord(argument)
        if (word.startsWith("-") && word.length > 1) {
            withStyle(SpanStyle(color = optionColor)) { append(word) }
        } else {
            append(word)
        }
    }
}

private fun SecretUseRequestDetails.outcome(): StatusSummary =
    when (state) {
        ApprovalRequestState.WAITING_FOR_COMPLETION ->
            if (decision == ApprovalDecision.APPROVED) {
                StatusSummary(
                    "Approved",
                    NoticeTone.SUCCESS,
                    Icons.Outlined.HourglassTop,
                    "Waiting for the client to finish.",
                )
            } else {
                StatusSummary(
                    "Denied",
                    NoticeTone.SUBDUED,
                    Icons.Outlined.HourglassTop,
                    "Waiting for the client to finish.",
                )
            }
        ApprovalRequestState.COMPLETED ->
            when (completionResult) {
                ApprovalCompletionResult.APPROVED ->
                    StatusSummary(
                        "Delivered",
                        NoticeTone.SUCCESS,
                        Icons.Outlined.CheckCircle,
                        "The client received the requested data.",
                    )
                ApprovalCompletionResult.DENIED ->
                    if (completionReason == "INVALID_REQUEST") {
                        StatusSummary(
                            "Request rejected",
                            NoticeTone.DANGER,
                            Icons.Outlined.ErrorOutline,
                            completionMessage ?: "The request was invalid.",
                        )
                    } else {
                        StatusSummary(
                            "Denied",
                            NoticeTone.SUBDUED,
                            Icons.Outlined.Block,
                            "No requested values were released.",
                        )
                    }
                ApprovalCompletionResult.ABORTED ->
                    StatusSummary(
                        "Aborted",
                        NoticeTone.SUBDUED,
                        Icons.Outlined.Block,
                        completionMessage ?: "The client stopped this request.",
                    )
                null ->
                    if (error != null) {
                        StatusSummary("Request ended", NoticeTone.NEUTRAL, null, error)
                    } else {
                        StatusSummary(
                            "Completed",
                            NoticeTone.NEUTRAL,
                            null,
                            "The request is complete.",
                        )
                    }
            }
        ApprovalRequestState.VERIFICATION_FAILED ->
            StatusSummary(
                "Could not verify request",
                NoticeTone.DANGER,
                Icons.Outlined.ErrorOutline,
                error ?: "The cryptographic message was invalid.",
            )
        ApprovalRequestState.APPROVAL_PENDING -> StatusSummary(statusLabel(), NoticeTone.ATTENTION)
    }

@Composable
private fun ApprovalDecisionHistory(
    decision: ApprovalDecision?,
    decisionSource: String?,
    aiReview: AiReview?,
    temporaryAccessScopes: String,
) {
    when (decisionSource) {
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
        else -> {
            approvalSummary(null, decision, decisionSource)?.let {
                Text(it, style = MaterialTheme.typography.labelLarge)
            }
            HistoricalAiReview(aiReview, decision)
        }
    }
}

private fun ApprovalEvaluation?.temporaryAccessHistory(formatTimestamp: (Long) -> String): String =
    this?.secrets
        ?.mapNotNull { secret ->
            secret.temporaryAccessExpiresAt?.let { expiresAt ->
                "${secret.secretName} until ${formatTimestamp(expiresAt)}"
            }
        }
        ?.joinToString("; ")
        .orEmpty()

@Composable
private fun SecretSummary(
    secret: SecretMetadata,
    environmentVariables: ApprovalReviewEnvironmentSecretFacts?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TonalIcon(Icons.Outlined.Key, contentDescription = null)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(secret.name.breakableAtHyphens(), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                        if (secret.type == "ssh") "SSH key" else "Environment variables",
                        secret.description.takeIf(String::isNotBlank),
                    )
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (secret.type == "ssh") {
                Text(
                    "The public key can be provided; private key operations remain protected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (environmentVariables != null) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    environmentVariables.variables.forEach { (source, variable) ->
                        val label =
                            when (variable.delivery) {
                                ApprovalReviewEnvironmentDelivery.ENVIRONMENT ->
                                    if (variable.target == source) source
                                    else "$source → ${variable.target}"
                                ApprovalReviewEnvironmentDelivery.STANDARD_INPUT ->
                                    "$source → standard input"
                                ApprovalReviewEnvironmentDelivery.OMITTED ->
                                    "$source · Not provided"
                            }
                        EnvironmentVariableFact(
                            name = label,
                            value = variable.value,
                            omitted =
                                variable.delivery == ApprovalReviewEnvironmentDelivery.OMITTED,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    secret.environmentVariableNames.forEach { name ->
                        val deliveredName = secret.environmentVariableRename[name] ?: name
                        val sentToStdin = secret.environmentVariableStdin == name
                        Text(
                            text =
                                when {
                                    sentToStdin -> "$name → standard input"
                                    deliveredName != name -> "$name → $deliveredName"
                                    else -> name
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Text(
                    "Values were not recorded for this request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EnvironmentVariableFact(name: String, value: String?, omitted: Boolean = false) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = with(density) { maxWidth.roundToPx() }
        val gap = with(density) { 12.dp.roundToPx() }
        val fitsOnOneLine =
            value == null ||
                ('\n' !in value &&
                    '\r' !in value &&
                    textMeasurer.measure(name, style = style, maxLines = 1).size.width +
                        textMeasurer.measure(value, style = style, maxLines = 1).size.width +
                        gap <= availableWidth)
        if (fitsOnOneLine) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = style, modifier = Modifier.weight(1f))
                if (value != null) {
                    SelectionContainer {
                        Text(
                            value,
                            style = style,
                            textAlign = TextAlign.End,
                        )
                    }
                } else if (!omitted) {
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
        modifier =
            Modifier.clearAndSetSemantics {
                contentDescription = "Sensitive value hidden"
            },
    )
}

private fun SecretUseRequestDetails.statusLabel(): String =
    secretUseStatusLabel(
        state,
        completionResult,
        completionReason,
    )

private fun SecretUseRequestDetails.isError(): Boolean =
    state == ApprovalRequestState.VERIFICATION_FAILED || completionReason == "INVALID_REQUEST"
