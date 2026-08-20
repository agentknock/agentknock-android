package dev.agentknock.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockApplication
import dev.agentknock.storage.FactoryResetResult
import dev.agentknock.storage.crypto.LocalEncryptionProtection
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.vault.DeviceManagementResult
import dev.agentknock.storage.vault.VaultConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class DataCounts(
    val profiles: Int = 0,
    val variables: Int = 0,
    val clients: Int = 0,
    val requests: Int = 0,
    val auditEvents: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentknockApplication).container
    private val selectedAuditId = MutableStateFlow<Long?>(null)
    private val _localEncryptionProtection = MutableStateFlow<LocalEncryptionProtection?>(null)

    val configuration: StateFlow<VaultConfiguration?> = container.vault.observeConfiguration()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val dataCounts: StateFlow<DataCounts> = combine(
        container.profiles.observeProfiles(),
        container.requests.observeClients(),
        container.requests.observeRequestCount(),
        container.audit.observeCount(),
    ) { profiles, clients, requests, events ->
        DataCounts(
            profiles = profiles.size,
            variables = profiles.sumOf { it.environmentVariableCount },
            clients = clients.size,
            requests = requests,
            auditEvents = events,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DataCounts())
    val auditEvents: StateFlow<List<AuditEvent>> = container.audit.observeEvents().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    val clients: StateFlow<List<ClientSummary>> = container.requests.observeClients().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    val auditSelection: StateFlow<Long?> = selectedAuditId.asStateFlow()
    val selectedAuditEvent: StateFlow<AuditEvent?> = selectedAuditId.flatMapLatest { id ->
        id?.let(container.audit::observeEvent) ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val syncing = container.requestConnection.syncing
    val lastSyncResult: StateFlow<RequestSyncResult?> = container.requestConnection.lastSyncResult
    val pushRegistrationState = container.requests.pushRegistrationState
    val localEncryptionProtection: StateFlow<LocalEncryptionProtection?> =
        _localEncryptionProtection.asStateFlow()

    init {
        viewModelScope.launch {
            container.localStorage.await()
            _localEncryptionProtection.value = container.encryptionKeyManager.activeProtection()
        }
    }

    fun selectAuditEvent(id: Long?) {
        selectedAuditId.value = id
    }

    fun reconnect() = container.requestConnection.refresh()

    suspend fun setPairingEnabled(enabled: Boolean): DeviceManagementResult =
        container.deviceManagement.setPairingEnabled(enabled)

    suspend fun clearCompletedRequests(): Int = container.requests.clearCompletedHistory()

    suspend fun factoryReset(localOnly: Boolean): FactoryResetResult = if (localOnly) {
        container.factoryReset.resetLocalOnly()
    } else {
        container.factoryReset.reset()
    }
}
