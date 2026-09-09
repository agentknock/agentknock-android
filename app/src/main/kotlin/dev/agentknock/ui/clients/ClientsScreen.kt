@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.clients

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
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.components.AdaptiveListDetail
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun ClientsScreen(
    aiReviewAccess: AiReviewAccess,
    onOpenSettings: () -> Unit,
    onChangePairingAddress: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    viewModel: ClientsViewModel,
) {
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val pendingPairings by viewModel.pendingPairings.collectAsStateWithLifecycle()
    val pane by viewModel.pane.collectAsStateWithLifecycle()
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(viewModel, snackbar) {
        viewModel.messages.collectLatest { message -> snackbar.showSnackbar(message) }
    }

    fun chooseSas(request: InboxRequestDetails, choice: Int?) =
        viewModel.chooseSas(request.id, choice)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        AdaptiveListDetail(
            hasDetail = pane.selection != ClientSelection.None,
            listWidth = 320.dp,
            onBack = viewModel::clearSelection,
            onTopLevelChanged = onTopLevelChanged,
            modifier = Modifier.fillMaxSize().padding(padding),
            list = { listModifier ->
                ClientList(
                    clients = clients,
                    pendingPairings = pendingPairings,
                    selectedClientId = (pane.selection as? ClientSelection.Client)?.clientId,
                    selectedPairingRequestId =
                        (pane.selection as? ClientSelection.Pairing)?.requestId,
                    identity = configuration?.active,
                    onOpen = viewModel::selectClient,
                    onOpenPairing = { requestId -> viewModel.selectPairing(requestId) },
                    onChangePairingAddress = onChangePairingAddress,
                    onSetPairingEnabled = viewModel::setPairingEnabled,
                    onOpenSettings = onOpenSettings,
                    report = ::report,
                    modifier = listModifier,
                )
            },
            emptyDetail = { detailModifier -> EmptyClientSelection(detailModifier) },
            detail = { showBack, detailModifier ->
                ClientSelectionPane(
                    aiReviewAccess = aiReviewAccess,
                    pane = pane,
                    onChooseSas = ::chooseSas,
                    onRejectPairing = viewModel::rejectPairing,
                    onRenameClient = viewModel::rename,
                    onSetClientState = viewModel::setState,
                    onSaveClientInstructions = viewModel::saveInstructions,
                    onEndTemporaryAccess = { clientId, grant ->
                        viewModel.endTemporaryAccess(
                            grant.secretId,
                            clientId,
                            grant.operation,
                        )
                    },
                    onBack = viewModel::clearSelection,
                    showBack = showBack,
                    modifier = detailModifier,
                )
            },
        )
    }
}

@Composable
private fun ClientSelectionPane(
    aiReviewAccess: AiReviewAccess,
    pane: ClientPaneState,
    onChooseSas: (InboxRequestDetails, Int?) -> Unit,
    onRejectPairing: (String) -> Unit,
    onRenameClient: (String, String) -> Unit,
    onSetClientState: (String, RelayClientState) -> Unit,
    onSaveClientInstructions: (String, String) -> Unit,
    onEndTemporaryAccess: (String, TemporaryAccessGrant) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    when (pane) {
        ClientPaneState.Empty -> EmptyClientSelection(modifier)
        is ClientPaneState.Loading,
        is ClientPaneState.Missing ->
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ClientPaneState.Client ->
            key(pane.details.clientId) {
                ClientDetail(
                    aiReviewAccess = aiReviewAccess,
                    client = pane.details,
                    temporaryAccessGrants = pane.temporaryAccess,
                    onRename = { name -> onRenameClient(pane.details.clientId, name) },
                    onSetState = { state -> onSetClientState(pane.details.clientId, state) },
                    onSaveInstructions = { instructions ->
                        onSaveClientInstructions(pane.details.clientId, instructions)
                    },
                    onEndTemporaryAccess = { grant ->
                        onEndTemporaryAccess(pane.details.clientId, grant)
                    },
                    onBack = onBack,
                    showBack = showBack,
                    modifier = modifier,
                )
            }
        is ClientPaneState.Pairing ->
            key(pane.request.id) {
                PairingRequestDetail(
                    request = pane.request,
                    onBack = onBack,
                    showBack = showBack,
                    onChooseSas = { choice -> onChooseSas(pane.request, choice) },
                    onReject = { onRejectPairing(pane.request.id) },
                    modifier = modifier,
                )
            }
    }
}

@Composable
private fun EmptyClientSelection(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "Select a client to view its details",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
