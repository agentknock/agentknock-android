@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.clients

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.formatRelativeTime
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientChangeResult
import dev.agentknock.storage.request.ClientDetails
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestStatus
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.device.DeviceIdentity
import dev.agentknock.storage.device.DeviceManagementResult
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.AdaptiveListDetail
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.TonalIcon
import dev.agentknock.ui.components.ActionListSurface
import dev.agentknock.ui.components.NavigationBackButton
import dev.agentknock.ui.requests.PairingRequestDetail
import dev.agentknock.ui.requests.message
import dev.agentknock.ui.theme.agentknockColors
import kotlinx.coroutines.launch

@Composable
internal fun ClientsScreen(
    authorizeProtectedAction: (String, () -> Unit, (String) -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    onChangePairingAddress: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    viewModel: ClientsViewModel,
) {
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val pendingPairings by viewModel.pendingPairings.collectAsStateWithLifecycle()
    val pane by viewModel.pane.collectAsStateWithLifecycle()
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun chooseSas(request: InboxRequestDetails, choice: Int?) {
        val pairing = (request.content as? InboxRequestContent.Pairing)?.details ?: return
        if (choice == null) {
            scope.launch { report(viewModel.chooseSas(request.id, null).message()) }
            return
        }
        scope.launch {
            if (viewModel.isMatchingPendingSas(request.id, choice)) {
                authorizeProtectedAction(
                    "Accept ${pairing.clientName}",
                    {
                        scope.launch {
                            report(viewModel.chooseSas(request.id, choice).message())
                        }
                    },
                    ::report,
                )
            } else {
                report(viewModel.chooseSas(request.id, choice).message())
            }
        }
    }

    fun rejectPairing(requestId: String) {
        scope.launch {
            report(viewModel.rejectPairing(requestId).message())
            viewModel.clearSelection(ClientSelection.Pairing(requestId))
        }
    }

    fun renameClient(clientId: String, name: String) {
        scope.launch {
            val result = viewModel.rename(clientId, name.trim())
            report(
                if (result == ClientChangeResult.CHANGED) {
                    "Client renamed"
                } else {
                    "Client is no longer available"
                },
            )
        }
    }

    fun setClientState(clientId: String, state: RelayClientState) {
        scope.launch {
            val result = viewModel.setState(clientId, state)
            if (result == ClientChangeResult.CHANGED && state == RelayClientState.REVOKED) {
                viewModel.clearSelection(ClientSelection.Client(clientId))
            }
            report(
                if (result == ClientChangeResult.CHANGED) {
                    state.successMessage()
                } else {
                    "Client state could not be changed"
                },
            )
        }
    }

    fun saveClientInstructions(clientId: String, instructions: String) {
        scope.launch {
            val result = viewModel.saveInstructions(clientId, instructions)
            report(
                if (result == ClientChangeResult.CHANGED) {
                    "Instructions updated"
                } else {
                    "Instructions could not be updated"
                },
            )
        }
    }

    fun endTemporaryAccess(clientId: String, grant: TemporaryAccessGrant) {
        scope.launch {
            val ended = viewModel.endTemporaryAccess(
                grant.secretId,
                clientId,
                grant.operation,
            )
            report(
                if (ended) {
                    "Temporary access ended"
                } else {
                    "Temporary access had already ended"
                },
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        AdaptiveListDetail(
            hasDetail = pane.selection != ClientSelection.None,
            listWidth = 320.dp,
            onBack = viewModel::clearSelection,
            onTopLevelChanged = onTopLevelChanged,
            modifier = Modifier.fillMaxSize().padding(padding),
            list = { listModifier ->
                ClientList(
                    clients = clients,
                    pendingPairings = pendingPairings,
                    selectedClientId = (pane.selection as? ClientSelection.Client)?.clientId,
                    selectedPairingRequestId =
                        (pane.selection as? ClientSelection.Pairing)?.requestId,
                    identity = configuration?.active,
                    onOpen = viewModel::selectClient,
                    onOpenPairing = viewModel::selectPairing,
                    onChangePairingAddress = onChangePairingAddress,
                    onSetPairingEnabled = { enabled ->
                        scope.launch { report(viewModel.setPairingEnabled(enabled).message(enabled)) }
                    },
                    onOpenSettings = onOpenSettings,
                    report = ::report,
                    modifier = listModifier,
                )
            },
            emptyDetail = { detailModifier -> EmptyClientSelection(detailModifier) },
            detail = { showBack, detailModifier ->
                ClientSelectionPane(
                    pane = pane,
                    onChooseSas = ::chooseSas,
                    onRejectPairing = ::rejectPairing,
                    onRenameClient = ::renameClient,
                    onSetClientState = ::setClientState,
                    onSaveClientInstructions = ::saveClientInstructions,
                    onEndTemporaryAccess = ::endTemporaryAccess,
                    onBack = viewModel::clearSelection,
                    showBack = showBack,
                    modifier = detailModifier,
                )
            },
        )
    }
}

@Composable
private fun ClientSelectionPane(
    pane: ClientPaneState,
    onChooseSas: (InboxRequestDetails, Int?) -> Unit,
    onRejectPairing: (String) -> Unit,
    onRenameClient: (String, String) -> Unit,
    onSetClientState: (String, RelayClientState) -> Unit,
    onSaveClientInstructions: (String, String) -> Unit,
    onEndTemporaryAccess: (String, TemporaryAccessGrant) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    when (pane) {
        ClientPaneState.Empty -> EmptyClientSelection(modifier)
        is ClientPaneState.Loading,
        is ClientPaneState.Missing,
        -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ClientPaneState.Client -> key(pane.details.clientId) {
            ClientSelectionDetail(
                client = pane.details,
                temporaryAccessGrants = pane.temporaryAccess,
                onRenameClient = onRenameClient,
                onSetClientState = onSetClientState,
                onSaveClientInstructions = onSaveClientInstructions,
                onEndTemporaryAccess = onEndTemporaryAccess,
                onBack = onBack,
                showBack = showBack,
                modifier = modifier,
            )
        }
        is ClientPaneState.Pairing -> key(pane.request.id) {
            PairingSelectionDetail(
                request = pane.request,
                onChooseSas = onChooseSas,
                onRejectPairing = onRejectPairing,
                onBack = onBack,
                showBack = showBack,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun PairingSelectionDetail(
    request: InboxRequestDetails,
    onChooseSas: (InboxRequestDetails, Int?) -> Unit,
    onRejectPairing: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    PairingRequestDetail(
        request = request,
        onBack = onBack,
        showBack = showBack,
        onChooseSas = { choice -> onChooseSas(request, choice) },
        onReject = { onRejectPairing(request.id) },
        modifier = modifier,
    )
}

@Composable
private fun ClientSelectionDetail(
    client: ClientDetails,
    temporaryAccessGrants: List<TemporaryAccessGrant>,
    onRenameClient: (String, String) -> Unit,
    onSetClientState: (String, RelayClientState) -> Unit,
    onSaveClientInstructions: (String, String) -> Unit,
    onEndTemporaryAccess: (String, TemporaryAccessGrant) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    ClientDetail(
        client = client,
        temporaryAccessGrants = temporaryAccessGrants,
        onBack = onBack,
        showBack = showBack,
        onRename = { name -> onRenameClient(client.clientId, name) },
        onSetState = { state -> onSetClientState(client.clientId, state) },
        onSaveInstructions = { instructions ->
            onSaveClientInstructions(client.clientId, instructions)
        },
        onEndTemporaryAccess = { grant -> onEndTemporaryAccess(client.clientId, grant) },
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
    pendingPairings: List<InboxRequestSummary>,
    selectedClientId: String?,
    selectedPairingRequestId: String?,
    identity: DeviceIdentity?,
    onOpen: (String) -> Unit,
    onOpenPairing: (String) -> Unit,
    onChangePairingAddress: () -> Unit,
    onSetPairingEnabled: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    report: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val duplicateClientNames = clients.groupingBy(ClientSummary::name).eachCount()
        .filterValues { it > 1 }
        .keys
    Column(modifier) {
        TopAppBar(
            title = { Text("Clients") },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            },
        )
        identity?.let {
            PairingControls(
                identity = it,
                onChangePairingAddress = onChangePairingAddress,
                onSetPairingEnabled = onSetPairingEnabled,
                report = report,
            )
        }
        if (clients.isEmpty() && pendingPairings.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("No paired clients", style = MaterialTheme.typography.titleLarge)
                    identity?.let {
                        Text(
                            if (it.pairingEnabled) {
                                "Run this command on the machine you want to pair."
                            } else {
                                "New pairings are paused. Resume them above to pair a client."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        if (it.pairingEnabled) {
                            val pairingCommand = "agentknock pairing start ${it.address}"
                            Surface(
                                onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(
                                            ClipData.newPlainText(
                                                "Agentknock pairing command",
                                                pairingCommand,
                                            ),
                                        )
                                    report("Pairing command copied")
                                },
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        start = 14.dp,
                                        end = 4.dp,
                                        top = 10.dp,
                                        bottom = 10.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        pairingCommand,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = {
                                        context.getSystemService(ClipboardManager::class.java)
                                            .setPrimaryClip(
                                                ClipData.newPlainText(
                                                    "Agentknock pairing command",
                                                    pairingCommand,
                                                ),
                                            )
                                        report("Pairing command copied")
                                    }) {
                                        Icon(
                                            Icons.Outlined.ContentCopy,
                                            contentDescription = "Copy pairing command",
                                        )
                                    }
                                }
                            }
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
                if (pendingPairings.isNotEmpty()) {
                    item(key = "pairing_requests_heading") {
                        SectionHeading(
                            title = "Pairing requests",
                            count = pendingPairings.size,
                        )
                    }
                    itemsIndexed(
                        pendingPairings,
                        key = { _, request -> "pairing_${request.id}" },
                    ) { _, request ->
                        PendingPairingRow(
                            request = request,
                            selected = request.id == selectedPairingRequestId,
                            onClick = { onOpenPairing(request.id) },
                        )
                    }
                    item(key = "paired_clients_heading") {
                        SectionHeading(
                            title = "Paired clients",
                            count = clients.size,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                if (clients.isEmpty()) {
                    item(key = "no_paired_clients") {
                        Text(
                            "No clients have finished pairing yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
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
                                val platform = listOfNotNull(
                                    client.platform?.let(::formatPlatformName),
                                    client.architecture,
                                ).joinToString(" · ").ifBlank { null }
                                val machine = when {
                                    hostname != null && platform != null -> "$platform on $hostname"
                                    hostname != null -> hostname
                                    platform != null -> platform
                                    else -> ""
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (machine.isNotEmpty()) Text(machine)
                                    client.pairedAt?.let {
                                        Text(
                                            "Paired ${formatRelativeTime(it)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    client.lastRequestAt?.let {
                                        Text(
                                            "Last request ${formatRelativeTime(it)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (client.name in duplicateClientNames) {
                                        Text(
                                            "Client ID …${client.clientId.takeLast(6)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                    if (client.temporaryAccessCount > 0) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Outlined.Schedule,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                "Temporary access to " +
                                                    "${client.temporaryAccessCount} " +
                                                    if (client.temporaryAccessCount == 1) {
                                                        "secret"
                                                    } else {
                                                        "secrets"
                                                    },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
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
private fun PairingControls(
    identity: DeviceIdentity,
    onChangePairingAddress: () -> Unit,
    onSetPairingEnabled: (Boolean) -> Unit,
    report: (String) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (identity.pairingEnabled) "Pairing address" else "Pairing address · paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("Agentknock pairing address", identity.address),
                    )
                    report("Pairing address copied")
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy pairing address")
                }
                IconButton(onClick = onChangePairingAddress) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Change pairing address")
                }
                IconButton(onClick = { onSetPairingEnabled(!identity.pairingEnabled) }) {
                    Icon(
                        if (identity.pairingEnabled) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
                        contentDescription = if (identity.pairingEnabled) "Pause new pairings" else "Resume new pairings",
                    )
                }
            }
            Text(
                identity.address,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

@Composable
private fun PendingPairingRow(
    request: InboxRequestSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val pairingState = (request.status as InboxRequestStatus.Pairing).state
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    ActionListSurface(
        actionRequired = actionRequired,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
    ) {
        ListItem(
            headlineContent = {
                Text(request.clientName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    pairingState.pairingListDescription(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { TonalIcon(Icons.Outlined.Computer, contentDescription = null) },
            trailingContent = {
                Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = null)
            },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}

private fun PairingState.pairingListDescription(): String = when (this) {
    PairingState.RECEIVING -> "Waiting for the secure exchange"
    PairingState.EXCHANGE_FAILED -> "Secure exchange failed; reject to continue"
    PairingState.SAS_VERIFICATION_PENDING -> "Compare the security code"
    PairingState.RELAY_ACTIVATION_PENDING -> "Applying the pairing"
    PairingState.WAITING_FOR_FINISH -> "Code verified; waiting for the client"
    PairingState.REJECTED -> "Pairing rejected"
    PairingState.COMPLETED -> "Pairing complete"
}

@Composable
private fun SectionHeading(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClientDetail(
    client: ClientDetails,
    temporaryAccessGrants: List<TemporaryAccessGrant>,
    onBack: () -> Unit,
    showBack: Boolean,
    onRename: (String) -> Unit,
    onSetState: (RelayClientState) -> Unit,
    onSaveInstructions: (String) -> Unit,
    onEndTemporaryAccess: (TemporaryAccessGrant) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRename by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    var instructions by remember(client.clientId, client.instructions) {
        mutableStateOf(client.instructions)
    }
    var confirmation by remember { mutableStateOf<RelayClientState?>(null) }
    Column(modifier) {
        TopAppBar(
            title = { Text(client.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (showBack) {
                    NavigationBackButton(onBack)
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
            val temporaryAccessPaused = client.state != RelayClientState.ACTIVE ||
                client.desiredState?.let { it != RelayClientState.ACTIVE } == true
            InformationSurface {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ClientStateBadge(client.state, pending)
                    Text(client.state.explanation(), modifier = Modifier.weight(1f))
                }
            }

            InformationSurface {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("AI review instructions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            client.instructions.ifBlank { "No instructions for this client." },
                            color = if (client.instructions.isBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    IconButton(onClick = {
                        instructions = client.instructions
                        showInstructions = true
                    }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit instructions")
                    }
                }
            }

            InformationSurface {
                Text("Client information", style = MaterialTheme.typography.titleMedium)
                ClientField("Hostname", client.hostname)
                ClientField(
                    "Platform",
                    listOfNotNull(client.platform?.let(::formatPlatformName), client.architecture)
                        .joinToString(" · ").ifBlank { null },
                )
                ClientField("Operating system", client.osVersion)
                client.clientSoftware?.let { software ->
                    ClientField("Last seen client software", renderSoftware(software.application))
                    if (software.library != software.application) {
                        ClientField("Agentknock library", renderSoftware(software.library))
                    }
                }
                client.pairedAt?.let { ClientField("Paired", formatTimestamp(it)) }
                ClientField(
                    "Last request",
                    client.lastRequestAt?.let(::formatTimestamp) ?: "None yet",
                )
            }

            if (temporaryAccessGrants.isNotEmpty()) {
                InformationSurface {
                    Text(
                        "Temporary access",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (temporaryAccessPaused) {
                            "They are paused until this client is active. They resume if that " +
                                "happens before their end time."
                        } else {
                            "These uses are already approved and will not ask you or AI again " +
                                "before they end."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    temporaryAccessGrants.forEachIndexed { index, grant ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(grant.secretName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${grant.operation.displayName()} · Ends " +
                                        formatTimestamp(grant.expiresAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onEndTemporaryAccess(grant) }) {
                                Text("End")
                            }
                        }
                    }
                }
            }

            if (client.state == RelayClientState.ACTIVE || client.state == RelayClientState.SUSPENDED) {
                InformationSurface {
                    Text("Access", style = MaterialTheme.typography.titleMedium)
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
            }

            InformationSurface {
                Text("Identifiers", style = MaterialTheme.typography.titleMedium)
                ClientField(
                    "Machine ID",
                    client.machineId,
                    monospace = true,
                )
                ClientField(
                    "Client ID",
                    client.clientId,
                    monospace = true,
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
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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

    if (showInstructions) {
        AlertDialog(
            onDismissRequest = { showInstructions = false },
            title = { Text("Client instructions") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Tell the AI reviewer what this client is used for and how much it should be trusted.",
                    )
                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = { Text("Instructions") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = instructions.trim() != client.instructions,
                    onClick = {
                        showInstructions = false
                        onSaveInstructions(instructions.trim())
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showInstructions = false }) { Text("Cancel") }
            },
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
) {
    if (value.isNullOrBlank()) return
    InformationRow(
        label = label,
        value = value,
        monospace = monospace,
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

private fun TemporaryAccessOperation.displayName(): String = when (this) {
    TemporaryAccessOperation.INVOCATION -> "Secret values for any command"
    TemporaryAccessOperation.GIT_SIGN -> "Git signing for any repository"
    TemporaryAccessOperation.SSH_AUTHENTICATE -> "SSH authentication for any server"
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
