package dev.agentknock.ui.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.subscription.AiReviewAccess

internal fun SecretApprovalMode.displayName(): String = when (this) {
    SecretApprovalMode.APPROVE -> "Approve"
    SecretApprovalMode.ASK_AI -> "Ask AI"
    SecretApprovalMode.ASK_ME -> "Ask me"
    SecretApprovalMode.DENY -> "Deny"
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
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (inherited) {
                    Text(
                        "Using default: ${selected.displayName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (onUseDefault != null) {
                    Text("Custom setting", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            onUseDefault?.let { clear ->
                TextButton(
                    onClick = { choose(defaultMode, clear) },
                    enabled = defaultMode != SecretApprovalMode.ASK_AI || canExploreAi,
                ) { Text("Use default") }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = 2,
        ) {
            approvalModes.forEach { mode ->
                val inactiveAi = mode == SecretApprovalMode.ASK_AI &&
                    aiReviewAccess == AiReviewAccess.INACTIVE
                FilterChip(
                    selected = selected == mode,
                    enabled = mode != SecretApprovalMode.ASK_AI || canExploreAi,
                    onClick = { choose(mode) { onSelect(mode) } },
                    label = {
                        Column {
                            Text(mode.displayName())
                            if (inactiveAi) {
                                Text(
                                    if (selected == mode) "Inactive" else "Requires subscription",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    leadingIcon = when {
                        selected == mode -> {
                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        }
                        inactiveAi -> {
                            { Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        }
                        else -> null
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
        explanation?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
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
