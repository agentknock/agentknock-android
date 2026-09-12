package dev.agentknock.subscription

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class GooglePlaySubscriptionBilling(context: Context) : PlaySubscriptionBilling {
    private val connectionMutex = Mutex()
    private val _updates = MutableSharedFlow<PlaySubscriptionUpdate>(extraBufferCapacity = 4)
    private val purchaseListener = PurchasesUpdatedListener { result, purchases ->
        _updates.tryEmit(
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK ->
                    if (purchases.isNullOrEmpty()) {
                        PlaySubscriptionUpdate.Failed
                    } else {
                        PlaySubscriptionUpdate.Changed
                    }
                BillingClient.BillingResponseCode.USER_CANCELED -> PlaySubscriptionUpdate.Canceled
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                    PlaySubscriptionUpdate.Changed
                else -> PlaySubscriptionUpdate.Failed
            }
        )
    }
    private val client =
        BillingClient.newBuilder(context.applicationContext)
            .setListener(purchaseListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .enableAutoServiceReconnection()
            .build()

    override val updates: Flow<PlaySubscriptionUpdate> = _updates.asSharedFlow()

    override suspend fun query(): PlaySubscriptionQueryResult {
        if (!connect()) return PlaySubscriptionQueryResult.Unavailable
        val purchases = queryPurchases() ?: return PlaySubscriptionQueryResult.Unavailable
        val productDetails = queryProductDetails()
        return PlaySubscriptionQueryResult.Success(
            PlaySubscriptionSnapshot(
                purchases = purchases,
                offers = preferFreeTrialOffers(productDetails?.flatMap(::offers).orEmpty()),
                offersAvailable = productDetails != null,
            )
        )
    }

    override suspend fun launchPurchase(
        activity: Activity,
        offerId: PlaySubscriptionOfferId,
    ): PlaySubscriptionLaunchResult {
        if (!connect()) return PlaySubscriptionLaunchResult.Unavailable
        val product =
            queryProductDetails()?.firstOrNull { it.productId == offerId.productId }
                ?: return PlaySubscriptionLaunchResult.Unavailable
        val offer =
            product.subscriptionOfferDetails?.firstOrNull {
                it.basePlanId == offerId.basePlanId && it.offerId == offerId.offerId
            } ?: return PlaySubscriptionLaunchResult.Unavailable
        val params =
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product)
                            .setOfferToken(offer.offerToken)
                            .build()
                    )
                )
                .build()
        val result =
            withContext(Dispatchers.Main.immediate) {
                client.launchBillingFlow(activity, params)
            }
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> PlaySubscriptionLaunchResult.Started
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                PlaySubscriptionLaunchResult.AlreadyOwned
            else -> PlaySubscriptionLaunchResult.Unavailable
        }
    }

    private suspend fun connect(): Boolean = connectionMutex.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { continuation ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (continuation.isActive) {
                            continuation.resume(
                                result.responseCode == BillingClient.BillingResponseCode.OK
                            )
                        }
                    }

                    override fun onBillingServiceDisconnected() = Unit
                }
            )
        }
    }

    private suspend fun queryPurchases(): List<PlaySubscriptionPurchase>? {
        // The relay tracks lifecycle changes through Google Play notifications. Sending a
        // suspended purchase here could replace a different entitlement that is still valid.
        val result =
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        return result.purchasesList.flatMap(::purchases)
    }

    private suspend fun queryProductDetails(): List<ProductDetails>? {
        val result =
            client.queryProductDetails(
                QueryProductDetailsParams.newBuilder()
                    .setProductList(
                        listOf(
                            QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID)
                                .setProductType(BillingClient.ProductType.SUBS)
                                .build()
                        )
                    )
                    .build()
            )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        return result.productDetailsList
    }

    private fun purchases(purchase: Purchase): List<PlaySubscriptionPurchase> {
        val state =
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> PlayPurchaseState.PURCHASED
                Purchase.PurchaseState.PENDING -> PlayPurchaseState.PENDING
                else -> return emptyList()
            }
        return purchase.products
            .filter { it == GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID }
            .map { productId ->
                PlaySubscriptionPurchase(
                    token = purchase.purchaseToken,
                    productId = productId,
                    purchasedAtMillis = purchase.purchaseTime,
                    state = state,
                )
            }
    }

    private fun offers(product: ProductDetails): List<PlaySubscriptionOffer> =
        product.subscriptionOfferDetails.orEmpty().mapNotNull { offer ->
            val phases =
                offer.pricingPhases.pricingPhaseList.map { phase ->
                    SubscriptionPricingPhase(
                        formattedPrice = phase.formattedPrice,
                        priceAmountMicros = phase.priceAmountMicros,
                        billingPeriod = phase.billingPeriod,
                        billingCycleCount = phase.billingCycleCount,
                        recurrence =
                            when (phase.recurrenceMode) {
                                ProductDetails.RecurrenceMode.INFINITE_RECURRING ->
                                    PricingRecurrence.INFINITE
                                ProductDetails.RecurrenceMode.FINITE_RECURRING ->
                                    PricingRecurrence.FINITE
                                ProductDetails.RecurrenceMode.NON_RECURRING ->
                                    PricingRecurrence.NONE
                                else -> return@mapNotNull null
                            },
                    )
                }
            val summary = summarizeSubscriptionPricing(phases) ?: return@mapNotNull null
            PlaySubscriptionOffer(
                id =
                    PlaySubscriptionOfferId(
                        productId = product.productId,
                        basePlanId = offer.basePlanId,
                        offerId = offer.offerId,
                    ),
                price = summary.price,
                terms = summary.terms,
                autoRenewing = summary.autoRenewing,
                freeTrialDuration = summary.freeTrialDuration,
            )
        }
}

