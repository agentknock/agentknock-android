package dev.agentknock.storage.device

import dev.agentknock.relay.RelayDeviceManagementClient
import dev.agentknock.relay.RelayDeviceManagementResult
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.storage.WriteTransaction
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
    private val writeTransaction: WriteTransaction,
) {
    suspend fun setPairingEnabled(enabled: Boolean): DeviceManagementResult {
        val active = when (val authorization = deviceAuthorization.activeDeviceAuthorization()) {
            is RelayDeviceAuthorizationResult.Available -> authorization.authorization
            RelayDeviceAuthorizationResult.Missing -> return DeviceManagementResult.NoDevice
            RelayDeviceAuthorizationResult.Unavailable ->
                return DeviceManagementResult.CredentialsUnavailable
            RelayDeviceAuthorizationResult.Corrupted ->
                return DeviceManagementResult.CredentialsCorrupted
            RelayDeviceAuthorizationResult.UnsupportedEncryption ->
                return DeviceManagementResult.UnsupportedEncryption
        }
        return when (
            val result = relay.setPairingEnabled(
                deviceId = active.deviceId,
                deviceToken = active.deviceToken,
                enabled = enabled,
            )
        ) {
            is RelayEndpointResult.Success -> writeTransaction.execute {
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

    suspend fun deleteRemoteDevice(): Boolean {
        val active = (
            deviceAuthorization.activeDeviceAuthorization()
                as? RelayDeviceAuthorizationResult.Available
            )?.authorization ?: return false
        return relay.deleteDevice(active.deviceId, active.deviceToken) is RelayEndpointResult.Success
    }
}
