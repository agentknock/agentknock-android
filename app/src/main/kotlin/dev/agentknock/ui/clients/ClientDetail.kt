@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.components.AiReviewInstructions
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
    aiReviewAccess: AiReviewAccess,
    onBack: () -> Unit,
    showBack: Boolean,
    onRename: (String) -> Unit,
    onSetState: (RelayClientState) -> Unit,
    onSaveInstructions: (String) -> Unit,
    onEndTemporaryAccess: (TemporaryAccessGrant) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
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
            owner = client.name,
            value = instructions,
            originalValue = client.instructions,
            supportingText =
                "Tell the AI reviewer what this client is used for and how much it should be trusted. " +
                    "Used when AI review is active.",
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
            Modifier.fillMaxSize().verticalScroll(scrollState)
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
                    Spacer(Modifier.weight(1f))
                    when (client.state) {
                        RelayClientState.ACTIVE -> TextButton(
                            onClick = { onSetState(RelayClientState.SUSPENDED) },
                        ) { Text("Suspend client") }
                        RelayClientState.SUSPENDED -> TextButton(
                            onClick = { onSetState(RelayClientState.ACTIVE) },
                        ) { Text("Resume client") }
                        else -> Unit
                    }
                }
                if (client.state != RelayClientState.ACTIVE) {
                    Text(client.state.explanation(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            InformationSurface {
                Text("Client information", style = MaterialTheme.typography.titleMedium)
                ClientField("Hostname", client.hostname)
                ClientField(
                    "Operating system",
                    client.osVersion ?: client.platform?.let(::formatPlatformName),
                )
                ClientField(
                    "Last request",
                    client.lastRequestAt?.let {
                        "${formatTimestamp(it)} (${formatRelativeTime(it)})"
                    } ?: "None yet",
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
                            "These uses are paused until this client is active. They resume if that " +
                                "happens before their end time."
                        } else {
                            "These uses skip manual and AI review until they end. " +
                                "A secret's Deny setting still blocks access."
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

            AiReviewInstructions(
                value = client.instructions,
                access = aiReviewAccess,
                onEdit = {
                    instructions = client.instructions
                    showInstructions = true
                },
            )

            InformationSurface {
                ClientField("Architecture", client.architecture)
                client.clientSoftware?.let { software ->
                    ClientField("Client software", renderSoftware(software.application))
                    if (software.library != software.application) {
                        ClientField("Agentknock library", renderSoftware(software.library))
                    }
                }
                client.pairedAt?.let {
                    ClientField("Paired", "${formatTimestamp(it)} (${formatRelativeTime(it)})")
                }
            }

            Disclosure("Technical identifiers") {
                ClientField("Machine ID", client.machineId, monospace = true)
                ClientField("Client ID", client.clientId, monospace = true)
            }

            if (client.state != RelayClientState.REVOKED) {
                TextButton(onClick = { confirmation = RelayClientState.REVOKED }) {
                    Text("Revoke client…", color = MaterialTheme.agentknockColors.danger)
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
                    "This permanently blocks future requests from this client. It must pair again " +
                        "to reconnect. Values already received cannot be recalled.",
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
