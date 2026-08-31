package dev.agentknock.ui.clients

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockApplication
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientChangeResult
import dev.agentknock.storage.request.ClientDetails
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.device.DeviceConfiguration
import dev.agentknock.storage.device.DeviceManagementResult
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.pendingPairings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
internal class ClientsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentknockApplication).container
    private val repository = container.requests
    private val selectedClientId = MutableStateFlow<String?>(null)
    private val selectedPairingRequestId = MutableStateFlow<String?>(null)

    val clients: StateFlow<List<ClientSummary>> = repository.observeClients().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )
    val selection: StateFlow<String?> = selectedClientId.asStateFlow()
    val pairingSelection: StateFlow<String?> = selectedPairingRequestId.asStateFlow()
    val selectedClient: StateFlow<ClientDetails?> = selectedClientId.flatMapLatest { id ->
        id?.let(repository::observeClient) ?: flowOf(null)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )
    val temporaryAccessGrants: StateFlow<List<TemporaryAccessGrant>> = selectedClientId
        .flatMapLatest { clientId ->
            clientId?.let { selected ->
                container.secrets.observeTemporaryAccessGrants().map { grants ->
                    grants.filter { it.clientId == selected }
                }
            } ?: flowOf(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )
    val pendingPairings: StateFlow<List<InboxRequestSummary>> = repository.observeRequests()
        .map(List<InboxRequestSummary>::pendingPairings)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )
    val selectedPairing: StateFlow<InboxRequestDetails?> = selectedPairingRequestId
        .flatMapLatest { id -> id?.let(repository::observeRequest) ?: flowOf(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
    val configuration: StateFlow<DeviceConfiguration?> = container.deviceIdentity
        .observeConfiguration()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun selectClient(clientId: String?) {
        if (clientId != null) selectedPairingRequestId.value = null
        selectedClientId.value = clientId
    }

    fun selectPairing(requestId: String?) {
        if (requestId != null) selectedClientId.value = null
        selectedPairingRequestId.value = requestId
    }

    suspend fun rename(clientId: String, name: String): ClientChangeResult =
        repository.renameClient(clientId, name)

    suspend fun saveInstructions(clientId: String, instructions: String): ClientChangeResult =
        repository.saveClientInstructions(clientId, instructions)

    suspend fun setState(clientId: String, state: RelayClientState): ClientChangeResult =
        repository.setClientState(clientId, state)

    suspend fun setPairingEnabled(enabled: Boolean): DeviceManagementResult =
        container.deviceManagement.setPairingEnabled(enabled)

    suspend fun endTemporaryAccess(
        secretId: String,
        clientId: String,
        operation: TemporaryAccessOperation,
    ): Boolean = container.secrets.endTemporaryAccess(secretId, clientId, operation)

    suspend fun chooseSas(requestId: String, selectedIndex: Int?): PairingDecisionResult {
        container.localStorage.await()
        return repository.chooseSas(requestId, selectedIndex)
    }

    suspend fun isMatchingPendingSas(requestId: String, selectedIndex: Int): Boolean {
        container.localStorage.await()
        return repository.isMatchingPendingSas(requestId, selectedIndex)
    }

    suspend fun rejectPairing(requestId: String): PairingDecisionResult {
        container.localStorage.await()
        return repository.rejectPairing(requestId)
    }
}
