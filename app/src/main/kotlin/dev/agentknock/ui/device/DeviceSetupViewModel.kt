package dev.agentknock.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.protocol.PairingAddressGenerator
import dev.agentknock.storage.device.ClaimPairingAddressResult
import dev.agentknock.storage.device.DeviceConfiguration
import dev.agentknock.storage.device.DeviceIdentityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DeviceSetupViewModel(
    private val repository: DeviceIdentityRepository,
    private val addressGenerator: PairingAddressGenerator,
    private val refreshConnection: () -> Unit,
) : ViewModel() {
    private val operationMutex = Mutex()
    private val _claiming = MutableStateFlow(false)
    private val _lastClaimResult = MutableStateFlow<ClaimPairingAddressResult?>(null)

    val configuration: StateFlow<DeviceConfiguration?> = repository.observeConfiguration().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    val claiming: StateFlow<Boolean> = _claiming.asStateFlow()
    val lastClaimResult: StateFlow<ClaimPairingAddressResult?> = _lastClaimResult.asStateFlow()

    fun generateAddress(): String = addressGenerator.generate()

    suspend fun stageAndClaim(address: String): ClaimPairingAddressResult = operationMutex.withLock {
        _claiming.value = true
        try {
            repository.stageAndClaim(address).also { result ->
                handleClaimResult(result)
            }
        } finally {
            _claiming.value = false
        }
    }

    suspend fun retryClaim(): ClaimPairingAddressResult = operationMutex.withLock {
        _claiming.value = true
        try {
            repository.claimCandidate().also { result ->
                handleClaimResult(result)
            }
        } finally {
            _claiming.value = false
        }
    }

    suspend fun discardCandidate() = operationMutex.withLock {
        repository.discardCandidate()
        _lastClaimResult.value = null
    }

    fun clearClaimResult() {
        _lastClaimResult.value = null
    }

    private fun handleClaimResult(result: ClaimPairingAddressResult) {
        _lastClaimResult.value = result
        if (result == ClaimPairingAddressResult.Claimed) refreshConnection()
    }
}
