@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.requests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.request.InvocationCompletionResult
import dev.agentknock.storage.request.SecretUseDecision
import dev.agentknock.storage.request.SecretUseDecisionResult
import dev.agentknock.storage.request.SecretUseRequestDetails
import dev.agentknock.storage.request.SecretUseRequestState
import dev.agentknock.storage.request.GitSignCompletionResult
import dev.agentknock.storage.request.GitSignDecisionResult
import dev.agentknock.storage.request.GitSignRequestDetails
import dev.agentknock.storage.request.GitSignRequestState
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.request.SecretUploadDecisionResult
import dev.agentknock.storage.request.SecretUploadRequestDetails
import dev.agentknock.storage.request.SecretUploadRequestState
import dev.agentknock.storage.request.SecretUploadVariableValue
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.rule.ApprovalRuleAction
import dev.agentknock.storage.rule.AiReviewDecision
import dev.agentknock.storage.rule.AiReviewFailure
import dev.agentknock.ui.components.ClientIdentity
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.SecretIdentities
import dev.agentknock.ui.theme.agentknockColors
import kotlinx.coroutines.launch

@Composable
internal fun RequestsScreen(
    onOpenSettings: () -> Unit,
    notificationsEnabled: Boolean,
    onTopLevelChanged: (Boolean) -> Unit,
    onCreateRule: (Long) -> Unit,
    viewModel: RequestsViewModel = viewModel(),
) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectedRequest by viewModel.selectedRequest.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSyncResult by viewModel.lastSyncResult.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun approve(request: InboxRequestSummary) {
        scope.launch {
            when {
                request.secretUseState == SecretUseRequestState.APPROVAL_PENDING -> {
                    report(viewModel.approveSecretUseRequest(request.id).message())
                }
                request.gitSignState == GitSignRequestState.APPROVAL_PENDING -> {
                    report(viewModel.approveGitSignRequest(request.id).message())
                }
            }
        }
    }

    fun reject(request: InboxRequestSummary) {
        scope.launch {
            when {
                request.secretUseState == SecretUseRequestState.APPROVAL_PENDING -> {
                    report(viewModel.denySecretUseRequest(request.id).message())
                }
                request.gitSignState == GitSignRequestState.APPROVAL_PENDING -> {
                    report(viewModel.denyGitSignRequest(request.id).message())
                }
            }
        }
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
                    RequestList(
                        requests = requests,
                        selectedRequestId = selection,
                        syncing = syncing,
                        syncProblem = lastSyncResult.problemMessage(),
                        onRefresh = viewModel::refresh,
                        onShowSyncProblem = ::report,
                        onOpenSettings = onOpenSettings,
                        notificationsEnabled = notificationsEnabled,
                        onOpen = viewModel::selectRequest,
                        onApprove = ::approve,
                        onReject = ::reject,
                        modifier = Modifier.width(440.dp).fillMaxHeight(),
                    )
                    VerticalDivider()
                    if (selection == null) {
                        EmptyRequestSelection(Modifier.weight(1f).fillMaxHeight())
                    } else {
                        RequestDetail(
                            request = selectedRequest,
                            viewModel = viewModel,
                            report = ::report,
                            onBack = { viewModel.selectRequest(null) },
                            showBack = false,
                            onCreateRule = onCreateRule,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            } else if (selection == null) {
                RequestList(
                    requests = requests,
                    selectedRequestId = selection,
                    syncing = syncing,
                    syncProblem = lastSyncResult.problemMessage(),
                    onRefresh = viewModel::refresh,
                    onShowSyncProblem = ::report,
                    onOpenSettings = onOpenSettings,
                    notificationsEnabled = notificationsEnabled,
                    onOpen = viewModel::selectRequest,
                    onApprove = ::approve,
                    onReject = ::reject,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                BackHandler { viewModel.selectRequest(null) }
                RequestDetail(
                    request = selectedRequest,
                    viewModel = viewModel,
                    report = ::report,
                    onBack = { viewModel.selectRequest(null) },
                    showBack = true,
                    onCreateRule = onCreateRule,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun RequestDetail(
    request: InboxRequestDetails?,
    viewModel: RequestsViewModel,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    onCreateRule: (Long) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    if (request == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (request.secretUse != null) {
        SecretUseDetail(
            request = request,
            onBack = onBack,
            showBack = showBack,
            onApprove = {
                scope.launch {
                    report(viewModel.approveSecretUseRequest(request.id).message())
                }
            },
            onDeny = { scope.launch { report(viewModel.denySecretUseRequest(request.id).message()) } },
            onCreateRule = { onCreateRule(request.id) },
            modifier = modifier,
        )
    } else if (request.gitSign != null) {
        GitSignDetail(
            request = request,
            onBack = onBack,
            showBack = showBack,
            onApprove = {
                scope.launch { report(viewModel.approveGitSignRequest(request.id).message()) }
            },
            onDeny = {
                scope.launch { report(viewModel.denyGitSignRequest(request.id).message()) }
            },
            modifier = modifier,
        )
    } else {
        MissingDetail(onBack = onBack, showBack = showBack, modifier = modifier)
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
    selectedRequestId: Long?,
    syncing: Boolean,
    syncProblem: String?,
    onRefresh: () -> Unit,
    onShowSyncProblem: (String) -> Unit,
    onOpenSettings: () -> Unit,
    notificationsEnabled: Boolean,
    onOpen: (Long) -> Unit,
    onApprove: (InboxRequestSummary) -> Unit,
    onReject: (InboxRequestSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var previousNewestRequestId by remember { mutableStateOf<Long?>(null) }
    val newestRequestId = requests.firstOrNull()?.id
    LaunchedEffect(newestRequestId) {
        val previousNewest = previousNewestRequestId
        if (newestRequestId != null && previousNewest != null && newestRequestId != previousNewest) {
            val previousNewestIndex = requests.indexOfFirst { it.id == previousNewest }
            if (
                listState.firstVisibleItemIndex == 0 ||
                listState.firstVisibleItemIndex == previousNewestIndex
            ) {
                listState.animateScrollToItem(0)
            }
        }
        previousNewestRequestId = newestRequestId
    }
    Column(modifier) {
        TopAppBar(
            title = { Text("Requests") },
            actions = {
                if (!notificationsEnabled) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.NotificationsOff,
                            contentDescription = "Notifications disabled",
                        )
                    }
                }
                syncProblem?.let { problem ->
                    IconButton(onClick = { onShowSyncProblem(problem) }) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = "Connection problem",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = onRefresh, enabled = !syncing) {
                    if (syncing) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp).semantics {
                                contentDescription = "Refreshing requests"
                            },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            },
        )
        if (requests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("No secret requests", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Requests to use your secrets will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(requests, key = { _, request -> request.id }) { _, request ->
                    RequestRow(
                        request = request,
                        selected = request.id == selectedRequestId,
                        onClick = { onOpen(request.id) },
                        onApprove = { onApprove(request) },
                        onReject = { onReject(request) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    request: InboxRequestSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val canApprove = request.secretUseState == SecretUseRequestState.APPROVAL_PENDING ||
        request.gitSignState == GitSignRequestState.APPROVAL_PENDING
    val canReject = request.canReject()
    val rejectLabel = "Deny once"
    val swipeState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.65f },
    )
    LaunchedEffect(swipeState.currentValue) {
        when (swipeState.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> if (canApprove) onApprove()
            SwipeToDismissBoxValue.EndToStart -> if (canReject) onReject()
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        swipeState.reset()
    }
    SwipeToDismissBox(
        state = swipeState,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .semantics {
                this.selected = selected
                customActions = buildList {
                    if (canApprove) {
                        add(CustomAccessibilityAction("Approve once") {
                            onApprove()
                            true
                        })
                    }
                    if (canReject) {
                        add(CustomAccessibilityAction(rejectLabel) {
                            onReject()
                            true
                        })
                    }
                }
            },
        enableDismissFromStartToEnd = canApprove,
        enableDismissFromEndToStart = canReject,
        backgroundContent = {
            val direction = swipeState.dismissDirection
            val approving = direction == SwipeToDismissBoxValue.StartToEnd
            val semanticColors = MaterialTheme.agentknockColors
            val backgroundColor = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> semanticColors.successContainer
                SwipeToDismissBoxValue.EndToStart -> semanticColors.dangerContainer
                SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> semanticColors.onSuccessContainer
                SwipeToDismissBoxValue.EndToStart -> semanticColors.onDangerContainer
                SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (approving) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (approving) Icons.Outlined.Check else Icons.Outlined.Close,
                            contentDescription = null,
                            tint = contentColor,
                        )
                        Text(if (approving) "Approve once" else rejectLabel, color = contentColor)
                    }
                }
            }
        },
    ) {
        RequestRowContent(request, selected, onClick)
    }
}

@Composable
private fun RequestRowContent(
    request: InboxRequestSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val rejected = request.wasRejected()
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    val semanticColors = MaterialTheme.agentknockColors
    val containerColor = when {
        actionRequired -> semanticColors.attentionContainer
        selected -> MaterialTheme.colorScheme.secondaryContainer
        rejected -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        color = containerColor,
        contentColor = when {
            actionRequired -> semanticColors.onAttentionContainer
            rejected && !selected -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        shape = MaterialTheme.shapes.large,
        border = if (actionRequired) {
            BorderStroke(1.dp, semanticColors.attentionAccent)
        } else {
            null
        },
        tonalElevation = if (actionRequired) 2.dp else 0.dp,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    request.title,
                    style = if (request.command == null) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    color = if (request.command == null) {
                        Color.Unspecified
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                RequestStatusBadge(request)
                Icon(
                    Icons.AutoMirrored.Outlined.NavigateNext,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            request.command?.let {
                Text(
                    renderShellCommand(it, request.arguments),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            request.listSummary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ClientIdentity(request.clientName)
                if (request.secretNames.isNotEmpty()) {
                    SecretIdentities(
                        request.secretNames,
                        unavailable = request.hasInvalidSecretReference(),
                    )
                }
            }
            Text(
                formatTimestamp(request.receivedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
internal fun PairingRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onChooseSas: (Int?) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier,
) {
    val pairing = checkNotNull(request.pairing)
    DetailPage("Pairing", onBack, modifier, showBack = showBack) {
        InformationSurface {
            StatusLine(
                pairing.pairingState.label(),
                pairing.pairingState.isError() ||
                    (pairing.pairingState == PairingState.RECEIVING && pairing.error != null),
                attention = request.state == InboxRequestState.ACTION_REQUIRED,
                subdued = pairing.pairingState == PairingState.REJECTED,
            )
            InformationRow("Client-reported name", pairing.clientName)
            val reported = listOfNotNull(
                pairing.hostname,
                pairing.platform?.let(::formatPlatformName),
                pairing.architecture,
            ).joinToString(" · ")
            if (reported.isNotEmpty()) {
                InformationRow("Client-reported host", reported)
            }
            InformationRow("Received", formatTimestamp(request.receivedAt))
            pairing.decidedAt?.let {
                InformationRow("User decision", formatTimestamp(it))
            }
            request.completedAt?.let {
                InformationRow("Completed", formatTimestamp(it))
            }
        }

        when (pairing.pairingState) {
            PairingState.SAS_VERIFICATION_PENDING -> {
                Text("Which code is shown by the client?", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Choose the exact same code to accept this client. " +
                        "A wrong choice rejects the pairing.",
                )
                pairing.sasOptions.forEachIndexed { index, sas ->
                    FilledTonalButton(
                        onClick = { onChooseSas(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(sas, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedButton(
                    onClick = { onChooseSas(null) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.agentknockColors.danger,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
                ) {
                    Text("None of the above")
                }
            }
            PairingState.WAITING_FOR_FINISH -> Notice(
                "Code verified",
                "The client must run agentknock pairing finish to activate this pairing.",
                NoticeTone.SUCCESS,
            )
            PairingState.RELAY_ACTIVATION_PENDING -> Notice(
                "Activating pairing",
                "Waiting for the relay to apply the client state.",
                NoticeTone.ATTENTION,
            )
            PairingState.ACTIVE -> Notice(
                "Pairing completed",
                "Access was granted to this client.",
                NoticeTone.SUCCESS,
            )
            PairingState.REJECTED -> Notice(
                "Pairing rejected",
                "No access was granted.",
                NoticeTone.SUBDUED,
            )
            PairingState.RECEIVING -> if (pairing.error == null) {
                Notice(
                    "Receiving pairing",
                    "The request is still being verified.",
                    NoticeTone.ATTENTION,
                )
            } else {
                Notice(
                    "Pairing message rejected",
                    "${pairing.error} Waiting for a valid completion, or you can reject this pairing.",
                    NoticeTone.DANGER,
                )
            }
            PairingState.VERIFICATION_FAILED -> Notice(
                "Pairing could not be verified",
                pairing.error ?: "The cryptographic message was invalid.",
                NoticeTone.DANGER,
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
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.agentknockColors.danger,
                ),
                border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
            ) {
                Text("Reject pairing")
            }
        }
        Disclosure("Technical details") {
            DetailValue("Pairing address", pairing.pairingAddress, true)
            pairing.osVersion?.let { DetailValue("OS version", it) }
            pairing.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            pairing.machineId?.let { DetailValue("Machine ID reported by client", it, true) }
            DetailValue("Client ID", pairing.clientId, true)
            DetailValue("Request ID", request.relayRequestId, true)
        }
    }
}

@Composable
private fun SecretUseDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onCreateRule: () -> Unit,
    modifier: Modifier,
) {
    val secretUse = checkNotNull(request.secretUse)
    DetailPage(
        title = "Secret use",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        bottomContent = if (secretUse.state == SecretUseRequestState.APPROVAL_PENDING) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = onDeny,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.agentknockColors.danger,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
                            ) {
                                Text("Deny once")
                            }
                            Button(
                                onClick = onApprove,
                                enabled = secretUse.missingSecrets.isEmpty(),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.agentknockColors.success,
                                    contentColor = MaterialTheme.agentknockColors.onSuccess,
                                ),
                            ) {
                                Text("Approve once")
                            }
                        }
                        FilledTonalButton(
                            onClick = onCreateRule,
                            enabled = secretUse.missingSecrets.isEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Approve for a while")
                        }
                    }
                }
            }
        } else {
            null
        },
    ) {
        InformationSurface {
            StatusLine(
                secretUse.statusLabel(),
                secretUse.isError(),
                attention = secretUse.state == SecretUseRequestState.APPROVAL_PENDING,
                subdued = secretUse.decision == SecretUseDecision.DENIED ||
                    secretUse.completionResult == InvocationCompletionResult.DENIED,
            )
            ClientIdentity(secretUse.clientName)
            SecretIdentities(secretUse.secrets)
            val environmentVariableCount = secretUse.secretDetails.sumOf {
                it.environmentVariableNames.size
            }
            if (environmentVariableCount > 0) {
                Text(
                    "$environmentVariableCount environment ${if (environmentVariableCount == 1) {
                        "variable requested"
                    } else {
                        "variables requested"
                    }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val sshKeyCount = secretUse.secretDetails.count { it.type == "ssh" }
            if (sshKeyCount > 0) {
                Text(
                    "$sshKeyCount SSH ${if (sshKeyCount == 1) "key" else "keys"} requested",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InformationRow("Received", formatTimestamp(request.receivedAt))
        }

        secretUse.reason?.takeIf(String::isNotBlank)?.let { reason ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reason reported by client", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Untrusted context supplied by the requesting client.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(reason, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        val renderedCommand = renderShellCommand(secretUse.command, secretUse.arguments)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Command", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(renderedCommand, fontFamily = FontFamily.Monospace)
                }
                if (renderedCommand.any { it.code > 0x7e }) {
                    Text(
                        "This command contains non-ASCII characters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (
            secretUse.missingSecrets.isNotEmpty() &&
            secretUse.completionResult != InvocationCompletionResult.DENIED
        ) {
            Notice(
                "Secrets are unavailable",
                secretUse.missingSecrets.joinToString(),
                NoticeTone.DANGER,
            )
        }

        if (secretUse.state == SecretUseRequestState.APPROVAL_PENDING) {
            when (secretUse.ruleEvaluation?.action) {
                ApprovalRuleAction.ASK_ME -> Notice(
                    "Your decision is required",
                    if (secretUse.ruleEvaluation.matchedRuleIds.isEmpty()) {
                        "No approval rule covers this request."
                    } else {
                        "An approval rule requires you to decide this request."
                    },
                    NoticeTone.ATTENTION,
                )
                ApprovalRuleAction.ASK_AI -> {
                    val review = secretUse.ruleEvaluation.aiReview
                    when {
                        review?.decision == AiReviewDecision.ASK_USER -> Notice(
                            "AI review needs your decision",
                            review.explanation ?: "The reviewer could not decide safely.",
                            NoticeTone.ATTENTION,
                        )
                        review?.failure == AiReviewFailure.SUBSCRIPTION_REQUIRED -> Notice(
                            "AI review requires a subscription",
                            "Decide this request yourself, or activate AI review in Plan and billing.",
                            NoticeTone.ATTENTION,
                        )
                        review?.failure != null -> Notice(
                            "AI review unavailable",
                            "The request was left for you to decide.",
                            NoticeTone.ATTENTION,
                        )
                        else -> Notice(
                            "AI review is pending",
                            "You can wait for the reviewer or decide this request yourself.",
                            NoticeTone.ATTENTION,
                        )
                    }
                }
                else -> Unit
            }
        }

        if (secretUse.secretDetails.isNotEmpty()) {
            Disclosure(
                title = "Secret details",
                initiallyExpanded = secretUse.state == SecretUseRequestState.APPROVAL_PENDING,
            ) {
                secretUse.secretDetails.forEach { secret -> SecretSummary(secret) }
                Text(
                    "Private keys and environment-variable values are never shown in a request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (secretUse.state != SecretUseRequestState.APPROVAL_PENDING) {
            SecretUseOutcome(secretUse)
        }

        Disclosure("Technical details") {
            DetailValue("Working directory", secretUse.workingDirectory, true)
            DetailValue("Executable path", secretUse.executablePath, true)
            secretUse.executableHash?.let { DetailValue("Executable hash", it, true) }
            DetailValue("Executable mode", secretUse.executableMode)
            DetailValue("Standard input", secretUse.stdinKind)
            DetailValue("Standard output", secretUse.stdoutKind)
            DetailValue("Standard error", secretUse.stderrKind)
            if (secretUse.launcherChain.isNotEmpty()) {
                DetailValue("Launcher chain", secretUse.launcherChain.joinToString("\n"), true)
            }
            secretUse.platform?.let {
                DetailValue("Platform reported by client", formatPlatformName(it))
            }
            secretUse.architecture?.let { DetailValue("Architecture reported by client", it) }
            secretUse.hostname?.takeIf { it != secretUse.clientName }
                ?.let { DetailValue("Hostname reported by client", it) }
            secretUse.osVersion?.let { DetailValue("OS version", it) }
            secretUse.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            secretUse.machineId?.let { DetailValue("Machine ID reported by client", it, true) }
            DetailValue("Client ID", secretUse.clientId, true)
            DetailValue("Request ID", request.relayRequestId, true)
        }
    }
}

@Composable
private fun GitSignDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier,
) {
    val signing = checkNotNull(request.gitSign)
    val pending = signing.state == GitSignRequestState.APPROVAL_PENDING
    val title = "Git signature"
    val exactContent = signing.message.displayForApproval()
    val gitCommitMessage = exactContent.substringAfter("\n\n", missingDelimiterValue = "")
        .trimEnd()
        .takeIf(String::isNotEmpty)
    DetailPage(
        title = title,
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        bottomContent = if (pending) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDeny,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.agentknockColors.danger,
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
                        ) {
                            Text("Deny")
                        }
                        Button(
                            onClick = onApprove,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.agentknockColors.success,
                                contentColor = MaterialTheme.agentknockColors.onSuccess,
                            ),
                        ) {
                            Text("Sign")
                        }
                    }
                }
            }
        } else {
            null
        },
    ) {
        InformationSurface {
            StatusLine(
                signing.statusLabel(),
                error = signing.state == GitSignRequestState.VERIFICATION_FAILED,
                attention = pending,
                subdued = signing.decision == SecretUseDecision.DENIED ||
                    signing.completionResult == GitSignCompletionResult.DENIED,
            )
            ClientIdentity(signing.clientName)
            SecretIdentities(listOf(signing.secretName))
            InformationRow("Received", formatTimestamp(request.receivedAt))
        }

        gitCommitMessage?.let { message ->
            Surface(
                color = if (pending) {
                    MaterialTheme.agentknockColors.attentionContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                contentColor = if (pending) {
                    MaterialTheme.agentknockColors.onAttentionContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Commit message", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text(message, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Triggered by", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(
                        renderShellCommand(signing.command, signing.arguments),
                        fontFamily = FontFamily.Monospace,
                    )
                }
                signing.reason?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Disclosure(
            title = if (gitCommitMessage == null) "Content to sign" else "Exact content to sign",
            initiallyExpanded = gitCommitMessage == null,
        ) {
            if (gitCommitMessage != null) {
                Text(
                    "The complete Git commit object that will be signed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(exactContent, fontFamily = FontFamily.Monospace)
            }
        }

        if (pending) {
            Text(
                "Signing uses the private key on this device. The private key is never sent to the client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            GitSignOutcome(signing)
        }

        Disclosure("Technical details") {
            signing.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            DetailValue("Client ID", signing.clientId, true)
            DetailValue("Invocation request ID", signing.invocationRequestId, true)
            DetailValue("Signing request ID", request.relayRequestId, true)
        }
    }
}

@Composable
internal fun SecretUploadRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: (String) -> Unit,
    onReject: () -> Unit,
    authorizeProtectedAction: (String, () -> Unit, (String) -> Unit) -> Unit,
    onReveal: suspend (String) -> SecretUploadVariableValue,
    onSensitivityChange: suspend (String, Boolean) -> Boolean,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val upload = checkNotNull(request.secretUpload)
    var approvedName by remember(upload.uploadedName, upload.approvedName) {
        mutableStateOf(upload.approvedName ?: upload.uploadedName)
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
        title = "Secret upload",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        bottomContent = if (upload.state == SecretUploadRequestState.REVIEW_PENDING) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = onReject,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.agentknockColors.danger,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
                            ) {
                                Text("Reject")
                            }
                            Button(
                                onClick = { onApprove(approvedName.trim()) },
                                enabled = approvedName.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.agentknockColors.success,
                                    contentColor = MaterialTheme.agentknockColors.onSuccess,
                                ),
                            ) {
                                Text("Approve")
                            }
                        }
                    }
                }
            }
        } else {
            null
        },
    ) {
        InformationSurface {
            StatusLine(
                upload.state.label(),
                upload.state == SecretUploadRequestState.VERIFICATION_FAILED,
                attention = upload.state == SecretUploadRequestState.REVIEW_PENDING,
                subdued = upload.state == SecretUploadRequestState.REJECTED,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${upload.mode.titleLabel()} $approvedName",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (
                    upload.mode == SecretUploadMode.CREATE &&
                    upload.state == SecretUploadRequestState.REVIEW_PENDING
                ) {
                    IconButton(onClick = { editingName = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Rename secret")
                    }
                }
            }
            InformationRow(
                "Type",
                if (upload.secretType == "ssh") "SSH key" else "Environment variables",
            )
            ClientIdentity(upload.clientName)
            InformationRow("Received", formatTimestamp(request.receivedAt))
            upload.description?.takeIf(String::isNotBlank)?.let {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Description reported by client",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(it)
                }
            }
        }

        if (upload.secretType == "ssh") {
            SshKeyUploadDetails(upload)
        } else {
        if (upload.mode != SecretUploadMode.CREATE) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Changes to the existing secret", style = MaterialTheme.typography.titleMedium)
                    ChangeGroup("New", upload.addedVariables)
                    ChangeGroup("Updated", upload.changedVariables)
                    ChangeGroup("Removed", upload.removedVariables)
                    ChangeGroup("Unchanged", upload.unchangedVariables, subdued = true)
                }
            }
        }

        Text(
            if (upload.state == SecretUploadRequestState.REVIEW_PENDING) {
                "Uploaded environment variables"
            } else {
                "Uploaded environment variable names"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (upload.state == SecretUploadRequestState.REVIEW_PENDING) {
            Text(
                "Values are hidden by default. You can reveal them and choose whether each " +
                    "should require device authentication after saving.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (upload.variableNames.isEmpty()) Text("No environment variables")
        if (upload.state != SecretUploadRequestState.REVIEW_PENDING) {
            SelectionContainer {
                Text(upload.variableNames.joinToString("\n"), fontFamily = FontFamily.Monospace)
            }
            Text(
                "Uploaded values were discarded after this upload was decided.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (upload.state == SecretUploadRequestState.REVIEW_PENDING) upload.variables.forEach { variable ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val value = revealedValues[variable.id]
                    val changeLabel = when {
                        variable.name in upload.addedVariables -> "New"
                        variable.name in upload.changedVariables -> "Updated"
                        else -> null
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            variable.name,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        changeLabel?.let {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = RoundedCornerShape(100.dp),
                            ) {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Sensitive", style = MaterialTheme.typography.labelMedium)
                            Text(
                                if (variable.sensitive) {
                                    "Authentication required to view after saving"
                                } else {
                                    "Visible without authentication after saving"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = variable.sensitive,
                            enabled = upload.state == SecretUploadRequestState.REVIEW_PENDING,
                            onCheckedChange = { sensitive ->
                                val change: () -> Unit = {
                                    scope.launch {
                                        if (!onSensitivityChange(variable.id, sensitive)) {
                                            report("Sensitivity could not be changed")
                                        }
                                    }
                                }
                                if (sensitive) {
                                    change()
                                } else {
                                    authorizeProtectedAction(
                                        "Mark ${variable.name} non-sensitive",
                                        change,
                                        report,
                                    )
                                }
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Sensitive handling for ${variable.name}"
                            },
                        )
                    }
                    OutlinedTextField(
                        value = value ?: "••••••••",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Uploaded value") },
                        minLines = 1,
                        maxLines = 6,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (value != null) {
                                        revealedValues -= variable.id
                                        return@IconButton
                                    }
                                    val reveal: () -> Unit = {
                                        scope.launch {
                                            when (val result = onReveal(variable.id)) {
                                                is SecretUploadVariableValue.Available -> {
                                                    revealedValues = revealedValues +
                                                        (variable.id to result.value)
                                                }
                                                SecretUploadVariableValue.NotFound ->
                                                    report("This environment variable is no longer available")
                                                SecretUploadVariableValue.Unavailable ->
                                                    report("The encryption key is unavailable")
                                                SecretUploadVariableValue.Corrupted ->
                                                    report("The uploaded value could not be authenticated")
                                                SecretUploadVariableValue.UnsupportedEncryption ->
                                                    report("The uploaded value uses unsupported encryption")
                                            }
                                        }
                                    }
                                    authorizeProtectedAction(
                                        "Show uploaded value",
                                        reveal,
                                        report,
                                    )
                                },
                            ) {
                                Icon(
                                    if (value == null) {
                                        Icons.Outlined.Visibility
                                    } else {
                                        Icons.Outlined.VisibilityOff
                                    },
                                    contentDescription = if (value == null) {
                                        "Show uploaded value for ${variable.name}"
                                    } else {
                                        "Hide uploaded value for ${variable.name}"
                                    },
                                )
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        }

        if (upload.state != SecretUploadRequestState.REVIEW_PENDING) {
            upload.approvedName?.takeIf { it != upload.uploadedName }?.let {
                DetailValue("Uploaded name", upload.uploadedName)
            }
            if (upload.state == SecretUploadRequestState.APPROVED) {
                val name = upload.approvedName ?: upload.uploadedName
                Notice(
                    "Upload approved",
                    when (upload.mode) {
                        SecretUploadMode.CREATE -> "$name was created."
                        SecretUploadMode.UPDATE -> "$name was updated."
                        SecretUploadMode.REPLACE -> "$name was replaced."
                    },
                    NoticeTone.SUCCESS,
                )
            }
            upload.error?.let {
                Notice("Upload could not be verified", it, NoticeTone.DANGER)
            }
        }

        Disclosure("Technical details") {
            upload.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            DetailValue("Client ID", upload.clientId, true)
            DetailValue("Request ID", request.relayRequestId, true)
        }
    }
    if (editingName) {
        var editedName by remember(approvedName) { mutableStateOf(approvedName) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Secret name") },
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
                        approvedName = editedName.trim()
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
private fun SshKeyUploadDetails(upload: SecretUploadRequestDetails) {
    if (upload.mode != SecretUploadMode.CREATE) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Changes to the existing SSH key", style = MaterialTheme.typography.titleMedium)
                if (upload.keyChanged) {
                    upload.previousFingerprint?.let { DetailValue("Current fingerprint", it, true) }
                    upload.fingerprint?.let { DetailValue("New fingerprint", it, true) }
                } else if (upload.publicKey != upload.previousPublicKey) {
                    Text(
                        "The public-key comment will change; the key material is unchanged.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "The key material is unchanged.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    upload.publicKey?.let { publicKey ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (upload.keyChanged || upload.mode == SecretUploadMode.CREATE) {
                        "Incoming public key"
                    } else {
                        "Public key"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                DetailValue("Algorithm", "Ed25519")
                upload.fingerprint?.let { DetailValue("Fingerprint", it, true) }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "OpenSSH public key",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            publicKey,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    } ?: Text(
        "No replacement key was included.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SecretUseOutcome(secretUse: SecretUseRequestDetails) {
    val ruleDetail = when (secretUse.decisionSource) {
        "rule" -> " An approval rule made this decision."
        "policy" -> " Approval settings made this decision."
        "ai" -> secretUse.ruleEvaluation?.aiReview?.explanation?.let { explanation ->
            " AI review made this decision: $explanation"
        } ?: " AI review made this decision."
        else -> ""
    }
    val outcome = when (secretUse.state) {
        SecretUseRequestState.WAITING_FOR_COMPLETION -> OutcomeNotice(
            title = if (secretUse.decision == SecretUseDecision.APPROVED) "Approved" else "Denied",
            detail = "Waiting for the client to finish.$ruleDetail",
            tone = if (secretUse.decision == SecretUseDecision.APPROVED) {
                NoticeTone.SUCCESS
            } else {
                NoticeTone.SUBDUED
            },
        )
        SecretUseRequestState.COMPLETED -> when (secretUse.completionResult) {
            InvocationCompletionResult.APPROVED -> OutcomeNotice(
                "Delivered",
                "The client received the secret values.$ruleDetail",
                NoticeTone.SUCCESS,
            )
            InvocationCompletionResult.DENIED -> if (
                secretUse.completionReason == "INVALID_REQUEST"
            ) {
                OutcomeNotice(
                    "Request rejected",
                    secretUse.completionMessage ?: "The request was invalid.",
                    NoticeTone.DANGER,
                )
            } else {
                OutcomeNotice(
                    "Denied",
                    (secretUse.completionMessage ?: "No values were released.") + ruleDetail,
                    NoticeTone.SUBDUED,
                )
            }
            InvocationCompletionResult.ABORTED -> OutcomeNotice(
                "Aborted",
                secretUse.completionMessage ?: "The client stopped this request.",
                NoticeTone.SUBDUED,
            )
            null -> OutcomeNotice("Completed", "The request is complete.")
        }
        SecretUseRequestState.VERIFICATION_FAILED -> OutcomeNotice(
            "Could not verify request",
            secretUse.error ?: "The cryptographic message was invalid.",
            NoticeTone.DANGER,
        )
        SecretUseRequestState.APPROVAL_PENDING -> return
    }
    Notice(outcome.title, outcome.detail, outcome.tone)
}

private data class OutcomeNotice(
    val title: String,
    val detail: String,
    val tone: NoticeTone = NoticeTone.NEUTRAL,
)

@Composable
private fun SecretSummary(secret: SecretMetadata) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(secret.name, style = MaterialTheme.typography.titleMedium)
        if (secret.description.isNotBlank()) {
            Text(secret.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (secret.type == "ssh") "SSH key" else "Environment variables",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                if (secret.type == "ssh") {
                    secret.sshPublicKey.orEmpty()
                } else {
                    secret.environmentVariableNames.joinToString("\n")
                },
                fontFamily = FontFamily.Monospace,
            )
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
    bottomContent: (@Composable () -> Unit)? = null,
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
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
        bottomContent?.invoke()
    }
}

@Composable
private fun Disclosure(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun Notice(
    title: String,
    detail: String,
    tone: NoticeTone = NoticeTone.NEUTRAL,
) {
    val semanticColors = MaterialTheme.agentknockColors
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (tone) {
                NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerLow
                NoticeTone.ATTENTION -> semanticColors.attentionContainer
                NoticeTone.SUCCESS -> semanticColors.successContainer
                NoticeTone.DANGER -> semanticColors.dangerContainer
                NoticeTone.SUBDUED -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = when (tone) {
                NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
                NoticeTone.ATTENTION -> semanticColors.onAttentionContainer
                NoticeTone.SUCCESS -> semanticColors.onSuccessContainer
                NoticeTone.DANGER -> semanticColors.onDangerContainer
                NoticeTone.SUBDUED -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail)
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    error: Boolean = false,
    attention: Boolean = false,
    subdued: Boolean = false,
) {
    val semanticColors = MaterialTheme.agentknockColors
    Surface(
        color = when {
            error -> semanticColors.dangerContainer
            attention -> semanticColors.attentionContainer
            subdued -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> semanticColors.successContainer
        },
        contentColor = when {
            error -> semanticColors.onDangerContainer
            attention -> semanticColors.onAttentionContainer
            subdued -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> semanticColors.onSuccessContainer
        },
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun RequestStatusBadge(request: InboxRequestSummary) {
    val error = request.secretUseState == SecretUseRequestState.VERIFICATION_FAILED ||
        request.gitSignState == GitSignRequestState.VERIFICATION_FAILED
    val rejected = request.wasRejected()
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    val accepted = request.wasAccepted()
    val semanticColors = MaterialTheme.agentknockColors
    Surface(
        color = when {
            error -> semanticColors.dangerContainer
            actionRequired -> semanticColors.attentionContainer
            accepted -> semanticColors.successContainer
            rejected -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = when {
            error -> semanticColors.onDangerContainer
            actionRequired -> semanticColors.onAttentionContainer
            accepted -> semanticColors.onSuccessContainer
            rejected -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            request.statusLabel(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
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
        Notice(
            "Request unavailable",
            "This request has no displayable details.",
            NoticeTone.DANGER,
        )
    }
}

private enum class NoticeTone {
    NEUTRAL,
    ATTENTION,
    SUCCESS,
    DANGER,
    SUBDUED,
}

private fun InboxRequestSummary.statusLabel(): String = when {
    secretUseState != null -> secretUseStatusLabel(
        secretUseState,
        secretUseResult,
        secretUseCompletionReason,
    )
    gitSignState != null -> gitSignStatusLabel(
        gitSignState,
        gitSignResult,
        gitSignCompletionReason,
    )
    state == InboxRequestState.ACTION_REQUIRED -> "Action required"
    state == InboxRequestState.WAITING -> "Waiting"
    else -> "Completed"
}

private fun InboxRequestSummary.canReject(): Boolean =
    secretUseState == SecretUseRequestState.APPROVAL_PENDING ||
        gitSignState == GitSignRequestState.APPROVAL_PENDING

private fun InboxRequestSummary.wasRejected(): Boolean =
    secretUseDecision == SecretUseDecision.DENIED ||
        secretUseResult == InvocationCompletionResult.DENIED ||
        gitSignDecision == SecretUseDecision.DENIED ||
        gitSignResult == GitSignCompletionResult.DENIED

private fun InboxRequestSummary.wasAccepted(): Boolean =
    secretUseResult == InvocationCompletionResult.APPROVED ||
        gitSignResult == GitSignCompletionResult.APPROVED

private fun InboxRequestSummary.hasInvalidSecretReference(): Boolean =
    secretUseCompletionReason == "INVALID_REQUEST" ||
        gitSignCompletionReason == "INVALID_REQUEST"

private fun GitSignRequestDetails.statusLabel(): String = gitSignStatusLabel(
    state,
    completionResult,
    completionReason,
)

private fun gitSignStatusLabel(
    state: GitSignRequestState,
    result: GitSignCompletionResult?,
    completionReason: String?,
): String = when (state) {
    GitSignRequestState.APPROVAL_PENDING -> "Action required"
    GitSignRequestState.WAITING_FOR_COMPLETION -> "Waiting for client"
    GitSignRequestState.VERIFICATION_FAILED -> "Verification failed"
    GitSignRequestState.COMPLETED -> when (result) {
        GitSignCompletionResult.APPROVED -> "Signed"
        GitSignCompletionResult.DENIED -> if (completionReason == "INVALID_REQUEST") {
            "Invalid request"
        } else {
            "Denied"
        }
        GitSignCompletionResult.ABORTED -> "Aborted"
        null -> "Completed"
    }
}

@Composable
private fun GitSignOutcome(signing: GitSignRequestDetails) {
    when {
        signing.state == GitSignRequestState.VERIFICATION_FAILED -> Notice(
            "Signature could not be confirmed",
            signing.error ?: "The client confirmation was invalid.",
            NoticeTone.DANGER,
        )
        signing.completionResult == GitSignCompletionResult.APPROVED -> Notice(
            "Content signed",
            "The signature was delivered to the client.",
            NoticeTone.SUCCESS,
        )
        signing.completionResult == GitSignCompletionResult.DENIED -> Notice(
            if (signing.completionReason == "INVALID_REQUEST") {
                "Invalid request"
            } else {
                "Signature denied"
            },
            signing.completionMessage ?: "No signature was created.",
            NoticeTone.SUBDUED,
        )
        signing.completionResult == GitSignCompletionResult.ABORTED -> Notice(
            "Request ended",
            signing.completionMessage ?: "The client ended the signing request.",
            NoticeTone.NEUTRAL,
        )
        signing.state == GitSignRequestState.WAITING_FOR_COMPLETION &&
            signing.decision == SecretUseDecision.APPROVED -> Notice(
            "Signature sent",
            "Waiting for the client to confirm receipt.",
            NoticeTone.SUCCESS,
        )
        signing.state == GitSignRequestState.WAITING_FOR_COMPLETION -> Notice(
            "Signature denied",
            signing.completionMessage ?: "Waiting for the client to confirm the denial.",
            NoticeTone.SUBDUED,
        )
    }
}

private fun ByteArray.displayForApproval(): String {
    val text = runCatching { decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    if (text != null && text.all { character ->
            character == '\n' || character == '\r' || character == '\t' ||
                !character.isISOControl()
        }
    ) {
        return text
    }
    return toList().chunked(16).joinToString("\n") { row ->
        row.joinToString(" ") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private fun PairingState.label(): String = when (this) {
    PairingState.RECEIVING -> "Receiving"
    PairingState.SAS_VERIFICATION_PENDING -> "Action required"
    PairingState.RELAY_ACTIVATION_PENDING -> "Activating"
    PairingState.WAITING_FOR_FINISH -> "Waiting for client"
    PairingState.REJECTED -> "Rejected"
    PairingState.ACTIVE -> "Completed"
    PairingState.VERIFICATION_FAILED -> "Verification failed"
}

private fun PairingState.isError(): Boolean = this == PairingState.VERIFICATION_FAILED

private fun SecretUseRequestDetails.statusLabel(): String = secretUseStatusLabel(
    state,
    completionResult,
    completionReason,
)

private fun secretUseStatusLabel(
    state: SecretUseRequestState,
    result: InvocationCompletionResult?,
    completionReason: String?,
): String = when (state) {
    SecretUseRequestState.APPROVAL_PENDING -> "Action required"
    SecretUseRequestState.WAITING_FOR_COMPLETION -> "Waiting for client"
    SecretUseRequestState.VERIFICATION_FAILED -> "Verification failed"
    SecretUseRequestState.COMPLETED -> when (result) {
        InvocationCompletionResult.APPROVED -> "Delivered"
        InvocationCompletionResult.DENIED -> if (completionReason == "INVALID_REQUEST") {
            "Invalid request"
        } else {
            "Denied"
        }
        InvocationCompletionResult.ABORTED -> "Aborted"
        null -> "Completed"
    }
}

private fun SecretUseRequestDetails.isError(): Boolean =
    state == SecretUseRequestState.VERIFICATION_FAILED || completionReason == "INVALID_REQUEST"

private fun SecretUploadRequestState.label(): String = when (this) {
    SecretUploadRequestState.REVIEW_PENDING -> "Action required"
    SecretUploadRequestState.APPROVED -> "Approved"
    SecretUploadRequestState.REJECTED -> "Rejected"
    SecretUploadRequestState.VERIFICATION_FAILED -> "Verification failed"
}

private fun SecretUploadMode.titleLabel(): String = when (this) {
    SecretUploadMode.CREATE -> "Create"
    SecretUploadMode.REPLACE -> "Replace"
    SecretUploadMode.UPDATE -> "Update"
}

internal fun PairingDecisionResult.message(): String = when (this) {
    PairingDecisionResult.VERIFIED -> "Pairing code verified"
    PairingDecisionResult.REJECTED -> "Pairing rejected"
    PairingDecisionResult.NOT_PENDING -> "This pairing no longer needs a decision"
    PairingDecisionResult.NOT_FOUND -> "Request is no longer available"
}

private fun SecretUseDecisionResult.message(): String = when (this) {
    SecretUseDecisionResult.Decided -> "Decision saved"
    SecretUseDecisionResult.SecretsChanged -> "A requested secret changed; review the request again"
    SecretUseDecisionResult.NotPending -> "This request no longer needs a decision"
    SecretUseDecisionResult.NotFound -> "Request is no longer available"
    is SecretUseDecisionResult.MissingSecrets -> "Missing secrets: ${names.joinToString()}"
    is SecretUseDecisionResult.ConflictingVariable ->
        "Conflicting environment variable: $name"
    is SecretUseDecisionResult.Invalid -> message
    SecretUseDecisionResult.SecretUnavailable -> "A secret value is unavailable on this device"
    SecretUseDecisionResult.SecretCorrupted -> "A secret value could not be authenticated"
    SecretUseDecisionResult.UnsupportedEncryption -> "A secret value uses unsupported encryption"
    SecretUseDecisionResult.PairingUnavailable -> "The paired client is unavailable"
}

private fun GitSignDecisionResult.message(): String = when (this) {
    GitSignDecisionResult.Decided -> "Decision saved"
    GitSignDecisionResult.NotPending -> "This signing request no longer needs a decision"
    GitSignDecisionResult.NotFound -> "Signing request is no longer available"
    GitSignDecisionResult.InvocationUnavailable -> "The original command request is unavailable"
    GitSignDecisionResult.PairingUnavailable -> "The paired client is unavailable"
    GitSignDecisionResult.KeyChanged ->
        "The SSH key changed or was renamed after the command began; start the command again"
    GitSignDecisionResult.SecretUnavailable -> "The SSH private key is unavailable on this device"
    GitSignDecisionResult.SecretCorrupted -> "The SSH private key could not be authenticated"
    GitSignDecisionResult.UnsupportedEncryption ->
        "The SSH private key uses unsupported encryption"
}

internal fun SecretUploadDecisionResult.message(): String = when (this) {
    is SecretUploadDecisionResult.Approved -> "Secret upload approved"
    SecretUploadDecisionResult.Rejected -> "Secret upload rejected"
    SecretUploadDecisionResult.NotPending -> "This upload no longer needs a decision"
    SecretUploadDecisionResult.NotFound -> "Upload is no longer available"
    is SecretUploadDecisionResult.Invalid -> message
    SecretUploadDecisionResult.SecretUnavailable -> "An uploaded value is unavailable on this device"
    SecretUploadDecisionResult.SecretCorrupted -> "An uploaded value could not be authenticated"
    SecretUploadDecisionResult.UnsupportedEncryption -> "An uploaded value uses unsupported encryption"
}

private fun RequestSyncResult?.problemMessage(): String? = when (this) {
    null, RequestSyncResult.Success, RequestSyncResult.NoDevice -> null
    RequestSyncResult.DeviceCredentialsUnavailable -> "Device keys are unavailable"
    RequestSyncResult.DeviceCredentialsCorrupted -> "Device keys could not be verified"
    RequestSyncResult.UnsupportedDeviceCredentialEncryption -> "Device keys use unsupported encryption"
    is RequestSyncResult.RelayRejected -> message ?: "The relay rejected the connection"
    is RequestSyncResult.RelayUnavailable -> message ?: "The relay is temporarily unavailable"
    RequestSyncResult.InvalidRelayResponse -> "The relay returned an invalid response"
}
