@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun PlanAndBillingScreen(
    state: SubscriptionUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TopAppBar(
            title = { Text("Plan and billing") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.notice?.let { notice ->
                SubscriptionNoticeSurface(notice)
            }
            SubscriptionStatusSurface(state)
            InformationSurface {
                Text("Access", style = MaterialTheme.typography.titleMedium)
                InformationRow("Secret storage and release", "Included")
                InformationRow("Manual and rule-based approvals", "Included")
                InformationRow(
                    "AI review",
                    if (state.access == SubscriptionAccess.ACTIVE) "Active" else "Subscription required",
                )
            }
            Text(
                "Ask AI rules send request and secret metadata to Agentknock's AI reviewer. " +
                    "Secret values and private keys are never sent for AI review.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.access != SubscriptionAccess.ACTIVE) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.WorkspacePremium, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Subscribe with Google Play")
                }
                Text(
                    "Google Play subscriptions are not available yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.refreshing && !state.redeeming,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                }
                Spacer(Modifier.size(8.dp))
                Text("Refresh subscription status")
            }
        }
    }
}

@Composable
private fun SubscriptionStatusSurface(state: SubscriptionUiState) {
    val presentation = when (state.access) {
        SubscriptionAccess.CHECKING -> StatusPresentation(
            title = "Checking subscription",
            detail = "Contacting the relay…",
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
            icon = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
        )
        SubscriptionAccess.FREE -> StatusPresentation(
            title = "Free",
            detail = "All core Agentknock features are available. AI review is not active.",
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
            icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
        )
        SubscriptionAccess.ACTIVE -> StatusPresentation(
            title = "AI review active",
            detail = "Ask AI approval rules can use the Agentknock reviewer.",
            container = MaterialTheme.agentknockColors.successContainer,
            content = MaterialTheme.agentknockColors.onSuccessContainer,
            icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
        )
        SubscriptionAccess.UNAVAILABLE -> StatusPresentation(
            title = "Subscription status unavailable",
            detail = "Agentknock could not check the current status. Try again shortly.",
            container = MaterialTheme.agentknockColors.attentionContainer,
            content = MaterialTheme.agentknockColors.onAttentionContainer,
            icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
        )
    }
    Surface(
        color = presentation.container,
        contentColor = presentation.content,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presentation.icon()
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    presentation.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(presentation.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SubscriptionNoticeSurface(notice: SubscriptionNotice) {
    Surface(
        color = if (notice.successful) {
            MaterialTheme.agentknockColors.successContainer
        } else {
            MaterialTheme.agentknockColors.dangerContainer
        },
        contentColor = if (notice.successful) {
            MaterialTheme.agentknockColors.onSuccessContainer
        } else {
            MaterialTheme.agentknockColors.onDangerContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            notice.message,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private data class StatusPresentation(
    val title: String,
    val detail: String,
    val container: Color,
    val content: Color,
    val icon: @Composable () -> Unit,
)
