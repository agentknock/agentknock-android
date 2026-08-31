package dev.agentknock.storage.device

import dev.agentknock.relay.RelayDeviceManagementClient
import dev.agentknock.relay.RelayDeviceManagementResult
import dev.agentknock.relay.RelayEndpointResult
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
    private val deviceAuthorization: RelayDeviceAuthorizationSource,
    private val relay: RelayDeviceManagementClient,
    private val audit: AuditSink,
) {
    suspend fun setPairingEnabled(enabled: Boolean): DeviceManagementResult {
        val active = when (val lookup = authorizationLookup()) {
            is AuthorizationLookup.Available -> lookup.authorization
            is AuthorizationLookup.Failed -> return lookup.result
        }
        return when (
            val result = relay.setPairingEnabled(
                deviceId = active.deviceId,
                deviceToken = active.deviceToken,
                enabled = enabled,
            )
        ) {
            is RelayEndpointResult.Success -> {
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
            is RelayEndpointResult.Rejected -> DeviceManagementResult.Rejected(
                result.status,
                result.code,
                result.message,
            )
            is RelayEndpointResult.Unavailable -> {
                DeviceManagementResult.Unavailable(result.cause.message)
            }
            RelayEndpointResult.InvalidResponse -> DeviceManagementResult.InvalidResponse
        }
    }

    suspend fun deleteRemoteDevice(): DeviceManagementResult {
        val active = when (val lookup = authorizationLookup()) {
            is AuthorizationLookup.Available -> lookup.authorization
            is AuthorizationLookup.Failed -> return lookup.result
        }
        return when (
            val result = relay.deleteDevice(active.deviceId, active.deviceToken)
        ) {
            is RelayEndpointResult.Success -> DeviceManagementResult.Changed
            is RelayEndpointResult.Rejected -> DeviceManagementResult.Rejected(
                result.status,
                result.code,
                result.message,
            )
            is RelayEndpointResult.Unavailable -> {
                DeviceManagementResult.Unavailable(result.cause.message)
            }
            RelayEndpointResult.InvalidResponse -> DeviceManagementResult.InvalidResponse
        }
    }

    private suspend fun authorizationLookup(): AuthorizationLookup =
        when (val result = deviceAuthorization.activeDeviceAuthorization()) {
            is RelayDeviceAuthorizationResult.Available -> AuthorizationLookup.Available(
                result.authorization,
            )
            RelayDeviceAuthorizationResult.Missing -> AuthorizationLookup.Failed(
                DeviceManagementResult.NoDevice,
            )
            RelayDeviceAuthorizationResult.Unavailable -> AuthorizationLookup.Failed(
                DeviceManagementResult.CredentialsUnavailable,
            )
            RelayDeviceAuthorizationResult.Corrupted -> AuthorizationLookup.Failed(
                DeviceManagementResult.CredentialsCorrupted,
            )
            RelayDeviceAuthorizationResult.UnsupportedEncryption -> AuthorizationLookup.Failed(
                DeviceManagementResult.UnsupportedEncryption,
            )
        }

    private sealed interface AuthorizationLookup {
        data class Available(val authorization: RelayDeviceAuthorization) : AuthorizationLookup
        data class Failed(val result: DeviceManagementResult) : AuthorizationLookup
    }
}
