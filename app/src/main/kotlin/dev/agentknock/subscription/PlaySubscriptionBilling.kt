package dev.agentknock.subscription

import android.app.Activity
import kotlinx.coroutines.flow.Flow

internal const val GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID = "agentknock_subscription"

internal data class PlaySubscriptionOfferId(
    val productId: String,
    val basePlanId: String,
    val offerId: String?,
)

internal data class PlaySubscriptionOffer(
    val id: PlaySubscriptionOfferId,
    val price: String,
    val terms: String,
    val autoRenewing: Boolean,
)

internal enum class PlayPurchaseState {
    PURCHASED,
    PENDING,
}

internal data class PlaySubscriptionPurchase(
    val token: String,
    val productId: String,
    val purchasedAtMillis: Long,
    val state: PlayPurchaseState,
)

internal data class PlaySubscriptionSnapshot(
    val purchases: List<PlaySubscriptionPurchase>,
    val offers: List<PlaySubscriptionOffer>,
    val offersAvailable: Boolean,
)

internal sealed interface PlaySubscriptionQueryResult {
    data class Success(val snapshot: PlaySubscriptionSnapshot) : PlaySubscriptionQueryResult

    data object Unavailable : PlaySubscriptionQueryResult

    data object NotSupported : PlaySubscriptionQueryResult
}

internal sealed interface PlaySubscriptionLaunchResult {
    data object Started : PlaySubscriptionLaunchResult

    data object AlreadyOwned : PlaySubscriptionLaunchResult

    data object Unavailable : PlaySubscriptionLaunchResult
}

internal sealed interface PlaySubscriptionUpdate {
    data object Changed : PlaySubscriptionUpdate

    data object Canceled : PlaySubscriptionUpdate

    data object Failed : PlaySubscriptionUpdate
}

internal interface PlaySubscriptionBilling {
    val updates: Flow<PlaySubscriptionUpdate>

    suspend fun query(): PlaySubscriptionQueryResult

    suspend fun launchPurchase(
        activity: Activity,
        offerId: PlaySubscriptionOfferId,
    ): PlaySubscriptionLaunchResult
}
