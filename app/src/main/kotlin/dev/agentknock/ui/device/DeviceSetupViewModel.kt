package dev.agentknock.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.protocol.PairingAddressGenerator
import dev.agentknock.storage.device.ClaimPairingAddressResult
import dev.agentknock.storage.device.DeviceSettingsCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class DeviceSetupViewModel(
    private val settings: DeviceSettingsCoordinator,
    private val addressGenerator: PairingAddressGenerator,
) : ViewModel() {
    private val _claiming = MutableStateFlow(false)
    private val _lastClaimResult = MutableStateFlow<ClaimPairingAddressResult?>(null)

    val claiming: StateFlow<Boolean> = _claiming.asStateFlow()
    val lastClaimResult: StateFlow<ClaimPairingAddressResult?> = _lastClaimResult.asStateFlow()

    fun generateAddress(): String = addressGenerator.generate()

    fun stageAndClaim(address: String) {
        claim { settings.stageAndClaim(address) }
    }

    fun retryClaim() {
        claim { settings.retryClaim() }
    }

    private fun claim(operation: suspend () -> ClaimPairingAddressResult) {
        if (!_claiming.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                _lastClaimResult.value = operation()
            } finally {
                _claiming.value = false
            }
        }
    }

    fun discardCandidate() {
        viewModelScope.launch {
            settings.discardCandidate()
            _lastClaimResult.value = null
        }
    }

    fun clearClaimResult() {
        _lastClaimResult.value = null
    }
}
