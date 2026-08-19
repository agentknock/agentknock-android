@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteForever
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.BuildConfig
import dev.agentknock.storage.FactoryResetResult
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.LocalEncryptionProtection
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.vault.DeviceManagementResult
import dev.agentknock.storage.vault.VaultIdentity
import java.text.DateFormat
import java.util.Date
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
    authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
    requestNotificationPermission: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.OVERVIEW) }
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val counts by viewModel.dataCounts.collectAsStateWithLifecycle()
    val auditEvents by viewModel.auditEvents.collectAsStateWithLifecycle()
    val selectedAudit by viewModel.selectedAuditEvent.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val syncResult by viewModel.lastSyncResult.collectAsStateWithLifecycle()
    val pushState by viewModel.pushRegistrationState.collectAsStateWithLifecycle()
    val localEncryptionProtection by viewModel.localEncryptionProtection.collectAsStateWithLifecycle()
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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (page) {
            SettingsPage.OVERVIEW -> SettingsOverview(
                onBack = onClose,
                onOpen = { page = it },
                pairing = configuration?.active,
                counts = counts,
                syncResult = syncResult,
                modifier = modifier,
            )
            SettingsPage.DEVICE -> DeviceAndPairing(
                identity = configuration?.active,
                onBack = ::back,
                onChangeAddress = onChangeAddress,
                onSetPairingEnabled = { enabled ->
                    authenticate(
                        if (enabled) "Resume new pairings" else "Pause new pairings",
                        {
                            scope.launch {
                                snackbar.showSnackbar(
                                    viewModel.setPairingEnabled(enabled).message(enabled),
                                )
                            }
                        },
                        { scope.launch { snackbar.showSnackbar(it) } },
                    )
                },
                report = { scope.launch { snackbar.showSnackbar(it) } },
                modifier = modifier,
            )
            SettingsPage.NOTIFICATIONS -> NotificationsSettings(
                pushState = pushState?.wireName,
                requestNotificationPermission = requestNotificationPermission,
                onBack = ::back,
                modifier = modifier,
            )
            SettingsPage.SECURITY -> SecuritySettings(
                protection = localEncryptionProtection,
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
                        snackbar.showSnackbar("Cleared $removed completed requests")
                    }
                },
                modifier = modifier,
            )
            SettingsPage.AUDIT -> if (selectedAudit == null) {
                AuditList(
                    events = auditEvents,
                    onBack = ::back,
                    onOpen = viewModel::selectAuditEvent,
                    modifier = modifier,
                )
            } else {
                AuditDetail(event = checkNotNull(selectedAudit), onBack = ::back, modifier = modifier)
            }
            SettingsPage.FACTORY_RESET -> FactoryReset(
                onBack = ::back,
                authenticate = authenticate,
                reset = viewModel::factoryReset,
                report = { scope.launch { snackbar.showSnackbar(it) } },
                modifier = modifier,
            )
            SettingsPage.DIAGNOSTICS -> Diagnostics(
                syncing = syncing,
                result = syncResult,
                onBack = ::back,
                onReconnect = viewModel::reconnect,
                modifier = modifier,
            )
            SettingsPage.ABOUT -> About(onBack = ::back, modifier = modifier)
        }
    }
}

