package dev.agentknock.subscription

import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.relay.RelaySubscriptionClient
import dev.agentknock.relay.RelaySubscriptionResult
import dev.agentknock.storage.device.DeviceCredentialResult
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface SubscriptionResult {
    data class Status(val active: Boolean) : SubscriptionResult

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : SubscriptionResult

    data class Unavailable(val message: String?) : SubscriptionResult

    data object NoDevice : SubscriptionResult

    data object DeviceCredentialsUnavailable : SubscriptionResult

    data object DeviceCredentialsCorrupted : SubscriptionResult

    data object UnsupportedEncryption : SubscriptionResult

    data object InvalidRelayResponse : SubscriptionResult
}

internal class SubscriptionRepository(
    private val deviceAuthorization: RelayDeviceAuthorizationSource,
    private val relay: RelaySubscriptionClient,
) {
    private val operations = Mutex()
    private var accessDeviceId: String? = null
    private val _access = MutableStateFlow(AiReviewAccess.CHECKING)
    val access = _access.asStateFlow()

    suspend fun status(): SubscriptionResult = withAuthorization { deviceId, deviceToken ->
        relay.status(deviceId, deviceToken).toSubscriptionResult()
    }

    suspend fun redeem(redemptionToken: String): SubscriptionResult =
        withAuthorization { deviceId, deviceToken ->
            relay.redeem(deviceId, deviceToken, redemptionToken).toSubscriptionResult()
        }

    suspend fun updateFromGooglePlay(purchaseToken: String): SubscriptionResult =
        withAuthorization { deviceId, deviceToken ->
            relay.updateFromGooglePlay(deviceId, deviceToken, purchaseToken).toSubscriptionResult()
        }

    suspend fun accessForReview(deviceId: String): AiReviewAccess {
        val result = withAuthorization { activeDeviceId, deviceToken ->
            if (activeDeviceId != deviceId) {
                SubscriptionResult.InvalidRelayResponse
            } else if (_access.value == AiReviewAccess.ACTIVE) {
                // The review endpoint checks entitlement again before running AI.
                SubscriptionResult.Status(active = true)
            } else {
                // Recheck inactive access so renewal also works while the app is in the background.
                relay.status(activeDeviceId, deviceToken).toSubscriptionResult()
            }
        }
        return when (result) {
            is SubscriptionResult.Status ->
                if (result.active) AiReviewAccess.ACTIVE else AiReviewAccess.INACTIVE
            else -> AiReviewAccess.UNAVAILABLE
        }
    }

    suspend fun recordInactiveReviewAccess(deviceId: String) = operations.withLock {
        if (deviceId == accessDeviceId) {
            _access.value = AiReviewAccess.INACTIVE
        }
    }

    private suspend fun withAuthorization(
        operation: suspend (deviceId: String, deviceToken: String) -> SubscriptionResult,
    ): SubscriptionResult = operations.withLock {
        val authorization = deviceAuthorization.activeDeviceAuthorization()
        val deviceId = (authorization as? DeviceCredentialResult.Available)?.value?.deviceId
        if (deviceId != accessDeviceId) {
            accessDeviceId = deviceId
            _access.value = AiReviewAccess.CHECKING
        }
        val result = when (authorization) {
            is DeviceCredentialResult.Available -> try {
                operation(authorization.value.deviceId, authorization.value.deviceToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SubscriptionResult.Unavailable(failure.message)
            }
            null -> SubscriptionResult.NoDevice
            DeviceCredentialResult.Unavailable -> SubscriptionResult.DeviceCredentialsUnavailable
            DeviceCredentialResult.Corrupted -> SubscriptionResult.DeviceCredentialsCorrupted
            DeviceCredentialResult.UnsupportedEncryption -> SubscriptionResult.UnsupportedEncryption
        }
        _access.value = when (result) {
            is SubscriptionResult.Status ->
                if (result.active) AiReviewAccess.ACTIVE else AiReviewAccess.INACTIVE
            SubscriptionResult.NoDevice -> AiReviewAccess.SETUP_REQUIRED
            else -> when (_access.value) {
                AiReviewAccess.ACTIVE, AiReviewAccess.INACTIVE -> _access.value
                else -> AiReviewAccess.UNAVAILABLE
            }
        }
        result
    }
}

private fun RelaySubscriptionResult.toSubscriptionResult(): SubscriptionResult = when (this) {
    is RelayEndpointResult.Success -> SubscriptionResult.Status(value.active)
    is RelayEndpointResult.Rejected -> SubscriptionResult.Rejected(status, code, message)
    is RelayEndpointResult.Unavailable -> SubscriptionResult.Unavailable(cause.message)
    RelayEndpointResult.InvalidResponse -> SubscriptionResult.InvalidRelayResponse
}
