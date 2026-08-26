package dev.agentknock.subscription

import dev.agentknock.relay.RelaySubscriptionClient
import dev.agentknock.relay.RelaySubscriptionResult
import dev.agentknock.storage.vault.RelayDeviceCredentialSource
import dev.agentknock.storage.vault.RelayDeviceCredentials
import dev.agentknock.storage.vault.RelayDeviceCredentialsResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionRepositoryTest {
    @Test
    fun `gets status with active device credentials`() = runTest {
        val relay = FakeRelay(statusResult = RelaySubscriptionResult.Status(active = true))
        val repository = SubscriptionRepository(AvailableCredentials, relay)

        assertEquals(SubscriptionResult.Status(active = true), repository.status())
        assertEquals(DEVICE_ID to DEVICE_TOKEN, relay.statusCredentials)
    }

    @Test
    fun `redeems with active device credentials`() = runTest {
        val relay = FakeRelay(redeemResult = RelaySubscriptionResult.Status(active = true))
        val repository = SubscriptionRepository(AvailableCredentials, relay)

        assertEquals(
            SubscriptionResult.Status(active = true),
            repository.redeem(REDEMPTION_TOKEN),
        )
        assertEquals(Triple(DEVICE_ID, DEVICE_TOKEN, REDEMPTION_TOKEN), relay.redemption)
    }

    @Test
    fun `does not contact relay without an active device`() = runTest {
        val relay = FakeRelay()
        val credentials = object : RelayDeviceCredentialSource {
            override suspend fun activeDeviceCredentials() = RelayDeviceCredentialsResult.Missing

            override suspend fun deviceCredentials(deviceIdentityId: String) =
                RelayDeviceCredentialsResult.Missing
        }
        val repository = SubscriptionRepository(credentials, relay)

        assertEquals(SubscriptionResult.NoDevice, repository.status())
        assertEquals(null, relay.statusCredentials)
    }

    private object AvailableCredentials : RelayDeviceCredentialSource {
        override suspend fun activeDeviceCredentials() = RelayDeviceCredentialsResult.Available(
            RelayDeviceCredentials(
                deviceIdentityId = "identity",
                address = "normal-judge-donor",
                addressId = "00000000000000000000000000000000",
                deviceId = DEVICE_ID,
                devicePublicKey = ByteArray(32),
                devicePrivateKey = ByteArray(32),
                deviceToken = DEVICE_TOKEN,
            ),
        )

        override suspend fun deviceCredentials(deviceIdentityId: String) =
            activeDeviceCredentials()
    }

    private class FakeRelay(
        private val statusResult: RelaySubscriptionResult =
            RelaySubscriptionResult.Status(active = false),
        private val redeemResult: RelaySubscriptionResult =
            RelaySubscriptionResult.Status(active = false),
    ) : RelaySubscriptionClient {
        var statusCredentials: Pair<String, String>? = null
        var redemption: Triple<String, String, String>? = null

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
    }

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val REDEMPTION_TOKEN = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDI"
    }
}
