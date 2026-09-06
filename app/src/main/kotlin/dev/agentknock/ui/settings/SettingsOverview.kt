package dev.agentknock.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.agentknock.BuildConfig
import dev.agentknock.push.RequestNotifications
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.storage.crypto.VaultProtection
import dev.agentknock.ui.auth.DeviceAuthenticationMode

@Composable
internal fun SettingsOverview(
    authenticationMode: DeviceAuthenticationMode,
    protection: VaultProtection?,
    notificationStateGeneration: Long,
    pushState: RelayPushRegistrationState?,
    subscription: SubscriptionUiState,
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val requestsEnabled = remember(notificationStateGeneration) {
        RequestNotifications.actionNotificationsEnabled(context)
    }
    SettingsOverviewContent(
        authenticationMode = authenticationMode,
        protection = protection,
        requestsEnabled = requestsEnabled,
        pushState = pushState,
        subscription = subscription,
        onBack = onBack,
        onOpen = onOpen,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsOverviewContent(
    authenticationMode: DeviceAuthenticationMode,
    protection: VaultProtection?,
    requestsEnabled: Boolean,
    pushState: RelayPushRegistrationState?,
    subscription: SubscriptionUiState,
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        PageTopBar("Settings", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Outlined.Security,
                        title = "Security and backup",
                        summary = "${authenticationMode.overviewLabel()} · ${protection.overviewDescription()}",
                        onClick = { onOpen(SettingsPage.SECURITY_BACKUP) },
                    )
                    SettingsGroupDivider(withIcon = true)
                    SettingsRow(
                        icon = Icons.Outlined.Notifications,
                        title = "Notifications",
                        summary = when {
                            !requestsEnabled -> "Requests needing action are muted"
                            pushState != null && pushState != RelayPushRegistrationState.REGISTERED ->
                                "Delivery needs attention"
                            else -> "Request alerts enabled"
                        },
                        onClick = { onOpen(SettingsPage.NOTIFICATIONS) },
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Outlined.WorkspacePremium,
                        title = "Plan and billing",
                        summary = subscription.overviewLabel(),
                        onClick = { onOpen(SettingsPage.SUBSCRIPTION) },
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Outlined.History,
                        title = "Audit log",
                        summary = "Security activity kept for one year",
                        onClick = { onOpen(SettingsPage.AUDIT) },
                    )
                    SettingsGroupDivider(withIcon = true)
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = "About Agentknock",
                        summary = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        onClick = { onOpen(SettingsPage.ABOUT) },
                    )
                }
            }
            item {
                SettingsRow(
                    title = "Factory reset Agentknock",
                    summary = "Erase this device's Agentknock data",
                    destructive = true,
                    onClick = { onOpen(SettingsPage.FACTORY_RESET) },
                )
            }
        }
    }
}

private fun VaultProtection?.overviewDescription(): String = when (this) {
    null -> "Checking encryption"
    else -> when {
        unavailableStoredData.isNotEmpty() -> "Stored data unavailable"
        this is VaultProtection.ActiveKeysAvailable -> "Backup information"
        this is VaultProtection.ActiveKeysUnavailable -> "Current encryption key unavailable"
        else -> "Encryption status unknown"
    }
}

private fun DeviceAuthenticationMode.overviewLabel(): String = when (this) {
    DeviceAuthenticationMode.DEVICE_LOCK -> "Device lock"
    DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING -> "Protect sensitive actions"
    DeviceAuthenticationMode.APP_LOCK -> "App lock"
}
