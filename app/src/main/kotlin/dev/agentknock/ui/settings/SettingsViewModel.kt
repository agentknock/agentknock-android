package dev.agentknock.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.storage.crypto.VaultProtection
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.device.DeviceConfiguration
import dev.agentknock.storage.device.DeviceIdentityRepository
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.secret.SecretRepository
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
    data object ConfirmLocalClear : FactoryResetUiState
    data object ClearFailed : FactoryResetUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class SettingsViewModel(
    deviceIdentity: DeviceIdentityRepository,
    secrets: SecretRepository,
    requests: RequestRepository,
    private val audit: AuditRepository,
    private val vaultKeys: VaultKeyManager,
    private val beginFactoryReset: suspend () -> Boolean,
    private val cancelFactoryReset: () -> Unit,
    private val clearApplicationData: () -> Boolean,
    private val awaitStorageReady: suspend () -> Unit,
) : ViewModel() {
    private val selectedAuditId = MutableStateFlow<Long?>(null)
    private val _vaultProtection = MutableStateFlow<VaultProtection?>(null)
    private val _factoryReset = MutableStateFlow<FactoryResetUiState>(FactoryResetUiState.Idle)

    val configuration: StateFlow<DeviceConfiguration?> = deviceIdentity
        .observeConfiguration()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val dataCounts: StateFlow<DataCounts> = combine(
        secrets.observeSecrets(),
        requests.observeClients(),
    ) { secrets, clients ->
        DataCounts(
            secrets = secrets.size,
            clients = clients.size,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DataCounts())
    val auditEvents: StateFlow<List<AuditEvent>> = audit.observeEvents().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    val auditSelection: StateFlow<Long?> = selectedAuditId.asStateFlow()
    val selectedAuditEvent: StateFlow<AuditEvent?> = selectedAuditId.flatMapLatest { id ->
        id?.let(audit::observeEvent) ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pushRegistrationState = requests.pushRegistrationState
    val vaultProtection: StateFlow<VaultProtection?> = _vaultProtection.asStateFlow()
    val factoryReset: StateFlow<FactoryResetUiState> = _factoryReset.asStateFlow()

    init {
        viewModelScope.launch {
            awaitStorageReady()
            _vaultProtection.value = vaultKeys.activeProtection()
        }
    }

    fun selectAuditEvent(id: Long?) {
        selectedAuditId.value = id
    }

    fun startFactoryReset() {
        if (_factoryReset.value != FactoryResetUiState.Idle) return
        _factoryReset.value = FactoryResetUiState.Working
        viewModelScope.launch {
            val remoteDeleted = try {
                beginFactoryReset()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (remoteDeleted) requestApplicationDataClear()
            else _factoryReset.value = FactoryResetUiState.ConfirmLocalClear
        }
    }

    fun confirmLocalClear() {
        if (_factoryReset.value != FactoryResetUiState.ConfirmLocalClear) return
        _factoryReset.value = FactoryResetUiState.Working
        requestApplicationDataClear()
    }

    fun cancelLocalClear() {
        if (_factoryReset.value != FactoryResetUiState.ConfirmLocalClear) return
        cancelFactoryReset()
        _factoryReset.value = FactoryResetUiState.Idle
    }

    fun consumeClearFailure() {
        if (_factoryReset.value == FactoryResetUiState.ClearFailed) {
            _factoryReset.value = FactoryResetUiState.Idle
        }
    }

    override fun onCleared() {
        if (_factoryReset.value == FactoryResetUiState.ConfirmLocalClear) cancelFactoryReset()
    }

    private fun requestApplicationDataClear() {
        if (runCatching(clearApplicationData).getOrDefault(false)) return
        cancelFactoryReset()
        _factoryReset.value = FactoryResetUiState.ClearFailed
    }
}
