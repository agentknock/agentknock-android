package dev.agentknock.ui.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.subscription.AiReviewAccess

internal fun SecretApprovalMode.displayName(): String = when (this) {
    SecretApprovalMode.APPROVE -> "Allow"
    SecretApprovalMode.ASK_AI -> "Ask AI"
    SecretApprovalMode.ASK_ME -> "Ask me"
    SecretApprovalMode.DENY -> "Deny"
}

@Composable
internal fun ApprovalModeHelp() {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { showHelp = true }) {
        Icon(Icons.Outlined.Info, contentDescription = "About access options")
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Access options") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf(
                        "Deny" to "Automatically deny requests.",
                        "Ask" to "You approve or deny requests.",
                        "AI" to "Use your instructions to approve, deny, or ask you. Requires a subscription.",
                        "Allow" to "Automatically approve requests.",
                    ).forEach { (label, description) ->
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
                            append(" — $description")
                        })
                    }
                    Text("Clients without a custom setting follow the default.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("Close") }
            },
        )
    }
}

@Composable
internal fun ApprovalModeRow(
    title: String,
    selected: SecretApprovalMode,
    inherited: Boolean,
    defaultMode: SecretApprovalMode,
    aiReviewAccess: AiReviewAccess,
    onSelect: (SecretApprovalMode) -> Unit,
    onOpenPlan: () -> Unit,
    isClient: Boolean = false,
    onUseDefault: (() -> Unit)? = null,
) {
    var showAiInfo by rememberSaveable(title) { mutableStateOf(false) }
    LaunchedEffect(aiReviewAccess) {
        if (aiReviewAccess != AiReviewAccess.INACTIVE) showAiInfo = false
    }
    fun choose(mode: SecretApprovalMode, apply: () -> Unit) {
        if (mode != SecretApprovalMode.ASK_AI || aiReviewAccess == AiReviewAccess.ACTIVE) {
            apply()
        } else if (aiReviewAccess == AiReviewAccess.INACTIVE) {
            showAiInfo = true
        }
    }
    val canExploreAi = aiReviewAccess == AiReviewAccess.ACTIVE ||
        aiReviewAccess == AiReviewAccess.INACTIVE
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (inherited) 0.65f else 1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isClient) {
                Icon(Icons.Outlined.Computer, contentDescription = "Client", modifier = Modifier.size(18.dp))
            }
            Text(
                title,
                modifier = Modifier.weight(1f).alignByBaseline().padding(vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            onUseDefault?.let { clear ->
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(
                        modifier = Modifier.height(36.dp).alignByBaseline(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        onClick = { choose(defaultMode, clear) },
                        enabled = defaultMode != SecretApprovalMode.ASK_AI || canExploreAi,
                    ) { Text("Use default") }
                }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            approvalModes.forEachIndexed { index, mode ->
                val inactiveAi = mode == SecretApprovalMode.ASK_AI &&
                    aiReviewAccess == AiReviewAccess.INACTIVE
                SegmentedButton(
                    selected = selected == mode,
                    shape = SegmentedButtonDefaults.itemShape(index, approvalModes.size),
                    modifier = Modifier.fillMaxHeight().semantics {
                        if (inactiveAi) stateDescription = "Requires subscription"
                    },
                    enabled = mode != SecretApprovalMode.ASK_AI || canExploreAi,
                    onClick = { choose(mode) { onSelect(mode) } },
                    colors = if (inactiveAi) {
                        val mutedText = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        SegmentedButtonDefaults.colors(
                            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            inactiveContentColor = mutedText,
                            activeContentColor = mutedText,
                        )
                    } else SegmentedButtonDefaults.colors(),
                    border = SegmentedButtonDefaults.borderStroke(
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    icon = {},
                    label = {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when (mode) {
                                    SecretApprovalMode.ASK_ME -> "Ask"
                                    SecretApprovalMode.ASK_AI -> "AI"
                                    else -> mode.displayName()
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                    },
                )
            }
        }
        val explanation = when (aiReviewAccess) {
            AiReviewAccess.INACTIVE -> if (selected == SecretApprovalMode.ASK_AI) {
                "Requests will ask you instead. Your Ask AI setting will resume when AI review is active."
            } else null
            AiReviewAccess.CHECKING -> "Checking AI access…"
            AiReviewAccess.ACTIVATING -> "Activating AI review…"
            AiReviewAccess.UNAVAILABLE -> "AI access couldn’t be checked. You can still decide requests yourself."
            AiReviewAccess.SETUP_REQUIRED -> "Finish device setup to check AI access."
            AiReviewAccess.ACTIVE -> null
        }
        if (!inherited && explanation != null) {
            Text(explanation, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showAiInfo && aiReviewAccess == AiReviewAccess.INACTIVE) {
        AlertDialog(
            onDismissRequest = { showAiInfo = false },
            title = { Text("AI review") },
            text = {
                Text(
                    "Ask AI uses your instructions to approve, deny, or leave requests for you to decide. " +
                        "It requires an AI review subscription. Manual approval is always available.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showAiInfo = false; onOpenPlan() }) { Text("View plans") }
            },
            dismissButton = {
                TextButton(onClick = { showAiInfo = false }) { Text("Close") }
            },
        )
    }
}

private val approvalModes = listOf(
    SecretApprovalMode.DENY,
    SecretApprovalMode.ASK_ME,
    SecretApprovalMode.ASK_AI,
    SecretApprovalMode.APPROVE,
)
