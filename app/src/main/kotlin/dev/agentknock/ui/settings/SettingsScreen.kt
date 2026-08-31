@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.settings

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.BuildConfig
import dev.agentknock.push.RequestNotifications
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.VaultProtection
import dev.agentknock.storage.device.DeviceIdentity
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.theme.agentknockColors
import dev.agentknock.ui.components.NavigationBackButton
import kotlinx.coroutines.launch

private enum class SettingsPage {
    OVERVIEW,
    SECURITY_BACKUP,
    NOTIFICATIONS,
    SUBSCRIPTION,
    AUDIT,
    FACTORY_RESET,
    ABOUT,
}

@Composable
internal fun SettingsScreen(
    onClose: () -> Unit,
    authenticate: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    authenticationMode: DeviceAuthenticationMode,
    onAuthenticationModeChange: (DeviceAuthenticationMode, (String) -> Unit) -> Unit,
    notificationStateGeneration: Long,
    requestNotificationPermission: () -> Unit,
    openPlanInitially: Boolean,
    onPlanOpened: () -> Unit,
    subscriptionViewModel: SubscriptionViewModel,
    viewModel: SettingsViewModel,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.OVERVIEW) }
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val counts by viewModel.dataCounts.collectAsStateWithLifecycle()
    val auditEvents by viewModel.auditEvents.collectAsStateWithLifecycle()
    val selectedAudit by viewModel.selectedAuditEvent.collectAsStateWithLifecycle()
    val pushState by viewModel.pushRegistrationState.collectAsStateWithLifecycle()
    val protection by viewModel.vaultProtection.collectAsStateWithLifecycle()
    val factoryReset by viewModel.factoryReset.collectAsStateWithLifecycle()
    val subscription by subscriptionViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(subscriptionViewModel) { subscriptionViewModel.refresh() }
    LaunchedEffect(openPlanInitially) {
        if (openPlanInitially) {
            page = SettingsPage.SUBSCRIPTION
            onPlanOpened()
        }
    }

    fun back() {
        when (page) {
            SettingsPage.OVERVIEW -> onClose()
            SettingsPage.AUDIT -> if (selectedAudit != null) {
                viewModel.selectAuditEvent(null)
            } else {
                page = SettingsPage.OVERVIEW
            }
            SettingsPage.FACTORY_RESET -> when (factoryReset) {
                FactoryResetUiState.Working -> Unit
                FactoryResetUiState.ConfirmLocalClear -> {
                    viewModel.cancelLocalClear()
                    page = SettingsPage.SECURITY_BACKUP
                }
                FactoryResetUiState.Idle,
                FactoryResetUiState.ClearFailed,
                -> page = SettingsPage.SECURITY_BACKUP
            }
            else -> page = SettingsPage.OVERVIEW
        }
    }
    BackHandler(onBack = ::back)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val modifier = if (page == SettingsPage.AUDIT) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxHeight().widthIn(max = 720.dp).align(Alignment.TopCenter)
            }
            when (page) {
                SettingsPage.OVERVIEW -> SettingsOverview(
                    authenticationMode = authenticationMode,
                    protection = protection,
                    notificationStateGeneration = notificationStateGeneration,
                    pushState = pushState?.wireName,
                    subscription = subscription,
                    onBack = onClose,
                    onOpen = { page = it },
                    modifier = modifier,
                )
                SettingsPage.SECURITY_BACKUP -> SecurityAndBackup(
                    counts = counts,
                    protection = protection,
                    authenticationMode = authenticationMode,
                    onAuthenticationModeChange = { mode ->
                        onAuthenticationModeChange(mode) { error ->
                            scope.launch { snackbar.showSnackbar(error) }
                        }
                    },
                    onFactoryReset = { page = SettingsPage.FACTORY_RESET },
                    onBack = ::back,
                    modifier = modifier,
                )
                SettingsPage.NOTIFICATIONS -> NotificationsSettings(
                    pushState = pushState?.wireName,
                    refreshGeneration = notificationStateGeneration,
                    requestNotificationPermission = requestNotificationPermission,
                    onBack = ::back,
                    modifier = modifier,
                )
                SettingsPage.SUBSCRIPTION -> SubscriptionAndBillingScreen(
                    state = subscription,
                    onBack = ::back,
                    onRefresh = subscriptionViewModel::refresh,
                    modifier = modifier,
                )
                SettingsPage.AUDIT -> AuditBrowser(
                    events = auditEvents,
                    selected = selectedAudit,
                    onBack = ::back,
                    onOpen = viewModel::selectAuditEvent,
                    report = { message -> scope.launch { snackbar.showSnackbar(message) } },
                    modifier = modifier,
                )
                SettingsPage.FACTORY_RESET -> FactoryReset(
                    onBack = ::back,
                    state = factoryReset,
                    startReset = {
                        authenticate(
                            "Confirm factory reset",
                            viewModel::startFactoryReset,
                            { message -> scope.launch { snackbar.showSnackbar(message) } },
                        )
                    },
                    confirmLocalClear = viewModel::confirmLocalClear,
                    cancelLocalClear = viewModel::cancelLocalClear,
                    consumeClearFailure = viewModel::consumeClearFailure,
                    report = { message -> scope.launch { snackbar.showSnackbar(message) } },
                    modifier = modifier,
                )
                SettingsPage.ABOUT -> About(
                    identity = configuration?.active,
                    onBack = ::back,
                    report = { message -> scope.launch { snackbar.showSnackbar(message) } },
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun SettingsOverview(
    authenticationMode: DeviceAuthenticationMode,
    protection: VaultProtection?,
    notificationStateGeneration: Long,
    pushState: String?,
    subscription: SubscriptionUiState,
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val requestsEnabled = remember(notificationStateGeneration) {
        RequestNotifications.actionNotificationsEnabled(context)
    }
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
                            pushState != null && pushState != "registered" -> "Delivery needs attention"
                            else -> "Requests needing action can alert you"
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
        }
    }
}

