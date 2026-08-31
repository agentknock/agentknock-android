package dev.agentknock.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockApplication
import dev.agentknock.storage.FactoryResetResult
import dev.agentknock.storage.crypto.VaultProtection
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.device.DeviceConfiguration
import kotlinx.coroutines.CancellationException
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
    val secrets: Int = 0,
    val clients: Int = 0,
)

internal sealed interface FactoryResetUiState {
    data object Idle : FactoryResetUiState
    data object Working : FactoryResetUiState
    data class Finished(val result: FactoryResetResult) : FactoryResetUiState
    data object Failed : FactoryResetUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentknockApplication).container
    private val selectedAuditId = MutableStateFlow<Long?>(null)
    private val _vaultProtection = MutableStateFlow<VaultProtection?>(null)
    private val _factoryReset = MutableStateFlow<FactoryResetUiState>(FactoryResetUiState.Idle)

    val configuration: StateFlow<DeviceConfiguration?> = container.deviceIdentity
        .observeConfiguration()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val dataCounts: StateFlow<DataCounts> = combine(
        container.secrets.observeSecrets(),
        container.requests.observeClients(),
    ) { secrets, clients ->
        DataCounts(
            secrets = secrets.size,
            clients = clients.size,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DataCounts())
    val auditEvents: StateFlow<List<AuditEvent>> = container.audit.observeEvents().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    val auditSelection: StateFlow<Long?> = selectedAuditId.asStateFlow()
    val selectedAuditEvent: StateFlow<AuditEvent?> = selectedAuditId.flatMapLatest { id ->
        id?.let(container.audit::observeEvent) ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pushRegistrationState = container.requests.pushRegistrationState
    val vaultProtection: StateFlow<VaultProtection?> = _vaultProtection.asStateFlow()
    val factoryReset: StateFlow<FactoryResetUiState> = _factoryReset.asStateFlow()

    init {
        viewModelScope.launch {
            container.localStorage.await()
            _vaultProtection.value = container.vaultKeyManager.activeProtection()
        }
    }

    fun selectAuditEvent(id: Long?) {
        selectedAuditId.value = id
    }

    fun startFactoryReset(localOnly: Boolean) {
        if (_factoryReset.value == FactoryResetUiState.Working) return
        _factoryReset.value = FactoryResetUiState.Working
        viewModelScope.launch {
            _factoryReset.value = try {
                FactoryResetUiState.Finished(
                    if (localOnly) {
                        container.factoryReset.resetLocalOnly()
                    } else {
                        container.factoryReset.reset()
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FactoryResetUiState.Failed
            }
        }
    }

    fun consumeFactoryResetResult() {
        if (_factoryReset.value != FactoryResetUiState.Working) {
            _factoryReset.value = FactoryResetUiState.Idle
        }
    }
}
