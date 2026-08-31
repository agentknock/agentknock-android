@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.request.ApprovalCompletionResult
import dev.agentknock.storage.request.ApprovalDecision
import dev.agentknock.storage.request.InvocationDecisionResult
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.request.GitSignDecisionResult
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.SecretUploadDecisionResult
import dev.agentknock.storage.request.SshAuthenticationDecisionResult
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.NavigationBackButton
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun AiReviewNotice(review: AiReview?, reviewInFlight: Boolean) {
    when {
        review?.decision == AiReviewDecision.APPROVE -> Notice(
            "AI review approved its part",
            review.explanationText() ?: "Another protected use still needs your decision.",
            NoticeTone.SUCCESS,
        )
        review?.decision == AiReviewDecision.ASK_USER -> Notice(
            "AI review asked you to decide",
            review.explanationText() ?: "The reviewer could not decide safely.",
            NoticeTone.ATTENTION,
        )
        review?.failure == AiReviewFailure.SUBSCRIPTION_REQUIRED -> Notice(
            "AI review requires a subscription",
            "Decide this request yourself, or activate AI review in Plan and billing.",
            NoticeTone.ATTENTION,
        )
        review?.failure != null -> Notice(
            "AI review unavailable",
            "The request was left for you to decide.",
            NoticeTone.ATTENTION,
        )
        reviewInFlight -> Unit
        else -> Notice(
            "AI review was interrupted",
            "Decide this request yourself.",
            NoticeTone.ATTENTION,
        )
    }
}

@Composable
internal fun RequestDecisionButtons(
    approveLabel: String,
    approveEnabled: Boolean,
    temporaryAccessAvailable: Boolean,
    onDeny: () -> Unit,
    onApprove: () -> Unit,
    onAllowTemporarily: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.agentknockColors.danger,
                ),
                border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
            ) {
                Text("Deny once")
            }
            Button(
                onClick = onApprove,
                enabled = approveEnabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.agentknockColors.success,
                    contentColor = MaterialTheme.agentknockColors.onSuccess,
                ),
            ) {
                Text(approveLabel)
            }
        }
        if (temporaryAccessAvailable) {
            FilledTonalButton(
                onClick = onAllowTemporarily,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Allow for 4 hours")
            }
        }
    }
}

