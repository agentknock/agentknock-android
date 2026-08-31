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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestStatus
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.ui.components.AdaptiveListDetail
import kotlinx.coroutines.launch

@Composable
internal fun RequestsScreen(
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

    fun approve(request: InboxRequestSummary) {
        scope.launch {
            val approval = request.status as? InboxRequestStatus.Approval
            if (approval?.state != ApprovalRequestState.APPROVAL_PENDING) return@launch
            when (request.kind) {
                InboxRequestKind.SECRET_USE -> {
                    report(viewModel.approveSecretUseRequest(request.id).message())
                }
                InboxRequestKind.GIT_SIGN -> {
                    report(viewModel.approveGitSignRequest(request.id).message())
                }
                InboxRequestKind.SSH_AUTHENTICATE -> {
                    report(viewModel.approveSshAuthenticationRequest(request.id).message())
                }
                else -> Unit
            }
        }
    }

    fun reject(request: InboxRequestSummary) {
        scope.launch {
            val approval = request.status as? InboxRequestStatus.Approval
            if (approval?.state != ApprovalRequestState.APPROVAL_PENDING) return@launch
            when (request.kind) {
                InboxRequestKind.SECRET_USE -> {
                    report(viewModel.denySecretUseRequest(request.id).message())
                }
                InboxRequestKind.GIT_SIGN -> {
                    report(viewModel.denyGitSignRequest(request.id).message())
                }
                InboxRequestKind.SSH_AUTHENTICATE -> {
                    report(viewModel.denySshAuthenticationRequest(request.id).message())
                }
                else -> Unit
            }
        }
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
                    requests = requests,
                    selectedRequestId = selection.requestId,
                    syncing = syncing,
                    syncProblem = lastSyncResult.problemMessage(),
                    onRefresh = viewModel::refresh,
                    onShowSyncProblem = ::report,
                    onOpenSettings = onOpenSettings,
                    notificationsEnabled = notificationsEnabled,
                    onOpen = viewModel::selectRequest,
                    onApprove = ::approve,
                    onReject = ::reject,
                    modifier = listModifier,
                )
            },
            emptyDetail = { detailModifier -> EmptyRequestSelection(detailModifier) },
            detail = { showBack, detailModifier ->
                RequestSelectionDetail(
                    selection = selection,
                    viewModel = viewModel,
                    report = ::report,
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
    viewModel: RequestsViewModel,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    when (selection) {
        RequestPaneState.Empty,
        is RequestPaneState.Loading,
        -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is RequestPaneState.Missing -> MissingRequestDetail(onBack, showBack, modifier)
        is RequestPaneState.Ready -> key(selection.requestId) {
            RequestDetail(
                request = selection.request,
                viewModel = viewModel,
                report = report,
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
    viewModel: RequestsViewModel,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    when (request.content) {
        is InboxRequestContent.SecretUse -> InvocationRequestDetail(
            request = request,
            onBack = onBack,
            showBack = showBack,
            onApprove = {
                scope.launch {
                    report(viewModel.approveSecretUseRequest(request.id).message())
                }
            },
            onDeny = {
                scope.launch { report(viewModel.denySecretUseRequest(request.id).message()) }
            },
            onAllowTemporarily = {
                scope.launch {
                    report(viewModel.allowSecretUseTemporarily(request.id).message())
                }
            },
            modifier = modifier,
        )
        is InboxRequestContent.GitSign -> GitSignRequestDetail(
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
        is InboxRequestContent.SshAuthentication -> SshAuthenticationRequestDetail(
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

private fun RequestSyncResult?.problemMessage(): String? = when (this) {
    null, RequestSyncResult.Success, RequestSyncResult.NoDevice -> null
    RequestSyncResult.DeviceCredentialsUnavailable -> "Device keys are unavailable"
    RequestSyncResult.DeviceCredentialsCorrupted -> "Device keys could not be verified"
    RequestSyncResult.UnsupportedDeviceCredentialEncryption ->
        "Device keys use unsupported encryption"
    is RequestSyncResult.RelayRejected -> "The relay rejected the connection"
    is RequestSyncResult.RelayUnavailable ->
        "Couldn't connect to the relay. Check your connection and try again."
}
