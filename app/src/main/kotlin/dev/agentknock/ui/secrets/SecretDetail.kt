@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.components.rememberDateTimeFormatter
import dev.agentknock.ui.components.InstructionsCard
import dev.agentknock.ui.components.AiInstructionsScope
import dev.agentknock.R
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.storage.secret.SshKeyMetadata
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.NavigationBackButton
import dev.agentknock.ui.components.ProseEditorScreen
import dev.agentknock.ui.components.ExactText
import dev.agentknock.ui.components.TonalIcon

internal data class SecretDetailActions(
    val onBack: () -> Unit,
    val onEditSecret: () -> Unit,
    val onDeleteSecret: () -> Unit,
    val onAddVariable: () -> Unit,
    val onReplaceSshKey: () -> Unit,
    val onSaveSshComment: (String) -> Unit,
    val onCopyPublicKey: () -> Unit,
    val onEditVariable: (EnvironmentVariableMetadata) -> Unit,
    val onReveal: (EnvironmentVariableMetadata) -> Unit,
    val onReadValue: suspend (EnvironmentVariableMetadata) -> String?,
    val onCopy: (EnvironmentVariableMetadata) -> Unit,
    val onSetApprovalMode: (SecretApprovalMode) -> Unit,
    val onSetClientApprovalOverride: (String, SecretApprovalMode?) -> Unit,
    val onSaveInstructions: (String) -> Unit,
    val onEndTemporaryAccess: (TemporaryAccessGrant) -> Unit,
)