@Composable
internal fun TemporaryAccessConfirmation(
    clientName: String,
    secretNames: List<String>,
    operation: TemporaryAccessOperation,
    approvesOtherUsesOnce: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow $clientName for 4 hours?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(temporaryAccessScope(clientName, operation))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 144.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        secretNames.forEach { secretName ->
                            Text("• $secretName", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    "Agentknock will not ask you or AI about these uses for the next 4 hours.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (approvesOtherUsesOnce) {
                    Text(
                        "Other protected uses in this request are approved once and do not gain temporary access.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Allow 4 hours") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun temporaryAccessScope(
    clientName: String,
    operation: TemporaryAccessOperation,
): String = when (operation) {
    TemporaryAccessOperation.INVOCATION ->
        "For any command, $clientName can receive protected values from:"
    TemporaryAccessOperation.GIT_SIGN ->
        "For any repository, $clientName can request Git signatures from:"
    TemporaryAccessOperation.SSH_AUTHENTICATE ->
        "For any SSH server, $clientName can request SSH authentication from:"
}

internal fun ApprovalEvaluation?.temporaryGrantSecretNames(
    aiReviewInFlight: Boolean,
): List<String> {
    val evaluation = this ?: return emptyList()
    if (aiReviewInFlight) return emptyList()
    val aiCanEscalate = evaluation.aiReview?.decision == AiReviewDecision.ASK_USER ||
        evaluation.aiReview?.failure != null ||
        evaluation.aiReview == null
    return evaluation.secrets.filter { secret ->
        secret.temporaryAccessEligible && secret.temporaryAccessExpiresAt == null &&
            (secret.action == ApprovalAction.ASK_ME ||
                (secret.action == ApprovalAction.ASK_AI && aiCanEscalate))
    }.map { it.secretName }
}

@Composable
internal fun HistoricalAiReview(
    review: AiReview?,
    decision: ApprovalDecision?,
    humanResolution: String? = null,
) {
    review ?: return
    val explanation = review.explanationText()
    when (review.decision) {
        AiReviewDecision.APPROVE -> Notice(
            "AI review approved",
            explanation ?: "AI review allowed its part of this use.",
            NoticeTone.SUCCESS,
        )
        AiReviewDecision.DENY -> Notice(
            "AI review denied",
            explanation ?: "AI review denied its part of this use.",
            NoticeTone.SUBDUED,
        )
        AiReviewDecision.ASK_USER -> {
            val resolution = humanResolution ?: when (decision) {
                ApprovalDecision.APPROVED -> "You approved it once."
                ApprovalDecision.DENIED -> "You denied it."
                null -> null
            }
            Notice(
                "AI review asked you to decide",
                listOfNotNull(explanation, resolution).joinToString(" ")
                    .ifBlank { "You made the final decision." },
                NoticeTone.NEUTRAL,
            )
        }
        null -> if (review.failure != null) {
            Notice(
                "AI review unavailable",
                humanResolution ?: "You made the final decision.",
                NoticeTone.NEUTRAL,
            )
        }
    }
}

internal fun AiReview.explanationText(): String? = explanation
    ?.trim()
    ?.let { text ->
        val labels = when (decision) {
            AiReviewDecision.APPROVE -> listOf("Approve:", "Approved:")
            AiReviewDecision.DENY -> listOf("Deny:", "Denied:")
            AiReviewDecision.ASK_USER -> listOf("Ask:", "Ask user:")
            null -> emptyList()
        }
        labels.firstOrNull { text.startsWith(it, ignoreCase = true) }
            ?.let { text.drop(it.length).trimStart() }
            ?: text
    }
    ?.replace("**", "")
    ?.replace("`", "")
    ?.takeIf(String::isNotEmpty)

@Composable
internal fun DetailPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    showBack: Boolean,
    scrollResetKey: Any? = null,
    titleContent: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
    bottomContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollResetKey) {
        scrollState.scrollTo(0)
    }
    Column(modifier) {
        TopAppBar(
            title = titleContent,
            navigationIcon = {
                if (showBack) {
                    NavigationBackButton(onBack)
                }
            },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
        bottomContent?.invoke()
    }
}

@Composable
internal fun Disclosure(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun Notice(
    title: String,
    detail: String,
    tone: NoticeTone = NoticeTone.NEUTRAL,
) {
    val semanticColors = MaterialTheme.agentknockColors
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (tone) {
                NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerLow
                NoticeTone.ATTENTION -> semanticColors.attentionContainer
                NoticeTone.SUCCESS -> semanticColors.successContainer
                NoticeTone.DANGER -> semanticColors.dangerContainer
                NoticeTone.SUBDUED -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = when (tone) {
                NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
                NoticeTone.ATTENTION -> semanticColors.onAttentionContainer
                NoticeTone.SUCCESS -> semanticColors.onSuccessContainer
                NoticeTone.DANGER -> semanticColors.onDangerContainer
                NoticeTone.SUBDUED -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail)
        }
    }
}

@Composable
internal fun StatusLine(
    label: String,
    error: Boolean = false,
    attention: Boolean = false,
    subdued: Boolean = false,
) {
    val semanticColors = MaterialTheme.agentknockColors
    Surface(
        color = when {
            error -> semanticColors.dangerContainer
            attention -> semanticColors.attentionContainer
            subdued -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> semanticColors.successContainer
        },
        contentColor = when {
            error -> semanticColors.onDangerContainer
            attention -> semanticColors.onAttentionContainer
            subdued -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> semanticColors.onSuccessContainer
        },
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
internal fun DetailValue(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(value, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default)
        }
    }
}

@Composable
internal fun MissingRequestDetail(
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    DetailPage("Request", onBack, modifier, showBack = showBack) {
        Notice(
            "Request unavailable",
            "This request has no displayable details.",
            NoticeTone.DANGER,
        )
    }
}

internal enum class NoticeTone {
    NEUTRAL,
    ATTENTION,
    SUCCESS,
    DANGER,
    SUBDUED,
}

internal fun shouldShowDecisionHistory(
    verificationFailed: Boolean,
    completionReason: String?,
): Boolean = !verificationFailed && completionReason != "INVALID_REQUEST"

internal fun secretUseStatusLabel(
    state: ApprovalRequestState,
    result: ApprovalCompletionResult?,
    completionReason: String?,
): String = when (state) {
    ApprovalRequestState.APPROVAL_PENDING -> "Needs approval"
    ApprovalRequestState.WAITING_FOR_COMPLETION -> "Waiting for client"
    ApprovalRequestState.VERIFICATION_FAILED -> "Verification failed"
    ApprovalRequestState.COMPLETED -> when (result) {
        ApprovalCompletionResult.APPROVED -> "Delivered"
        ApprovalCompletionResult.DENIED -> if (completionReason == "INVALID_REQUEST") {
            "Invalid request"
        } else {
            "Denied"
        }
        ApprovalCompletionResult.ABORTED -> "Aborted"
        null -> "Completed"
    }
}

internal fun gitSignStatusLabel(
    state: ApprovalRequestState,
    result: ApprovalCompletionResult?,
    completionReason: String?,
): String = when (state) {
    ApprovalRequestState.APPROVAL_PENDING -> "Needs approval"
    ApprovalRequestState.WAITING_FOR_COMPLETION -> "Waiting for client"
    ApprovalRequestState.VERIFICATION_FAILED -> "Verification failed"
    ApprovalRequestState.COMPLETED -> when (result) {
        ApprovalCompletionResult.APPROVED -> "Signed"
        ApprovalCompletionResult.DENIED -> if (completionReason == "INVALID_REQUEST") {
            "Invalid request"
        } else {
            "Denied"
        }
        ApprovalCompletionResult.ABORTED -> "Aborted"
        null -> "Completed"
    }
}

internal fun sshAuthenticationStatusLabel(
    state: ApprovalRequestState,
    result: ApprovalCompletionResult?,
    completionReason: String?,
): String = when (state) {
    ApprovalRequestState.APPROVAL_PENDING -> "Needs approval"
    ApprovalRequestState.WAITING_FOR_COMPLETION -> "Waiting for client"
    ApprovalRequestState.VERIFICATION_FAILED -> "Verification failed"
    ApprovalRequestState.COMPLETED -> when (result) {
        ApprovalCompletionResult.APPROVED -> "Authenticated"
        ApprovalCompletionResult.DENIED -> if (completionReason == "INVALID_REQUEST") {
            "Invalid request"
        } else {
            "Denied"
        }
        ApprovalCompletionResult.ABORTED -> "Aborted"
        null -> "Completed"
    }
}

internal fun PairingDecisionResult.message(): String = when (this) {
    PairingDecisionResult.VERIFIED -> "Pairing code verified"
    PairingDecisionResult.REJECTED -> "Pairing rejected"
    PairingDecisionResult.NOT_PENDING -> "This pairing no longer needs a decision"
    PairingDecisionResult.NOT_FOUND -> "Request is no longer available"
}

internal fun InvocationDecisionResult.message(): String = when (this) {
    InvocationDecisionResult.Decided -> "Decision saved"
    InvocationDecisionResult.SecretsChanged ->
        "A requested secret changed; review the request again"
    InvocationDecisionResult.NotPending -> "This request no longer needs a decision"
    InvocationDecisionResult.NotFound -> "Request is no longer available"
    is InvocationDecisionResult.MissingSecrets -> "Missing secrets: ${names.joinToString()}"
    is InvocationDecisionResult.ConflictingVariable ->
        "Conflicting environment variable: $name"
    is InvocationDecisionResult.Invalid -> message
    InvocationDecisionResult.SecretUnavailable ->
        "A secret value is unavailable on this device"
    InvocationDecisionResult.SecretCorrupted -> "A secret value could not be authenticated"
    InvocationDecisionResult.UnsupportedEncryption ->
        "A secret value uses unsupported encryption"
    InvocationDecisionResult.PairingUnavailable -> "The paired client is unavailable"
    InvocationDecisionResult.TemporaryAccessUnavailable ->
        "Temporary access is no longer available"
    InvocationDecisionResult.TemporaryAccessNotStarted ->
        "Request approved once, but temporary access could not be started"
}

internal fun GitSignDecisionResult.message(): String = when (this) {
    GitSignDecisionResult.Decided -> "Decision saved"
    GitSignDecisionResult.NotPending ->
        "This Git signing request no longer needs a decision"
    GitSignDecisionResult.NotFound -> "Git signing request is no longer available"
    GitSignDecisionResult.InvocationUnavailable -> "The original command request is unavailable"
    GitSignDecisionResult.PairingUnavailable -> "The paired client is unavailable"
    GitSignDecisionResult.ApprovalChanged ->
        "The SSH key or approval setting changed; review the request again"
    GitSignDecisionResult.KeyChanged ->
        "The SSH key changed or was renamed after the command began; start the command again"
    GitSignDecisionResult.SecretUnavailable ->
        "The SSH private key is unavailable on this device"
    GitSignDecisionResult.SecretCorrupted ->
        "The SSH private key could not be authenticated"
    GitSignDecisionResult.UnsupportedEncryption ->
        "The SSH private key uses unsupported encryption"
    GitSignDecisionResult.TemporaryAccessUnavailable ->
        "Temporary access is no longer available"
    GitSignDecisionResult.TemporaryAccessNotStarted ->
        "Signature approved once, but temporary access could not be started"
}

internal fun SshAuthenticationDecisionResult.message(): String = when (this) {
    SshAuthenticationDecisionResult.Decided -> "Decision saved"
    SshAuthenticationDecisionResult.NotPending ->
        "This SSH authentication request no longer needs a decision"
    SshAuthenticationDecisionResult.NotFound ->
        "SSH authentication request is no longer available"
    SshAuthenticationDecisionResult.InvocationUnavailable ->
        "The original command request is unavailable"
    SshAuthenticationDecisionResult.PairingUnavailable ->
        "The paired client is unavailable"
    SshAuthenticationDecisionResult.ApprovalChanged ->
        "The SSH key or approval setting changed; review the request again"
    SshAuthenticationDecisionResult.KeyChanged ->
        "The SSH key changed or was renamed after the command began; start the command again"
    SshAuthenticationDecisionResult.InvalidMessage ->
        "The SSH authentication data changed or is invalid; start the command again"
    SshAuthenticationDecisionResult.SecretUnavailable ->
        "The SSH private key is unavailable on this device"
    SshAuthenticationDecisionResult.SecretCorrupted ->
        "The SSH private key could not be authenticated"
    SshAuthenticationDecisionResult.UnsupportedEncryption ->
        "The SSH private key uses unsupported encryption"
    SshAuthenticationDecisionResult.TemporaryAccessUnavailable ->
        "Temporary access is no longer available"
    SshAuthenticationDecisionResult.TemporaryAccessNotStarted ->
        "Authentication approved once, but temporary access could not be started"
}

internal fun SecretUploadDecisionResult.message(): String = when (this) {
    is SecretUploadDecisionResult.Approved -> "Secret upload approved"
    SecretUploadDecisionResult.Rejected -> "Secret upload rejected"
    SecretUploadDecisionResult.NotPending -> "This upload no longer needs a decision"
    SecretUploadDecisionResult.NotFound -> "Upload is no longer available"
    is SecretUploadDecisionResult.Invalid -> message
    SecretUploadDecisionResult.SecretUnavailable ->
        "An uploaded value is unavailable on this device"
    SecretUploadDecisionResult.SecretCorrupted ->
        "An uploaded value could not be authenticated"
    SecretUploadDecisionResult.UnsupportedEncryption ->
        "An uploaded value uses unsupported encryption"
}
