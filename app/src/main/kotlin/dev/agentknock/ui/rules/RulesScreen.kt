@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.rules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.presentation.ParsedShellCommand
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.parseShellCommand
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.presentation.renderShellWord
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.rule.ApprovalRule
import dev.agentknock.storage.rule.ApprovalRuleAction
import dev.agentknock.storage.rule.CommandMatch
import dev.agentknock.storage.secret.SecretSummary
import dev.agentknock.ui.components.ClientIdentity
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.SecretIdentities
import kotlinx.coroutines.launch

@Composable
internal fun RulesScreen(
    onOpenSettings: () -> Unit,
    onOpenRequest: (Long) -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    viewModel: RulesViewModel = viewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectedRule by viewModel.selectedRule.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val loadingEditor by viewModel.loadingEditor.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDeletion by remember { mutableStateOf<ApprovalRule?>(null) }

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun save() {
        scope.launch {
            when (val result = viewModel.saveEditor()) {
                is RuleOperationResult.Saved -> report(result.message)
                is RuleOperationResult.Failed -> report(result.message)
            }
        }
    }

    fun setEnabled(rule: ApprovalRule, enabled: Boolean) {
        val action = fun() {
            scope.launch {
                report(
                    if (viewModel.setEnabled(rule.id, enabled)) {
                        if (enabled) "Approval rule enabled." else "Approval rule paused."
                    } else {
                        "That rule no longer exists."
                    },
                )
            }
        }
        action()
    }

    val topLevel = selection == null && editor == null && !loadingEditor
    LaunchedEffect(topLevel) { onTopLevelChanged(topLevel) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        when {
            loadingEditor -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            editor != null -> {
                BackHandler { viewModel.closeEditor() }
                RuleEditor(
                    editor = checkNotNull(editor),
                    clients = clients,
                    secrets = secrets,
                    onUpdate = viewModel::updateEditor,
                    onClose = viewModel::closeEditor,
                    onSave = ::save,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
            selection != null -> {
                BackHandler { viewModel.selectRule(null) }
                if (selectedRule == null) {
                    Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                } else {
                    RuleDetail(
                        rule = checkNotNull(selectedRule),
                        clients = clients,
                        secrets = secrets,
                        requests = requests,
                        onBack = { viewModel.selectRule(null) },
                        onEdit = { viewModel.startEditing(checkNotNull(selectedRule)) },
                        onSetEnabled = { enabled ->
                            setEnabled(checkNotNull(selectedRule), enabled)
                        },
                        onRenew = {
                            scope.launch {
                                when (val result = viewModel.renew(it, 4)) {
                                    is RuleOperationResult.Saved -> report(result.message)
                                    is RuleOperationResult.Failed -> report(result.message)
                                }
                            }
                        },
                        onDelete = { pendingDeletion = selectedRule },
                        onOpenRequest = onOpenRequest,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
            else -> RuleList(
                rules = rules,
                clients = clients,
                secrets = secrets,
                onOpen = viewModel::selectRule,
                onSetEnabled = ::setEnabled,
                onAdd = viewModel::startManualRule,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }

    pendingDeletion?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete ${rule.name}?") },
            text = {
                Text("This stops the rule permanently. Existing request and audit history remain.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletion = null
                        scope.launch {
                            report(
                                if (viewModel.delete(rule.id)) {
                                    "Approval rule deleted."
                                } else {
                                    "That rule no longer exists."
                                },
                            )
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RuleList(
    rules: List<ApprovalRule>,
    clients: List<ClientSummary>,
    secrets: List<SecretSummary>,
    onOpen: (String) -> Unit,
    onSetEnabled: (ApprovalRule, Boolean) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Approval rules") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (rules.isNotEmpty()) {
                FloatingActionButton(onClick = onAdd) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add approval rule")
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (rules.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Rule,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("No approval rules", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add a standing rule here, or create a temporary rule while approving a request.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAdd) { Text("Add rule") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(rules, key = ApprovalRule::id) { rule ->
                    RuleListItem(
                        rule = rule,
                        clientName = clients.firstOrNull { it.clientId == rule.clientId }?.name
                            ?: "Unavailable client",
                        secretNames = secrets.filter { it.id in rule.secretIds }
                            .map(SecretSummary::name),
                        expired = rule.isExpired(now),
                        onOpen = { onOpen(rule.id) },
                        onSetEnabled = { onSetEnabled(rule, it) },
                    )
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun RuleListItem(
    rule: ApprovalRule,
    clientName: String,
    secretNames: List<String>,
    expired: Boolean,
    onOpen: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    val secretText = secretNames.ifEmpty { listOf("unavailable secret") }.joinToString()
    val command = renderShellCommand(rule.command.first(), rule.command.drop(1))
    val status = when {
        expired -> "Expired"
        !rule.enabled -> "Paused"
        rule.expiresAt != null -> "Expires ${formatTimestamp(rule.expiresAt)}"
        else -> "Standing rule"
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = {
            Text(rule.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "${rule.action.listVerb()} $secretText for $clientName",
                    color = if (expired || !rule.enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    if (rule.commandMatch == CommandMatch.EXACT) {
                        "Exact command · $command"
                    } else {
                        "Command prefix · $command …"
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = if (expired) {
            { Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = null) }
        } else {
            {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onSetEnabled,
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (expired || !rule.enabled) {
                MaterialTheme.colorScheme.surfaceContainerLowest
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    )
}

@Composable
private fun RuleDetail(
    rule: ApprovalRule,
    clients: List<ClientSummary>,
    secrets: List<SecretSummary>,
    requests: List<InboxRequestSummary>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onRenew: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenRequest: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val expired = rule.isExpired(now)
    val clientName = clients.firstOrNull { it.clientId == rule.clientId }?.name
        ?: "Unavailable client"
    val secretNames = secrets.filter { it.id in rule.secretIds }.map(SecretSummary::name)
    val activity = requests.filter { rule.id in it.matchedRuleIds }.take(5)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(rule.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit approval rule")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete approval rule")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InformationSurface {
                Text(rule.action.label(), style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        expired -> "Expired"
                        !rule.enabled -> "Paused"
                        rule.expiresAt != null -> "Active until ${formatTimestamp(rule.expiresAt)}"
                        else -> "Active standing rule"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expired) {
                    Button(onClick = { onRenew(rule.id) }) { Text("Renew for 4 hours") }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (rule.enabled) "Enabled" else "Paused")
                        Switch(checked = rule.enabled, onCheckedChange = onSetEnabled)
                    }
                }
            }

            Text("Matches", style = MaterialTheme.typography.titleMedium)
            InformationSurface {
                InformationRow("Client", clientName)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Applies to secrets",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecretIdentities(secretNames.ifEmpty { listOf("Unavailable secret") })
                }
                InformationRow(
                    if (rule.commandMatch == CommandMatch.EXACT) {
                        "Exact command"
                    } else {
                        "Command prefix"
                    },
                    renderShellCommand(rule.command.first(), rule.command.drop(1)) +
                        if (rule.commandMatch == CommandMatch.PREFIX) " …" else "",
                    monospace = true,
                )
            }

            if (rule.executableHash != null || rule.workingDirectory != null) {
                Text("Additional checks", style = MaterialTheme.typography.titleMedium)
                InformationSurface {
                    rule.executableHash?.let {
                        InformationRow("Executable", "Same executable version")
                        rule.executablePath?.let { path ->
                            InformationRow("Resolved path", path, monospace = true)
                        }
                    }
                    rule.workingDirectory?.let {
                        InformationRow("Working directory", it, monospace = true)
                    }
                }
            }

            Text("Activity", style = MaterialTheme.typography.titleMedium)
            InformationSurface {
                InformationRow("Matched requests", rule.matchCount.toString())
                rule.lastMatchedAt?.let {
                    InformationRow("Last matched", formatTimestamp(it))
                }
                if (activity.isEmpty()) {
                    Text(
                        "No matching request is retained in recent history.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    activity.forEach { request ->
                        val command = request.command?.let {
                            renderShellCommand(it, request.arguments)
                        } ?: request.title
                        ListItem(
                            modifier = Modifier.clickable { onOpenRequest(request.id) },
                            headlineContent = {
                                Text(command, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text(formatTimestamp(request.receivedAt)) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Outlined.NavigateNext,
                                    contentDescription = null,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                    }
                }
            }

            Text("Details", style = MaterialTheme.typography.titleMedium)
            InformationSurface {
                InformationRow("Created", formatTimestamp(rule.createdAt))
                InformationRow("Updated", formatTimestamp(rule.updatedAt))
                rule.expiresAt?.let { InformationRow("Expires", formatTimestamp(it)) }
                InformationRow("Rule ID", rule.id, monospace = true)
                rule.executableHash?.let {
                    InformationRow("Executable SHA-256", it, monospace = true)
                }
            }
        }
    }
}

@Composable
private fun RuleEditor(
    editor: RuleEditorState,
    clients: List<ClientSummary>,
    secrets: List<SecretSummary>,
    onUpdate: (RuleEditorState) -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requestDerived = editor.origin == RuleEditorOrigin.REQUEST
    val parsed = if (requestDerived) {
        ParsedShellCommand.Valid(
            if (editor.commandMatch == CommandMatch.EXACT) {
                editor.requestCommand
            } else {
                editor.requestCommand.take(editor.prefixLength)
            },
        )
    } else {
        parseShellCommand(editor.commandText)
    }
    val parsedCommand = (parsed as? ParsedShellCommand.Valid)?.tokens.orEmpty()
    val selectedSecretNames = if (requestDerived) {
        editor.secretNames
    } else {
        secrets.filter { it.id in editor.secretIds }.map(SecretSummary::name)
    }
    val clientName = clients.firstOrNull { it.clientId == editor.clientId }?.name
        ?: if (requestDerived) "Requesting client" else "No client selected"
    val canSave = editor.clientId.isNotBlank() && editor.secretIds.isNotEmpty() &&
        parsed is ParsedShellCommand.Valid && editor.action != ApprovalRuleAction.ASK_AI

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (editor.origin) {
                            RuleEditorOrigin.MANUAL -> "New approval rule"
                            RuleEditorOrigin.REQUEST -> "Approve for a while"
                            RuleEditorOrigin.EXISTING -> "Edit approval rule"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 3.dp) {
                Button(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    Text(if (requestDerived) "Create rule and approve" else "Save rule")
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OutlinedTextField(
                value = editor.name,
                onValueChange = { onUpdate(editor.copy(name = it)) },
                label = { Text("Name (optional)") },
                supportingText = { Text("A name is generated if this is left blank.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("When all of these match", style = MaterialTheme.typography.titleMedium)
            if (requestDerived) {
                InformationSurface {
                    ClientIdentity(clientName)
                    SecretIdentities(selectedSecretNames)
                }
            } else {
                SelectionGroup("Client") {
                    if (clients.isEmpty()) {
                        Text(
                            "Pair a client before creating a rule.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        clients.forEach { client ->
                            FilterChip(
                                selected = editor.clientId == client.clientId,
                                onClick = { onUpdate(editor.copy(clientId = client.clientId)) },
                                label = { Text(client.name) },
                            )
                        }
                    }
                }
                SelectionGroup("Applies to secrets") {
                    if (secrets.isEmpty()) {
                        Text(
                            "Add a secret before creating a rule.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        secrets.forEach { secret ->
                            FilterChip(
                                selected = secret.id in editor.secretIds,
                                onClick = {
                                    onUpdate(
                                        editor.copy(
                                            secretIds = if (secret.id in editor.secretIds) {
                                                editor.secretIds - secret.id
                                            } else {
                                                editor.secretIds + secret.id
                                            },
                                        ),
                                    )
                                },
                                label = { Text(secret.name) },
                            )
                        }
                    }
                }
            }

            if (requestDerived) {
                Text("Command", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Touch a word to choose where the approved prefix ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CommandPrefixPicker(
                    command = editor.requestCommand,
                    match = editor.commandMatch,
                    prefixLength = editor.prefixLength,
                    onExact = {
                        onUpdate(
                            editor.copy(
                                commandMatch = CommandMatch.EXACT,
                                prefixLength = editor.requestCommand.size,
                            ),
                        )
                    },
                    onPrefixEnd = { length ->
                        onUpdate(
                            editor.copy(
                                commandMatch = CommandMatch.PREFIX,
                                prefixLength = length,
                            ),
                        )
                    },
                )
            } else {
                OutlinedTextField(
                    value = editor.commandText,
                    onValueChange = { onUpdate(editor.copy(commandText = it)) },
                    label = { Text("Command") },
                    supportingText = {
                        Text(
                            when (parsed) {
                                is ParsedShellCommand.Invalid -> parsed.message
                                is ParsedShellCommand.Valid ->
                                    "Quoting is supported; shell expansion is not performed."
                            },
                        )
                    },
                    isError = parsed is ParsedShellCommand.Invalid && editor.commandText.isNotBlank(),
                    minLines = 2,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                MatchModeSelector(editor.commandMatch) {
                    onUpdate(editor.copy(commandMatch = it))
                }
            }

            if (
                editor.executableHash != null || editor.workingDirectory != null
            ) {
                Text("Additional checks", style = MaterialTheme.typography.titleMedium)
                InformationSurface {
                    if (editor.executableHash != null) {
                        ToggleRow(
                            title = "Same executable version",
                            detail = editor.executablePath.orEmpty(),
                            checked = editor.matchExecutable,
                            onCheckedChange = {
                                onUpdate(editor.copy(matchExecutable = it))
                            },
                        )
                    }
                    editor.workingDirectory?.let { directory ->
                        ToggleRow(
                            title = "Only in this folder",
                            detail = directory,
                            checked = editor.matchWorkingDirectory,
                            onCheckedChange = {
                                onUpdate(editor.copy(matchWorkingDirectory = it))
                            },
                        )
                    }
                }
            }

            if (!requestDerived) {
                Text("Then", style = MaterialTheme.typography.titleMedium)
                ActionSelector(editor.action) { onUpdate(editor.copy(action = it)) }
                Text(
                    "Ask AI rules are not available yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (requestDerived) {
                Text("For", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1, 4, 8, 24).forEach { hours ->
                        FilterChip(
                            selected = editor.durationHours == hours,
                            onClick = { onUpdate(editor.copy(durationHours = hours)) },
                            label = { Text(if (hours == 1) "1 hour" else "$hours hours") },
                        )
                    }
                }
            } else if (editor.expiresAt != null) {
                InformationRow("Expires", formatTimestamp(editor.expiresAt))
            }

            if (parsedCommand.isNotEmpty() && editor.clientId.isNotBlank() && selectedSecretNames.isNotEmpty()) {
                Text("Summary", style = MaterialTheme.typography.titleMedium)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        buildRuleSummary(
                            action = editor.action,
                            secretNames = selectedSecretNames,
                            clientName = clientName,
                            command = parsedCommand,
                            match = editor.commandMatch,
                            durationHours = editor.durationHours.takeIf { requestDerived },
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandPrefixPicker(
    command: List<String>,
    match: CommandMatch,
    prefixLength: Int,
    onExact: () -> Unit,
    onPrefixEnd: (Int) -> Unit,
) {
    MatchModeSelector(match) { selected ->
        if (selected == CommandMatch.EXACT) onExact() else onPrefixEnd(prefixLength)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        command.forEachIndexed { index, token ->
            val included = match == CommandMatch.EXACT || index < prefixLength
            Surface(
                modifier = Modifier
                    .semantics {
                        selected = included
                        role = Role.Button
                    }
                    .clickable { onPrefixEnd(index + 1) },
                color = if (included) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (included) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = RoundedCornerShape(9.dp),
            ) {
                Text(
                    renderShellWord(token),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (match == CommandMatch.PREFIX) {
            Text(
                "…",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MatchModeSelector(selected: CommandMatch, onSelect: (CommandMatch) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == CommandMatch.EXACT,
            onClick = { onSelect(CommandMatch.EXACT) },
            label = { Text("Exact") },
        )
        FilterChip(
            selected = selected == CommandMatch.PREFIX,
            onClick = { onSelect(CommandMatch.PREFIX) },
            label = { Text("Prefix") },
        )
    }
    Text(
        if (selected == CommandMatch.EXACT) {
            "Only the complete command matches."
        } else {
            "Additional arguments may follow the selected prefix."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SelectionGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun ActionSelector(
    selected: ApprovalRuleAction,
    onSelect: (ApprovalRuleAction) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            ApprovalRuleAction.APPROVE,
            ApprovalRuleAction.ASK_ME,
            ApprovalRuleAction.DENY,
            ApprovalRuleAction.ASK_AI,
        ).forEach { action ->
            FilterChip(
                selected = selected == action,
                enabled = action != ApprovalRuleAction.ASK_AI,
                onClick = { onSelect(action) },
                label = { Text(action.label()) },
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun buildRuleSummary(
    action: ApprovalRuleAction,
    secretNames: List<String>,
    clientName: String,
    command: List<String>,
    match: CommandMatch,
    durationHours: Int?,
): String {
    val rendered = renderShellCommand(command.first(), command.drop(1))
    val commandText = if (match == CommandMatch.EXACT) {
        "runs exactly $rendered"
    } else {
        "runs a command beginning with $rendered"
    }
    val duration = durationHours?.let { " for ${if (it == 1) "1 hour" else "$it hours"}" }.orEmpty()
    return "${action.summaryVerb()} ${secretNames.joinToString()} for $clientName when it $commandText$duration."
}

private fun ApprovalRuleAction.label(): String = when (this) {
    ApprovalRuleAction.APPROVE -> "Approve"
    ApprovalRuleAction.ASK_ME -> "Ask me"
    ApprovalRuleAction.DENY -> "Deny"
    ApprovalRuleAction.ASK_AI -> "Ask AI"
}

private fun ApprovalRuleAction.listVerb(): String = when (this) {
    ApprovalRuleAction.APPROVE -> "Approve"
    ApprovalRuleAction.ASK_ME -> "Ask about"
    ApprovalRuleAction.DENY -> "Deny"
    ApprovalRuleAction.ASK_AI -> "Ask AI about"
}

private fun ApprovalRuleAction.summaryVerb(): String = when (this) {
    ApprovalRuleAction.APPROVE -> "Approve"
    ApprovalRuleAction.ASK_ME -> "Ask me about"
    ApprovalRuleAction.DENY -> "Deny"
    ApprovalRuleAction.ASK_AI -> "Ask AI about"
}
