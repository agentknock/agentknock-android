@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.clients

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.formatRelativeTime
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientDetails
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.NavigationBackButton
import dev.agentknock.ui.components.Disclosure
import dev.agentknock.ui.components.ProseEditorScreen
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun ClientDetail(
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
    var showRename by rememberSaveable(client.clientId) { mutableStateOf(false) }
    var rename by rememberSaveable(client.clientId, client.name) { mutableStateOf(client.name) }
    var showInstructions by rememberSaveable(client.clientId) { mutableStateOf(false) }
    var instructions by rememberSaveable(client.clientId, client.instructions) {
        mutableStateOf(client.instructions)
    }
    var confirmation by rememberSaveable(client.clientId) {
        mutableStateOf<RelayClientState?>(null)
    }
    if (showInstructions) {
        ProseEditorScreen(
            title = "Client instructions",
            value = instructions,
            originalValue = client.instructions,
            supportingText =
                "Tell the AI reviewer what this client is used for and how much it should be trusted.",
            onValueChange = { instructions = it },
            onSave = {
                showInstructions = false
                onSaveInstructions(instructions.trim())
            },
            onBack = { showInstructions = false },
        )
        return
    }
    Column(modifier) {
        TopAppBar(
            title = { Text(client.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (showBack) {
                    NavigationBackButton(onBack)
                }
            },
            actions = {
                IconButton(onClick = {
                    rename = client.name
                    showRename = true
                }) {
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
                if (client.state != RelayClientState.REVOKED) {
                    HorizontalDivider()
                    Text(
                        "Revoking is permanent. This client must be paired again to reconnect.",
                        style = MaterialTheme.typography.bodySmall,
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
                client.pairedAt?.let {
                    ClientField("Paired", "${formatTimestamp(it)} (${formatRelativeTime(it)})")
                }
                ClientField(
                    "Last request",
                    client.lastRequestAt?.let {
                        "${formatTimestamp(it)} (${formatRelativeTime(it)})"
                    } ?: "None yet",
                )
            }

            Disclosure("Technical information") {
                ClientField("Machine ID", client.machineId, monospace = true)
                ClientField("Client ID", client.clientId, monospace = true)
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

        }
    }

    if (showRename) {
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
                        value = rename,
                        onValueChange = { rename = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = rename.isNotBlank() && rename.trim() != client.name,
                    onClick = {
                        onRename(rename)
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

@Composable
internal fun stateColor(state: RelayClientState) = when (state) {
    RelayClientState.ACTIVE -> MaterialTheme.agentknockColors.success
    RelayClientState.PENDING -> MaterialTheme.agentknockColors.attentionAccent
    RelayClientState.SUSPENDED -> MaterialTheme.colorScheme.tertiary
    RelayClientState.REVOKED -> MaterialTheme.agentknockColors.danger
}

internal fun RelayClientState.stateLabel(): String = when (this) {
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

internal fun RelayClientState.successMessage(): String = when (this) {
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
