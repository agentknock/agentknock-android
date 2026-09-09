package dev.agentknock.storage.device

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes configuration changes that may replace the active device identity. */
internal class DeviceSettingsCoordinator(
    private val identities: DeviceIdentityRepository,
    private val management: DeviceManagementRepository,
    private val awaitStorageReady: suspend () -> Unit,
    private val onIdentityChanged: () -> Unit,
) {
    private val operations = Mutex()

    suspend fun stageAndClaim(address: String): ClaimPairingAddressResult = operation {
        completeClaim(identities.stageAndClaim(address))
    }

    suspend fun retryClaim(): ClaimPairingAddressResult = operation {
        completeClaim(identities.claimCandidate())
    }

    suspend fun discardCandidate() = operation {
        identities.discardCandidate()
    }

    suspend fun saveInstructions(instructions: String): Boolean = operation {
        identities.saveInstructions(instructions)
    }

    suspend fun setPairingEnabled(enabled: Boolean): DeviceManagementResult = operation {
        management.setPairingEnabled(enabled)
    }

    private suspend fun completeClaim(
        result: ClaimPairingAddressResult
    ): ClaimPairingAddressResult {
        if (result == ClaimPairingAddressResult.Claimed) {
            onIdentityChanged()
        }
        return result
    }

    private suspend fun <T> operation(block: suspend () -> T): T = operations.withLock {
        awaitStorageReady()
        block()
    }
}
