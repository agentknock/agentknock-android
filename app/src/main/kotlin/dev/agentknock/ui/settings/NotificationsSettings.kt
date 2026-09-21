package dev.agentknock.ui.settings

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.core.net.toUri
import dev.agentknock.BACKGROUND_DELIVERY_SUPPORTED
import dev.agentknock.push.PushServiceIssue
import dev.agentknock.push.RequestNotifications
import dev.agentknock.pushServiceIssue
import dev.agentknock.relay.RelayPushRegistrationState

@Composable
internal fun NotificationsSettings(
    pushState: RelayPushRegistrationState?,
    refreshGeneration: Long,
    requestNotificationPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    report: (String) -> Unit,
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
                RequestNotifications.PROCESSING_CHANNEL_ID,
            )
        }

    val backgroundDataRestricted =
        remember(refreshGeneration) {
            context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }
    val backgroundActivityRestricted =
        remember(refreshGeneration) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            } else null
        }
    val serviceIssue =
        remember(refreshGeneration) {
            if (BACKGROUND_DELIVERY_SUPPORTED) pushServiceIssue(context) else null
        }
    val appSettings =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )

    fun openSettings(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(appSettings)
            } catch (_: ActivityNotFoundException) {
                report("Android settings could not be opened")
            }
        }
    }

    fun openChannel(channelId: String) {
        openSettings(
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
        backgroundDataRestricted = backgroundDataRestricted,
        backgroundActivityRestricted = backgroundActivityRestricted,
        pushServiceIssue = serviceIssue,
        openAppNotifications = {
            openSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
        },
        openDataSettings = {
            openSettings(
                Intent(
                    Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                    "package:${context.packageName}".toUri(),
                )
            )
        },
        openBatterySettings = { openSettings(appSettings) },
        resolvePushServiceIssue = { serviceIssue?.resolution?.let(::openSettings) },
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
    backgroundDataRestricted: Boolean = false,
    backgroundActivityRestricted: Boolean? = false,
    pushServiceIssue: PushServiceIssue? = null,
    openAppNotifications: () -> Unit = {},
    openDataSettings: () -> Unit = {},
    openBatterySettings: () -> Unit = {},
    resolvePushServiceIssue: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    Column(modifier) {
        PageTopBar("Notifications", onBack)
        LazyColumn(
            state = listState,
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
                        onClick = openAppNotifications,
                        external = true,
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
                                if (appNotificationsEnabled && !requestsEnabled)
                                    "Blocked in Android settings"
                                else "Allow or deny requests from notifications.",
                            attention = appNotificationsEnabled && !requestsEnabled,
                            onClick = { openChannel(RequestNotifications.ACTION_CHANNEL_ID) },
                            external = true,
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Request processing",
                            summary =
                                if (appNotificationsEnabled && !backgroundEnabled) {
                                    "Hidden in Android settings"
                                } else {
                                    "Progress while handling requests."
                                },
                            onClick = { openChannel(RequestNotifications.PROCESSING_CHANNEL_ID) },
                            external = true,
                        )
                    }
                    if (appNotificationsEnabled && !backgroundEnabled) {
                        Text(
                            "Processing continues when these notifications are hidden." +
                                if (BACKGROUND_DELIVERY_SUPPORTED) {
                                    " If automatic approvals show no notification, future requests may arrive later."
                                } else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (BACKGROUND_DELIVERY_SUPPORTED) {
                item {
                    Column {
                        SettingsSectionLabel("Background delivery")
                        SettingsGroup {
                            SettingsRow(
                                title = "Push registration",
                                summary =
                                    pushServiceIssue?.description
                                        ?: when (pushState) {
                                            RelayPushRegistrationState.REGISTERED -> "Registered"
                                            RelayPushRegistrationState.MISSING ->
                                                "Not registered yet. Agentknock will retry."
                                            RelayPushRegistrationState.INVALID ->
                                                "Registration rejected. Agentknock will retry."
                                            null -> "Not yet confirmed"
                                        },
                                attention =
                                    pushServiceIssue != null ||
                                        pushState == RelayPushRegistrationState.MISSING ||
                                        pushState == RelayPushRegistrationState.INVALID,
                                onClick =
                                    if (pushServiceIssue?.resolution != null)
                                        resolvePushServiceIssue
                                    else null,
                                external = pushServiceIssue?.resolution != null,
                            )
                            SettingsGroupDivider()
                            SettingsRow(
                                title = "Background data",
                                summary =
                                    if (backgroundDataRestricted)
                                        "Restricted on mobile data and metered Wi-Fi"
                                    else "No restriction detected",
                                attention = backgroundDataRestricted,
                                onClick = openDataSettings,
                                external = true,
                            )
                            SettingsGroupDivider()
                            SettingsRow(
                                title = "Background activity",
                                summary =
                                    when (backgroundActivityRestricted) {
                                        true ->
                                            "Restricted — requests may wait until you open Agentknock"
                                        false -> "No restriction detected"
                                        null -> "Not available on this Android version"
                                    },
                                attention = backgroundActivityRestricted == true,
                                onClick = openBatterySettings,
                                external = true,
                            )
                        }
                        Text(
                            "These checks identify settings that can delay requests. " +
                                "They cannot verify background delivery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
