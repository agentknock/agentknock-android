package dev.agentknock.subscription

import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.relay.RelaySubscriptionClient
import dev.agentknock.relay.RelaySubscriptionResult
import dev.agentknock.relay.RelaySubscriptionStatus
import dev.agentknock.storage.device.DeviceCredentialResult
import dev.agentknock.storage.device.RelayDeviceAuthorization
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionRepositoryTest {
    @Test
    fun `transient failure preserves known access without assuming new access`() = runTest {
        val relay = FakeRelay()
        val repository = SubscriptionRepository(availableAuthorization, relay)
        repository.status()
        relay.statusResult = RelayEndpointResult.InvalidResponse

        repository.status()
        assertEquals(AiReviewAccess.INACTIVE, repository.access.value)
        relay.statusResult = RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))
        repository.status()
        relay.statusResult = RelayEndpointResult.InvalidResponse
        repository.status()
        assertEquals(AiReviewAccess.ACTIVE, repository.access.value)
    }

    @Test
    fun `reviews update access without status but an old device cannot change it`() = runTest {
        val relay = FakeRelay()
        val repository = SubscriptionRepository(availableAuthorization, relay)
        repository.recordReviewAccess("old-device", active = true)
        assertEquals(AiReviewAccess.CHECKING, repository.access.value)
        repository.recordReviewAccess(DEVICE_ID, active = true)
        assertEquals(AiReviewAccess.ACTIVE, repository.access.value)
        repository.recordReviewAccess("old-device", active = false)
        assertEquals(AiReviewAccess.ACTIVE, repository.access.value)
        repository.recordReviewAccess(DEVICE_ID, active = false)
        assertEquals(AiReviewAccess.INACTIVE, repository.access.value)
        repository.recordReviewAccess("old-device", active = true)
        assertEquals(AiReviewAccess.INACTIVE, repository.access.value)
        repository.recordReviewAccess(DEVICE_ID, active = true)
        assertEquals(AiReviewAccess.ACTIVE, repository.access.value)
        assertEquals(null, relay.statusCredentials)
    }

    @Test
    fun `switching devices discards the previous entitlement even when status fails`() = runTest {
        var authorization = RelayDeviceAuthorization("identity", DEVICE_ID, DEVICE_TOKEN)
        val relay =
            FakeRelay(
                statusResult = RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))
            )
        val repository =
            SubscriptionRepository(
                RelayDeviceAuthorizationSource { DeviceCredentialResult.Available(authorization) },
                relay,
            )
        repository.status()
        authorization = RelayDeviceAuthorization("new-identity", "new-device", "new-token")
        relay.statusResult = RelayEndpointResult.InvalidResponse
        repository.status()
        assertEquals(AiReviewAccess.UNAVAILABLE, repository.access.value)
    }

    @Test
    fun `gets status with active device credentials`() = runTest {
        val relay =
            FakeRelay(
                statusResult = RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))
            )
        val repository = SubscriptionRepository(availableAuthorization, relay)

        assertEquals(SubscriptionResult.Status(active = true), repository.status())
        assertEquals(DEVICE_ID to DEVICE_TOKEN, relay.statusCredentials)
    }

    @Test
    fun `redeems with active device credentials`() = runTest {
        val relay =
            FakeRelay(
                redeemResult = RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))
            )
        val repository = SubscriptionRepository(availableAuthorization, relay)

        assertEquals(
            SubscriptionResult.Status(active = true),
            repository.redeem(REDEMPTION_TOKEN),
        )
        assertEquals(Triple(DEVICE_ID, DEVICE_TOKEN, REDEMPTION_TOKEN), relay.redemption)
    }

    @Test
    fun `submits a Google Play purchase with active device credentials`() = runTest {
        val relay =
            FakeRelay(
                googlePlayResult =
                    RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))
            )
        val repository = SubscriptionRepository(availableAuthorization, relay)

        assertEquals(
            SubscriptionResult.Status(active = true),
            repository.updateFromGooglePlay(PURCHASE_TOKEN),
        )
        assertEquals(Triple(DEVICE_ID, DEVICE_TOKEN, PURCHASE_TOKEN), relay.googlePlayPurchase)
    }

    @Test
    fun `does not contact relay without an active device`() = runTest {
        val relay = FakeRelay()
        val authorization = RelayDeviceAuthorizationSource {
            null
        }
        val repository = SubscriptionRepository(authorization, relay)

        assertEquals(SubscriptionResult.NoDevice, repository.status())
        assertEquals(null, relay.statusCredentials)
    }

    private val availableAuthorization = RelayDeviceAuthorizationSource {
        DeviceCredentialResult.Available(
            RelayDeviceAuthorization(
                deviceIdentityId = "identity",
                deviceId = DEVICE_ID,
                deviceToken = DEVICE_TOKEN,
            )
        )
    }

    private class FakeRelay(
        var statusResult: RelaySubscriptionResult =
            RelayEndpointResult.Success(RelaySubscriptionStatus(active = false)),
        private val redeemResult: RelaySubscriptionResult =
            RelayEndpointResult.Success(RelaySubscriptionStatus(active = false)),
        private val googlePlayResult: RelaySubscriptionResult =
            RelayEndpointResult.Success(RelaySubscriptionStatus(active = false)),
    ) : RelaySubscriptionClient {
        var statusCredentials: Pair<String, String>? = null
        var redemption: Triple<String, String, String>? = null
        var googlePlayPurchase: Triple<String, String, String>? = null

        override suspend fun status(
            deviceId: String,
            deviceToken: String,
        ): RelaySubscriptionResult {
            statusCredentials = deviceId to deviceToken
            return statusResult
        }

        override suspend fun redeem(
            deviceId: String,
            deviceToken: String,
            redemptionToken: String,
        ): RelaySubscriptionResult {
            redemption = Triple(deviceId, deviceToken, redemptionToken)
            return redeemResult
        }

        override suspend fun updateFromGooglePlay(
            deviceId: String,
            deviceToken: String,
            purchaseToken: String,
        ): RelaySubscriptionResult {
            googlePlayPurchase = Triple(deviceId, deviceToken, purchaseToken)
            return googlePlayResult
        }
    }

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val REDEMPTION_TOKEN = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDI"
        const val PURCHASE_TOKEN = "google-play-purchase-token"
    }
}
