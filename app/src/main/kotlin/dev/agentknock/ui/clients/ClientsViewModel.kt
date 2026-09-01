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
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.device.DeviceConfiguration
import dev.agentknock.storage.device.DeviceSettingsCoordinator
import dev.agentknock.storage.device.DeviceManagementResult
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.RequestInbox
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.pendingPairings
import dev.agentknock.ui.auth.DeviceAuthenticationResult
import dev.agentknock.ui.auth.ProtectedActionAuthorizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface ClientSelection {
    data object None : ClientSelection

    data class Client(val clientId: String) : ClientSelection

    data class Pairing(
        val requestId: String,
        val retainResolved: Boolean = false,
    ) : ClientSelection
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
    val configuration: StateFlow<DeviceConfiguration?>,
    private val deviceManagement: DeviceSettingsCoordinator,
    private val awaitStorageReady: suspend () -> Unit,
    private val protectedActions: ProtectedActionAuthorizer,
) : ViewModel() {
    private val selected = MutableStateFlow(
        savedStateHandle.get<String>(SELECTED_CLIENT)?.let(ClientSelection::Client)
            ?: savedStateHandle.get<String>(SELECTED_PAIRING)?.let { requestId ->
                ClientSelection.Pairing(
                    requestId = requestId,
                    retainResolved = savedStateHandle[RETAIN_RESOLVED_PAIRING] ?: false,
                )
            }
            ?: ClientSelection.None,
    )
    private val messageEvents = Channel<String>(Channel.BUFFERED)

    val clients: StateFlow<List<ClientSummary>> = clientSummaries
    val messages: Flow<String> = messageEvents.receiveAsFlow()
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
                    if (!pane.selection.retainResolved) {
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
    fun selectClient(clientId: String?) {
        setSelection(clientId?.let(ClientSelection::Client) ?: ClientSelection.None)
    }

    fun selectPairing(requestId: String?, retainResolved: Boolean = false) {
        setSelection(
            requestId?.let { ClientSelection.Pairing(it, retainResolved) }
                ?: ClientSelection.None,
        )
    }

    fun clearSelection(expected: ClientSelection) {
        compareAndSetSelection(expected, ClientSelection.None)
    }

    fun clearSelection() {
        setSelection(ClientSelection.None)
    }

    fun rename(clientId: String, name: String) {
        launchMutation {
            when (repository.renameClient(clientId, name.trim())) {
                ClientChangeResult.CHANGED -> "Client renamed"
                ClientChangeResult.NOT_FOUND,
                ClientChangeResult.INVALID_STATE,
                -> "Client is no longer available"
            }
        }
    }

    fun saveInstructions(clientId: String, instructions: String) {
        launchMutation {
            when (repository.saveClientInstructions(clientId, instructions)) {
                ClientChangeResult.CHANGED -> "Instructions updated"
                ClientChangeResult.NOT_FOUND,
                ClientChangeResult.INVALID_STATE,
                -> "Instructions could not be updated"
            }
        }
    }

    fun setState(clientId: String, state: RelayClientState) {
        launchMutation {
            when (repository.setClientState(clientId, state)) {
                ClientChangeResult.CHANGED -> {
                    if (state == RelayClientState.REVOKED) {
                        compareAndSetSelection(
                            ClientSelection.Client(clientId),
                            ClientSelection.None,
                        )
                    }
                    state.successMessage()
                }
                ClientChangeResult.NOT_FOUND,
                ClientChangeResult.INVALID_STATE,
                -> "Client state could not be changed"
            }
        }
    }

    fun setPairingEnabled(enabled: Boolean) {
        launchMutation {
            deviceManagement.setPairingEnabled(enabled).message(enabled)
        }
    }

    fun endTemporaryAccess(
        secretId: String,
        clientId: String,
        operation: TemporaryAccessOperation,
    ) {
        launchMutation {
            if (secrets.endTemporaryAccess(secretId, clientId, operation)) {
                "Temporary access ended"
            } else {
                "Temporary access had already ended"
            }
        }
    }

    fun chooseSas(requestId: String, selectedIndex: Int?) {
        launchMutation {
            awaitStorageReady()
            if (
                selectedIndex != null &&
                repository.isMatchingPendingSas(requestId, selectedIndex)
            ) {
                val pairing = (pane.value as? ClientPaneState.Pairing)
                    ?.takeIf { it.request.id == requestId }
                    ?.request
                    ?.content as? InboxRequestContent.Pairing
                when (
                    val authentication = protectedActions.authorize(
                        "Accept ${pairing?.details?.clientName ?: "client"}",
                    )
                ) {
                    DeviceAuthenticationResult.Success -> Unit
                    is DeviceAuthenticationResult.Error -> return@launchMutation authentication.message
                }
            }
            repository.chooseSas(requestId, selectedIndex).message()
        }
    }

    fun rejectPairing(requestId: String) {
        launchMutation {
            awaitStorageReady()
            val result = repository.rejectPairing(requestId)
            compareAndSetSelection(ClientSelection.Pairing(requestId), ClientSelection.None)
            result.message()
        }
    }

    private fun launchMutation(mutation: suspend () -> String) {
        viewModelScope.launch {
            messageEvents.send(mutation())
        }
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
        savedStateHandle[RETAIN_RESOLVED_PAIRING] =
            (selection as? ClientSelection.Pairing)?.retainResolved ?: false
    }

    private companion object {
        const val SELECTED_CLIENT = "selected_client_id"
        const val SELECTED_PAIRING = "selected_pairing_request_id"
        const val RETAIN_RESOLVED_PAIRING = "retain_resolved_pairing"
        const val READ_MODEL_STOP_TIMEOUT_MILLIS = 5_000L
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

private fun PairingDecisionResult.message(): String = when (this) {
    PairingDecisionResult.VERIFIED -> "Pairing code verified"
    PairingDecisionResult.REJECTED -> "Pairing rejected"
    PairingDecisionResult.NOT_PENDING -> "This pairing no longer needs a decision"
    PairingDecisionResult.NOT_FOUND -> "Request is no longer available"
}
