package dev.agentknock.ui.settings

import dev.agentknock.subscription.AiReviewAccess
import android.app.Activity
import androidx.lifecycle.viewModelScope
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.relay.RelaySubscriptionClient
import dev.agentknock.relay.RelaySubscriptionResult
import dev.agentknock.relay.RelaySubscriptionStatus
import dev.agentknock.storage.device.RelayDeviceAuthorization
import dev.agentknock.storage.device.DeviceCredentialResult
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource
import dev.agentknock.subscription.GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID
import dev.agentknock.subscription.PlayPurchaseState
import dev.agentknock.subscription.PlaySubscriptionBilling
import dev.agentknock.subscription.PlaySubscriptionLaunchResult
import dev.agentknock.subscription.PlaySubscriptionOfferId
import dev.agentknock.subscription.PlaySubscriptionPurchase
import dev.agentknock.subscription.PlaySubscriptionQueryResult
import dev.agentknock.subscription.PlaySubscriptionSnapshot
import dev.agentknock.subscription.PlaySubscriptionUpdate
import dev.agentknock.subscription.SubscriptionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionViewModelTest {
    @Test
    fun `code redemption shows activation without hiding existing access`() {
        assertEquals(AiReviewAccess.ACTIVATING,
            SubscriptionUiState(access = AiReviewAccess.INACTIVE, redeeming = true).aiReviewAccess)
        assertEquals(AiReviewAccess.ACTIVE,
            SubscriptionUiState(access = AiReviewAccess.ACTIVE, redeeming = true).aiReviewAccess)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `failed refresh preserves active controls and reports unavailable status separately`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        try {
            fixture.relay.statusResult = RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))
            fixture.viewModel.refresh()
            runCurrent()
            fixture.relay.statusResult = RelayEndpointResult.InvalidResponse
            fixture.viewModel.refresh()
            runCurrent()
            assertEquals(AiReviewAccess.ACTIVE, fixture.viewModel.state.value.aiReviewAccess)
            assertEquals(true, fixture.viewModel.state.value.statusUnavailable)
            fixture.relay.statusResult = RelayEndpointResult.Success(RelaySubscriptionStatus(active = false))
            fixture.viewModel.refresh()
            runCurrent()
            assertEquals(AiReviewAccess.INACTIVE, fixture.viewModel.state.value.aiReviewAccess)
            assertEquals(false, fixture.viewModel.state.value.statusUnavailable)
        } finally {
            fixture.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restores a purchased subscription through the relay`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        try {
            fixture.billing.queryResult = queryResult(PlayPurchaseState.PURCHASED)
            fixture.relay.googlePlayResult =
                RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))

            fixture.viewModel.refresh()
            runCurrent()

            assertEquals(PURCHASE_TOKEN, fixture.relay.googlePlayPurchase)
            assertEquals(0, fixture.relay.statusCalls)
            assertEquals(AiReviewAccess.ACTIVE, fixture.viewModel.state.value.access)
            assertEquals(
                GooglePlayPurchaseState.PURCHASED,
                fixture.viewModel.state.value.googlePlayPurchase,
            )
        } finally {
            fixture.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `keeps an existing entitlement when a purchase update fails`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        try {
            fixture.billing.queryResult = queryResult(PlayPurchaseState.PURCHASED)
            fixture.relay.statusResult =
                RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))
            fixture.relay.googlePlayResult =
                RelayEndpointResult.Rejected(status = 401, code = null, message = null)

            fixture.viewModel.refresh()
            runCurrent()

            assertEquals(PURCHASE_TOKEN, fixture.relay.googlePlayPurchase)
            assertEquals(1, fixture.relay.statusCalls)
            assertEquals(AiReviewAccess.ACTIVE, fixture.viewModel.state.value.access)
        } finally {
            fixture.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `does not submit a pending purchase`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        try {
            fixture.billing.queryResult = queryResult(PlayPurchaseState.PENDING)

            fixture.viewModel.refresh()
            runCurrent()

            assertEquals(null, fixture.relay.googlePlayPurchase)
            assertEquals(1, fixture.relay.statusCalls)
            assertEquals(AiReviewAccess.INACTIVE, fixture.viewModel.state.value.access)
            assertEquals(
                GooglePlayPurchaseState.PENDING,
                fixture.viewModel.state.value.googlePlayPurchase,
            )
        } finally {
            fixture.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `keeps server entitlement when Google Play is unavailable`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        try {
            fixture.billing.queryResult = PlaySubscriptionQueryResult.Unavailable
            fixture.relay.statusResult =
                RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))

            fixture.viewModel.refresh()
            runCurrent()

            assertEquals(AiReviewAccess.ACTIVE, fixture.viewModel.state.value.access)
            assertEquals(
                PlayStoreAvailability.UNAVAILABLE,
                fixture.viewModel.state.value.playStore,
            )
        } finally {
            fixture.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `billing unsupported keeps access inactive until redeemed and refreshes existing access`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        try {
            fixture.billing.queryResult = PlaySubscriptionQueryResult.NotSupported
            fixture.viewModel.refresh()
            runCurrent()
            assertEquals(PlayStoreAvailability.NOT_SUPPORTED, fixture.viewModel.state.value.playStore)
            assertEquals(AiReviewAccess.INACTIVE, fixture.viewModel.state.value.access)
            assertEquals(true, fixture.viewModel.state.value.offers.isEmpty())
            assertEquals(null, fixture.relay.googlePlayPurchase)

            fixture.viewModel.redeem("activation-token")
            runCurrent()
            assertEquals("activation-token", fixture.relay.redeemedToken)
            assertEquals(AiReviewAccess.ACTIVE, fixture.viewModel.state.value.access)

            fixture.relay.statusResult = RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))
            fixture.viewModel.refresh()
            runCurrent()
            assertEquals(AiReviewAccess.ACTIVE, fixture.viewModel.state.value.access)
            assertEquals(PlayStoreAvailability.NOT_SUPPORTED, fixture.viewModel.state.value.playStore)
            assertEquals(null, fixture.relay.googlePlayPurchase)
        } finally {
            fixture.close()
        }
    }

    private fun queryResult(state: PlayPurchaseState) = PlaySubscriptionQueryResult.Success(
        PlaySubscriptionSnapshot(
            purchases = listOf(
                PlaySubscriptionPurchase(
                    token = PURCHASE_TOKEN,
                    productId = GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID,
                    purchasedAtMillis = 1_000,
                    state = state,
                ),
            ),
            offers = emptyList(),
            offersAvailable = true,
        ),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private class Fixture(dispatcher: CoroutineDispatcher) {
        val billing = FakeBilling()
        val relay = FakeRelay()
        val viewModel: SubscriptionViewModel

        init {
            Dispatchers.setMain(dispatcher)
            val authorization = RelayDeviceAuthorizationSource {
                DeviceCredentialResult.Available(
                    RelayDeviceAuthorization(
                        deviceIdentityId = "identity",
                        deviceId = "device",
                        deviceToken = "token",
                    ),
                )
            }
            viewModel = SubscriptionViewModel(
                repository = SubscriptionRepository(authorization, relay),
                billing = billing,
                awaitStorageReady = {},
            )
        }

        fun close() {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class FakeBilling : PlaySubscriptionBilling {
        private val events = MutableSharedFlow<PlaySubscriptionUpdate>()
        var queryResult: PlaySubscriptionQueryResult = PlaySubscriptionQueryResult.Unavailable

        override val updates: Flow<PlaySubscriptionUpdate> = events

        override suspend fun query(): PlaySubscriptionQueryResult = queryResult

        override suspend fun launchPurchase(
            activity: Activity,
            offerId: PlaySubscriptionOfferId,
        ): PlaySubscriptionLaunchResult = PlaySubscriptionLaunchResult.Unavailable
    }

    private class FakeRelay : RelaySubscriptionClient {
        var statusResult: RelaySubscriptionResult =
            RelayEndpointResult.Success(RelaySubscriptionStatus(active = false))
        var googlePlayResult: RelaySubscriptionResult =
            RelayEndpointResult.Success(RelaySubscriptionStatus(active = false))
        var statusCalls = 0
        var googlePlayPurchase: String? = null
        var redeemedToken: String? = null

        override suspend fun status(
            deviceId: String,
            deviceToken: String,
        ): RelaySubscriptionResult {
            statusCalls += 1
            return statusResult
        }

        override suspend fun redeem(
            deviceId: String,
            deviceToken: String,
            redemptionToken: String,
        ): RelaySubscriptionResult {
            redeemedToken = redemptionToken
            return RelayEndpointResult.Success(RelaySubscriptionStatus(active = true))
        }

        override suspend fun updateFromGooglePlay(
            deviceId: String,
            deviceToken: String,
            purchaseToken: String,
        ): RelaySubscriptionResult {
            googlePlayPurchase = purchaseToken
            return googlePlayResult
        }
    }

    private companion object {
        const val PURCHASE_TOKEN = "google-play-purchase-token"
    }
}
