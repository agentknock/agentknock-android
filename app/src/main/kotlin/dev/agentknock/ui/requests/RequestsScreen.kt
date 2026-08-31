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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Key
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.protocol.GitSignChangeStatus
import dev.agentknock.protocol.GitSignHead
import dev.agentknock.protocol.GitSignRepository
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.describeGitSigningContent
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
import dev.agentknock.storage.request.SshAuthenticationCompletionResult
import dev.agentknock.storage.request.SshAuthenticationDecisionResult
import dev.agentknock.storage.request.SshAuthenticationRequestDetails
import dev.agentknock.storage.request.SshAuthenticationRequestState
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.request.SecretUploadDecisionResult
import dev.agentknock.storage.request.SecretUploadRequestDetails
import dev.agentknock.storage.request.SecretUploadRequestState
import dev.agentknock.storage.request.SecretUploadVariableValue
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.components.ClientIdentity
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.SecretIdentities
import dev.agentknock.ui.components.NavigationBackButton
import dev.agentknock.ui.theme.agentknockColors
import kotlinx.coroutines.launch

@Composable
internal fun RequestsScreen(
    onOpenSettings: () -> Unit,
    notificationsEnabled: Boolean,
    onTopLevelChanged: (Boolean) -> Unit,
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
                request.sshAuthenticationState ==
                    SshAuthenticationRequestState.APPROVAL_PENDING -> {
                    report(viewModel.approveSshAuthenticationRequest(request.id).message())
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
                request.sshAuthenticationState ==
                    SshAuthenticationRequestState.APPROVAL_PENDING -> {
                    report(viewModel.denySshAuthenticationRequest(request.id).message())
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // The app-wide navigation rail already consumes part of an expanded window.
            // Switch on the remaining content width so unfolded phones get a useful
            // list-detail layout without hovering around the breakpoint.
            val twoPane = maxWidth >= 720.dp
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
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
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
            onAllowTemporarily = {
                scope.launch {
                    report(viewModel.allowSecretUseTemporarily(request.id).message())
                }
            },
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
            onAllowTemporarily = {
                scope.launch {
                    report(viewModel.allowGitSignTemporarily(request.id).message())
                }
            },
            modifier = modifier,
        )
    } else if (request.sshAuthentication != null) {
        SshAuthenticationDetail(
            request = request,
            onBack = onBack,
            showBack = showBack,
            onApprove = {
                scope.launch {
                    report(viewModel.approveSshAuthenticationRequest(request.id).message())
                }
            },
            onDeny = {
                scope.launch {
                    report(viewModel.denySshAuthenticationRequest(request.id).message())
                }
            },
            onAllowTemporarily = {
                scope.launch {
                    report(viewModel.allowSshAuthenticationTemporarily(request.id).message())
                }
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
    selectedRequestId: String?,
    syncing: Boolean,
    syncProblem: String?,
    onRefresh: () -> Unit,
    onShowSyncProblem: (String) -> Unit,
    onOpenSettings: () -> Unit,
    notificationsEnabled: Boolean,
    onOpen: (String) -> Unit,
    onApprove: (InboxRequestSummary) -> Unit,
    onReject: (InboxRequestSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var previousNewestRequestId by remember { mutableStateOf<String?>(null) }
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
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    syncProblem?.let { problem ->
                        IconButton(onClick = { onShowSyncProblem(problem) }) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = "Connection problem",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
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
                    Text("No requests yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Secret use, Git signing, and SSH authentication requests will appear here.",
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
    val canApprove = request.userDecisionAvailable && (
        request.secretUseState == SecretUseRequestState.APPROVAL_PENDING ||
            request.gitSignState == GitSignRequestState.APPROVAL_PENDING ||
            request.sshAuthenticationState ==
            SshAuthenticationRequestState.APPROVAL_PENDING
        )
    val canReject = request.canReject() && (
        request.userDecisionAvailable ||
            request.secretUseState == null && request.gitSignState == null
                && request.sshAuthenticationState == null
        )
    val rejectLabel = "Deny once"
    val swipeState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.65f },
    )
    LaunchedEffect(swipeState.currentValue) {
        val completedSwipe = swipeState.currentValue
        if (completedSwipe == SwipeToDismissBoxValue.Settled) return@LaunchedEffect

        when (completedSwipe) {
            SwipeToDismissBoxValue.StartToEnd -> if (canApprove) onApprove()
            SwipeToDismissBoxValue.EndToStart -> if (canReject) onReject()
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        // The decision runs in its own UI coroutine. Snap back synchronously while it starts;
        // an animated reset can be interrupted by the backing-list update and strand the row.
        swipeState.snapTo(SwipeToDismissBoxValue.Settled)
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
    val subdued = request.wasRejected() || request.wasAborted() || request.hasVerificationFailure()
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED &&
        request.userDecisionAvailable
    val semanticColors = MaterialTheme.agentknockColors
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        subdued -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        color = containerColor,
        contentColor = when {
            subdued && !selected -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier.width(5.dp).fillMaxHeight().background(
                    if (actionRequired) semanticColors.attentionAccent else Color.Transparent,
                ),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
            request.command?.takeUnless {
                request.kind == InboxRequestKind.GIT_SIGN ||
                    request.kind == InboxRequestKind.SSH_AUTHENTICATE
            }?.let {
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (
                (request.kind == InboxRequestKind.GIT_SIGN ||
                    request.kind == InboxRequestKind.SSH_AUTHENTICATE) &&
                request.command != null
            ) {
                Text(
                    "Triggered by ${renderShellCommand(request.command, request.arguments)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    DetailPage(
        title = "Pairing",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = pairing.pairingState,
    ) {
        InformationSurface {
            StatusLine(
                pairing.pairingState.label(),
                pairing.error != null,
                attention = request.state == InboxRequestState.ACTION_REQUIRED,
                subdued = pairing.pairingState == PairingState.REJECTED,
            )
            InformationRow("Client", pairing.clientName)
            val reported = listOfNotNull(
                pairing.hostname,
                pairing.platform?.let(::formatPlatformName),
                pairing.architecture,
            ).joinToString(" · ")
            if (reported.isNotEmpty()) {
                InformationRow("Machine", reported)
            }
            InformationRow("Received", formatTimestamp(request.receivedAt))
            pairing.decidedAt?.let {
                InformationRow("Code accepted", formatTimestamp(it))
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
                "Code verified",
                "Finishing the secure pairing with this client.",
                NoticeTone.ATTENTION,
            )
            PairingState.COMPLETED -> Notice(
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
        }
        if (
            pairing.pairingState in setOf(
                PairingState.RECEIVING,
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
            DetailValue("Request ID", request.id, true)
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
    onAllowTemporarily: () -> Unit,
    modifier: Modifier,
) {
    val secretUse = checkNotNull(request.secretUse)
    var confirmTemporaryAccess by remember(request.id) { mutableStateOf(false) }
    val aiReviewInFlight = !request.userDecisionAvailable
    val temporarySecretNames = secretUse.approvalEvaluation.temporaryGrantSecretNames(
        aiReviewInFlight,
    )
    DetailPage(
        title = "Secret use",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = secretUse.state to secretUse.completionResult,
        bottomContent = if (
            secretUse.state == SecretUseRequestState.APPROVAL_PENDING &&
            request.userDecisionAvailable
        ) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    RequestDecisionButtons(
                        approveLabel = "Approve once",
                        approveEnabled = secretUse.missingSecrets.isEmpty(),
                        temporaryAccessAvailable = temporarySecretNames.isNotEmpty() &&
                            secretUse.missingSecrets.isEmpty(),
                        onDeny = onDeny,
                        onApprove = onApprove,
                        onAllowTemporarily = { confirmTemporaryAccess = true },
                    )
                }
            }
        } else {
            null
        },
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val secretCount = secretUse.secrets.size
                    val secretLabel = if (secretCount == 1) "secret" else "secrets"
                    val headline = when {
                        secretUse.state == SecretUseRequestState.COMPLETED &&
                            secretUse.completionResult == InvocationCompletionResult.APPROVED ->
                            "${secretUse.clientName} used $secretCount $secretLabel"
                        secretUse.state == SecretUseRequestState.COMPLETED ->
                            "${secretUse.clientName} requested $secretCount $secretLabel"
                        else ->
                            "${secretUse.clientName} requests $secretCount $secretLabel"
                    }
                    Text(
                        headline,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    StatusLine(
                        if (aiReviewInFlight) "AI review in progress" else secretUse.statusLabel(),
                        secretUse.isError(),
                        attention = secretUse.state == SecretUseRequestState.APPROVAL_PENDING &&
                            request.userDecisionAvailable,
                        subdued = aiReviewInFlight ||
                            secretUse.decision == SecretUseDecision.DENIED ||
                            secretUse.completionResult == InvocationCompletionResult.DENIED ||
                            secretUse.completionResult == InvocationCompletionResult.ABORTED,
                    )
                    Text(
                        "Received ${formatTimestamp(request.receivedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (secretUse.secretDetails.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Requested secrets", style = MaterialTheme.typography.titleMedium)
                InformationSurface(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    secretUse.secretDetails.forEachIndexed { index, secret ->
                        SecretSummary(secret, secretUse.environmentVariables[secret.name])
                        if (index != secretUse.secretDetails.lastIndex) HorizontalDivider()
                    }
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
            val evaluation = secretUse.approvalEvaluation
            if (
                evaluation?.aiReview != null ||
                evaluation?.secrets?.any { it.action == ApprovalAction.ASK_AI } == true
            ) {
                AiReviewNotice(evaluation.aiReview, aiReviewInFlight)
            }
        }

        if (secretUse.state != SecretUseRequestState.APPROVAL_PENDING) {
            SecretUseOutcome(secretUse)
        }

        secretUse.reason?.takeIf(String::isNotBlank)?.let { reason ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Why this command says it needs access", style = MaterialTheme.typography.labelLarge)
                    Text(reason, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Reported by the requesting client; not verified by Agentknock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
            DetailValue("Request ID", request.id, true)
        }
    }
    if (confirmTemporaryAccess) {
        TemporaryAccessConfirmation(
            clientName = secretUse.clientName,
            secretNames = temporarySecretNames,
            operation = TemporaryAccessOperation.INVOCATION,
            approvesOtherUsesOnce = secretUse.approvalEvaluation
                ?.secrets
                ?.any { it.secretName !in temporarySecretNames && it.action != ApprovalAction.APPROVE }
                == true,
            onConfirm = {
                confirmTemporaryAccess = false
                onAllowTemporarily()
            },
            onDismiss = { confirmTemporaryAccess = false },
        )
    }
}

@Composable
private fun AiReviewNotice(review: AiReview?, reviewInFlight: Boolean) {
    when {
        review?.decision == AiReviewDecision.APPROVE -> Notice(
            "AI review approved its part",
            review.explanationText() ?: "Another protected use still needs your decision.",
            NoticeTone.SUCCESS,
        )
        review?.decision == AiReviewDecision.ASK_USER -> Notice(
            "AI review asked you to decide",
            review.explanationText() ?: "The reviewer could not decide safely.",
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
        reviewInFlight -> Unit
        else -> Notice(
            "AI review was interrupted",
            "Decide this request yourself.",
            NoticeTone.ATTENTION,
        )
    }
}

@Composable
private fun RequestDecisionButtons(
    approveLabel: String,
    approveEnabled: Boolean,
    temporaryAccessAvailable: Boolean,
    onDeny: () -> Unit,
    onApprove: () -> Unit,
    onAllowTemporarily: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                enabled = approveEnabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.agentknockColors.success,
                    contentColor = MaterialTheme.agentknockColors.onSuccess,
                ),
            ) {
                Text(approveLabel)
            }
        }
        if (temporaryAccessAvailable) {
            FilledTonalButton(
                onClick = onAllowTemporarily,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Allow for 4 hours")
            }
        }
    }
}

@Composable
private fun TemporaryAccessConfirmation(
    clientName: String,
    secretNames: List<String>,
    operation: TemporaryAccessOperation,
    approvesOtherUsesOnce: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow $clientName for 4 hours?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(temporaryAccessScope(clientName, operation))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 144.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        secretNames.forEach { secretName ->
                            Text("• $secretName", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    "Agentknock will not ask you or AI about these uses for the next 4 hours.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (approvesOtherUsesOnce) {
                    Text(
                        "Other protected uses in this request are approved once and do not gain temporary access.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Allow 4 hours") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun temporaryAccessScope(
    clientName: String,
    operation: TemporaryAccessOperation,
): String = when (operation) {
    TemporaryAccessOperation.INVOCATION ->
        "For any command, $clientName can receive protected values from:"
    TemporaryAccessOperation.GIT_SIGN ->
        "For any repository, $clientName can request Git signatures from:"
    TemporaryAccessOperation.SSH_AUTHENTICATE ->
        "For any SSH server, $clientName can request SSH authentication from:"
}

private fun ApprovalEvaluation?.temporaryGrantSecretNames(
    aiReviewInFlight: Boolean,
): List<String> {
    val evaluation = this ?: return emptyList()
    if (aiReviewInFlight) return emptyList()
    val aiCanEscalate = evaluation.aiReview?.decision == AiReviewDecision.ASK_USER ||
        evaluation.aiReview?.failure != null ||
        evaluation.aiReview == null
    return evaluation.secrets.filter { secret ->
        secret.temporaryAccessEligible && secret.temporaryAccessExpiresAt == null &&
            (secret.action == ApprovalAction.ASK_ME ||
                (secret.action == ApprovalAction.ASK_AI && aiCanEscalate))
    }.map { it.secretName }
}

@Composable
private fun GitSignDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAllowTemporarily: () -> Unit,
    modifier: Modifier,
) {
    val signing = checkNotNull(request.gitSign)
    val pending = signing.state == GitSignRequestState.APPROVAL_PENDING
    var confirmTemporaryAccess by remember(request.id) { mutableStateOf(false) }
    val aiReviewInFlight = !request.userDecisionAvailable
    val temporarySecretNames = signing.approvalEvaluation.temporaryGrantSecretNames(
        aiReviewInFlight,
    )
    val signingContent = describeGitSigningContent(signing.message)
    val title = signingContent.requestTitle
    val exactContent = signing.message.displayForApproval()
    val gitMessage = signingContent.message
    DetailPage(
        title = title,
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = signing.state to signing.completionResult,
        bottomContent = if (pending && request.userDecisionAvailable) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    RequestDecisionButtons(
                        approveLabel = "Sign once",
                        approveEnabled = true,
                        temporaryAccessAvailable = temporarySecretNames.isNotEmpty(),
                        onDeny = onDeny,
                        onApprove = onApprove,
                        onAllowTemporarily = { confirmTemporaryAccess = true },
                    )
                }
            }
        } else {
            null
        },
    ) {
        InformationSurface {
            StatusLine(
                if (aiReviewInFlight) "AI review in progress" else signing.statusLabel(),
                error = signing.state == GitSignRequestState.VERIFICATION_FAILED,
                attention = pending && request.userDecisionAvailable,
                subdued = aiReviewInFlight ||
                    signing.decision == SecretUseDecision.DENIED ||
                    signing.completionResult == GitSignCompletionResult.DENIED ||
                    signing.completionResult == GitSignCompletionResult.ABORTED,
            )
            ClientIdentity(signing.clientName)
            SecretIdentities(listOf(signing.secretName))
            InformationRow("Received", formatTimestamp(request.receivedAt))
        }

        if (
            pending && (
                signing.approvalEvaluation?.aiReview != null ||
                    signing.approvalEvaluation?.secrets
                        ?.any { it.action == ApprovalAction.ASK_AI } == true
                )
        ) {
            AiReviewNotice(signing.approvalEvaluation.aiReview, aiReviewInFlight)
        }

        if (!pending) {
            GitSignOutcome(signing)
        }

        signing.repository?.takeIf(GitSignRepository::hasVisibleContext)?.let { repository ->
            GitRepositoryContext(repository)
        }

        gitMessage?.let { message ->
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
                    Text(
                        checkNotNull(signingContent.messageLabel),
                        style = MaterialTheme.typography.labelLarge,
                    )
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
                    HorizontalDivider()
                    Text(
                        "Why this command says it needs a signature",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Reported by the requesting client; not verified by Agentknock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Disclosure(
            title = if (gitMessage == null) "Content to sign" else "Exact content to sign",
            initiallyExpanded = gitMessage == null,
        ) {
            if (gitMessage != null) {
                Text(
                    "The complete Git object that will be signed.",
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
                "Git signing uses the private key on this device. The private key is never sent to the client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Disclosure("Technical details") {
            signing.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            signing.repository?.worktree?.let {
                DetailValue("Worktree reported by client", it, true)
            }
            DetailValue("Client ID", signing.clientId, true)
            DetailValue("Invocation request ID", signing.invocationRequestId, true)
            DetailValue("Signing request ID", request.id, true)
        }
    }
    if (confirmTemporaryAccess) {
        TemporaryAccessConfirmation(
            clientName = signing.clientName,
            secretNames = temporarySecretNames,
            operation = TemporaryAccessOperation.GIT_SIGN,
            approvesOtherUsesOnce = false,
            onConfirm = {
                confirmTemporaryAccess = false
                onAllowTemporarily()
            },
            onDismiss = { confirmTemporaryAccess = false },
        )
    }
}

@Composable
private fun SshAuthenticationDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAllowTemporarily: () -> Unit,
    modifier: Modifier,
) {
    val authentication = checkNotNull(request.sshAuthentication)
    val pending = authentication.state == SshAuthenticationRequestState.APPROVAL_PENDING
    val aiReviewInFlight = !request.userDecisionAvailable
    val temporarySecretNames = authentication.approvalEvaluation.temporaryGrantSecretNames(
        aiReviewInFlight,
    )
    var confirmTemporaryAccess by remember(request.id) { mutableStateOf(false) }
    DetailPage(
        title = "SSH authentication",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = authentication.state to authentication.completionResult,
        bottomContent = if (pending && request.userDecisionAvailable) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    RequestDecisionButtons(
                        approveLabel = "Authenticate once",
                        approveEnabled = true,
                        temporaryAccessAvailable = temporarySecretNames.isNotEmpty(),
                        onDeny = onDeny,
                        onApprove = onApprove,
                        onAllowTemporarily = { confirmTemporaryAccess = true },
                    )
                }
            }
        } else {
            null
        },
    ) {
        InformationSurface {
            StatusLine(
                if (aiReviewInFlight) {
                    "AI review in progress"
                } else {
                    authentication.statusLabel()
                },
                error = authentication.state ==
                    SshAuthenticationRequestState.VERIFICATION_FAILED,
                attention = pending && request.userDecisionAvailable,
                subdued = aiReviewInFlight ||
                    authentication.decision == SecretUseDecision.DENIED ||
                    authentication.completionResult ==
                    SshAuthenticationCompletionResult.DENIED ||
                    authentication.completionResult ==
                    SshAuthenticationCompletionResult.ABORTED,
            )
            ClientIdentity(authentication.clientName)
            SecretIdentities(listOf(authentication.secretName))
            InformationRow("Received", formatTimestamp(request.receivedAt))
        }

        if (
            pending && (
                authentication.approvalEvaluation?.aiReview != null ||
                    authentication.approvalEvaluation?.secrets
                        ?.any { it.action == ApprovalAction.ASK_AI } == true
                )
        ) {
            AiReviewNotice(authentication.approvalEvaluation.aiReview, aiReviewInFlight)
        }
        if (!pending) SshAuthenticationOutcome(authentication)

        InformationSurface {
            InformationRow("Remote account", authentication.username)
            InformationRow(
                "Method",
                when (authentication.method.wireName) {
                    "publickey" -> "Public-key authentication"
                    else -> "Host-bound public-key authentication"
                },
            )
            InformationRow("Signature", authentication.algorithm.wireName)
            authentication.hostKeyAlgorithm?.let { InformationRow("Host key", it) }
            authentication.hostKeyFingerprint?.let {
                InformationRow("Host fingerprint", it, monospace = true)
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
                        renderShellCommand(authentication.command, authentication.arguments),
                        fontFamily = FontFamily.Monospace,
                    )
                }
                authentication.reason?.takeIf(String::isNotBlank)?.let {
                    HorizontalDivider()
                    Text(
                        "Why this command says it needs the key",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Reported by the requesting client; not verified by Agentknock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (pending) {
            Text(
                "Agentknock signs the SSH authentication request on this device. " +
                    "The private key is never sent to the client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Disclosure("Technical details") {
            authentication.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            DetailValue("Client ID", authentication.clientId, true)
            DetailValue("Invocation request ID", authentication.invocationRequestId, true)
            DetailValue("Authentication request ID", request.id, true)
        }
    }
    if (confirmTemporaryAccess) {
        TemporaryAccessConfirmation(
            clientName = authentication.clientName,
            secretNames = temporarySecretNames,
            operation = TemporaryAccessOperation.SSH_AUTHENTICATE,
            approvesOtherUsesOnce = false,
            onConfirm = {
                confirmTemporaryAccess = false
                onAllowTemporarily()
            },
            onDismiss = { confirmTemporaryAccess = false },
        )
    }
}

@Composable
private fun GitRepositoryContext(repository: GitSignRepository) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Repository", style = MaterialTheme.typography.labelLarge)
            (repository.remote ?: repository.worktree)?.let {
                SelectionContainer {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
            }
            repository.head?.let { head ->
                Text(
                    when (head) {
                        is GitSignHead.Branch -> buildString {
                            append("Branch ")
                            append(head.name)
                            head.upstream?.let {
                                append(" · upstream ")
                                append(it)
                            }
                        }
                        GitSignHead.Detached -> "Detached HEAD"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            repository.changedPathCount?.let { count ->
                Text(
                    if (count == 1L) "1 changed file" else "$count changed files",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            repository.changedPaths?.takeIf { it.isNotEmpty() }?.forEach { path ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        when (path.status) {
                            GitSignChangeStatus.ADDED -> "A"
                            GitSignChangeStatus.DELETED -> "D"
                            GitSignChangeStatus.MODIFIED -> "M"
                            GitSignChangeStatus.TYPE_CHANGED -> "T"
                        },
                        modifier = Modifier.width(16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                    SelectionContainer {
                        Text(path.path, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

private fun GitSignRepository.hasVisibleContext(): Boolean =
    remote != null || worktree != null || head != null ||
        changedPathCount != null || changedPaths != null

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
        scrollResetKey = upload.state,
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
                    approvedName,
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
                        "Description",
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                "Values are hidden by default. Reveal any value you want to inspect, and " +
                    "choose which values remain sensitive after saving.",
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
                                    "Protected by approval settings"
                                } else {
                                    "Provided without approval"
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
            DetailValue("Request ID", request.id, true)
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
                    enabled = editedName.isNotBlank() && editedName.trim() != approvedName,
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
                        "The public key comment will change; the key material is unchanged.",
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
                DetailValue(
                    "Algorithm",
                    when (publicKey.substringBefore(' ')) {
                        "ssh-ed25519" -> "Ed25519"
                        "ssh-rsa" -> "RSA"
                        else -> publicKey.substringBefore(' ')
                    },
                )
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
    val temporaryAccessScopes = secretUse.approvalEvaluation.temporaryAccessHistory()
    val aiReview = secretUse.approvalEvaluation?.aiReview
    val outcome = when (secretUse.state) {
        SecretUseRequestState.WAITING_FOR_COMPLETION -> OutcomeNotice(
            title = if (secretUse.decision == SecretUseDecision.APPROVED) "Approved" else "Denied",
            detail = "Waiting for the client to finish.",
            tone = if (secretUse.decision == SecretUseDecision.APPROVED) {
                NoticeTone.SUCCESS
            } else {
                NoticeTone.SUBDUED
            },
        )
        SecretUseRequestState.COMPLETED -> when (secretUse.completionResult) {
            InvocationCompletionResult.APPROVED -> OutcomeNotice(
                "Delivered",
                "The client received the secret values.",
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
                    "No requested values were released.",
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
    if (shouldShowDecisionHistory(
            verificationFailed = secretUse.state == SecretUseRequestState.VERIFICATION_FAILED,
            completionReason = secretUse.completionReason,
        )
    ) {
        SecretUseDecisionHistory(
            decision = secretUse.decision,
            decisionSource = secretUse.decisionSource,
            aiReview = aiReview,
            temporaryAccessScopes = temporaryAccessScopes,
        )
    }
}

@Composable
private fun SecretUseDecisionHistory(
    decision: SecretUseDecision?,
    decisionSource: String?,
    aiReview: AiReview?,
    temporaryAccessScopes: String,
) {
    val approved = decision == SecretUseDecision.APPROVED
    when (decisionSource) {
        "rule" -> Notice(
            if (approved) "Approved by previous access" else "Denied by previous access",
            "A previously saved approval made this decision.",
            if (approved) NoticeTone.SUCCESS else NoticeTone.SUBDUED,
        )
        "policy" -> Notice(
            if (approved) "Approved automatically" else "Denied automatically",
            "Approval settings made this decision.",
            if (approved) NoticeTone.SUCCESS else NoticeTone.SUBDUED,
        )
        "ai" -> HistoricalAiReview(aiReview, decision)
        "temporary_access" -> {
            Notice(
                "Temporary access allowed",
                if (temporaryAccessScopes.isEmpty()) {
                    "A current temporary approval allowed this use."
                } else {
                    "$temporaryAccessScopes."
                },
                NoticeTone.SUCCESS,
            )
            HistoricalAiReview(aiReview, decision, "You allowed temporary access.")
        }
        "mixed" -> {
            Notice(
                "Multiple approvals used",
                if (temporaryAccessScopes.isEmpty()) {
                    "Temporary access allowed part of this use."
                } else {
                    "Temporary access allowed part of this use: $temporaryAccessScopes."
                },
                NoticeTone.SUCCESS,
            )
            HistoricalAiReview(aiReview, decision)
        }
        "non_sensitive" -> Notice(
            "No approval needed",
            "This request used only non-sensitive data.",
            NoticeTone.NEUTRAL,
        )
        else -> HistoricalAiReview(aiReview, decision)
    }
}

@Composable
private fun HistoricalAiReview(
    review: AiReview?,
    decision: SecretUseDecision?,
    humanResolution: String? = null,
) {
    review ?: return
    val explanation = review.explanationText()
    when (review.decision) {
        AiReviewDecision.APPROVE -> Notice(
            "AI review approved",
            explanation ?: "AI review allowed its part of this use.",
            NoticeTone.SUCCESS,
        )
        AiReviewDecision.DENY -> Notice(
            "AI review denied",
            explanation ?: "AI review denied its part of this use.",
            NoticeTone.SUBDUED,
        )
        AiReviewDecision.ASK_USER -> {
            val resolution = humanResolution ?: when (decision) {
                SecretUseDecision.APPROVED -> "You approved it once."
                SecretUseDecision.DENIED -> "You denied it."
                null -> null
            }
            Notice(
                "AI review asked you to decide",
                listOfNotNull(explanation, resolution).joinToString(" ")
                    .ifBlank { "You made the final decision." },
                NoticeTone.NEUTRAL,
            )
        }
        null -> if (review.failure != null) {
            Notice(
                "AI review unavailable",
                humanResolution ?: "You made the final decision.",
                NoticeTone.NEUTRAL,
            )
        }
    }
}

private fun ApprovalEvaluation?.temporaryAccessHistory(): String = this?.secrets
    ?.mapNotNull { secret ->
        secret.temporaryAccessExpiresAt?.let { expiresAt ->
            "${secret.secretName} until ${formatTimestamp(expiresAt)}"
        }
    }
    ?.joinToString("; ")
    .orEmpty()

private fun AiReview.explanationText(): String? = explanation
    ?.trim()
    ?.let { text ->
        val labels = when (decision) {
            AiReviewDecision.APPROVE -> listOf("Approve:", "Approved:")
            AiReviewDecision.DENY -> listOf("Deny:", "Denied:")
            AiReviewDecision.ASK_USER -> listOf("Ask:", "Ask user:")
            null -> emptyList()
        }
        labels.firstOrNull { text.startsWith(it, ignoreCase = true) }
            ?.let { text.drop(it.length).trimStart() }
            ?: text
    }
    ?.replace("**", "")
    ?.replace("`", "")
    ?.takeIf(String::isNotEmpty)

private fun AiReview.explanationClause(): String = explanationText()
    ?.trimEnd('.', '!', '?')
    ?.let { ": $it" }
    .orEmpty()

private fun AiReview.escalationSummary(): String? = when {
    decision == AiReviewDecision.ASK_USER ->
        "AI review asked you to decide${explanationClause()}"
    failure == AiReviewFailure.SUBSCRIPTION_REQUIRED ->
        "AI review required an active subscription"
    failure != null -> "AI review was unavailable"
    else -> null
}

private data class OutcomeNotice(
    val title: String,
    val detail: String,
    val tone: NoticeTone = NoticeTone.NEUTRAL,
)

@Composable
private fun SecretSummary(
    secret: SecretMetadata,
    environmentVariables: Map<String, String?>?,
) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(secret.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (secret.type == "ssh") "SSH key" else "Environment variables",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (secret.description.isNotBlank()) {
            Text(
                secret.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (secret.type == "ssh") {
            Text(
                "The public key can be provided; private key operations remain protected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            secret.environmentVariableNames.forEach { name ->
                val deliveredName = secret.environmentVariableRename[name] ?: name
                val sentToStdin = secret.environmentVariableStdin == name
                EnvironmentVariableFact(
                    name = when {
                        sentToStdin -> "$name → standard input"
                        deliveredName != name -> "$name → $deliveredName"
                        else -> name
                    },
                    value = environmentVariables?.get(if (sentToStdin) name else deliveredName),
                )
            }
        }
    }
}

@Composable
private fun EnvironmentVariableFact(name: String, value: String?) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = with(density) { maxWidth.roundToPx() }
        val gap = with(density) { 12.dp.roundToPx() }
        val fitsOnOneLine = value == null || (
            '\n' !in value && '\r' !in value &&
                textMeasurer.measure(name, style = style, maxLines = 1).size.width +
                textMeasurer.measure(value, style = style, maxLines = 1).size.width + gap <=
                availableWidth
            )
        if (fitsOnOneLine) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = style, modifier = Modifier.weight(1f))
                if (value != null) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            value,
                            style = style,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    HiddenSensitiveValue(style)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = style)
                SelectionContainer {
                    Text(checkNotNull(value), style = style)
                }
            }
        }
    }
}

@Composable
private fun HiddenSensitiveValue(style: androidx.compose.ui.text.TextStyle) {
    Text(
        "••••••••",
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Sensitive value hidden"
        },
    )
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
    scrollResetKey: Any? = null,
    titleContent: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
    bottomContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollResetKey) {
        scrollState.scrollTo(0)
    }
    Column(modifier) {
        TopAppBar(
            title = titleContent,
                navigationIcon = {
                    if (showBack) {
                        NavigationBackButton(onBack)
                    }
                },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
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
        request.gitSignState == GitSignRequestState.VERIFICATION_FAILED ||
        request.sshAuthenticationState == SshAuthenticationRequestState.VERIFICATION_FAILED
    val rejected = request.wasRejected()
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED &&
        request.userDecisionAvailable
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
    state == InboxRequestState.REVIEWING -> "AI reviewing"
    !userDecisionAvailable && (
        secretUseState == SecretUseRequestState.APPROVAL_PENDING ||
            gitSignState == GitSignRequestState.APPROVAL_PENDING ||
            sshAuthenticationState == SshAuthenticationRequestState.APPROVAL_PENDING
        ) -> "AI reviewing"
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
    sshAuthenticationState != null -> sshAuthenticationStatusLabel(
        sshAuthenticationState,
        sshAuthenticationResult,
        sshAuthenticationCompletionReason,
    )
    state == InboxRequestState.ACTION_REQUIRED -> "Needs attention"
    state == InboxRequestState.WAITING -> "Waiting"
    else -> "Completed"
}

private fun InboxRequestSummary.canReject(): Boolean =
    secretUseState == SecretUseRequestState.APPROVAL_PENDING ||
        gitSignState == GitSignRequestState.APPROVAL_PENDING ||
        sshAuthenticationState == SshAuthenticationRequestState.APPROVAL_PENDING

private fun InboxRequestSummary.wasRejected(): Boolean =
    secretUseDecision == SecretUseDecision.DENIED ||
        secretUseResult == InvocationCompletionResult.DENIED ||
        gitSignDecision == SecretUseDecision.DENIED ||
        gitSignResult == GitSignCompletionResult.DENIED ||
        sshAuthenticationDecision == SecretUseDecision.DENIED ||
        sshAuthenticationResult == SshAuthenticationCompletionResult.DENIED

private fun InboxRequestSummary.wasAborted(): Boolean =
    secretUseResult == InvocationCompletionResult.ABORTED ||
        gitSignResult == GitSignCompletionResult.ABORTED ||
        sshAuthenticationResult == SshAuthenticationCompletionResult.ABORTED

private fun InboxRequestSummary.hasVerificationFailure(): Boolean =
    secretUseState == SecretUseRequestState.VERIFICATION_FAILED ||
        gitSignState == GitSignRequestState.VERIFICATION_FAILED ||
        sshAuthenticationState == SshAuthenticationRequestState.VERIFICATION_FAILED

private fun InboxRequestSummary.wasAccepted(): Boolean =
    secretUseResult == InvocationCompletionResult.APPROVED ||
        gitSignResult == GitSignCompletionResult.APPROVED ||
        sshAuthenticationResult == SshAuthenticationCompletionResult.APPROVED

private fun InboxRequestSummary.hasInvalidSecretReference(): Boolean =
    secretUseCompletionReason == "INVALID_REQUEST" ||
        gitSignCompletionReason == "INVALID_REQUEST" ||
        sshAuthenticationCompletionReason == "INVALID_REQUEST"

private fun GitSignRequestDetails.statusLabel(): String = gitSignStatusLabel(
    state,
    completionResult,
    completionReason,
)

private fun SshAuthenticationRequestDetails.statusLabel(): String =
    sshAuthenticationStatusLabel(state, completionResult, completionReason)

private fun sshAuthenticationStatusLabel(
    state: SshAuthenticationRequestState,
    result: SshAuthenticationCompletionResult?,
    completionReason: String?,
): String = when (state) {
    SshAuthenticationRequestState.APPROVAL_PENDING -> "Needs approval"
    SshAuthenticationRequestState.WAITING_FOR_COMPLETION -> "Waiting for client"
    SshAuthenticationRequestState.VERIFICATION_FAILED -> "Verification failed"
    SshAuthenticationRequestState.COMPLETED -> when (result) {
        SshAuthenticationCompletionResult.APPROVED -> "Authenticated"
        SshAuthenticationCompletionResult.DENIED -> if (completionReason == "INVALID_REQUEST") {
            "Invalid request"
        } else {
            "Denied"
        }
        SshAuthenticationCompletionResult.ABORTED -> "Aborted"
        null -> "Completed"
    }
}

internal fun shouldShowDecisionHistory(
    verificationFailed: Boolean,
    completionReason: String?,
): Boolean = !verificationFailed && completionReason != "INVALID_REQUEST"

private fun gitSignStatusLabel(
    state: GitSignRequestState,
    result: GitSignCompletionResult?,
    completionReason: String?,
): String = when (state) {
    GitSignRequestState.APPROVAL_PENDING -> "Needs approval"
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
    val temporaryAccessUntil = signing.approvalEvaluation?.secrets
        ?.mapNotNull { it.temporaryAccessExpiresAt }
        ?.maxOrNull()
    val aiReview = signing.approvalEvaluation?.aiReview
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
            signing.completionMessage ?: "The client ended the Git signing request.",
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
    if (shouldShowDecisionHistory(
            verificationFailed = signing.state == GitSignRequestState.VERIFICATION_FAILED,
            completionReason = signing.completionReason,
        )
    ) {
        temporaryAccessUntil?.let {
            Notice(
                "Temporary signing access",
                "Future Git signing from this client is allowed through ${formatTimestamp(it)}.",
                NoticeTone.SUCCESS,
            )
        }
        HistoricalAiReview(
            review = aiReview,
            decision = signing.decision,
            humanResolution = when {
                aiReview?.decision != AiReviewDecision.ASK_USER -> null
                signing.decision == SecretUseDecision.DENIED -> "You denied it."
                temporaryAccessUntil != null -> "You signed it and allowed temporary access."
                else -> "You signed it once."
            },
        )
    }
}

@Composable
private fun SshAuthenticationOutcome(authentication: SshAuthenticationRequestDetails) {
    val temporaryAccessUntil = authentication.approvalEvaluation?.secrets
        ?.mapNotNull { it.temporaryAccessExpiresAt }
        ?.maxOrNull()
    val aiReview = authentication.approvalEvaluation?.aiReview
    when {
        authentication.state == SshAuthenticationRequestState.VERIFICATION_FAILED -> Notice(
            "Authentication could not be confirmed",
            authentication.error ?: "The client confirmation was invalid.",
            NoticeTone.DANGER,
        )
        authentication.completionResult == SshAuthenticationCompletionResult.APPROVED -> Notice(
            "Authentication signed",
            "The SSH signature was delivered to the client.",
            NoticeTone.SUCCESS,
        )
        authentication.completionResult == SshAuthenticationCompletionResult.DENIED -> Notice(
            if (authentication.completionReason == "INVALID_REQUEST") {
                "Invalid request"
            } else {
                "Authentication denied"
            },
            authentication.completionMessage ?: "No SSH signature was created.",
            NoticeTone.SUBDUED,
        )
        authentication.completionResult == SshAuthenticationCompletionResult.ABORTED -> Notice(
            "Request ended",
            authentication.completionMessage ?: "The client ended the SSH authentication request.",
            NoticeTone.NEUTRAL,
        )
        authentication.state == SshAuthenticationRequestState.WAITING_FOR_COMPLETION &&
            authentication.decision == SecretUseDecision.APPROVED -> Notice(
            "Authentication signed",
            "Waiting for the client to confirm receipt.",
            NoticeTone.SUCCESS,
        )
        authentication.state == SshAuthenticationRequestState.WAITING_FOR_COMPLETION -> Notice(
            "Authentication denied",
            authentication.completionMessage ?: "Waiting for the client to confirm the denial.",
            NoticeTone.SUBDUED,
        )
    }
    if (shouldShowDecisionHistory(
            verificationFailed = authentication.state ==
                SshAuthenticationRequestState.VERIFICATION_FAILED,
            completionReason = authentication.completionReason,
        )
    ) {
        temporaryAccessUntil?.let {
            Notice(
                "Temporary SSH access",
                "SSH authentication from this client is allowed through ${formatTimestamp(it)}.",
                NoticeTone.SUCCESS,
            )
        }
        HistoricalAiReview(
            review = aiReview,
            decision = authentication.decision,
            humanResolution = when {
                aiReview?.decision != AiReviewDecision.ASK_USER -> null
                authentication.decision == SecretUseDecision.DENIED -> "You denied it."
                temporaryAccessUntil != null ->
                    "You authenticated it and allowed temporary access."
                else -> "You authenticated it once."
            },
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
    PairingState.SAS_VERIFICATION_PENDING -> "Verify security code"
    PairingState.RELAY_ACTIVATION_PENDING -> "Activating"
    PairingState.WAITING_FOR_FINISH -> "Waiting for client"
    PairingState.REJECTED -> "Rejected"
    PairingState.COMPLETED -> "Completed"
}

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
    SecretUseRequestState.APPROVAL_PENDING -> "Needs approval"
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
    SecretUploadRequestState.REVIEW_PENDING -> "Needs review"
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
    SecretUseDecisionResult.TemporaryAccessUnavailable ->
        "Temporary access is no longer available"
    SecretUseDecisionResult.TemporaryAccessNotStarted ->
        "Request approved once, but temporary access could not be started"
}

private fun GitSignDecisionResult.message(): String = when (this) {
    GitSignDecisionResult.Decided -> "Decision saved"
    GitSignDecisionResult.NotPending -> "This Git signing request no longer needs a decision"
    GitSignDecisionResult.NotFound -> "Git signing request is no longer available"
    GitSignDecisionResult.InvocationUnavailable -> "The original command request is unavailable"
    GitSignDecisionResult.PairingUnavailable -> "The paired client is unavailable"
    GitSignDecisionResult.ApprovalChanged ->
        "The SSH key or approval setting changed; review the request again"
    GitSignDecisionResult.KeyChanged ->
        "The SSH key changed or was renamed after the command began; start the command again"
    GitSignDecisionResult.SecretUnavailable -> "The SSH private key is unavailable on this device"
    GitSignDecisionResult.SecretCorrupted -> "The SSH private key could not be authenticated"
    GitSignDecisionResult.UnsupportedEncryption ->
        "The SSH private key uses unsupported encryption"
    GitSignDecisionResult.TemporaryAccessUnavailable ->
        "Temporary access is no longer available"
    GitSignDecisionResult.TemporaryAccessNotStarted ->
        "Signature approved once, but temporary access could not be started"
}

private fun SshAuthenticationDecisionResult.message(): String = when (this) {
    SshAuthenticationDecisionResult.Decided -> "Decision saved"
    SshAuthenticationDecisionResult.NotPending ->
        "This SSH authentication request no longer needs a decision"
    SshAuthenticationDecisionResult.NotFound ->
        "SSH authentication request is no longer available"
    SshAuthenticationDecisionResult.InvocationUnavailable ->
        "The original command request is unavailable"
    SshAuthenticationDecisionResult.PairingUnavailable -> "The paired client is unavailable"
    SshAuthenticationDecisionResult.ApprovalChanged ->
        "The SSH key or approval setting changed; review the request again"
    SshAuthenticationDecisionResult.KeyChanged ->
        "The SSH key changed or was renamed after the command began; start the command again"
    SshAuthenticationDecisionResult.InvalidMessage ->
        "The SSH authentication data changed or is invalid; start the command again"
    SshAuthenticationDecisionResult.SecretUnavailable ->
        "The SSH private key is unavailable on this device"
    SshAuthenticationDecisionResult.SecretCorrupted ->
        "The SSH private key could not be authenticated"
    SshAuthenticationDecisionResult.UnsupportedEncryption ->
        "The SSH private key uses unsupported encryption"
    SshAuthenticationDecisionResult.TemporaryAccessUnavailable ->
        "Temporary access is no longer available"
    SshAuthenticationDecisionResult.TemporaryAccessNotStarted ->
        "Authentication approved once, but temporary access could not be started"
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
    is RequestSyncResult.RelayRejected -> "The relay rejected the connection"
    is RequestSyncResult.RelayUnavailable ->
        "Couldn't connect to the relay. Check your connection and try again."
    RequestSyncResult.InvalidRelayResponse -> "The relay returned an invalid response"
}
