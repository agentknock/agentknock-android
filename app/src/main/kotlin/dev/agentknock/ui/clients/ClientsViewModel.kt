package dev.agentknock.ui.clients

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockApplication
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientChangeResult
import dev.agentknock.storage.request.ClientDetails
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.vault.VaultConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
internal class ClientsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentknockApplication).container
    private val repository = container.requests
    private val selectedClientId = MutableStateFlow<String?>(null)

    val clients: StateFlow<List<ClientSummary>> = repository.observeClients().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )
    val selection: StateFlow<String?> = selectedClientId.asStateFlow()
    val selectedClient: StateFlow<ClientDetails?> = selectedClientId.flatMapLatest { id ->
        id?.let(repository::observeClient) ?: flowOf(null)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )
    val configuration: StateFlow<VaultConfiguration?> = container.vault.observeConfiguration()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun selectClient(clientId: String?) {
        selectedClientId.value = clientId
    }

    suspend fun rename(clientId: String, name: String): ClientChangeResult =
        repository.renameClient(clientId, name)

    suspend fun setState(clientId: String, state: RelayClientState): ClientChangeResult =
        repository.setClientState(clientId, state)
}
