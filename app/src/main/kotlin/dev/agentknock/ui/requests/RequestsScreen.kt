@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.requests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.storage.request.GitSignRequestState
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.request.SecretUseRequestState
import dev.agentknock.storage.request.SshAuthenticationRequestState
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
    when {
        request.secretUse != null -> InvocationRequestDetail(
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
        request.gitSign != null -> GitSignRequestDetail(
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
        request.sshAuthentication != null -> SshAuthenticationRequestDetail(
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
