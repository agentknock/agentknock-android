package dev.agentknock.ui.clients

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientChangeResult
import dev.agentknock.storage.request.ClientDetails
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.device.DeviceConfiguration
import dev.agentknock.storage.device.DeviceIdentityRepository
import dev.agentknock.storage.device.DeviceManagementRepository
import dev.agentknock.storage.device.DeviceManagementResult
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.RequestInbox
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.pendingPairings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

internal sealed interface ClientSelection {
    data object None : ClientSelection

    data class Client(val clientId: String) : ClientSelection

    data class Pairing(val requestId: String) : ClientSelection
}

internal sealed interface ClientPaneState {
    val selection: ClientSelection

    data object Empty : ClientPaneState {
        override val selection = ClientSelection.None
    }

    data class Loading(override val selection: ClientSelection) : ClientPaneState

    data class Client(
        override val selection: ClientSelection.Client,
        val details: ClientDetails,
        val temporaryAccess: List<TemporaryAccessGrant>,
    ) : ClientPaneState

    data class Pairing(
        override val selection: ClientSelection.Pairing,
        val request: InboxRequestDetails,
    ) : ClientPaneState

    data class Missing(override val selection: ClientSelection) : ClientPaneState
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ClientsViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: RequestRepository,
    private val inbox: RequestInbox,
    requestSummaries: StateFlow<List<InboxRequestSummary>>,
    clientSummaries: StateFlow<List<ClientSummary>>,
    private val secrets: SecretRepository,
    private val deviceIdentity: DeviceIdentityRepository,
    private val deviceManagement: DeviceManagementRepository,
    private val awaitStorageReady: suspend () -> Unit,
) : ViewModel() {
    private val selected = MutableStateFlow(
        savedStateHandle.get<String>(SELECTED_CLIENT)?.let(ClientSelection::Client)
            ?: savedStateHandle.get<String>(SELECTED_PAIRING)?.let(ClientSelection::Pairing)
            ?: ClientSelection.None,
    )

    val clients: StateFlow<List<ClientSummary>> = clientSummaries
    val pane: StateFlow<ClientPaneState> = selected
        .flatMapLatest { selection ->
            when (selection) {
                ClientSelection.None -> flowOf(ClientPaneState.Empty)
                is ClientSelection.Client -> combine(
                    repository.observeClient(selection.clientId),
                    secrets.observeTemporaryAccessGrants(),
                ) { details, grants ->
                    if (details == null) {
                        ClientPaneState.Missing(selection)
                    } else {
                        ClientPaneState.Client(
                            selection = selection,
                            details = details,
                            temporaryAccess = grants.filter { it.clientId == selection.clientId },
                        )
                    }
                }
                    .onStart { emit(ClientPaneState.Loading(selection)) }
                is ClientSelection.Pairing -> inbox.observeRequest(selection.requestId)
                    .map { request ->
                        if (request?.content is InboxRequestContent.Pairing) {
                            ClientPaneState.Pairing(selection, request)
                        } else {
                            ClientPaneState.Missing(selection)
                        }
                    }
                    .onStart { emit(ClientPaneState.Loading(selection)) }
            }
        }
        .onEach { pane ->
            when (pane) {
                is ClientPaneState.Missing ->
                    compareAndSetSelection(pane.selection, ClientSelection.None)
                is ClientPaneState.Pairing -> {
                    val pairing = (pane.request.content as InboxRequestContent.Pairing).details
                    when (pairing.pairingState) {
                        PairingState.COMPLETED -> compareAndSetSelection(
                            pane.selection,
                            ClientSelection.Client(pairing.clientId),
                        )
                        PairingState.REJECTED ->
                            compareAndSetSelection(pane.selection, ClientSelection.None)
                        else -> Unit
                    }
                }
                ClientPaneState.Empty,
                is ClientPaneState.Loading,
                is ClientPaneState.Client,
                -> Unit
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ClientPaneState.Empty,
        )
    val pendingPairings: StateFlow<List<InboxRequestSummary>> = requestSummaries
        .map(List<InboxRequestSummary>::pendingPairings)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(READ_MODEL_STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )
    val configuration: StateFlow<DeviceConfiguration?> = deviceIdentity
        .observeConfiguration()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun selectClient(clientId: String?) {
        setSelection(clientId?.let(ClientSelection::Client) ?: ClientSelection.None)
    }

    fun selectPairing(requestId: String?) {
        setSelection(requestId?.let(ClientSelection::Pairing) ?: ClientSelection.None)
    }

    fun clearSelection(expected: ClientSelection) {
        compareAndSetSelection(expected, ClientSelection.None)
    }

    fun clearSelection() {
        setSelection(ClientSelection.None)
    }

    suspend fun rename(clientId: String, name: String): ClientChangeResult =
        repository.renameClient(clientId, name)

    suspend fun saveInstructions(clientId: String, instructions: String): ClientChangeResult =
        repository.saveClientInstructions(clientId, instructions)

    suspend fun setState(clientId: String, state: RelayClientState): ClientChangeResult =
        repository.setClientState(clientId, state)

    suspend fun setPairingEnabled(enabled: Boolean): DeviceManagementResult =
        deviceManagement.setPairingEnabled(enabled)

    suspend fun endTemporaryAccess(
        secretId: String,
        clientId: String,
        operation: TemporaryAccessOperation,
    ): Boolean = secrets.endTemporaryAccess(secretId, clientId, operation)

    suspend fun chooseSas(requestId: String, selectedIndex: Int?): PairingDecisionResult {
        awaitStorageReady()
        return repository.chooseSas(requestId, selectedIndex)
    }

    suspend fun isMatchingPendingSas(requestId: String, selectedIndex: Int): Boolean {
        awaitStorageReady()
        return repository.isMatchingPendingSas(requestId, selectedIndex)
    }

    suspend fun rejectPairing(requestId: String): PairingDecisionResult {
        awaitStorageReady()
        return repository.rejectPairing(requestId)
    }

    private fun setSelection(selection: ClientSelection) {
        selected.value = selection
        persistSelection(selection)
    }

    private fun compareAndSetSelection(
        expected: ClientSelection,
        selection: ClientSelection,
    ) {
        if (selected.compareAndSet(expected, selection)) persistSelection(selection)
    }

    private fun persistSelection(selection: ClientSelection) {
        savedStateHandle[SELECTED_CLIENT] = (selection as? ClientSelection.Client)?.clientId
        savedStateHandle[SELECTED_PAIRING] = (selection as? ClientSelection.Pairing)?.requestId
    }

    private companion object {
        const val SELECTED_CLIENT = "selected_client_id"
        const val SELECTED_PAIRING = "selected_pairing_request_id"
        const val READ_MODEL_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