@Composable
internal fun SecretDetail(
    secret: SecretDetails,
    clients: List<ClientSummary>,
    revealedValues: Map<String, String>,
    showBack: Boolean,
    actions: SecretDetailActions,
    aiReviewAccess: AiReviewAccess,
    onOpenPlan: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val dates = rememberDateTimeFormatter()
    var menuExpanded by remember(secret.id) { mutableStateOf(false) }
    var editingSshComment by rememberSaveable(secret.id) { mutableStateOf(false) }
    var sshComment by rememberSaveable(secret.id, secret.sshKey?.comment) {
        mutableStateOf(secret.sshKey?.comment.orEmpty())
    }
    var editingInstructions by rememberSaveable(secret.id) { mutableStateOf(false) }
    var instructions by rememberSaveable(secret.id, secret.instructions) {
        mutableStateOf(secret.instructions)
    }
    val overrides = secret.clientApprovalOverrides.associateBy { it.clientId }
    val duplicateClientNames = clients.groupingBy(ClientSummary::name).eachCount()
        .filterValues { it > 1 }
        .keys
    if (editingInstructions) {
        ProseEditorScreen(
            title = "Secret instructions",
            owner = secret.name,
            value = instructions,
            originalValue = secret.instructions,
            supportingText =
                "Tell the AI reviewer when this secret may and may not be used. " +
                    "Do not include secret values. Used when AI review is active.",
            onValueChange = { instructions = it },
            onSave = {
                editingInstructions = false
                actions.onSaveInstructions(instructions.trim())
            },
            onBack = { editingInstructions = false },
        )
        return
    }
    val fontScale = LocalDensity.current.fontScale
    Column(modifier) {
        TopAppBar(
            title = { Text(secret.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (showBack) {
                    NavigationBackButton(actions.onBack)
                }
            },
            actions = {
                TextButton(onClick = actions.onEditSecret) {
                    Text("Edit secret")
                }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.delete_secret),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            actions.onDeleteSecret()
                        },
                    )
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    secret.description.ifBlank { "No description" },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (secret.type) {
                SecretType.ENVIRONMENT -> {
                    item {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            itemVerticalAlignment = Alignment.CenterVertically,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = if (fontScale >= 1.5f) 1 else Int.MAX_VALUE,
                        ) {
                            Text(
                                "Environment variables",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(
                                onClick = actions.onAddVariable,
                                modifier = Modifier.semantics {
                                    contentDescription = "Add environment variable"
                                },
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add")
                            }
                        }
                    }
                    if (secret.environmentVariables.isEmpty()) {
                        item {
                            EmptyMessage(
                                title = stringResource(R.string.no_variables),
                                description = stringResource(R.string.no_variables_description),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            )
                        }
                    } else {
                        item {
                            InformationSurface(contentPadding = PaddingValues(0.dp)) {
                                Column {
                                    secret.environmentVariables.forEachIndexed { index, variable ->
                                        EnvironmentVariableCard(
                                            variable = variable,
                                            revealedValue = revealedValues[variable.id],
                                            onReveal = { actions.onReveal(variable) },
                                            onReadValue = { actions.onReadValue(variable) },
                                            onCopy = { actions.onCopy(variable) },
                                            onEdit = { actions.onEditVariable(variable) },
                                            embedded = true,
                                        )
                                        if (index != secret.environmentVariables.lastIndex) {
                                            HorizontalDivider(Modifier.padding(start = 16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                SecretType.SSH -> {
                    secret.sshKey?.let { key ->
                        item {
                            Text("SSH key", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        item {
                            SshPublicKeyCard(
                                key = key,
                                onCopy = actions.onCopyPublicKey,
                                onEditComment = {
                                    sshComment = key.comment
                                    editingSshComment = true
                                },
                                onReplace = actions.onReplaceSshKey,
                            )
                        }
                    } ?: item {
                        EmptyMessage(
                            title = "SSH key unavailable",
                            description = "The encrypted private key could not be recovered on this device.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
            }
            item {
                InstructionsCard(
                    scope = AiInstructionsScope.SECRET,
                    value = secret.instructions,
                    access = aiReviewAccess,
                    onEdit = {
                        instructions = secret.instructions
                        editingInstructions = true
                    },
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Client access", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        ApprovalModeHelp()
                    }
                    Text(
                        if (secret.type == SecretType.SSH) {
                            "Approval for signing and authentication. Public keys need no approval."
                        } else {
                            "Approval for sensitive values. Non-sensitive values need no approval."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                InformationSurface(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    Column {
                        if (secret.temporaryAccessGrants.isNotEmpty()) {
                            SecretTemporaryApprovals(
                                grants = secret.temporaryAccessGrants,
                                clients = clients,
                                onEnd = actions.onEndTemporaryAccess,
                            )
                            HorizontalDivider()
                        }
                        ApprovalModeRow(
                            title = "Default for all clients",
                            selected = secret.approvalMode,
                            defaultMode = secret.approvalMode,
                            aiReviewAccess = aiReviewAccess,
                            onOpenPlan = onOpenPlan,
                            inherited = false,
                            onSelect = actions.onSetApprovalMode,
                        )
                        clients.forEach { client ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            val override = overrides[client.clientId]
                            ApprovalModeRow(
                                title = client.approvalLabel(duplicateClientNames),
                                isClient = true,
                                selected = override?.mode ?: secret.approvalMode,
                                defaultMode = secret.approvalMode,
                                aiReviewAccess = aiReviewAccess,
                                onOpenPlan = onOpenPlan,
                                inherited = override == null,
                                onSelect = { mode ->
                                    actions.onSetClientApprovalOverride(client.clientId, mode)
                                },
                                onUseDefault = override?.let {
                                    { actions.onSetClientApprovalOverride(client.clientId, null) }
                                },
                            )
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Created ${dates.timestamp(secret.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Updated ${dates.timestamp(secret.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (editingSshComment) {
        AlertDialog(
            onDismissRequest = { editingSshComment = false },
            title = { Text("Edit public key comment") },
            text = {
                OutlinedTextField(
                    value = sshComment,
                    onValueChange = { sshComment = it },
                    label = { Text("Comment (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { editingSshComment = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    enabled = sshComment != secret.sshKey?.comment.orEmpty(),
                    onClick = {
                        editingSshComment = false
                        actions.onSaveSshComment(sshComment)
                    },
                ) { Text("Save") }
            },
        )
    }
}

@Composable
private fun SecretTemporaryApprovals(
    grants: List<TemporaryAccessGrant>,
    clients: List<ClientSummary>,
    onEnd: (TemporaryAccessGrant) -> Unit,
) {
    val dates = rememberDateTimeFormatter()
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Temporary access", style = MaterialTheme.typography.titleMedium)
        Text(
            "These uses skip manual and AI review until they end. The saved settings below " +
                "apply again afterwards; Deny still blocks access.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        grants.forEachIndexed { index, grant ->
            if (index > 0) HorizontalDivider()
            val client = clients.firstOrNull { it.clientId == grant.clientId }
            val paused = client == null || client.state != RelayClientState.ACTIVE ||
                client.desiredState?.let { it != RelayClientState.ACTIVE } == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(client?.name ?: "Unknown client", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${grant.operation.displayName()} · Ends ${dates.timestamp(grant.expiresAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (paused) {
                        Text(
                            "Paused until this client is active",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { onEnd(grant) }) { Text("End") }
            }
        }
    }
}

private fun ClientSummary.approvalLabel(duplicateNames: Set<String>): String =
    if (name in duplicateNames) "$name · ${clientId.takeLast(6)}" else name

private fun TemporaryAccessOperation.displayName(): String = when (this) {
    TemporaryAccessOperation.INVOCATION -> "Secret values for any command"
    TemporaryAccessOperation.GIT_SIGN -> "Git signing for any repository"
    TemporaryAccessOperation.SSH_AUTHENTICATE -> "SSH authentication for any server"
}

@Composable
private fun SshPublicKeyCard(
    key: SshKeyMetadata,
    onCopy: () -> Unit,
    onEditComment: () -> Unit,
    onReplace: () -> Unit,
) {
    var expanded by rememberSaveable(key.fingerprint) { mutableStateOf(false) }
    InformationSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonalIcon(Icons.Outlined.Key, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(key.algorithm.displayName(), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${key.bits}-bit SSH key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Key comment", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(key.comment.ifBlank { "No comment" }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            IconButton(onClick = onEditComment) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit public key comment")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Fingerprint", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(key.fingerprint, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!key.privateKeyAvailable) {
            Text("Private key unavailable", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy public key")
            }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            ) {
                Text("Details")
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("OpenSSH public key", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExactText(key.publicKey)
            TextButton(onClick = onReplace) { Text("Replace key") }
        }
    }
}

@Composable
private fun EnvironmentVariableCard(
    variable: EnvironmentVariableMetadata,
    revealedValue: String?,
    onReveal: () -> Unit,
    onReadValue: suspend () -> String?,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    embedded: Boolean = false,
) {
    var publicValue by remember(
        variable.id,
        variable.valueUpdatedAt,
        variable.sensitive,
    ) { mutableStateOf<String?>(null) }
    var publicValueUnavailable by remember(
        variable.id,
        variable.valueUpdatedAt,
        variable.sensitive,
    ) { mutableStateOf(false) }

    LaunchedEffect(variable.id, variable.valueUpdatedAt, variable.sensitive, variable.valueAvailable) {
        if (!variable.sensitive && variable.valueAvailable) {
            publicValue = onReadValue()
            publicValueUnavailable = publicValue == null
        }
    }

    val displayedValue = if (variable.sensitive) revealedValue else publicValue
    Surface(
        color = if (embedded) androidx.compose.ui.graphics.Color.Transparent else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = if (embedded) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.medium,
        tonalElevation = if (embedded) 0.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    variable.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (variable.sensitive) {
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                stringResource(R.string.sensitive),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (!variable.valueAvailable || publicValueUnavailable) {
                        Text(
                            stringResource(R.string.value_unavailable),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (displayedValue == null) {
                        if (variable.sensitive) {
                            Text(
                                stringResource(R.string.masked_value),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clearAndSetSemantics {
                                    contentDescription = "Value hidden"
                                },
                            )
                        } else {
                            Text(
                                stringResource(R.string.loading_value),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                displayedValue,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (variable.sensitive) {
                    IconButton(onClick = onReveal, enabled = variable.valueAvailable) {
                        Icon(
                            if (revealedValue == null) {
                                Icons.Outlined.Visibility
                            } else {
                                Icons.Outlined.VisibilityOff
                            },
                            contentDescription = stringResource(
                                if (revealedValue == null) R.string.show else R.string.hide,
                            ) + " ${variable.name}",
                        )
                    }
                }
                IconButton(onClick = onCopy, enabled = variable.valueAvailable) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy ${variable.name} value",
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Edit ${variable.name}",
                    )
                }
            }
        }
    }
}
