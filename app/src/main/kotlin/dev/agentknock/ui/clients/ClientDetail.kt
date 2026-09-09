@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.clients

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientDetails
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.components.AiInstructionsScope
import dev.agentknock.ui.components.Disclosure
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InstructionsCard
import dev.agentknock.ui.components.NavigationBackButton
import dev.agentknock.ui.components.ProseEditorScreen
import dev.agentknock.ui.components.rememberDateTimeFormatter
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
    informationInitiallyExpanded: Boolean = false,
) {
    val dates = rememberDateTimeFormatter()
    var showRename by rememberSaveable(client.clientId) { mutableStateOf(false) }
    var rename by rememberSaveable(client.clientId, client.name) { mutableStateOf(client.name) }
    var showInstructions by rememberSaveable(client.clientId) { mutableStateOf(false) }
    var instructions by
        rememberSaveable(client.clientId, client.instructions) {
            mutableStateOf(client.instructions)
        }
    var confirmation by
        rememberSaveable(client.clientId) {
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
                IconButton(
                    onClick = {
                        rename = client.name
                        showRename = true
                    }
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename client")
                }
            },
        )
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val pending = client.desiredState?.takeIf { it != client.state }
            val temporaryAccessPaused =
                client.state != RelayClientState.ACTIVE ||
                    client.desiredState?.let { it != RelayClientState.ACTIVE } == true
            ClientStatus(client, pending, onSetState)

            InstructionsCard(
                scope = AiInstructionsScope.CLIENT,
                value = client.instructions,
                access = aiReviewAccess,
                onEdit = {
                    instructions = client.instructions
                    showInstructions = true
                },
            )

            if (temporaryAccessGrants.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Temporary access",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
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
                                        Text(
                                            grant.secretName,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            grant.operation.displayName(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            "Ends ${dates.timestamp(grant.expiresAt)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    FilledTonalButton(onClick = { onEndTemporaryAccess(grant) }) {
                                        Text("End")
                                    }
                                }
                            }
                            Text(
                                if (temporaryAccessPaused) {
                                    "Paused until this client is active. Access resumes if it becomes active " +
                                        "before the end time. A secret's Deny setting still blocks access."
                                } else {
                                    "Skips manual and AI review until access ends. " +
                                        "A secret's Deny setting still blocks access."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Disclosure("Client information", initiallyExpanded = informationInitiallyExpanded) {
                ClientField("Hostname", client.hostname)
                ClientField(
                    "Operating system",
                    client.osVersion ?: client.platform?.let(::formatPlatformName),
                )
                ClientField(
                    "Last request",
                    client.lastRequestAt?.let(dates::timestamp) ?: "None yet",
                )
                ClientField("Architecture", client.architecture)
                client.clientSoftware?.let { software ->
                    ClientField("Client software", renderSoftware(software.application))
                    if (software.library != software.application) {
                        ClientField("Agentknock library", renderSoftware(software.library))
                    }
                }
                client.pairedAt?.let {
                    ClientField("Paired", dates.timestamp(it))
                }
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
                ) {
                    Text("Save")
                }
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
                        "to reconnect. Values already received cannot be recalled."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetState(target)
                        confirmation = null
                    }
                ) {
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
private fun ClientStatus(
    client: ClientDetails,
    pending: RelayClientState?,
    onSetState: (RelayClientState) -> Unit,
) {
    val semanticColors = MaterialTheme.agentknockColors
    Surface(
        color =
            when {
                client.state == RelayClientState.REVOKED -> semanticColors.dangerContainer
                client.state == RelayClientState.ACTIVE && pending == null ->
                    semanticColors.successContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        contentColor =
            when {
                client.state == RelayClientState.REVOKED -> semanticColors.onDangerContainer
                client.state == RelayClientState.ACTIVE && pending == null ->
                    semanticColors.onSuccessContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (client.state == RelayClientState.SUSPENDED && pending == null) {
                    Icon(
                        Icons.Outlined.PauseCircleOutline,
                        contentDescription = null,
                        tint = stateColor(client.state),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Text(
                    pending?.let { "Changing to ${it.stateLabel().lowercase()}…" }
                        ?: client.state.stateLabel(),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                val actionColors =
                    ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
                when (client.state) {
                    RelayClientState.ACTIVE ->
                        TextButton(
                            onClick = { onSetState(RelayClientState.SUSPENDED) },
                            colors = actionColors,
                        ) {
                            Text("Suspend")
                        }
                    RelayClientState.SUSPENDED ->
                        FilledTonalButton(onClick = { onSetState(RelayClientState.ACTIVE) }) {
                            Text("Resume")
                        }
                    else -> Unit
                }
            }
            val machine =
                listOfNotNull(
                        client.hostname,
                        client.osVersion ?: client.platform?.let(::formatPlatformName),
                    )
                    .joinToString(" · ")
            if (machine.isNotBlank()) {
                Text(machine, style = MaterialTheme.typography.bodyMedium)
            }
            if (client.state != RelayClientState.ACTIVE) {
                Text(client.state.explanation(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun stateColor(state: RelayClientState) =
    when (state) {
        RelayClientState.ACTIVE -> MaterialTheme.agentknockColors.success
        RelayClientState.PENDING -> MaterialTheme.agentknockColors.attentionAccent
        RelayClientState.SUSPENDED -> MaterialTheme.colorScheme.tertiary
        RelayClientState.REVOKED -> MaterialTheme.agentknockColors.danger
    }

internal fun RelayClientState.stateLabel(): String =
    when (this) {
        RelayClientState.PENDING -> "Pending"
        RelayClientState.ACTIVE -> "Active"
        RelayClientState.SUSPENDED -> "Suspended"
        RelayClientState.REVOKED -> "Revoked"
    }

private fun RelayClientState.explanation(): String =
    when (this) {
        RelayClientState.PENDING -> "Pairing has not finished yet."
        RelayClientState.ACTIVE -> "This client can connect and make requests."
        RelayClientState.SUSPENDED -> "This client cannot connect until it is resumed."
        RelayClientState.REVOKED -> "This client's pairing has been permanently revoked."
    }

private fun RelayClientState.actionLabel(): String =
    when (this) {
        RelayClientState.PENDING -> "Set pending"
        RelayClientState.ACTIVE -> "Resume"
        RelayClientState.SUSPENDED -> "Suspend"
        RelayClientState.REVOKED -> "Revoke"
    }

internal fun RelayClientState.successMessage(): String =
    when (this) {
        RelayClientState.ACTIVE -> "Client resumed"
        RelayClientState.SUSPENDED -> "Client suspended"
        RelayClientState.REVOKED -> "Client revoked"
        RelayClientState.PENDING -> "Client state updated"
    }

private fun TemporaryAccessOperation.displayName(): String =
    when (this) {
        TemporaryAccessOperation.INVOCATION -> "Secret values for any command"
        TemporaryAccessOperation.GIT_SIGN -> "Git signing for any repository"
        TemporaryAccessOperation.SSH_AUTHENTICATE -> "SSH authentication for any server"
    }