@Composable
private fun SecurityAndBackup(
    counts: DataCounts,
    protection: VaultProtection?,
    authenticationMode: DeviceAuthenticationMode,
    onAuthenticationModeChange: (DeviceAuthenticationMode) -> Unit,
    onFactoryReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val deviceSecure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
    var chooseAuthentication by remember { mutableStateOf(false) }
    Column(modifier) {
        PageTopBar("Security and backup", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { EncryptionStatus(protection) }
            item {
                Column {
                    SettingsSectionLabel("Protection")
                    SettingsGroup {
                        SettingsRow(
                            title = "Device authentication",
                            summary = authenticationMode.displayLabel(),
                            onClick = { chooseAuthentication = true },
                        )
                        SettingsGroupDivider()
                        SettingsValueRow("Encryption", "AES-128-GCM")
                        SettingsGroupDivider()
                        SettingsValueRow("Key storage", protection.keyStorageDescription())
                        SettingsGroupDivider()
                        SettingsValueRow(
                            "Device screen lock",
                            if (deviceSecure) "Configured" else "Not configured",
                        )
                    }
            }
            }
            item {
                Column {
                    SettingsSectionLabel("Backup")
                    SettingsGroup {
                        SettingsRow(
                            title = "Android backup",
                            summary = "Metadata and encrypted values are backed up; device-bound encryption keys are not.",
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Key recovery",
                            summary = "Not configured. Restored secrets and client pairings cannot be decrypted on another device.",
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Stored data",
                            summary = "${counts.secrets.countLabel("secret")} · ${counts.clients.countLabel("client")}",
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("Reset")
                    SettingsGroup {
                        SettingsRow(
                            title = "Factory reset Agentknock",
                            summary = "Erase the device identity, encryption keys, secrets, clients, and history",
                            destructive = true,
                            onClick = onFactoryReset,
                        )
                    }
                }
            }
        }
    }
    if (chooseAuthentication) {
        AlertDialog(
            onDismissRequest = { chooseAuthentication = false },
            title = { Text("Device authentication") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DeviceAuthenticationMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chooseAuthentication = false
                                    onAuthenticationModeChange(mode)
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = mode == authenticationMode, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(mode.displayLabel(), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    mode.explanation(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { chooseAuthentication = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EncryptionStatus(protection: VaultProtection?) {
    val hardwareBacked = protection is VaultProtection.Available &&
        protection.backings.values.all(EncryptionKeyBacking::isHardwareBacked)
    val softwareBacked = protection is VaultProtection.Available &&
        protection.backings.values.any { it == EncryptionKeyBacking.SOFTWARE }
    val style = when {
        hardwareBacked -> StatusStyle(
            "Hardware-backed encryption",
            "Stored secret values and device credentials are encrypted with keys protected by secure hardware.",
            MaterialTheme.agentknockColors.successContainer,
            MaterialTheme.agentknockColors.onSuccessContainer,
        )
        softwareBacked -> StatusStyle(
            "Android Keystore encryption",
            "Stored secret values and device credentials are encrypted on this device.",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
        )
        protection is VaultProtection.KeyUnavailable -> StatusStyle(
            "Encryption key unavailable",
            "One or more keys cannot be used on this device.",
            MaterialTheme.agentknockColors.dangerContainer,
            MaterialTheme.agentknockColors.onDangerContainer,
        )
        protection == null -> StatusStyle(
            "Checking encryption",
            "Reading this device's key protection.",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
        )
        else -> StatusStyle(
            "Stored values are encrypted",
            "Android did not report the exact hardware protection for the encryption keys.",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
        )
    }
    Surface(
        color = style.container,
        contentColor = style.content,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = style.content.copy(alpha = 0.14f),
                contentColor = style.content,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Security, contentDescription = null)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(style.title, style = MaterialTheme.typography.titleLarge)
                Text(style.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun NotificationsSettings(
    pushState: String?,
    refreshGeneration: Long,
    requestNotificationPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val permissionGranted = remember(refreshGeneration) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val appNotificationsEnabled = remember(refreshGeneration) {
        RequestNotifications.appNotificationsEnabled(context)
    }

    fun openChannel(channelId: String) {
        context.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            },
        )
    }

    Column(modifier) {
        PageTopBar("Notifications", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Outlined.Notifications,
                        title = if (appNotificationsEnabled) "Notifications allowed" else "Notifications blocked",
                        summary = if (appNotificationsEnabled) {
                            "Android can show Agentknock notifications."
                        } else {
                            "Android is blocking Agentknock notifications."
                        },
                    )
                }
            }
            if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    Button(
                        onClick = requestNotificationPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow notifications") }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("Categories")
                    SettingsGroup {
                        SettingsRow(
                            title = "Requests needing action",
                            summary = "Alerts for requests that need your attention",
                            onClick = { openChannel(RequestNotifications.ACTION_CHANNEL_ID) },
                            external = true,
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Background processing",
                            summary = "Silent status while Agentknock checks for requests",
                            onClick = { openChannel(RequestNotifications.BACKGROUND_CHANNEL_ID) },
                            external = true,
                        )
                    }
                    Text(
                        "Android controls sound, vibration, lock-screen visibility, and interruption for each category.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (pushState != null && pushState != "registered") {
                item {
                    Column {
                        SettingsSectionLabel("Delivery")
                        SettingsGroup {
                            SettingsRow(
                                title = "Push delivery needs attention",
                                summary = when (pushState) {
                                    "missing" -> "This device has not finished registering. Agentknock will retry."
                                    "invalid" -> "The relay rejected the registration. Agentknock will retry."
                                    else -> "Agentknock will retry push registration automatically."
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun About(
    identity: DeviceIdentity?,
    onBack: () -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    fun open(label: String, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { report("Could not open $label") }
    }
    Column(modifier) {
        PageTopBar("About Agentknock", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Agentknock", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Developer secrets on your phone, provided only to approved commands. " +
                            "Agentknock can supply environment variables or sign Git objects " +
                            "without exposing SSH private keys.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Column {
                    SettingsSectionLabel("Links")
                    SettingsGroup {
                        SettingsRow(
                            title = "Website",
                            summary = "agentknock.dev",
                            onClick = { open("website", "https://agentknock.dev/") },
                            external = true,
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Privacy notice",
                            summary = "agentknock.dev/privacy",
                            onClick = { open("privacy notice", "https://agentknock.dev/privacy") },
                            external = true,
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Source code",
                            summary = "github.com/agentknock/agentknock-android",
                            onClick = {
                                open("source code", "https://github.com/agentknock/agentknock-android")
                            },
                            external = true,
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("App information")
                    SettingsGroup {
                        SettingsValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        SettingsGroupDivider()
                        SettingsValueRow("Source revision", BuildConfig.SOURCE_REVISION, monospace = true)
                        SettingsGroupDivider()
                        SettingsValueRow("Developer", "Full Disclosure")
                    }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("This device")
                    SettingsGroup {
                        identity?.let { device ->
                            SettingsValueRow("Device ID", device.deviceId, monospace = true)
                            SettingsGroupDivider()
                        }
                        SettingsValueRow("Relay", "relay.agentknock.dev", monospace = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun FactoryReset(
    onBack: () -> Unit,
    state: FactoryResetUiState,
    startReset: () -> Unit,
    confirmLocalClear: () -> Unit,
    cancelLocalClear: () -> Unit,
    consumeClearFailure: () -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    var phrase by rememberSaveable { mutableStateOf("") }
    val working = state == FactoryResetUiState.Working

    LaunchedEffect(state) {
        if (state == FactoryResetUiState.ClearFailed) {
            consumeClearFailure()
            report("Android could not clear Agentknock's app data. Clear its storage from Android settings.")
        }
    }

    if (state == FactoryResetUiState.ConfirmLocalClear) {
        AlertDialog(
            onDismissRequest = cancelLocalClear,
            title = { Text("Relay deletion not confirmed") },
            text = {
                Text(
                    "Clearing Agentknock now may leave the old device registration on the relay. Clear all app data anyway?",
                )
            },
            confirmButton = {
                TextButton(onClick = confirmLocalClear) {
                    Text("Clear app data", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = cancelLocalClear) { Text("Cancel") }
            },
        )
    }

    Column(modifier) {
        PageTopBar("Factory reset Agentknock", onBack = { if (!working) onBack() })
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "This cannot be undone",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { heading() },
            )
            Text("Agentknock will ask the relay to delete this device registration and its live relay state.")
            Text("It will then permanently erase:")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("• Device identity and encryption keys")
                Text("• Secrets and their stored values")
                Text("• Paired clients and requests")
                Text("• Audit log and settings")
            }
            Text("Setup starts again with a new device identity and pairing address. Every client must pair again.")
            Text(
                "If relay deletion cannot be confirmed, you will be asked again before any app data is cleared. Factory reset does not fix temporary connection problems.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Type RESET AGENTKNOCK to confirm.", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
                enabled = !working,
                label = { Text("Confirmation phrase") },
                supportingText = { Text("Enter the phrase exactly as shown, including spaces.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
            )
            Button(
                onClick = startReset,
                enabled = phrase == "RESET AGENTKNOCK" && !working,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onError)
                } else {
                    Text("Erase and reset Agentknock")
                }
            }
        }
    }
}

@Composable
internal fun PageTopBar(title: String, onBack: () -> Unit, showBack: Boolean = true) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (showBack) {
                NavigationBackButton(onBack)
            }
        },
    )
}

private data class StatusStyle(
    val title: String,
    val detail: String,
    val container: Color,
    val content: Color,
)

private fun EncryptionKeyBacking.isHardwareBacked(): Boolean = when (this) {
    EncryptionKeyBacking.STRONGBOX,
    EncryptionKeyBacking.TRUSTED_ENVIRONMENT,
    EncryptionKeyBacking.UNKNOWN_SECURE,
    -> true
    EncryptionKeyBacking.SOFTWARE,
    EncryptionKeyBacking.UNKNOWN,
    -> false
}

private fun VaultProtection?.keyStorageDescription(): String = when (this) {
    null -> "Checking this device…"
    is VaultProtection.Available -> backings.values.distinct().let { kinds ->
        if (kinds.size != 1) {
            "Mixed Android Keystore protection"
        } else {
            when (kinds.single()) {
                EncryptionKeyBacking.STRONGBOX -> "Android StrongBox"
                EncryptionKeyBacking.TRUSTED_ENVIRONMENT -> "Trusted execution environment"
                EncryptionKeyBacking.SOFTWARE -> "Android Keystore, software-backed"
                EncryptionKeyBacking.UNKNOWN_SECURE -> "Secure hardware"
                EncryptionKeyBacking.UNKNOWN -> "Android Keystore, backing not reported"
            }
        }
    }
    is VaultProtection.KeyUnavailable -> "Key unavailable on this device"
    VaultProtection.Unknown -> "Android Keystore, protection not reported"
}

private fun VaultProtection?.overviewDescription(): String = when (this) {
    null -> "Checking encryption"
    is VaultProtection.Available -> if (backings.values.all(EncryptionKeyBacking::isHardwareBacked)) {
        "Hardware-backed encryption"
    } else {
        "Android Keystore encryption"
    }
    is VaultProtection.KeyUnavailable -> "Encryption key unavailable"
    VaultProtection.Unknown -> "Encryption status unknown"
}

private fun DeviceAuthenticationMode.displayLabel(): String = when (this) {
    DeviceAuthenticationMode.DEVICE_LOCK -> "Rely on device lock"
    DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING -> "Sensitive values and pairing"
    DeviceAuthenticationMode.APP_LOCK -> "Lock Agentknock"
}

private fun DeviceAuthenticationMode.overviewLabel(): String = when (this) {
    DeviceAuthenticationMode.DEVICE_LOCK -> "Device lock"
    DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING -> "Protected values and pairing"
    DeviceAuthenticationMode.APP_LOCK -> "App lock"
}

private fun DeviceAuthenticationMode.explanation(): String = when (this) {
    DeviceAuthenticationMode.DEVICE_LOCK ->
        "No additional Agentknock prompts. Request decisions and destructive confirmations still apply."
    DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING ->
        "Authenticate before showing, copying, or editing sensitive values, weakening their protection, or accepting a new client."
    DeviceAuthenticationMode.APP_LOCK ->
        "Authenticate before any Agentknock content is shown. One unlock lasts for the foreground session."
}

private fun Int.countLabel(noun: String): String = "$this $noun${if (this == 1) "" else "s"}"
