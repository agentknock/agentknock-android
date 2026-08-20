@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.clients

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientChangeResult
import dev.agentknock.storage.request.ClientDetails
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.vault.VaultIdentity
import kotlinx.coroutines.launch

@Composable
internal fun ClientsScreen(
    onOpenSettings: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val twoPane = maxWidth >= 840.dp
            LaunchedEffect(selection, twoPane) {
                onTopLevelChanged(twoPane || selection == null)
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    ClientList(
                        clients = clients,
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
                            authenticate = authenticate,
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
                    identity = configuration?.active,
                    onOpen = viewModel::selectClient,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                BackHandler { viewModel.selectClient(null) }
                ClientSelectionDetail(
                    client = selectedClient,
                    authenticate = authenticate,
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
    authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
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
            authenticate(
                "Rename client",
                {
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
                report,
            )
        },
        onSetState = { state ->
            authenticate(
                "${state.actionLabel()} client",
                {
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
                report,
            )
        },
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
    identity: VaultIdentity?,
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
            LazyColumn(Modifier.fillMaxSize()) {
                items(clients, key = ClientSummary::clientId) { client ->
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
                        leadingContent = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                        trailingContent = {
                            val state = client.visibleState()
                            if (state != null) Text(state, color = stateColor(client.state))
                        },
                        modifier = Modifier.clickable { onOpen(client.clientId) },
                    )
                    HorizontalDivider()
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
    modifier: Modifier = Modifier,
) {
    var showRename by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<RelayClientState?>(null) }
    var reportedExpanded by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val pending = client.desiredState?.takeIf { it != client.state }
            if (pending != null) {
                Text(
                    "Changing to ${pending.stateLabel().lowercase()}…",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
            } else if (client.state != RelayClientState.ACTIVE) {
                Text(
                    client.state.stateLabel(),
                    color = stateColor(client.state),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            client.pairedAt?.let {
                Text(
                    "Paired ${formatTimestamp(it)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (client.state) {
                RelayClientState.ACTIVE -> OutlinedButton(
                    onClick = { confirmation = RelayClientState.SUSPENDED },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.PauseCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Suspend client")
                }
                RelayClientState.SUSPENDED -> Button(
                    onClick = { confirmation = RelayClientState.ACTIVE },
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
            if (client.state != RelayClientState.REVOKED) {
                TextButton(
                    onClick = { confirmation = RelayClientState.REVOKED },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Icon(
                        Icons.Outlined.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Revoke client", color = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { reportedExpanded = !reportedExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Reported information", style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (reportedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            if (reportedExpanded) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ClientField("Hostname", client.hostname)
                        ClientField("Platform", client.platform)
                        ClientField("Architecture", client.architecture)
                        ClientField("Operating system", client.osVersion)
                        ClientField("CLI version", client.cliVersion)
                        ClientField("Machine ID", client.machineId, monospace = true)
                        ClientField("Client ID", client.clientId, monospace = true)
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
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
        val destructive = target == RelayClientState.REVOKED
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("${target.actionLabel()} ${client.name}?") },
            text = {
                Text(
                    when (target) {
                        RelayClientState.ACTIVE -> "This client can make requests again."
                        RelayClientState.SUSPENDED -> "The client keeps its identity but cannot connect until resumed."
                        RelayClientState.REVOKED -> "This is permanent. The client must pair again."
                        RelayClientState.PENDING -> ""
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetState(target)
                    confirmation = null
                }) {
                    Text(target.actionLabel(), color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ClientField(label: String, value: String?, monospace: Boolean = false) {
    if (value.isNullOrBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (monospace) FontFamily.Monospace else null)
    }
}

private fun ClientSummary.visibleState(): String? {
    val pending = desiredState?.takeIf { it != state }
    return pending?.let { "Changing…" } ?: state.takeIf { it != RelayClientState.ACTIVE }?.stateLabel()
}

@Composable
private fun stateColor(state: RelayClientState) = when (state) {
    RelayClientState.ACTIVE -> MaterialTheme.colorScheme.primary
    RelayClientState.PENDING -> MaterialTheme.colorScheme.tertiary
    RelayClientState.SUSPENDED -> MaterialTheme.colorScheme.tertiary
    RelayClientState.REVOKED -> MaterialTheme.colorScheme.error
}

private fun RelayClientState.stateLabel(): String = when (this) {
    RelayClientState.PENDING -> "Pending"
    RelayClientState.ACTIVE -> "Active"
    RelayClientState.SUSPENDED -> "Suspended"
    RelayClientState.REVOKED -> "Revoked"
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
