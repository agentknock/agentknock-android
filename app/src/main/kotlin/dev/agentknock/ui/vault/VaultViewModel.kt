package dev.agentknock.ui.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentKnockApplication
import dev.agentknock.R
import dev.agentknock.protocol.VaultAddressGenerator
import dev.agentknock.storage.vault.ClaimVaultResult
import dev.agentknock.storage.vault.VaultConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentKnockApplication).container
    private val repository = container.vault
    private val addressGenerator = VaultAddressGenerator(
        application.resources.openRawResource(R.raw.vault_address_words)
            .bufferedReader()
            .use { reader -> reader.readLines() },
    )
    private val operationMutex = Mutex()
    private val _claiming = MutableStateFlow(false)
    private val _lastClaimResult = MutableStateFlow<ClaimVaultResult?>(null)

    val configuration: StateFlow<VaultConfiguration?> = repository.observeConfiguration().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    val claiming: StateFlow<Boolean> = _claiming.asStateFlow()
    val lastClaimResult: StateFlow<ClaimVaultResult?> = _lastClaimResult.asStateFlow()

    fun generateAddress(): String = addressGenerator.generate()

    suspend fun stageAndClaim(address: String): ClaimVaultResult = operationMutex.withLock {
        _claiming.value = true
        try {
            repository.stageAndClaim(address).also { result ->
                handleClaimResult(result)
            }
        } finally {
            _claiming.value = false
        }
    }

    suspend fun retryClaim(): ClaimVaultResult = operationMutex.withLock {
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

    private fun handleClaimResult(result: ClaimVaultResult) {
        _lastClaimResult.value = result
        if (result == ClaimVaultResult.Claimed) container.requestConnection.refresh()
    }
}
