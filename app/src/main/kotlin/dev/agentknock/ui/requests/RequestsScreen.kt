@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.requests

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.protocol.ProfileUploadMode
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.storage.profile.CredentialProfileMetadata
import dev.agentknock.storage.request.CredentialCompletionResult
import dev.agentknock.storage.request.CredentialDecision
import dev.agentknock.storage.request.CredentialDecisionResult
import dev.agentknock.storage.request.CredentialRequestDetails
import dev.agentknock.storage.request.CredentialRequestState
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.request.ProfileUploadDecisionResult
import dev.agentknock.storage.request.ProfileUploadRequestDetails
import dev.agentknock.storage.request.ProfileUploadRequestState
import dev.agentknock.storage.request.ProfileUploadVariableValue
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.vault.VaultIdentity
import dev.agentknock.ui.components.ClientIdentity
import dev.agentknock.ui.components.ProfileIdentities
import kotlinx.coroutines.launch

@Composable
internal fun RequestsScreen(
    authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    viewModel: RequestsViewModel = viewModel(),
) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectedRequest by viewModel.selectedRequest.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSyncResult by viewModel.lastSyncResult.collectAsStateWithLifecycle()
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
                    RequestList(
                        requests = requests,
                        identity = configuration?.active,
                        syncing = syncing,
                        syncProblem = lastSyncResult.problemMessage(),
                        onRefresh = viewModel::refresh,
                        onOpenSettings = onOpenSettings,
                        onOpen = viewModel::selectRequest,
                        modifier = Modifier.width(440.dp).fillMaxHeight(),
                    )
                    VerticalDivider()
                    if (selection == null) {
                        EmptyRequestSelection(Modifier.weight(1f).fillMaxHeight())
                    } else {
                        RequestDetail(
                            request = selectedRequest,
                            authenticate = authenticate,
                            viewModel = viewModel,
                            report = ::report,
                            onBack = { viewModel.selectRequest(null) },
                            showBack = false,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            } else if (selection == null) {
                RequestList(
                    requests = requests,
                    identity = configuration?.active,
                    syncing = syncing,
                    syncProblem = lastSyncResult.problemMessage(),
                    onRefresh = viewModel::refresh,
                    onOpenSettings = onOpenSettings,
                    onOpen = viewModel::selectRequest,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                BackHandler { viewModel.selectRequest(null) }
                RequestDetail(
                    request = selectedRequest,
                    authenticate = authenticate,
                    viewModel = viewModel,
                    report = ::report,
                    onBack = { viewModel.selectRequest(null) },
                    showBack = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun RequestDetail(
    request: InboxRequestDetails?,
    authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
    viewModel: RequestsViewModel,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    if (request == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    when {
        request.pairing != null -> PairingDetail(
            request = request,
            onBack = onBack,
            showBack = showBack,
            onChooseSas = { choice ->
                scope.launch { report(viewModel.chooseSas(request.id, choice).message()) }
            },
            onReject = { scope.launch { report(viewModel.rejectPairing(request.id).message()) } },
            modifier = modifier,
        )
        request.credential != null -> CredentialDetail(
            request = request,
            onBack = onBack,
            showBack = showBack,
            onApprove = {
                authenticate(
                    "Approve credential release",
                    {
                        scope.launch {
                            report(viewModel.approveCredentialRequest(request.id).message())
                        }
                    },
                    report,
                )
            },
            onDeny = { scope.launch { report(viewModel.denyCredentialRequest(request.id).message()) } },
            modifier = modifier,
        )
        request.profileUpload != null -> ProfileUploadDetail(
            request = request,
            onBack = onBack,
            showBack = showBack,
            onAccept = { name ->
                authenticate(
                    "Accept profile proposal",
                    {
                        scope.launch {
                            report(viewModel.acceptProfileUpload(request.id, name).message())
                        }
                    },
                    report,
                )
            },
            onReject = { scope.launch { report(viewModel.rejectProfileUpload(request.id).message()) } },
            authenticate = authenticate,
            onReveal = { variableId -> viewModel.readProfileUploadVariable(request.id, variableId) },
            onSensitivityChange = { variableId, sensitive ->
                viewModel.setProfileUploadVariableSensitivity(request.id, variableId, sensitive)
            },
            report = report,
            modifier = modifier,
        )
        else -> MissingDetail(onBack = onBack, showBack = showBack, modifier = modifier)
    }
}

@Composable
private fun EmptyRequestSelection(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "Select a request to view its details",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RequestList(
    requests: List<InboxRequestSummary>,
    identity: VaultIdentity?,
    syncing: Boolean,
    syncProblem: String?,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TopAppBar(
            title = { Text("Requests") },
            actions = {
                IconButton(onClick = onRefresh, enabled = !syncing) {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            },
        )
        syncProblem?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (requests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("No requests", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Requests that need review will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    identity?.let {
                        Text(
                            if (it.pairingEnabled) {
                                "New pairings are accepted at"
                            } else {
                                "New pairings are paused for"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        SelectionContainer {
                            Text(it.address, fontFamily = FontFamily.Monospace)
                        }
                        if (it.pairingEnabled) {
                            SelectionContainer {
                                Text(
                                    "agentknock pairing start ${it.address}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(requests, key = InboxRequestSummary::id) { request ->
                    RequestRow(request, onClick = { onOpen(request.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RequestRow(request: InboxRequestSummary, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    request.statusLabel(),
                    color = request.statusColor(),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (request.command != null) {
                    Text(
                        renderShellCommand(request.command, request.arguments),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(request.title, style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ClientIdentity(request.clientName)
                if (request.profileNames.isNotEmpty()) {
                    ProfileIdentities(request.profileNames)
                }
                Text(
                    formatTimestamp(request.receivedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PairingDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onChooseSas: (Int?) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier,
) {
    val pairing = checkNotNull(request.pairing)
    DetailPage("Pairing request", onBack, modifier, showBack = showBack) {
        StatusLine(
            pairing.pairingState.label(),
            pairing.pairingState.isError() ||
                (pairing.pairingState == PairingState.RECEIVING && pairing.error != null),
        )
        Text(
            pairing.hostname ?: pairing.platform ?: "Unknown client",
            style = MaterialTheme.typography.headlineSmall,
        )
        val reported = listOfNotNull(
            pairing.platform?.let(::formatPlatformName),
            pairing.architecture,
        ).joinToString(" · ")
        if (reported.isNotEmpty()) {
            Text(reported, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        when (pairing.pairingState) {
            PairingState.SAS_VERIFICATION_PENDING -> {
                Text("Which code is shown by the client?", style = MaterialTheme.typography.titleLarge)
                Text("Choose the exact same code. A wrong choice rejects the pairing.")
                pairing.sasOptions.forEachIndexed { index, sas ->
                    Button(onClick = { onChooseSas(index) }, modifier = Modifier.fillMaxWidth()) {
                        Text(sas, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedButton(onClick = { onChooseSas(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("None of the above")
                }
            }
            PairingState.WAITING_FOR_FINISH -> Notice(
                "Code verified",
                "The client must run agentknock pairing finish to activate this pairing.",
            )
            PairingState.RELAY_ACTIVATION_PENDING -> Notice(
                "Activating pairing",
                "Waiting for the relay to apply the client state.",
            )
            PairingState.ACTIVE -> Notice("Pairing complete", "This client can now make requests.")
            PairingState.REJECTED -> Notice("Pairing rejected", "No access was granted.", true)
            PairingState.RECEIVING -> if (pairing.error == null) {
                Notice("Receiving pairing", "The request is still being verified.")
            } else {
                Notice(
                    "Pairing message rejected",
                    "${pairing.error} Waiting for a valid completion, or you can reject this pairing.",
                    true,
                )
            }
            PairingState.VERIFICATION_FAILED -> Notice(
                "Pairing could not be verified",
                pairing.error ?: "The cryptographic message was invalid.",
                true,
            )
        }
        if (
            pairing.pairingState in setOf(
                PairingState.RECEIVING,
                PairingState.VERIFICATION_FAILED,
                PairingState.RELAY_ACTIVATION_PENDING,
                PairingState.WAITING_FOR_FINISH,
            )
        ) {
            OutlinedButton(onClick = onReject, modifier = Modifier.fillMaxWidth()) {
                Text("Reject pairing")
            }
        }
        Disclosure("Technical details") {
            DetailValue("Received", formatTimestamp(request.receivedAt))
            DetailValue("Pairing address", pairing.vaultAddress, true)
            pairing.osVersion?.let { DetailValue("OS version", it) }
            pairing.cliVersion?.let { DetailValue("CLI version", it) }
            pairing.machineId?.let { DetailValue("Machine ID reported by client", it, true) }
            DetailValue("Client ID", pairing.clientId, true)
            DetailValue("Request ID", request.relayRequestId, true)
        }
    }
}

@Composable
private fun CredentialDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier,
) {
    val credential = checkNotNull(request.credential)
    DetailPage("Profile access", onBack, modifier, showBack = showBack) {
        StatusLine(credential.statusLabel(), credential.isError())
        ClientIdentity(credential.clientName)
        ProfileIdentities(credential.profiles)

        credential.reason?.takeIf(String::isNotBlank)?.let { reason ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reason reported by client", style = MaterialTheme.typography.labelLarge)
                    Text(reason, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This text is supplied by the requesting client.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Command", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(
                    renderShellCommand(credential.command, credential.arguments),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        if (credential.missingProfiles.isNotEmpty()) {
            Notice(
                "Profiles are unavailable",
                credential.missingProfiles.joinToString(),
                error = true,
            )
        }

        if (credential.profileDetails.isNotEmpty()) {
            Disclosure("Profile details") {
                credential.profileDetails.forEach { profile -> ProfileSummary(profile) }
                Text(
                    "Values are never shown in a request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (credential.state == CredentialRequestState.APPROVAL_PENDING) {
            Button(
                onClick = onApprove,
                enabled = credential.missingProfiles.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Approve once") }
            OutlinedButton(onClick = onDeny, modifier = Modifier.fillMaxWidth()) {
                Text("Deny once")
            }
        } else {
            CredentialOutcome(credential)
        }

        Disclosure("Technical details") {
            DetailValue("Received", formatTimestamp(request.receivedAt))
            DetailValue("Working directory", credential.workingDirectory, true)
            DetailValue("Executable path", credential.executablePath, true)
            credential.executableHash?.let { DetailValue("Executable hash", it, true) }
            DetailValue("Executable mode", credential.executableMode)
            DetailValue("Standard input", credential.stdinKind)
            DetailValue("Standard output", credential.stdoutKind)
            DetailValue("Standard error", credential.stderrKind)
            if (credential.launcherChain.isNotEmpty()) {
                DetailValue("Launcher chain", credential.launcherChain.joinToString("\n"), true)
            }
            credential.platform?.let {
                DetailValue("Platform reported by client", formatPlatformName(it))
            }
            credential.architecture?.let { DetailValue("Architecture reported by client", it) }
            credential.hostname?.takeIf { it != credential.clientName }
                ?.let { DetailValue("Hostname reported by client", it) }
            credential.osVersion?.let { DetailValue("OS version", it) }
            DetailValue("CLI version", credential.cliVersion)
            credential.machineId?.let { DetailValue("Machine ID reported by client", it, true) }
            DetailValue("Client ID", credential.clientId, true)
            DetailValue("Request ID", request.relayRequestId, true)
        }
    }
}

@Composable
private fun ProfileUploadDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onAccept: (String) -> Unit,
    onReject: () -> Unit,
    authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
    onReveal: suspend (String) -> ProfileUploadVariableValue,
    onSensitivityChange: suspend (String, Boolean) -> Boolean,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val upload = checkNotNull(request.profileUpload)
    var acceptedName by remember(upload.proposedName, upload.acceptedName) {
        mutableStateOf(upload.acceptedName ?: upload.proposedName)
    }
    var editingName by remember { mutableStateOf(false) }
    var revealedValues by remember(request.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, request.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) revealedValues = emptyMap()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DetailPage(
        title = "${upload.mode.titleLabel()} $acceptedName",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${upload.mode.titleLabel()} $acceptedName",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (
                    upload.mode == ProfileUploadMode.CREATE &&
                    upload.state == ProfileUploadRequestState.REVIEW_PENDING
                ) {
                    IconButton(onClick = { editingName = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Rename profile")
                    }
                }
            }
        },
    ) {
        StatusLine(upload.state.label(), upload.state == ProfileUploadRequestState.VERIFICATION_FAILED)
        DetailValue("Profile type", "Environment variables")
        DetailValue("Proposed by client", upload.clientName)
        upload.description?.takeIf(String::isNotBlank)?.let {
            DetailValue("Proposed description", it)
        }

        if (upload.mode != ProfileUploadMode.CREATE) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Changes to the existing profile", style = MaterialTheme.typography.titleMedium)
                    ChangeGroup("New", upload.addedVariables)
                    ChangeGroup("Updated", upload.changedVariables)
                    ChangeGroup("Removed", upload.removedVariables)
                    ChangeGroup("Unchanged", upload.unchangedVariables, subdued = true)
                }
            }
        }

        Text(
            if (upload.state == ProfileUploadRequestState.REVIEW_PENDING) {
                "Incoming variables"
            } else {
                "Incoming variable names"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        if (upload.variableNames.isEmpty()) Text("No variables")
        if (upload.state != ProfileUploadRequestState.REVIEW_PENDING) {
            SelectionContainer {
                Text(upload.variableNames.joinToString("\n"), fontFamily = FontFamily.Monospace)
            }
            Text(
                "Uploaded values were discarded after this proposal was decided.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (upload.state == ProfileUploadRequestState.REVIEW_PENDING) upload.variables.forEach { variable ->
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(variable.name, fontFamily = FontFamily.Monospace)
                    val value = revealedValues[variable.id]
                    if (value == null) {
                        Text("Value hidden", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(
                            onClick = {
                                val reveal: () -> Unit = {
                                    scope.launch {
                                        when (val result = onReveal(variable.id)) {
                                            is ProfileUploadVariableValue.Available -> {
                                                revealedValues = revealedValues +
                                                    (variable.id to result.value)
                                            }
                                            ProfileUploadVariableValue.NotFound ->
                                                report("This variable is no longer available")
                                            ProfileUploadVariableValue.Unavailable ->
                                                report("The encryption key is unavailable")
                                            ProfileUploadVariableValue.Corrupted ->
                                                report("The uploaded value could not be authenticated")
                                            ProfileUploadVariableValue.UnsupportedEncryption ->
                                                report("The uploaded value uses unsupported encryption")
                                        }
                                    }
                                }
                                if (variable.sensitive) {
                                    authenticate("Show uploaded value", reveal, report)
                                } else {
                                    reveal()
                                }
                            },
                        ) { Text("Show value") }
                    } else {
                        SelectionContainer {
                            Text(value, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Sensitive")
                        Switch(
                            checked = variable.sensitive,
                            enabled = upload.state == ProfileUploadRequestState.REVIEW_PENDING,
                            onCheckedChange = { sensitive ->
                                scope.launch {
                                    if (!onSensitivityChange(variable.id, sensitive)) {
                                        report("Sensitivity could not be changed")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        if (upload.state == ProfileUploadRequestState.REVIEW_PENDING) {
            Button(
                onClick = { onAccept(acceptedName.trim()) },
                enabled = acceptedName.isNotBlank() &&
                    upload.variables.all { revealedValues.containsKey(it.id) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Accept proposal") }
            if (upload.variables.any { !revealedValues.containsKey(it.id) }) {
                Text(
                    "Review every incoming value before accepting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onReject, modifier = Modifier.fillMaxWidth()) {
                Text("Reject proposal")
            }
        } else {
            upload.acceptedName?.takeIf { it != upload.proposedName }?.let {
                DetailValue("Proposed name", upload.proposedName)
            }
            upload.error?.let { Notice("Proposal could not be verified", it, true) }
        }

        Disclosure("Technical details") {
            DetailValue("Received", formatTimestamp(request.receivedAt))
            DetailValue("Profile type", upload.profileType)
            DetailValue("Client ID", upload.clientId, true)
            DetailValue("Request ID", request.relayRequestId, true)
        }
    }
    if (editingName) {
        var editedName by remember(acceptedName) { mutableStateOf(acceptedName) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Profile name") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editedName.isNotBlank(),
                    onClick = {
                        acceptedName = editedName.trim()
                        editingName = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CredentialOutcome(credential: CredentialRequestDetails) {
    val (title, detail, error) = when (credential.state) {
        CredentialRequestState.WAITING_FOR_COMPLETION -> Triple(
            if (credential.decision == CredentialDecision.APPROVED) "Approved" else "Denied",
            "Waiting for the client to finish.",
            credential.decision == CredentialDecision.DENIED,
        )
        CredentialRequestState.COMPLETED -> when (credential.completionResult) {
            CredentialCompletionResult.APPROVED -> Triple("Delivered", "The client received the profile values.", false)
            CredentialCompletionResult.DENIED -> Triple("Denied", credential.completionMessage ?: "No values were released.", false)
            CredentialCompletionResult.ABORTED -> Triple("Aborted", credential.completionMessage ?: "The client stopped this request.", false)
            null -> Triple("Completed", "The request is complete.", false)
        }
        CredentialRequestState.VERIFICATION_FAILED -> Triple(
            "Could not verify request",
            credential.error ?: "The cryptographic message was invalid.",
            true,
        )
        CredentialRequestState.APPROVAL_PENDING -> return
    }
    Notice(title, detail, error)
}

@Composable
private fun ProfileSummary(profile: CredentialProfileMetadata) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(profile.name, style = MaterialTheme.typography.titleMedium)
        if (profile.description.isNotBlank()) {
            Text(profile.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer {
            Text(profile.environmentVariableNames.joinToString("\n"), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ChangeGroup(label: String, names: List<String>, subdued: Boolean = false) {
    if (names.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            names.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            color = if (subdued) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        )
    }
}

@Composable
private fun DetailPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    showBack: Boolean,
    titleContent: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        TopAppBar(
            title = titleContent,
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
    }
}

@Composable
private fun Disclosure(title: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun Notice(title: String, detail: String, error: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail)
        }
    }
}

@Composable
private fun StatusLine(label: String, error: Boolean = false) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DetailValue(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default)
        }
    }
}

@Composable
private fun MissingDetail(onBack: () -> Unit, showBack: Boolean, modifier: Modifier) {
    DetailPage("Request", onBack, modifier, showBack = showBack) {
        Notice("Request unavailable", "This request has no displayable details.", true)
    }
}

private fun InboxRequestSummary.statusLabel(): String = when {
    pairingState != null -> pairingState.label()
    credentialState != null -> credentialStatusLabel(credentialState, credentialResult)
    profileUploadState != null -> profileUploadState.label()
    state == InboxRequestState.ACTION_REQUIRED -> "Action required"
    state == InboxRequestState.WAITING -> "Waiting"
    else -> "Completed"
}

@Composable
private fun InboxRequestSummary.statusColor(): Color = when {
    pairingState?.isError() == true || credentialState == CredentialRequestState.VERIFICATION_FAILED ||
        profileUploadState == ProfileUploadRequestState.VERIFICATION_FAILED -> MaterialTheme.colorScheme.error
    state == InboxRequestState.ACTION_REQUIRED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun PairingState.label(): String = when (this) {
    PairingState.RECEIVING -> "Receiving"
    PairingState.SAS_VERIFICATION_PENDING -> "Action required"
    PairingState.RELAY_ACTIVATION_PENDING -> "Activating"
    PairingState.WAITING_FOR_FINISH -> "Waiting for client"
    PairingState.REJECTED -> "Rejected"
    PairingState.ACTIVE -> "Paired"
    PairingState.VERIFICATION_FAILED -> "Verification failed"
}

private fun PairingState.isError(): Boolean = this == PairingState.VERIFICATION_FAILED

private fun CredentialRequestDetails.statusLabel(): String = credentialStatusLabel(state, completionResult)

private fun credentialStatusLabel(
    state: CredentialRequestState,
    result: CredentialCompletionResult?,
): String = when (state) {
    CredentialRequestState.APPROVAL_PENDING -> "Action required"
    CredentialRequestState.WAITING_FOR_COMPLETION -> "Waiting for client"
    CredentialRequestState.VERIFICATION_FAILED -> "Verification failed"
    CredentialRequestState.COMPLETED -> when (result) {
        CredentialCompletionResult.APPROVED -> "Delivered"
        CredentialCompletionResult.DENIED -> "Denied"
        CredentialCompletionResult.ABORTED -> "Aborted"
        null -> "Completed"
    }
}

private fun CredentialRequestDetails.isError(): Boolean =
    state == CredentialRequestState.VERIFICATION_FAILED

private fun ProfileUploadRequestState.label(): String = when (this) {
    ProfileUploadRequestState.REVIEW_PENDING -> "Action required"
    ProfileUploadRequestState.ACCEPTED -> "Accepted"
    ProfileUploadRequestState.REJECTED -> "Rejected"
    ProfileUploadRequestState.VERIFICATION_FAILED -> "Verification failed"
}

private fun ProfileUploadMode.titleLabel(): String = when (this) {
    ProfileUploadMode.CREATE -> "Create"
    ProfileUploadMode.REPLACE -> "Replace"
    ProfileUploadMode.UPDATE -> "Update"
}

private fun PairingDecisionResult.message(): String = when (this) {
    PairingDecisionResult.VERIFIED -> "Pairing code verified"
    PairingDecisionResult.REJECTED -> "Pairing rejected"
    PairingDecisionResult.NOT_PENDING -> "This pairing no longer needs a decision"
    PairingDecisionResult.NOT_FOUND -> "Request is no longer available"
}

private fun CredentialDecisionResult.message(): String = when (this) {
    CredentialDecisionResult.Decided -> "Decision saved"
    CredentialDecisionResult.ProfilesChanged -> "A requested profile changed; review the request again"
    CredentialDecisionResult.NotPending -> "This request no longer needs a decision"
    CredentialDecisionResult.NotFound -> "Request is no longer available"
    is CredentialDecisionResult.MissingProfiles -> "Missing profiles: ${names.joinToString()}"
    is CredentialDecisionResult.ConflictingVariable -> "Conflicting variable: $name"
    CredentialDecisionResult.SecretUnavailable -> "A profile value is unavailable on this device"
    CredentialDecisionResult.SecretCorrupted -> "A profile value could not be authenticated"
    CredentialDecisionResult.UnsupportedEncryption -> "A profile value uses unsupported encryption"
    CredentialDecisionResult.PairingUnavailable -> "The paired client is unavailable"
}

private fun ProfileUploadDecisionResult.message(): String = when (this) {
    is ProfileUploadDecisionResult.Accepted -> "Profile proposal accepted"
    ProfileUploadDecisionResult.Rejected -> "Profile proposal rejected"
    ProfileUploadDecisionResult.NotPending -> "This proposal no longer needs a decision"
    ProfileUploadDecisionResult.NotFound -> "Proposal is no longer available"
    is ProfileUploadDecisionResult.Invalid -> message
    ProfileUploadDecisionResult.SecretUnavailable -> "An uploaded value is unavailable on this device"
    ProfileUploadDecisionResult.SecretCorrupted -> "An uploaded value could not be authenticated"
    ProfileUploadDecisionResult.UnsupportedEncryption -> "An uploaded value uses unsupported encryption"
}

private fun RequestSyncResult?.problemMessage(): String? = when (this) {
    null, RequestSyncResult.Success, RequestSyncResult.NoVault -> null
    RequestSyncResult.VaultSecretsUnavailable -> "Device keys are unavailable"
    RequestSyncResult.VaultSecretsCorrupted -> "Device keys could not be verified"
    RequestSyncResult.UnsupportedVaultEncryption -> "Device keys use unsupported encryption"
    is RequestSyncResult.RelayRejected -> message ?: "The relay rejected the connection"
    is RequestSyncResult.RelayUnavailable -> message ?: "The relay is temporarily unavailable"
    RequestSyncResult.InvalidRelayResponse -> "The relay returned an invalid response"
}
