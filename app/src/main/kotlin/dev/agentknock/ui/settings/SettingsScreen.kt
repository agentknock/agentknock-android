@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.settings

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.BuildConfig
import dev.agentknock.push.RequestNotifications
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.TonalIcon
import dev.agentknock.ui.theme.agentknockColors
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.storage.FactoryResetResult
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.crypto.VaultProtection
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.vault.DeviceManagementResult
import dev.agentknock.storage.vault.DeviceIdentity
import kotlinx.coroutines.launch

private enum class SettingsPage {
    OVERVIEW,
    DEVICE,
    NOTIFICATIONS,
    SECURITY,
    DATA,
    AUDIT,
    FACTORY_RESET,
    DIAGNOSTICS,
    ABOUT,
}

@Composable
internal fun SettingsScreen(
    onClose: () -> Unit,
    onChangeAddress: () -> Unit,
    authenticationMode: DeviceAuthenticationMode,
    onAuthenticationModeChange: (DeviceAuthenticationMode, (String) -> Unit) -> Unit,
    notificationStateGeneration: Long,
    requestNotificationPermission: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.OVERVIEW) }
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val counts by viewModel.dataCounts.collectAsStateWithLifecycle()
    val auditEvents by viewModel.auditEvents.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val selectedAudit by viewModel.selectedAuditEvent.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val syncResult by viewModel.lastSyncResult.collectAsStateWithLifecycle()
    val pushState by viewModel.pushRegistrationState.collectAsStateWithLifecycle()
    val vaultProtection by viewModel.vaultProtection.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun back() {
        when (page) {
            SettingsPage.OVERVIEW -> onClose()
            SettingsPage.AUDIT -> if (selectedAudit != null) viewModel.selectAuditEvent(null) else page = SettingsPage.DATA
            SettingsPage.FACTORY_RESET -> page = SettingsPage.DATA
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
                    onBack = onClose,
                    onOpen = { page = it },
                    pairing = configuration?.active,
                    counts = counts,
                    syncResult = syncResult,
                    pushState = pushState?.wireName,
                    protection = vaultProtection,
                    authenticationMode = authenticationMode,
                    modifier = modifier,
                )
                SettingsPage.DEVICE -> DeviceAndPairing(
                    identity = configuration?.active,
                    onBack = ::back,
                    onChangeAddress = onChangeAddress,
                    onSetPairingEnabled = { enabled ->
                        scope.launch {
                            snackbar.showSnackbar(
                                viewModel.setPairingEnabled(enabled).message(enabled),
                            )
                        }
                    },
                    report = { scope.launch { snackbar.showSnackbar(it) } },
                    modifier = modifier,
                )
                SettingsPage.NOTIFICATIONS -> NotificationsSettings(
                    pushState = pushState?.wireName,
                    refreshGeneration = notificationStateGeneration,
                    requestNotificationPermission = requestNotificationPermission,
                    onBack = ::back,
                    modifier = modifier,
                )
                SettingsPage.SECURITY -> SecuritySettings(
                    protection = vaultProtection,
                    authenticationMode = authenticationMode,
                    onAuthenticationModeChange = { mode ->
                        onAuthenticationModeChange(mode) {
                            scope.launch { snackbar.showSnackbar(it) }
                        }
                    },
                    onBack = ::back,
                    modifier = modifier,
                )
                SettingsPage.DATA -> DataAndHistory(
                    counts = counts,
                    onBack = ::back,
                    onAudit = { page = SettingsPage.AUDIT },
                    onFactoryReset = { page = SettingsPage.FACTORY_RESET },
                    onClearRequests = {
                        scope.launch {
                            val removed = viewModel.clearCompletedRequests()
                            snackbar.showSnackbar("Cleared $removed completed workflows")
                        }
                    },
                    modifier = modifier,
                )
                SettingsPage.AUDIT -> AuditBrowser(
                    events = auditEvents,
                    selected = selectedAudit,
                    clients = clients,
                    onBack = ::back,
                    onOpen = viewModel::selectAuditEvent,
                    report = { scope.launch { snackbar.showSnackbar(it) } },
                    modifier = modifier,
                )
                SettingsPage.FACTORY_RESET -> FactoryReset(
                    onBack = ::back,
                    reset = viewModel::factoryReset,
                    report = { scope.launch { snackbar.showSnackbar(it) } },
                    modifier = modifier,
                )
                SettingsPage.DIAGNOSTICS -> Diagnostics(
                    syncing = syncing,
                    result = syncResult,
                    onBack = ::back,
                    onReconnect = viewModel::reconnect,
                    report = { scope.launch { snackbar.showSnackbar(it) } },
                    modifier = modifier,
                )
                SettingsPage.ABOUT -> About(
                    onBack = ::back,
                    report = { scope.launch { snackbar.showSnackbar(it) } },
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun AuditBrowser(
    events: List<AuditEvent>,
    selected: AuditEvent?,
    clients: List<ClientSummary>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val clientNames = clients.associate { it.clientId to it.name }
    BoxWithConstraints(modifier) {
        val twoPane = maxWidth >= 840.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                AuditList(
                    events = events,
                    selectedEventId = selected?.id,
                    onBack = onBack,
                    onOpen = onOpen,
                    clientNames = clientNames,
                    modifier = Modifier.width(420.dp).fillMaxHeight(),
                )
                VerticalDivider()
                if (selected == null) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Select an event to view its details",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    AuditDetail(
                        event = selected,
                        clientName = selected.clientId?.let(clientNames::get),
                        report = report,
                        onBack = onBack,
                        showBack = false,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        } else if (selected == null) {
            AuditList(
                events = events,
                selectedEventId = selected?.id,
                onBack = onBack,
                onOpen = onOpen,
                clientNames = clientNames,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AuditDetail(
                event = selected,
                clientName = selected.clientId?.let(clientNames::get),
                report = report,
                onBack = onBack,
                showBack = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SettingsOverview(
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    pairing: DeviceIdentity?,
    counts: DataCounts,
    syncResult: RequestSyncResult?,
    pushState: String?,
    protection: VaultProtection?,
    authenticationMode: DeviceAuthenticationMode,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val notificationsEnabled = RequestNotifications.areEnabled(context)
    Column(modifier) {
        PageTopBar("Settings", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val pairingSummary = when {
                    pairing == null -> "Setup incomplete"
                    pairing.pairingEnabled -> "${pairing.address} · Accepting new pairings"
                    else -> "${pairing.address} · New pairings paused"
                }
                SettingsRow(Icons.Outlined.Smartphone, "Device & pairing", pairingSummary) {
                    onOpen(SettingsPage.DEVICE)
                }
            }
            item {
                val notificationSummary = when {
                    !notificationsEnabled -> "Disabled"
                    pushState != null && pushState != "registered" -> "Delivery needs attention"
                    else -> "Enabled"
                }
                SettingsRow(Icons.Outlined.Notifications, "Notifications", notificationSummary) {
                    onOpen(SettingsPage.NOTIFICATIONS)
                }
            }
            item {
                SettingsRow(
                    Icons.Outlined.Security,
                    "Security",
                    "${protection.overviewDescription()} · ${authenticationMode.overviewLabel()}",
                ) { onOpen(SettingsPage.SECURITY) }
            }
            item {
                SettingsRow(
                    Icons.Outlined.History,
                    "Data & history",
                    "${counts.secrets.countLabel("secret")} · " +
                        "${counts.requests.countLabel("workflow")} · " +
                        "${counts.auditEvents.countLabel("audit event")}",
                ) { onOpen(SettingsPage.DATA) }
            }
            item {
                val status = when (syncResult) {
                    null, RequestSyncResult.Success -> "Connected"
                    else -> "Needs attention"
                }
                SettingsRow(Icons.Outlined.DataUsage, "Connection diagnostics", status) { onOpen(SettingsPage.DIAGNOSTICS) }
            }
            item {
                SettingsRow(
                    Icons.Outlined.Info,
                    "About",
                    "Version ${BuildConfig.VERSION_NAME}",
                ) { onOpen(SettingsPage.ABOUT) }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { if (summary.isNotBlank()) Text(summary) },
            leadingContent = { TonalIcon(icon, contentDescription = null) },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}

@Composable
private fun DeviceAndPairing(
    identity: DeviceIdentity?,
    onBack: () -> Unit,
    onChangeAddress: () -> Unit,
    onSetPairingEnabled: (Boolean) -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    var technicalExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pairingCommand = identity?.let { "agentknock pairing start ${it.address}" }
    Column(modifier) {
        PageTopBar("Device & pairing", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (identity == null) {
                Text("Device setup is incomplete.")
                return@Column
            }
            Text(
                "Pairing address",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            identity.address,
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    IconButton(
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText("Agentknock pairing address", identity.address),
                            )
                            report("Pairing address copied")
                        },
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy pairing address")
                    }
                }
            }
            Text(
                "This address is public. Sharing it does not grant access.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onChangeAddress, modifier = Modifier.fillMaxWidth()) {
                Text("Change pairing address")
            }
            Text(
                "Existing clients keep working after the pairing address changes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = identity.pairingEnabled,
                        role = Role.Switch,
                        onValueChange = onSetPairingEnabled,
                    ),
            ) {
                ListItem(
                    headlineContent = { Text("Accept new pairings") },
                    supportingContent = {
                        Text(
                            if (identity.pairingEnabled) {
                                "New clients can request pairing. Every request still needs your approval."
                            } else {
                                "New pairing requests are paused."
                            },
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = identity.pairingEnabled,
                            onCheckedChange = null,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                )
            }
            if (identity.pairingEnabled) {
                Text("Pair a client", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Run this command on the machine you want to pair. Review the pairing in Clients before starting another one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("Agentknock pairing command", pairingCommand),
                        )
                        report("Pairing command copied")
                    },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(
                                checkNotNull(pairingCommand),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy pairing command")
                        Spacer(Modifier.width(12.dp))
                    }
                }
            } else {
                Text("Pairing is paused", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Resume new pairings before giving a pairing command to a client.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { technicalExpanded = !technicalExpanded }
                    .semantics {
                        stateDescription = if (technicalExpanded) "Expanded" else "Collapsed"
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Technical details", style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (technicalExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            if (technicalExpanded) SelectionContainer {
                LabeledValue("Device ID", identity.deviceId, true)
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
    val notificationsEnabled = remember(refreshGeneration) {
        RequestNotifications.areEnabled(context)
    }
    Column(modifier) {
        PageTopBar("Notifications", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                color = if (notificationsEnabled) {
                    MaterialTheme.agentknockColors.successContainer
                } else {
                    MaterialTheme.agentknockColors.attentionContainer
                },
                contentColor = if (notificationsEnabled) {
                    MaterialTheme.agentknockColors.onSuccessContainer
                } else {
                    MaterialTheme.agentknockColors.onAttentionContainer
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (notificationsEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.Notifications,
                        contentDescription = null,
                    )
                    Text(
                        if (notificationsEnabled) "Notifications enabled" else "Notifications disabled",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Text(
                if (notificationsEnabled) {
                    "Agentknock can alert you as soon as a request needs your attention."
                } else {
                    "You will not see new requests until you open Agentknock."
                },
            )
            Text(
                "Secret use notifications can include Approve once and Deny once actions. Approving requires the device to be unlocked; on older Android versions, Agentknock opens the request for review.",
            )
            Text(
                "Android controls notification sounds and how much content is visible on the lock screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(
                    onClick = requestNotificationPermission,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow notifications") }
            }
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Launch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Android notification settings")
            }
            if (pushState != null && pushState != "registered") {
                Text("Relay push registration: $pushState", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SecuritySettings(
    protection: VaultProtection?,
    authenticationMode: DeviceAuthenticationMode,
    onAuthenticationModeChange: (DeviceAuthenticationMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val deviceSecure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
    val hardwareBackings = setOf(
        EncryptionKeyBacking.STRONGBOX,
        EncryptionKeyBacking.TRUSTED_ENVIRONMENT,
        EncryptionKeyBacking.UNKNOWN_SECURE,
    )
    val hardwareBacked = protection is VaultProtection.Available &&
        protection.backings.values.all { it in hardwareBackings }
    val protectionKnown = protection is VaultProtection.Available
    Column(modifier) {
        PageTopBar("Security", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                color = when {
                    hardwareBacked -> MaterialTheme.agentknockColors.successContainer
                    protectionKnown -> MaterialTheme.agentknockColors.attentionContainer
                    else -> MaterialTheme.agentknockColors.dangerContainer
                },
                contentColor = when {
                    hardwareBacked -> MaterialTheme.agentknockColors.onSuccessContainer
                    protectionKnown -> MaterialTheme.agentknockColors.onAttentionContainer
                    else -> MaterialTheme.agentknockColors.onDangerContainer
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null)
                    Column {
                        Text(
                            if (
                                protection is VaultProtection.Available &&
                                protection.backings.values.any { it == EncryptionKeyBacking.SOFTWARE }
                            ) {
                                "Encrypted without hardware protection"
                            } else {
                                "Stored values are encrypted"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            protection.protectionExplanation(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            LabeledValue("Encryption", "AES-128-GCM")
            LabeledValue(
                "Secret values key",
                protection.description(VaultKeyPurpose.SECRET_VALUES),
            )
            LabeledValue(
                "Device state key",
                protection.description(VaultKeyPurpose.DEVICE_STATE),
            )
            LabeledValue(
                "Device authentication",
                if (deviceSecure) "Secure screen lock configured" else "No secure screen lock configured",
            )
            Text("Require device authentication", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceAuthenticationMode.entries.forEach { mode ->
                    AuthenticationModeOption(
                        mode = mode,
                        selected = mode == authenticationMode,
                        onSelect = { onAuthenticationModeChange(mode) },
                    )
                }
            }
            Text(
                "This controls Agentknock's interface. Background synchronization and automatic approval rules continue while the app is locked.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuthenticationModeOption(
    mode: DeviceAuthenticationMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MaterialTheme.shapes.large,
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        "No additional Agentknock prompts. Review and destructive confirmations still apply."
    DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING ->
        "Authenticate before showing, copying, or editing sensitive values, weakening their protection, or accepting a new client."
    DeviceAuthenticationMode.APP_LOCK ->
        "Authenticate before any Agentknock content is shown. One unlock lasts for the foreground session."
}

private fun VaultProtection?.description(purpose: VaultKeyPurpose): String = when (this) {
    null -> "Checking this device…"
    is VaultProtection.Available -> when (backings[purpose]) {
        EncryptionKeyBacking.STRONGBOX -> "StrongBox hardware"
        EncryptionKeyBacking.TRUSTED_ENVIRONMENT -> "Trusted execution environment"
        EncryptionKeyBacking.SOFTWARE -> "Android Keystore (software-backed)"
        EncryptionKeyBacking.UNKNOWN_SECURE -> "Secure hardware (type unavailable)"
        EncryptionKeyBacking.UNKNOWN -> "Android Keystore (backing unknown)"
        null -> "Protection could not be determined"
    }
    is VaultProtection.KeyUnavailable -> if (purpose in purposes) {
        "Key unavailable on this device"
    } else {
        "Protection could not be determined"
    }
    VaultProtection.Unknown -> "Protection could not be determined"
}

private fun VaultProtection?.overviewDescription(): String = when (this) {
    null -> "Checking this device…"
    is VaultProtection.Available -> when {
        backings.values.all {
            it == EncryptionKeyBacking.STRONGBOX ||
                it == EncryptionKeyBacking.TRUSTED_ENVIRONMENT ||
                it == EncryptionKeyBacking.UNKNOWN_SECURE
        } -> "Hardware-backed encryption"
        backings.values.any { it == EncryptionKeyBacking.SOFTWARE } -> "OS-protected encryption"
        else -> "Encryption protection unknown"
    }
    is VaultProtection.KeyUnavailable -> "Encryption key unavailable"
    VaultProtection.Unknown -> "Protection could not be determined"
}

private fun VaultProtection?.protectionExplanation(): String = when (this) {
    is VaultProtection.Available ->
        "Secret values and device state use separate keys in Android Keystore."
    is VaultProtection.KeyUnavailable -> "A vault key is not available on this device."
    VaultProtection.Unknown -> "Agentknock could not determine how the vault keys are protected."
    null -> "Checking how this device protects the vault keys…"
}

@Composable
private fun DataAndHistory(
    counts: DataCounts,
    onBack: () -> Unit,
    onAudit: () -> Unit,
    onFactoryReset: () -> Unit,
    onClearRequests: () -> Unit,
    modifier: Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier) {
        PageTopBar("Data & history", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsRow(
                Icons.Outlined.History,
                "Audit log",
                "${counts.auditEvents.countLabel("event")} · kept for one year",
                onClick = onAudit,
            )
            SettingsInformationRow(
                icon = Icons.Outlined.Storage,
                title = "On this device",
                summary = listOf(
                    counts.secrets.countLabel("secret"),
                    counts.variables.countLabel("environment variable"),
                    counts.clients.countLabel("client"),
                    counts.requests.countLabel("workflow"),
                ).joinToString(" · "),
            )
            SettingsInformationRow(
                icon = Icons.Outlined.Backup,
                title = "Android backup and transfer",
                summary = "Secrets, clients, workflow history, audit events, and encrypted values " +
                    "are included. Device-bound keys cannot be restored on another device.",
            )
            SettingsActionRow(
                icon = Icons.Outlined.DeleteSweep,
                title = "Clear completed workflow history",
                summary = if (counts.requests == 0) {
                    "No workflow history to clear"
                } else {
                    "Pending workflows and audit events are kept"
                },
                enabled = counts.requests > 0,
                onClick = { confirmClear = true },
            )
            Spacer(Modifier.size(8.dp))
            SettingsActionRow(
                icon = Icons.Outlined.DeleteForever,
                title = "Factory reset Agentknock",
                summary = "Erase this Agentknock device identity, secrets, clients, and history",
                destructive = true,
                onClick = onFactoryReset,
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear completed workflows?") },
            text = { Text("Pending workflows, paired clients, secrets, and the audit log are not removed.") },
            confirmButton = { TextButton(onClick = { onClearRequests(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsInformationRow(
    icon: ImageVector,
    title: String,
    summary: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            leadingContent = { TonalIcon(icon, contentDescription = null) },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    summary: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.agentknockColors.danger
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = { Text(title, color = contentColor) },
            supportingContent = { Text(summary) },
            leadingContent = {
                Icon(icon, contentDescription = null, tint = contentColor)
            },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}

@Composable
private fun AuditList(
    events: List<AuditEvent>,
    selectedEventId: Long?,
    clientNames: Map<String, String>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        PageTopBar("Audit log", onBack)
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No audit events yet") }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(events, key = AuditEvent::id) { event ->
                    val selected = event.id == selectedEventId
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = MaterialTheme.shapes.medium,
                        onClick = { onOpen(event.id) },
                        modifier = Modifier.fillMaxWidth().semantics {
                            this.selected = selected
                        },
                    ) {
                        ListItem(
                            headlineContent = { Text(event.title) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    event.clientId?.let(clientNames::get)?.let {
                                        Text("Client: $it", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    event.detail.takeIf(String::isNotBlank)?.let {
                                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(formatTimestamp(event.occurredAt))
                                }
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AuditOutcomeBadge(event.outcome)
                                    Icon(
                                        Icons.Outlined.ChevronRight,
                                        contentDescription = null,
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditDetail(
    event: AuditEvent,
    clientName: String?,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current

    fun copy(label: String, value: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(label, value),
        )
        report("$label copied")
    }

    Column(modifier) {
        PageTopBar("Audit event", onBack, showBack)
        SelectionContainer {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                InformationSurface {
                    Text(event.title, style = MaterialTheme.typography.headlineSmall)
                    AuditOutcomeBadge(event.outcome)
                }
                if (event.detail.isNotBlank()) {
                    InformationSurface {
                        Text(
                            "Recorded details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(event.detail, style = MaterialTheme.typography.titleMedium)
                    }
                }
                InformationSurface {
                    InformationRow("Time", formatTimestamp(event.occurredAt))
                    InformationRow(
                        "Category",
                        event.category.storedName.replace('_', ' ').replaceFirstChar(Char::uppercase),
                    )
                    clientName?.let { InformationRow("Client", it) }
                    event.clientId?.let {
                        CopyableLabeledValue("Client ID", it) { copy("Client ID", it) }
                    }
                    event.relayRequestId?.let {
                        CopyableLabeledValue("Request ID", it) { copy("Request ID", it) }
                    }
                    InformationRow(
                        "Audit sequence number",
                        event.id.toString(),
                        monospace = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyableLabeledValue(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            LabeledValue(label, value, true)
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $label")
        }
    }
}

@Composable
private fun AuditOutcomeBadge(outcome: AuditOutcome) {
    val failed = outcome == AuditOutcome.FAILED
    val rejected = outcome == AuditOutcome.DENIED || outcome == AuditOutcome.REJECTED
    val positive = outcome == AuditOutcome.APPROVED ||
        outcome == AuditOutcome.COMPLETED ||
        outcome == AuditOutcome.CHANGED
    Surface(
        color = when {
            failed -> MaterialTheme.agentknockColors.dangerContainer
            positive -> MaterialTheme.agentknockColors.successContainer
            rejected -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = when {
            failed -> MaterialTheme.agentknockColors.onDangerContainer
            positive -> MaterialTheme.agentknockColors.onSuccessContainer
            rejected -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            outcome.storedName.replaceFirstChar(Char::uppercase),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FactoryReset(
    onBack: () -> Unit,
    reset: suspend (Boolean) -> FactoryResetResult,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    var phrase by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var allowLocalOnly by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun start(localOnly: Boolean) {
        scope.launch {
            working = true
            when (val result = reset(localOnly)) {
                FactoryResetResult.Reset -> Unit
                FactoryResetResult.NoDevice -> {
                    allowLocalOnly = true
                    report("The relay device could not be found. Nothing was erased.")
                }
                is FactoryResetResult.RemoteRejected -> {
                    allowLocalOnly = true
                    report("The relay rejected deletion. Nothing was erased.")
                }
                is FactoryResetResult.RemoteUnavailable -> {
                    allowLocalOnly = true
                    report("The relay is unavailable. Nothing was erased.")
                }
                FactoryResetResult.InvalidRemoteResponse -> {
                    allowLocalOnly = true
                    report("Relay deletion could not be confirmed. Nothing was erased.")
                }
                FactoryResetResult.DeviceCredentialsUnavailable -> {
                    allowLocalOnly = true
                    report("Relay authentication is unavailable. Nothing was erased.")
                }
            }
            working = false
        }
    }

    Column(modifier) {
        PageTopBar("Factory reset Agentknock", onBack)
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
            Text("Agentknock will ask the relay to delete this Agentknock device registration and its live state.")
            Text("It will then permanently erase:")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("• Device identity and encryption keys")
                Text("• Secrets and their stored values")
                Text("• Paired clients and requests")
                Text("• Audit log and settings")
            }
            Text("Setup starts again with a new device identity and pairing address. Every client must pair again.")
            Text(
                "If relay deletion cannot be confirmed, nothing is erased unless you explicitly choose a local-only reset. Factory reset does not fix temporary connection problems.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Type RESET AGENTKNOCK to confirm.",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
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
                onClick = { start(false) },
                enabled = phrase == "RESET AGENTKNOCK" && !working,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Text("Erase and reset Agentknock")
                }
            }
            if (allowLocalOnly) {
                Text("Remote deletion was not confirmed. Resetting only this app may leave inaccessible relay state until automatic cleanup.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = { start(true) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset this app anyway", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun Diagnostics(
    syncing: Boolean,
    result: RequestSyncResult?,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val healthy = result == null || result == RequestSyncResult.Success
    Column(modifier) {
        PageTopBar("Connection diagnostics", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                color = if (healthy) {
                    MaterialTheme.agentknockColors.successContainer
                } else {
                    MaterialTheme.agentknockColors.dangerContainer
                },
                contentColor = if (healthy) {
                    MaterialTheme.agentknockColors.onSuccessContainer
                } else {
                    MaterialTheme.agentknockColors.onDangerContainer
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (healthy && !syncing) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    } else if (syncing) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.DataUsage, contentDescription = null)
                    }
                    Column {
                        Text(
                            when {
                                syncing -> "Synchronizing"
                                healthy -> "Connected"
                                else -> "Temporarily unavailable"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (healthy) "Synchronized with the relay" else "Agentknock will keep retrying",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            val detail = result.diagnosticMessage()
            if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onReconnect, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reconnect and sync now")
            }
            Text("Technical details", style = MaterialTheme.typography.titleMedium)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionContainer(Modifier.weight(1f)) {
                        LabeledValue("Relay", "wss://relay.agentknock.dev", true)
                    }
                    IconButton(
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText(
                                    "Agentknock relay",
                                    "wss://relay.agentknock.dev",
                                ),
                            )
                            report("Relay address copied")
                        },
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy relay address")
                    }
                }
            }
        }
    }
}

@Composable
private fun About(onBack: () -> Unit, report: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val version = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
    Column(modifier) {
        PageTopBar("About", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Agentknock", style = MaterialTheme.typography.headlineMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(version, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("Agentknock version", version),
                        )
                        report("Version copied")
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy version")
                }
            }
            Text(
                "Stored values and device identity keys are protected on this device. Secret use responses are encrypted end to end for the paired client. The relay routes ciphertext and generic wake signals.",
            )
            Text("Made by Full Disclosure", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PageTopBar(title: String, onBack: () -> Unit, showBack: Boolean = true) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        },
    )
}

@Composable
private fun LabeledValue(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (monospace) FontFamily.Monospace else null)
    }
}

private fun DeviceManagementResult.message(enabled: Boolean): String = when (this) {
    DeviceManagementResult.Changed -> if (enabled) "New pairings resumed" else "New pairings paused"
    DeviceManagementResult.NoDevice -> "Device setup is incomplete"
    DeviceManagementResult.CredentialsUnavailable -> "Device keys are unavailable"
    DeviceManagementResult.CredentialsCorrupted -> "Device keys could not be verified"
    DeviceManagementResult.UnsupportedEncryption -> "Device keys use unsupported encryption"
    is DeviceManagementResult.Rejected -> message ?: "The relay rejected the change"
    is DeviceManagementResult.Unavailable -> message ?: "The relay is unavailable"
    DeviceManagementResult.InvalidResponse -> "The relay returned an invalid response"
}

private fun Int.countLabel(noun: String): String = "$this $noun${if (this == 1) "" else "s"}"

private fun RequestSyncResult?.diagnosticMessage(): String? = when (this) {
    null, RequestSyncResult.Success -> null
    RequestSyncResult.NoDevice -> "Device setup is incomplete."
    RequestSyncResult.DeviceCredentialsUnavailable -> "Device keys are unavailable on this device."
    RequestSyncResult.DeviceCredentialsCorrupted -> "Device keys could not be verified."
    RequestSyncResult.UnsupportedDeviceCredentialEncryption -> "Device keys use unsupported encryption."
    is RequestSyncResult.RelayRejected -> message ?: "The relay rejected the connection."
    is RequestSyncResult.RelayUnavailable -> message ?: "The relay could not be reached."
    RequestSyncResult.InvalidRelayResponse -> "The relay returned an invalid response."
}
