package dev.agentknock.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.agentknock.BACKGROUND_DELIVERY_SUPPORTED
import dev.agentknock.push.RequestNotifications
import dev.agentknock.relay.RelayPushRegistrationState

@Composable
internal fun NotificationsSettings(
    pushState: RelayPushRegistrationState?,
    refreshGeneration: Long,
    requestNotificationPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val permissionGranted =
        remember(refreshGeneration) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        }
    val appNotificationsEnabled =
        remember(refreshGeneration) {
            RequestNotifications.appNotificationsEnabled(context)
        }
    val requestsEnabled =
        remember(refreshGeneration) {
            RequestNotifications.actionNotificationsEnabled(context)
        }
    val backgroundEnabled =
        remember(refreshGeneration) {
            RequestNotifications.channelNotificationsEnabled(
                context,
                RequestNotifications.BACKGROUND_CHANNEL_ID,
            )
        }

    fun openChannel(channelId: String) {
        context.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            }
        )
    }

    NotificationsSettingsContent(
        pushState = pushState,
        permissionGranted = permissionGranted,
        appNotificationsEnabled = appNotificationsEnabled,
        requestsEnabled = requestsEnabled,
        backgroundEnabled = backgroundEnabled,
        requestNotificationPermission = requestNotificationPermission,
        openChannel = ::openChannel,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun NotificationsSettingsContent(
    pushState: RelayPushRegistrationState?,
    permissionGranted: Boolean,
    appNotificationsEnabled: Boolean,
    requestsEnabled: Boolean,
    backgroundEnabled: Boolean,
    requestNotificationPermission: () -> Unit,
    openChannel: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val deliveryWarning = if (BACKGROUND_DELIVERY_SUPPORTED) pushState?.deliveryWarning() else null
    Column(modifier) {
        PageTopBar("Notifications", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!BACKGROUND_DELIVERY_SUPPORTED) {
                item {
                    Column {
                        SettingsSectionLabel("Delivery")
                        SettingsGroup {
                            SettingsRow(
                                title = "Receive requests while the app is open",
                                summary =
                                    "Keep Agentknock in the foreground to receive new requests. " +
                                        "This build does not support background push delivery.",
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        icon =
                            if (appNotificationsEnabled) {
                                Icons.Outlined.Notifications
                            } else {
                                Icons.Outlined.NotificationsOff
                            },
                        title =
                            if (appNotificationsEnabled) {
                                "Notifications allowed"
                            } else {
                                "Notifications blocked"
                            },
                        summary =
                            if (appNotificationsEnabled) {
                                "Android can show Agentknock notifications."
                            } else {
                                "Android is blocking request alerts. You can still review requests in the app."
                            },
                        attention = !appNotificationsEnabled,
                    )
                }
            }
            if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    Button(
                        onClick = requestNotificationPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Allow notifications")
                    }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("Categories")
                    SettingsGroup {
                        SettingsRow(
                            title = "Requests needing action",
                            summary =
                                if (requestsEnabled) "Enabled" else "Blocked by Android settings",
                            attention = !requestsEnabled,
                            onClick = { openChannel(RequestNotifications.ACTION_CHANNEL_ID) },
                            external = true,
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Background processing",
                            summary =
                                if (backgroundEnabled) {
                                    "Silent notification shown"
                                } else {
                                    "Silent notification hidden"
                                },
                            onClick = { openChannel(RequestNotifications.BACKGROUND_CHANNEL_ID) },
                            external = true,
                        )
                    }
                    Text(
                        "Hiding the background processing notification does not stop request " +
                            "processing. Android controls sound, vibration, lock-screen visibility, " +
                            "and interruption for each category.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (deliveryWarning != null) {
                item {
                    Column {
                        SettingsSectionLabel("Delivery")
                        SettingsGroup {
                            SettingsRow(
                                title = "Push delivery needs attention",
                                summary = deliveryWarning,
                                attention = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun RelayPushRegistrationState.deliveryWarning(): String? =
    when (this) {
        RelayPushRegistrationState.MISSING ->
            "This device has not finished registering. Agentknock will retry."
        RelayPushRegistrationState.INVALID ->
            "The relay rejected the registration. Agentknock will retry."
        RelayPushRegistrationState.REGISTERED -> null
    }
