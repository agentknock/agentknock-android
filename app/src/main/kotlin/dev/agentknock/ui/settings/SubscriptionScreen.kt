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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentknock.subscription.PlaySubscriptionOffer
import dev.agentknock.subscription.PlaySubscriptionOfferId
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun SubscriptionAndBillingScreen(
    state: SubscriptionUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSubscribe: (PlaySubscriptionOfferId) -> Unit,
    onManageSubscription: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state.access == SubscriptionAccess.ACTIVE
    val busy = state.refreshing || state.redeeming || state.purchasing
    Column(modifier) {
        PageTopBar("Plan and billing", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.notice?.let { SubscriptionNoticeSurface(it) }

            Text(
                "Agentknock's core features are free. A subscription adds AI review to the approval choices already available on your device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AccessCard(
                title = "Included for everyone",
                status = "Always free",
                highlighted = false,
            ) {
                IncludedFeature("Store and release secrets")
                IncludedFeature("Manual approvals")
                IncludedFeature("Temporary access")
            }

            AccessCard(
                title = "AI review",
                status = when (state.access) {
                    SubscriptionAccess.ACTIVE -> "Active"
                    SubscriptionAccess.CHECKING -> "Checking"
                    SubscriptionAccess.SETUP_REQUIRED -> "Finish device setup"
                    SubscriptionAccess.FREE -> "Not active"
                    SubscriptionAccess.UNAVAILABLE -> "Status unavailable"
                },
                highlighted = active,
                warning = state.access == SubscriptionAccess.UNAVAILABLE,
            ) {
                Text(
                    "Ask AI can approve, deny, or leave a request for you to decide. Secret values and private keys are never sent for review.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                state.googlePlayPurchase == GooglePlayPurchaseState.PENDING ->
                    PurchaseStatusCard(
                        title = "Payment pending",
                        body = "AI review will activate after Google Play confirms the payment.",
                        icon = { Icon(Icons.Outlined.HourglassTop, contentDescription = null) },
                    )
                active -> state.googlePlayProductId?.let { productId ->
                    ManageSubscriptionButton(
                        onClick = { onManageSubscription(productId) },
                        enabled = !busy,
                    )
                }
                state.googlePlayPurchase == GooglePlayPurchaseState.PURCHASED -> {
                    PurchaseStatusCard(
                        title = "Subscription needs attention",
                        body = "Google Play reports a subscription, but AI review access is not active.",
                        icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
                    )
                    state.googlePlayProductId?.let { productId ->
                        ManageSubscriptionButton(
                            onClick = { onManageSubscription(productId) },
                            enabled = !busy,
                        )
                    }
                }
                state.access == SubscriptionAccess.SETUP_REQUIRED ->
                    StoreStatus("Finish device setup before subscribing.")
                state.playStore == PlayStoreAvailability.CHECKING ->
                    StoreStatus("Loading Google Play plans…", showProgress = true)
                state.playStore == PlayStoreAvailability.UNAVAILABLE ->
                    StoreStatus("Google Play subscriptions are unavailable on this installation.")
                state.offers.isEmpty() ->
                    StoreStatus("No subscription plan is currently available in Google Play.")
                else -> {
                    if (state.offers.size > 1) {
                        Text(
                            "Choose a plan",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    state.offers.forEach { offer ->
                        SubscriptionOffer(
                            offer = offer,
                            purchasing = state.purchasing,
                            enabled = !busy,
                            onSubscribe = { onSubscribe(offer.id) },
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onRefresh,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.refreshing || state.redeeming) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                }
                Spacer(Modifier.size(8.dp))
                Text(if (state.redeeming) "Activating…" else "Refresh subscription status")
            }
        }
    }
}

@Composable
private fun SubscriptionOffer(
    offer: PlaySubscriptionOffer,
    purchasing: Boolean,
    enabled: Boolean,
    onSubscribe: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.WorkspacePremium, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text("AI review subscription", style = MaterialTheme.typography.titleMedium)
                    Text(offer.price, style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(
                offer.terms,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSubscribe,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (purchasing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Opening Google Play…")
                } else {
                    Text(if (offer.autoRenewing) "Subscribe" else "Buy plan")
                }
            }
        }
    }
}

@Composable
private fun PurchaseStatusCard(
    title: String,
    body: String,
    icon: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.agentknockColors.attentionContainer,
        contentColor = MaterialTheme.agentknockColors.onAttentionContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            icon()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ManageSubscriptionButton(onClick: () -> Unit, enabled: Boolean) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Manage in Google Play")
    }
}

@Composable
private fun StoreStatus(text: String, showProgress: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showProgress) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccessCard(
    title: String,
    status: String,
    highlighted: Boolean,
    warning: Boolean = false,
    content: @Composable () -> Unit,
) {
    val container = when {
        warning -> MaterialTheme.agentknockColors.attentionContainer
        highlighted -> MaterialTheme.agentknockColors.successContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = when {
        warning -> MaterialTheme.agentknockColors.onAttentionContainer
        highlighted -> MaterialTheme.agentknockColors.onSuccessContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        color = container,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (title == "AI review") Icons.Outlined.AutoAwesome else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(status, style = MaterialTheme.typography.labelLarge)
                }
                if (warning) Icon(Icons.Outlined.CloudOff, contentDescription = null)
            }
            content()
        }
    }
}

@Composable
private fun IncludedFeature(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
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
        Text(notice.message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
    }
}
