@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.clients

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.device.DeviceIdentity
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestStatus
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingState
import dev.agentknock.ui.components.rememberDateTimeFormatter
import dev.agentknock.ui.components.ActionListSurface
import dev.agentknock.ui.components.TonalIcon

@Composable
internal fun ClientList(
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
    val dates = rememberDateTimeFormatter()
    val context = LocalContext.current
    val duplicateClientNames = clients.groupingBy(ClientSummary::name).eachCount()
        .filterValues { it > 1 }
        .keys
    Column(modifier) {
        TopAppBar(
            title = { Text("Clients", style = MaterialTheme.typography.headlineMedium) },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            },
        )
        if (clients.isEmpty() && pendingPairings.isEmpty()) {
            identity?.let {
                PairingControls(
                    identity = it,
                    onChangePairingAddress = onChangePairingAddress,
                    onSetPairingEnabled = onSetPairingEnabled,
                    report = report,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
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
                identity?.let {
                    item(key = "pairing_controls") {
                        PairingControls(
                            identity = it,
                            onChangePairingAddress = onChangePairingAddress,
                            onSetPairingEnabled = onSetPairingEnabled,
                            report = report,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
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
                }
                item(key = "paired_clients_heading") {
                    SectionHeading(
                        title = "Paired clients",
                        count = clients.size,
                        modifier = Modifier.padding(top = if (pendingPairings.isEmpty()) 0.dp else 10.dp),
                    )
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
                            headlineContent = {
                                Text(client.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val hostname = client.hostname
                                    ?.takeUnless { it.equals(client.name, ignoreCase = true) }
                                val platform = client.platform?.let(::formatPlatformName)
                                val machine = when {
                                    hostname != null && platform != null -> "$hostname · $platform"
                                    hostname != null -> hostname
                                    platform != null -> platform
                                    else -> ""
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val activity = client.lastRequestAt?.let {
                                        "Last request ${dates.relativeTime(it)}"
                                    } ?: client.pairedAt?.let { "Paired ${dates.relativeTime(it)}" }.orEmpty()
                                    val summary = listOf(machine, activity)
                                        .filter(String::isNotBlank)
                                        .joinToString(" · ")
                                    if (summary.isNotEmpty()) {
                                        Text(
                                            summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        color = if (identity.pairingEnabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (identity.pairingEnabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pairing address",
                    style = MaterialTheme.typography.labelLarge,
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
            }
            Text(
                identity.address,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (identity.pairingEnabled) "New pairings on" else "New pairings paused",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = identity.pairingEnabled,
                    onCheckedChange = onSetPairingEnabled,
                    modifier = Modifier.semantics {
                        contentDescription = "Allow new pairings"
                    },
                )
            }
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
    PairingState.EXCHANGE_PENDING -> "Waiting for the secure exchange"
    PairingState.EXCHANGE_FAILED -> "Pairing could not continue; reject to continue"
    PairingState.SAS_VERIFICATION_PENDING -> "Compare the security code"
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
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ClientSummary.visibleState(): String? {
    val pending = desiredState?.takeIf { it != state }
    return pending?.let { "Changing…" } ?: state.takeIf { it != RelayClientState.ACTIVE }?.stateLabel()
}
