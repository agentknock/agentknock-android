package dev.agentknock.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.storage.crypto.VaultProtection
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.device.DeviceConfiguration
import dev.agentknock.push.PushRegistration
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.auth.DeviceAuthenticationResult
import dev.agentknock.ui.auth.ProtectedActionAuthorizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal sealed interface FactoryResetUiState {
    data object Idle : FactoryResetUiState
    data object Working : FactoryResetUiState
    data object ConfirmLocalClear : FactoryResetUiState
    data object ClearFailed : FactoryResetUiState
}

internal class SettingsViewModel(
    val configuration: StateFlow<DeviceConfiguration?>,
    pushRegistration: PushRegistration,
    private val vaultKeys: VaultKeyManager,
    private val beginFactoryReset: suspend () -> Boolean,
    private val cancelFactoryReset: () -> Unit,
    private val clearApplicationData: () -> Boolean,
    private val awaitStorageReady: suspend () -> Unit,
    private val protectedActions: ProtectedActionAuthorizer,
) : ViewModel() {
    private val _vaultProtection = MutableStateFlow<VaultProtection?>(null)
    private val _factoryReset = MutableStateFlow<FactoryResetUiState>(FactoryResetUiState.Idle)
    private val messageEvents = Channel<String>(Channel.BUFFERED)

    val pushRegistrationState = pushRegistration.registrationState
    val vaultProtection: StateFlow<VaultProtection?> = _vaultProtection.asStateFlow()
    val factoryReset: StateFlow<FactoryResetUiState> = _factoryReset.asStateFlow()
    val messages = messageEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            awaitStorageReady()
            vaultKeys.observeProtection().collect { protection ->
                _vaultProtection.value = protection
            }
        }
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

    fun changeAuthenticationMode(mode: DeviceAuthenticationMode) {
        viewModelScope.launch {
            val result = protectedActions.changeMode(mode)
            if (result is DeviceAuthenticationResult.Error) messageEvents.send(result.message)
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
