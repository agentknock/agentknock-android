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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.device.DeviceManagementResult
import dev.agentknock.storage.request.ClientChangeResult
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.ui.components.AdaptiveListDetail
import kotlinx.coroutines.launch

@Composable
internal fun ClientsScreen(
    authorizeProtectedAction: (String, () -> Unit, (String) -> Unit) -> Unit,
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

    fun chooseSas(request: InboxRequestDetails, choice: Int?) {
        val pairing = (request.content as? InboxRequestContent.Pairing)?.details ?: return
        if (choice == null) {
            scope.launch { report(viewModel.chooseSas(request.id, null).message()) }
            return
        }
        scope.launch {
            if (viewModel.isMatchingPendingSas(request.id, choice)) {
                authorizeProtectedAction(
                    "Accept ${pairing.clientName}",
                    {
                        scope.launch {
                            report(viewModel.chooseSas(request.id, choice).message())
                        }
                    },
                    ::report,
                )
            } else {
                report(viewModel.chooseSas(request.id, choice).message())
            }
        }
    }

    fun rejectPairing(requestId: String) {
        scope.launch {
            report(viewModel.rejectPairing(requestId).message())
            viewModel.clearSelection(ClientSelection.Pairing(requestId))
        }
    }

    fun renameClient(clientId: String, name: String) {
        scope.launch {
            val result = viewModel.rename(clientId, name.trim())
            report(
                if (result == ClientChangeResult.CHANGED) {
                    "Client renamed"
                } else {
                    "Client is no longer available"
                },
            )
        }
    }

    fun setClientState(clientId: String, state: RelayClientState) {
        scope.launch {
            val result = viewModel.setState(clientId, state)
            if (result == ClientChangeResult.CHANGED && state == RelayClientState.REVOKED) {
                viewModel.clearSelection(ClientSelection.Client(clientId))
            }
            report(
                if (result == ClientChangeResult.CHANGED) {
                    state.successMessage()
                } else {
                    "Client state could not be changed"
                },
            )
        }
    }

    fun saveClientInstructions(clientId: String, instructions: String) {
        scope.launch {
            val result = viewModel.saveInstructions(clientId, instructions)
            report(
                if (result == ClientChangeResult.CHANGED) {
                    "Instructions updated"
                } else {
                    "Instructions could not be updated"
                },
            )
        }
    }

    fun endTemporaryAccess(clientId: String, grant: TemporaryAccessGrant) {
        scope.launch {
            val ended = viewModel.endTemporaryAccess(
                grant.secretId,
                clientId,
                grant.operation,
            )
            report(
                if (ended) {
                    "Temporary access ended"
                } else {
                    "Temporary access had already ended"
                },
            )
        }
    }

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
                    onOpenPairing = viewModel::selectPairing,
                    onChangePairingAddress = onChangePairingAddress,
                    onSetPairingEnabled = { enabled ->
                        scope.launch { report(viewModel.setPairingEnabled(enabled).message(enabled)) }
                    },
                    onOpenSettings = onOpenSettings,
                    report = ::report,
                    modifier = listModifier,
                )
            },
            emptyDetail = { detailModifier -> EmptyClientSelection(detailModifier) },
            detail = { showBack, detailModifier ->
                ClientSelectionPane(
                    pane = pane,
                    onChooseSas = ::chooseSas,
                    onRejectPairing = ::rejectPairing,
                    onRenameClient = ::renameClient,
                    onSetClientState = ::setClientState,
                    onSaveClientInstructions = ::saveClientInstructions,
                    onEndTemporaryAccess = ::endTemporaryAccess,
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
        is ClientPaneState.Missing,
        -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ClientPaneState.Client -> key(pane.details.clientId) {
            ClientDetail(
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
        is ClientPaneState.Pairing -> key(pane.request.id) {
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

private fun DeviceManagementResult.message(enabled: Boolean): String = when (this) {
    DeviceManagementResult.Changed -> if (enabled) "New pairings resumed" else "New pairings paused"
    DeviceManagementResult.NoDevice -> "Device setup is incomplete"
    DeviceManagementResult.CredentialsUnavailable -> "Device keys are unavailable"
    DeviceManagementResult.CredentialsCorrupted -> "Device keys could not be verified"
    DeviceManagementResult.UnsupportedEncryption -> "Device keys use unsupported encryption"
    is DeviceManagementResult.Rejected -> message ?: "The relay rejected the change"
    is DeviceManagementResult.Unavailable -> message ?: "The relay is unavailable"
    DeviceManagementResult.InvalidResponse -> "The relay returned an invalid response"
}
