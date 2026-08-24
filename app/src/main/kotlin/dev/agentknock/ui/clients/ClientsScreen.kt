@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.clients

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientChangeResult
import dev.agentknock.storage.request.ClientDetails
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.vault.DeviceIdentity
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.TonalIcon
import dev.agentknock.ui.theme.agentknockColors
import kotlinx.coroutines.launch

@Composable
internal fun ClientsScreen(
    onOpenSettings: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    viewModel: ClientsViewModel = viewModel(),
) {
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectedClient by viewModel.selectedClient.collectAsStateWithLifecycle()
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val twoPane = maxWidth >= 840.dp
            LaunchedEffect(selection, twoPane) {
                onTopLevelChanged(twoPane || selection == null)
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    ClientList(
                        clients = clients,
                        selectedClientId = selection,
                        identity = configuration?.active,
                        onOpen = viewModel::selectClient,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.width(340.dp).fillMaxHeight(),
                    )
                    VerticalDivider()
                    if (selection == null) {
                        EmptyClientSelection(Modifier.weight(1f).fillMaxHeight())
                    } else {
                        ClientSelectionDetail(
                            client = selectedClient,
                            viewModel = viewModel,
                            report = ::report,
                            onBack = { viewModel.selectClient(null) },
                            showBack = false,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            } else if (selection == null) {
                ClientList(
                    clients = clients,
                    selectedClientId = selection,
                    identity = configuration?.active,
                    onOpen = viewModel::selectClient,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                BackHandler { viewModel.selectClient(null) }
                ClientSelectionDetail(
                    client = selectedClient,
                    viewModel = viewModel,
                    report = ::report,
                    onBack = { viewModel.selectClient(null) },
                    showBack = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ClientSelectionDetail(
    client: ClientDetails?,
    viewModel: ClientsViewModel,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    if (client == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    ClientDetail(
        client = client,
        onBack = onBack,
        showBack = showBack,
        onRename = { name ->
            scope.launch {
                val result = viewModel.rename(client.clientId, name.trim())
                report(
                    if (result == ClientChangeResult.CHANGED) {
                        "Client renamed"
                    } else {
                        "Client is no longer available"
                    },
                )
            }
        },
        onSetState = { state ->
            scope.launch {
                val result = viewModel.setState(client.clientId, state)
                if (result == ClientChangeResult.CHANGED && state == RelayClientState.REVOKED) {
                    viewModel.selectClient(null)
                }
                report(
                    if (result == ClientChangeResult.CHANGED) {
                        state.successMessage()
                    } else {
                        "Client state could not be changed"
                    },
                )
            }
        },
        report = report,
        modifier = modifier,
    )
}

@Composable
private fun EmptyClientSelection(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "Select a client to view its details",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClientList(
    clients: List<ClientSummary>,
    selectedClientId: String?,
    identity: DeviceIdentity?,
    onOpen: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TopAppBar(
            title = { Text("Clients") },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            },
        )
        if (clients.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("No paired clients", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Pairing requests will appear in Requests.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    identity?.let {
                        Text(
                            if (it.pairingEnabled) {
                                "Pairing address"
                            } else {
                                "Pairing paused"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        SelectionContainer {
                            Text(it.address, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(clients, key = { _, client -> client.clientId }) { _, client ->
                    val selected = client.clientId == selectedClientId
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = MaterialTheme.shapes.large,
                        onClick = { onOpen(client.clientId) },
                        modifier = Modifier.fillMaxWidth().semantics {
                            this.selected = selected
                        },
                    ) {
                        ListItem(
                            headlineContent = { Text(client.name) },
                            supportingContent = {
                                val hostname = client.hostname
                                    ?.takeUnless { it.equals(client.name, ignoreCase = true) }
                                val platform = client.platform?.let(::formatPlatformName)
                                val machine = when {
                                    hostname != null && platform != null -> "$platform on $hostname"
                                    hostname != null -> hostname
                                    platform != null -> platform
                                    else -> ""
                                }
                                if (machine.isNotEmpty()) Text(machine)
                            },
                            leadingContent = {
                                TonalIcon(Icons.Outlined.Computer, contentDescription = null)
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    client.visibleState()?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = stateColor(client.state),
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Outlined.NavigateNext,
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
private fun ClientDetail(
    client: ClientDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onRename: (String) -> Unit,
    onSetState: (RelayClientState) -> Unit,
    report: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRename by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<RelayClientState?>(null) }
    val context = LocalContext.current

    fun copy(label: String, value: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(label, value),
        )
        report("$label copied")
    }

    Column(modifier) {
        TopAppBar(
            title = { Text(client.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                IconButton(onClick = { showRename = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename client")
                }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val pending = client.desiredState?.takeIf { it != client.state }
            InformationSurface {
                ClientStateBadge(client.state, pending)
                Text(client.state.explanation())
            }

            InformationSurface {
                Text("Pairing", style = MaterialTheme.typography.titleMedium)
                client.pairedAt?.let {
                    ClientField("Paired", formatTimestamp(it))
                }
                ClientField(
                    "Client ID",
                    client.clientId,
                    monospace = true,
                    onCopy = { copy("Client ID", client.clientId) },
                )
                when (client.state) {
                    RelayClientState.ACTIVE -> OutlinedButton(
                        onClick = { onSetState(RelayClientState.SUSPENDED) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.PauseCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Suspend client")
                    }
                    RelayClientState.SUSPENDED -> Button(
                        onClick = { onSetState(RelayClientState.ACTIVE) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.PlayCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Resume client")
                    }
                    RelayClientState.PENDING,
                    RelayClientState.REVOKED,
                    -> Unit
                }
                when (client.state) {
                    RelayClientState.ACTIVE -> Text(
                        "Suspending temporarily blocks this client. You can resume it later.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RelayClientState.SUSPENDED -> Text(
                        "Resuming lets this client connect and make requests again.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RelayClientState.PENDING,
                    RelayClientState.REVOKED,
                    -> Unit
                }
            }

            InformationSurface {
                Text("Reported information", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Supplied by the client when it paired.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ClientField("Hostname", client.hostname)
                ClientField("Platform", client.platform?.let(::formatPlatformName))
                ClientField("Architecture", client.architecture)
                ClientField("Operating system", client.osVersion)
                client.clientSoftware?.let { software ->
                    ClientField("Client software", renderSoftware(software.application))
                    if (software.library != software.application) {
                        ClientField("Agentknock library", renderSoftware(software.library))
                    }
                }
                ClientField(
                    "Machine ID",
                    client.machineId,
                    monospace = true,
                    onCopy = client.machineId?.let { { copy("Machine ID", it) } },
                )
            }
            if (client.state != RelayClientState.REVOKED) {
                InformationSurface {
                    Text("Remove access", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Revoking is permanent. This client must be paired again before it can reconnect.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { confirmation = RelayClientState.REVOKED },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.agentknockColors.danger,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
                    ) {
                        Icon(Icons.Outlined.Block, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Revoke client")
                    }
                }
            }
        }
    }

    if (showRename) {
        var name by remember(client.clientId) { mutableStateOf(client.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename client") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This only changes how the client appears in Agentknock.")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != client.name,
                    onClick = {
                        onRename(name)
                        showRename = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }

    confirmation?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("${target.actionLabel()} ${client.name}?") },
            text = {
                Text(
                    "This is permanent. The client must pair again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetState(target)
                    confirmation = null
                }) {
                    Text(target.actionLabel(), color = MaterialTheme.agentknockColors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ClientField(
    label: String,
    value: String?,
    monospace: Boolean = false,
    onCopy: (() -> Unit)? = null,
) {
    if (value.isNullOrBlank()) return
    InformationRow(
        label = label,
        value = value,
        monospace = monospace,
        trailingContent = onCopy?.let { copy ->
            {
                IconButton(onClick = copy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $label")
                }
            }
        },
    )
}

@Composable
private fun ClientStateBadge(state: RelayClientState, pending: RelayClientState?) {
    val text = pending?.let { "Changing to ${it.stateLabel().lowercase()}…" }
        ?: state.stateLabel()
    Surface(
        color = if (state == RelayClientState.REVOKED) {
            MaterialTheme.agentknockColors.dangerContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (state == RelayClientState.REVOKED) {
            MaterialTheme.agentknockColors.onDangerContainer
        } else {
            stateColor(state)
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(100.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private fun ClientSummary.visibleState(): String? {
    val pending = desiredState?.takeIf { it != state }
    return pending?.let { "Changing…" } ?: state.takeIf { it != RelayClientState.ACTIVE }?.stateLabel()
}

@Composable
private fun stateColor(state: RelayClientState) = when (state) {
    RelayClientState.ACTIVE -> MaterialTheme.agentknockColors.success
    RelayClientState.PENDING -> MaterialTheme.agentknockColors.attentionAccent
    RelayClientState.SUSPENDED -> MaterialTheme.colorScheme.tertiary
    RelayClientState.REVOKED -> MaterialTheme.agentknockColors.danger
}

private fun RelayClientState.stateLabel(): String = when (this) {
    RelayClientState.PENDING -> "Pending"
    RelayClientState.ACTIVE -> "Active"
    RelayClientState.SUSPENDED -> "Suspended"
    RelayClientState.REVOKED -> "Revoked"
}

private fun RelayClientState.explanation(): String = when (this) {
    RelayClientState.PENDING -> "Pairing has not finished yet."
    RelayClientState.ACTIVE -> "This client can connect and make requests."
    RelayClientState.SUSPENDED -> "This client cannot connect until it is resumed."
    RelayClientState.REVOKED -> "This client's pairing has been permanently revoked."
}

private fun RelayClientState.actionLabel(): String = when (this) {
    RelayClientState.PENDING -> "Set pending"
    RelayClientState.ACTIVE -> "Resume"
    RelayClientState.SUSPENDED -> "Suspend"
    RelayClientState.REVOKED -> "Revoke"
}

private fun RelayClientState.successMessage(): String = when (this) {
    RelayClientState.ACTIVE -> "Client resumed"
    RelayClientState.SUSPENDED -> "Client suspended"
    RelayClientState.REVOKED -> "Client revoked"
    RelayClientState.PENDING -> "Client state updated"
}