@Composable
private fun SettingsOverview(
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    pairing: VaultIdentity?,
    counts: DataCounts,
    syncResult: RequestSyncResult?,
    modifier: Modifier,
) {
    Column(modifier) {
        PageTopBar("Settings", onBack)
        LazyColumn {
            item { SettingsRow(Icons.Outlined.Smartphone, "Device & pairing", pairing?.address.orEmpty()) { onOpen(SettingsPage.DEVICE) } }
            item { SettingsRow(Icons.Outlined.Notifications, "Notifications", "Android and relay delivery") { onOpen(SettingsPage.NOTIFICATIONS) } }
            item { SettingsRow(Icons.Outlined.Security, "Security", "Local protection") { onOpen(SettingsPage.SECURITY) } }
            item { SettingsRow(Icons.Outlined.History, "Data and history", "${counts.auditEvents} audit events") { onOpen(SettingsPage.DATA) } }
            item {
                val status = when (syncResult) {
                    null, RequestSyncResult.Success -> "Connected when app is visible"
                    else -> "Needs attention"
                }
                SettingsRow(Icons.Outlined.DataUsage, "Connection diagnostics", status) { onOpen(SettingsPage.DIAGNOSTICS) }
            }
            item { SettingsRow(Icons.Outlined.Info, "About", BuildConfig.VERSION_NAME) { onOpen(SettingsPage.ABOUT) } }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { if (summary.isNotBlank()) Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

@Composable
private fun DeviceAndPairing(
    identity: VaultIdentity?,
    onBack: () -> Unit,
    onChangeAddress: () -> Unit,
    onSetPairingEnabled: (Boolean) -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    var confirmPairingChange by remember { mutableStateOf(false) }
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
            Text("Pairing address", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SelectionContainer {
                Text(identity.address, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            }
            Text(
                if (identity.pairingEnabled) "Accepting new pairings" else "New pairings paused",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = { confirmPairingChange = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (identity.pairingEnabled) "Pause new pairings" else "Resume new pairings")
            }
            HorizontalDivider()
            if (identity.pairingEnabled) {
                Text("Pair a client", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(
                        checkNotNull(pairingCommand),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("Agentknock pairing command", pairingCommand),
                        )
                        report("Pairing command copied")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy pairing command")
                }
            } else {
                Text("Pairing is paused", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Resume new pairings before giving a pairing command to a client.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            TextButton(onClick = onChangeAddress, modifier = Modifier.align(Alignment.Start)) {
                Text("Change pairing address")
            }
            Text(
                "Existing clients keep working after the pairing address changes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().clickable { technicalExpanded = !technicalExpanded },
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
    if (confirmPairingChange && identity != null) {
        AlertDialog(
            onDismissRequest = { confirmPairingChange = false },
            title = { Text(if (identity.pairingEnabled) "Pause new pairings?" else "Resume new pairings?") },
            text = {
                Text(
                    if (identity.pairingEnabled) {
                        "New clients will not be able to start pairing. A pairing request already shown in Requests is unaffected."
                    } else {
                        "New clients will be able to start pairing again. A pairing request already shown in Requests is unaffected."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetPairingEnabled(!identity.pairingEnabled)
                    confirmPairingChange = false
                }) { Text(if (identity.pairingEnabled) "Pause" else "Resume") }
            },
            dismissButton = { TextButton(onClick = { confirmPairingChange = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NotificationsSettings(
    pushState: String?,
    requestNotificationPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val notificationsEnabled = context.getSystemService(NotificationManager::class.java)
        .areNotificationsEnabled()
    Column(modifier) {
        PageTopBar("Notifications", onBack)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                if (notificationsEnabled) "Notifications enabled" else "Notifications disabled",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Notifications identify the client and request. Profile access requests can be approved or denied directly when Android allows authentication from the notification; other requests open in Agentknock for review.",
            )
            Text(
                "Android controls how much notification content is visible on the lock screen.",
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
            ) { Text("Open Android notification settings") }
            if (pushState != null && pushState != "registered") {
                Text("Relay push registration: $pushState", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SecuritySettings(
    protection: LocalEncryptionProtection?,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        PageTopBar("Security", onBack)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Stored values are encrypted", style = MaterialTheme.typography.titleLarge)
            LabeledValue("Encryption", "AES-128-GCM")
            LabeledValue("Key protection", protection.description())
            Text(
                "Sensitive values require device authentication before they are revealed or copied.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LocalEncryptionProtection?.description(): String = when (this) {
    null -> "Checking this device…"
    is LocalEncryptionProtection.Available -> when (backing) {
        EncryptionKeyBacking.STRONGBOX -> "StrongBox hardware"
        EncryptionKeyBacking.TRUSTED_ENVIRONMENT -> "Trusted execution environment"
        EncryptionKeyBacking.SOFTWARE -> "Android Keystore (software-backed)"
        EncryptionKeyBacking.UNKNOWN_SECURE -> "Secure hardware (type unavailable)"
        EncryptionKeyBacking.UNKNOWN -> "Android Keystore (backing unknown)"
    }
    LocalEncryptionProtection.KeyUnavailable -> "Key unavailable on this device"
    LocalEncryptionProtection.Unknown -> "Protection could not be determined"
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
        PageTopBar("Data and history", onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsRow(Icons.Outlined.History, "Audit log", "${counts.auditEvents} events · kept for one year", onAudit)
            ListItem(
                headlineContent = { Text("On this device") },
                supportingContent = {
                    Text(
                        listOf(
                            counts.profiles.countLabel("profile"),
                            counts.clients.countLabel("client"),
                            counts.requests.countLabel("request"),
                        ).joinToString(" · "),
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Android backup and transfer") },
                supportingContent = { Text("Metadata and encrypted values are backed up. Device-bound keys cannot be restored on another device.") },
            )
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Clear completed request history")
            }
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            TextButton(onClick = onFactoryReset, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Factory reset Agentknock", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear completed requests?") },
            text = { Text("Pending requests, paired clients, profiles, and the audit log are not removed.") },
            confirmButton = { TextButton(onClick = { onClearRequests(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AuditList(events: List<AuditEvent>, onBack: () -> Unit, onOpen: (Long) -> Unit, modifier: Modifier) {
    Column(modifier) {
        PageTopBar("Audit log", onBack)
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No audit events yet") }
        } else {
            LazyColumn {
                items(events, key = AuditEvent::id) { event ->
                    ListItem(
                        headlineContent = { Text(event.title) },
                        supportingContent = {
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.occurredAt)))
                        },
                        trailingContent = { Text(event.outcome.storedName.replaceFirstChar(Char::uppercase)) },
                        modifier = Modifier.clickable { onOpen(event.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AuditDetail(event: AuditEvent, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        PageTopBar(event.title, onBack)
        SelectionContainer {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(event.outcome.storedName.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleLarge)
                if (event.detail.isNotBlank()) Text(event.detail)
                LabeledValue("Time", DateFormat.getDateTimeInstance().format(Date(event.occurredAt)))
                event.clientId?.let { LabeledValue("Client ID", it, true) }
                event.relayRequestId?.let { LabeledValue("Request ID", it, true) }
            }
        }
    }
}

@Composable
private fun FactoryReset(
    onBack: () -> Unit,
    authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
    reset: suspend (Boolean) -> FactoryResetResult,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    var phrase by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var allowLocalOnly by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun start(localOnly: Boolean) {
        authenticate(
            "Factory reset Agentknock",
            {
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
                        FactoryResetResult.LocalSecretsUnavailable -> {
                            allowLocalOnly = true
                            report("Device credentials are unavailable. Nothing was erased.")
                        }
                    }
                    working = false
                }
            },
            report,
        )
    }

    Column(modifier) {
        PageTopBar("Factory reset Agentknock", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("This cannot be undone", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            Text("Profiles and values, clients, requests, the audit log, settings, and this device identity will be erased.")
            Text("Agentknock first asks the relay to delete this device and its live state. Every client must pair again.")
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
                label = { Text("Enter RESET AGENTKNOCK") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { start(false) },
                enabled = phrase == "RESET AGENTKNOCK" && !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) CircularProgressIndicator(Modifier.width(20.dp)) else Text("Factory reset Agentknock")
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
private fun Diagnostics(syncing: Boolean, result: RequestSyncResult?, onBack: () -> Unit, onReconnect: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        PageTopBar("Connection diagnostics", onBack)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                when {
                    syncing -> "Synchronizing"
                    result == null || result == RequestSyncResult.Success -> "Up to date"
                    else -> "Temporarily unavailable"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            val detail = result.diagnosticMessage()
            if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onReconnect, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reconnect and synchronize now")
            }
            SelectionContainer {
                LabeledValue("Relay", "wss://relay.agentknock.dev", true)
            }
        }
    }
}

@Composable
private fun About(onBack: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        PageTopBar("About", onBack)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Agentknock", style = MaterialTheme.typography.headlineMedium)
            Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text("Profiles are protected on this phone. The relay routes encrypted messages and sends generic wake signals.")
            Text("Made by Full Disclosure", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PageTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
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
    DeviceManagementResult.SecretsUnavailable -> "Device keys are unavailable"
    DeviceManagementResult.SecretsCorrupted -> "Device keys could not be verified"
    DeviceManagementResult.UnsupportedEncryption -> "Device keys use unsupported encryption"
    is DeviceManagementResult.Rejected -> message ?: "The relay rejected the change"
    is DeviceManagementResult.Unavailable -> message ?: "The relay is unavailable"
    DeviceManagementResult.InvalidResponse -> "The relay returned an invalid response"
}

private fun Int.countLabel(noun: String): String = "$this $noun${if (this == 1) "" else "s"}"

private fun RequestSyncResult?.diagnosticMessage(): String? = when (this) {
    null, RequestSyncResult.Success -> null
    RequestSyncResult.NoVault -> "Device setup is incomplete."
    RequestSyncResult.VaultSecretsUnavailable -> "Device keys are unavailable on this phone."
    RequestSyncResult.VaultSecretsCorrupted -> "Device keys could not be verified."
    RequestSyncResult.UnsupportedVaultEncryption -> "Device keys use unsupported encryption."
    is RequestSyncResult.RelayRejected -> message ?: "The relay rejected the connection."
    is RequestSyncResult.RelayUnavailable -> message ?: "The relay could not be reached."
    RequestSyncResult.InvalidRelayResponse -> "The relay returned an invalid response."
}
