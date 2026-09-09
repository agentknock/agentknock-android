@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.requests

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.RequestDecision
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.ui.components.AdaptiveListDetail
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun RequestsScreen(
    onPairClient: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    notificationsEnabled: Boolean,
    onTopLevelChanged: (Boolean) -> Unit,
    viewModel: RequestsViewModel,
) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSyncResult by viewModel.lastSyncResult.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(viewModel, snackbar) {
        viewModel.messages.collectLatest { message -> snackbar.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        AdaptiveListDetail(
            hasDetail = selection != RequestPaneState.Empty,
            listWidth = 360.dp,
            onBack = { viewModel.selectRequest(null) },
            onTopLevelChanged = onTopLevelChanged,
            modifier = Modifier.fillMaxSize().padding(padding),
            list = { listModifier ->
                RequestList(
                    onPairClient = onPairClient,
                    requests = requests,
                    selectedRequestId = selection.requestId,
                    syncing = syncing,
                    syncProblem = lastSyncResult.problemMessage(),
                    onRefresh = viewModel::refresh,
                    onShowSyncProblem = ::report,
                    onOpenSettings = onOpenSettings,
                    notificationsEnabled = notificationsEnabled,
                    onOpen = viewModel::selectRequest,
                    onDecision = viewModel::decideRequest,
                    modifier = listModifier,
                )
            },
            emptyDetail = { detailModifier -> EmptyRequestSelection(detailModifier) },
            detail = { showBack, detailModifier ->
                RequestSelectionDetail(
                    selection = selection,
                    onDecision = viewModel::decideRequest,
                    onBack = { viewModel.selectRequest(null) },
                    showBack = showBack,
                    modifier = detailModifier,
                )
            },
        )
    }
}

@Composable
private fun RequestSelectionDetail(
    selection: RequestPaneState,
    onDecision: (String, RequestDecision) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    when (selection) {
        RequestPaneState.Empty,
        is RequestPaneState.Loading ->
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is RequestPaneState.Missing -> MissingRequestDetail(onBack, showBack, modifier)
        is RequestPaneState.Ready ->
            key(selection.requestId) {
                RequestDetail(
                    request = selection.request,
                    onDecision = onDecision,
                    onBack = onBack,
                    showBack = showBack,
                    modifier = modifier,
                )
            }
    }
}

@Composable
private fun RequestDetail(
    request: InboxRequestDetails,
    onDecision: (String, RequestDecision) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    when (request.content) {
        is InboxRequestContent.SecretUse ->
            InvocationRequestDetail(
                request = request,
                onBack = onBack,
                showBack = showBack,
                onApprove = { onDecision(request.id, RequestDecision.APPROVE) },
                onDeny = { onDecision(request.id, RequestDecision.DENY) },
                onAllowTemporarily = {
                    onDecision(request.id, RequestDecision.ALLOW_TEMPORARILY)
                },
                modifier = modifier,
            )
        is InboxRequestContent.GitSign ->
            GitSignRequestDetail(
                request = request,
                onBack = onBack,
                showBack = showBack,
                onApprove = { onDecision(request.id, RequestDecision.APPROVE) },
                onDeny = { onDecision(request.id, RequestDecision.DENY) },
                onAllowTemporarily = {
                    onDecision(request.id, RequestDecision.ALLOW_TEMPORARILY)
                },
                modifier = modifier,
            )
        is InboxRequestContent.SshAuthentication ->
            SshAuthenticationRequestDetail(
                request = request,
                onBack = onBack,
                showBack = showBack,
                onApprove = { onDecision(request.id, RequestDecision.APPROVE) },
                onDeny = { onDecision(request.id, RequestDecision.DENY) },
                onAllowTemporarily = {
                    onDecision(request.id, RequestDecision.ALLOW_TEMPORARILY)
                },
                modifier = modifier,
            )
        else -> MissingRequestDetail(onBack, showBack, modifier)
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

private fun RequestSyncResult?.problemMessage(): String? =
    when (this) {
        null,
        RequestSyncResult.Success,
        RequestSyncResult.NoDevice -> null
        RequestSyncResult.DeviceCredentialsUnavailable -> "Device keys are unavailable"
        RequestSyncResult.DeviceCredentialsCorrupted -> "Device keys could not be verified"
        RequestSyncResult.UnsupportedDeviceCredentialEncryption ->
            "Device keys use unsupported encryption"
        is RequestSyncResult.RelayRejected -> "The relay rejected the connection"
        is RequestSyncResult.RelayUnavailable ->
            "Couldn't connect to the relay. Check your connection and try again."
        is RequestSyncResult.InternalFailure -> "Agentknock couldn't process relay messages"
    }
