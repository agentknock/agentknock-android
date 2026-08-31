package dev.agentknock.storage.device

import dev.agentknock.relay.RelayDeviceManagementClient
import dev.agentknock.relay.RelayDeviceManagementResult
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink

internal sealed interface DeviceManagementResult {
    data object Changed : DeviceManagementResult
    data object NoDevice : DeviceManagementResult
    data object CredentialsUnavailable : DeviceManagementResult
    data object CredentialsCorrupted : DeviceManagementResult
    data object UnsupportedEncryption : DeviceManagementResult
    data class Rejected(val status: Int, val code: String?, val message: String?) :
        DeviceManagementResult
    data class Unavailable(val message: String?) : DeviceManagementResult
    data object InvalidResponse : DeviceManagementResult
}

internal class DeviceManagementRepository(
    private val deviceIdentityDao: DeviceIdentityDao,
    private val credentials: RelayDeviceCredentialSource,
    private val relay: RelayDeviceManagementClient,
    private val audit: AuditSink,
) {
    suspend fun setPairingEnabled(enabled: Boolean): DeviceManagementResult {
        val active = when (val lookup = credentialLookup()) {
            is CredentialLookup.Available -> lookup.credentials
            is CredentialLookup.Failed -> return lookup.result
        }
        return when (
            val result = relay.setPairingEnabled(
                deviceId = active.deviceId,
                deviceToken = active.deviceToken,
                enabled = enabled,
            )
        ) {
            RelayDeviceManagementResult.Changed -> {
                check(
                    deviceIdentityDao.updatePairingEnabled(
                        identityId = active.deviceIdentityId,
                        enabled = enabled,
                        activeRole = DeviceIdentityRole.ACTIVE.storedName,
                    ) == 1,
                )
                audit.record(
                    AuditRecord(
                        type = if (enabled) {
                            AuditEventType.NEW_PAIRINGS_RESUMED
                        } else {
                            AuditEventType.NEW_PAIRINGS_PAUSED
                        },
                        outcome = AuditOutcome.CHANGED,
                    ),
                )
                DeviceManagementResult.Changed
            }
            is RelayDeviceManagementResult.Rejected -> DeviceManagementResult.Rejected(
                result.status,
                result.code,
                result.message,
            )
            is RelayDeviceManagementResult.Unavailable -> {
                DeviceManagementResult.Unavailable(result.cause.message)
            }
            RelayDeviceManagementResult.InvalidResponse -> DeviceManagementResult.InvalidResponse
        }
    }

    suspend fun deleteRemoteDevice(): DeviceManagementResult {
        val active = when (val lookup = credentialLookup()) {
            is CredentialLookup.Available -> lookup.credentials
            is CredentialLookup.Failed -> return lookup.result
        }
        return when (
            val result = relay.deleteDevice(active.deviceId, active.deviceToken)
        ) {
            RelayDeviceManagementResult.Changed -> DeviceManagementResult.Changed
            is RelayDeviceManagementResult.Rejected -> DeviceManagementResult.Rejected(
                result.status,
                result.code,
                result.message,
            )
            is RelayDeviceManagementResult.Unavailable -> {
                DeviceManagementResult.Unavailable(result.cause.message)
            }
            RelayDeviceManagementResult.InvalidResponse -> DeviceManagementResult.InvalidResponse
        }
    }

    private suspend fun credentialLookup(): CredentialLookup =
        when (val result = credentials.activeDeviceCredentials()) {
            is RelayDeviceCredentialsResult.Available -> CredentialLookup.Available(
                result.credentials,
            )
            RelayDeviceCredentialsResult.Missing -> CredentialLookup.Failed(
                DeviceManagementResult.NoDevice,
            )
            RelayDeviceCredentialsResult.CredentialsUnavailable -> CredentialLookup.Failed(
                DeviceManagementResult.CredentialsUnavailable,
            )
            RelayDeviceCredentialsResult.CredentialsCorrupted -> CredentialLookup.Failed(
                DeviceManagementResult.CredentialsCorrupted,
            )
            RelayDeviceCredentialsResult.UnsupportedEncryption -> CredentialLookup.Failed(
                DeviceManagementResult.UnsupportedEncryption,
            )
        }

    private sealed interface CredentialLookup {
        data class Available(val credentials: RelayDeviceCredentials) : CredentialLookup
        data class Failed(val result: DeviceManagementResult) : CredentialLookup
    }
}