// Play returns the paid base plan alongside user-eligible offers for that same plan.
internal fun preferFreeTrialOffers(
    offers: List<PlaySubscriptionOffer>
): List<PlaySubscriptionOffer> {
    val trialPlans =
        offers
            .filter { it.id.offerId != null && it.freeTrialDuration != null }
            .map { it.id.productId to it.id.basePlanId }
            .toSet()
    return offers.filter {
        it.id.offerId != null || (it.id.productId to it.id.basePlanId) !in trialPlans
    }
}

internal enum class PricingRecurrence {
    INFINITE,
    FINITE,
    NONE,
}

internal data class SubscriptionPricingPhase(
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val billingPeriod: String,
    val billingCycleCount: Int,
    val recurrence: PricingRecurrence,
)

internal data class SubscriptionPricingSummary(
    val price: String,
    val terms: String,
    val autoRenewing: Boolean,
    val freeTrialDuration: String?,
)

internal fun summarizeSubscriptionPricing(
    phases: List<SubscriptionPricingPhase>
): SubscriptionPricingSummary? {
    val final = phases.lastOrNull() ?: return null
    val finalDuration =
        billingPeriodDuration(
            period = final.billingPeriod,
            cycles = final.billingCycleCount.coerceAtLeast(1),
        ) ?: return null
    val autoRenewing = final.recurrence == PricingRecurrence.INFINITE
    val price =
        when (final.recurrence) {
            PricingRecurrence.INFINITE -> "${final.formattedPrice}/${finalDuration.priceUnit}"
            PricingRecurrence.FINITE ->
                if (final.priceAmountMicros == 0L) {
                    "$finalDuration free"
                } else if (final.billingCycleCount > 1) {
                    val period = billingPeriodDuration(final.billingPeriod, 1) ?: return null
                    "${final.formattedPrice}/${period.priceUnit} for $finalDuration"
                } else {
                    "${final.formattedPrice} for $finalDuration"
                }
            PricingRecurrence.NONE ->
                if (final.priceAmountMicros == 0L) {
                    "$finalDuration free"
                } else {
                    "${final.formattedPrice} for $finalDuration"
                }
        }
    val introductory = phases.dropLast(1).map { describeIntroductoryPhase(it) ?: return null }
    val first = phases.first()
    val freeTrialDuration =
        if (phases.size > 1 && first.priceAmountMicros == 0L) {
            billingPeriodDuration(first.billingPeriod, first.billingCycleCount.coerceAtLeast(1))
                ?.toString() ?: return null
        } else {
            null
        }
    val terms = buildList {
        if (introductory.isNotEmpty()) {
            add("${introductory.joinToString(", then ")}, then $price.")
        }
        if (freeTrialDuration != null) {
            add("You will be charged automatically unless you cancel before the trial ends.")
        }
        add(
            if (autoRenewing) {
                "Renews automatically until canceled. Manage or cancel in Google Play."
            } else {
                "Does not renew automatically."
            }
        )
    }
        .joinToString(" ")
    return SubscriptionPricingSummary(price, terms, autoRenewing, freeTrialDuration)
}

private fun describeIntroductoryPhase(phase: SubscriptionPricingPhase): String? {
    val duration =
        billingPeriodDuration(
            period = phase.billingPeriod,
            cycles = phase.billingCycleCount.coerceAtLeast(1),
        ) ?: return null
    return if (phase.priceAmountMicros == 0L) {
        "$duration free"
    } else if (phase.recurrence == PricingRecurrence.FINITE && phase.billingCycleCount > 1) {
        val period = billingPeriodDuration(phase.billingPeriod, 1) ?: return null
        "${phase.formattedPrice}/${period.priceUnit} for $duration"
    } else {
        "${phase.formattedPrice} for $duration"
    }
}

private data class BillingDuration(
    val count: Int,
    val unit: String,
) {
    val priceUnit: String
        get() = if (count == 1) unit else toString()

    override fun toString(): String = "$count ${if (count == 1) unit else "${unit}s"}"
}

private fun billingPeriodDuration(period: String, cycles: Int): BillingDuration? {
    val match = BILLING_PERIOD.matchEntire(period) ?: return null
    val components =
        listOf(
                "year" to match.groupValues[1],
                "month" to match.groupValues[2],
                "week" to match.groupValues[3],
                "day" to match.groupValues[4],
            )
            .filter { (_, value) -> value.isNotEmpty() }
    if (components.size != 1) return null
    val (unit, value) = components.single()
    return BillingDuration(value.toInt() * cycles, unit)
}

private val BILLING_PERIOD = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?")
