package dev.agentknock.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.subscription.GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID
import dev.agentknock.subscription.PlayPurchaseState
import dev.agentknock.subscription.PlaySubscriptionBilling
import dev.agentknock.subscription.PlaySubscriptionLaunchResult
import dev.agentknock.subscription.PlaySubscriptionOffer
import dev.agentknock.subscription.PlaySubscriptionOfferId
import dev.agentknock.subscription.PlaySubscriptionQueryResult
import dev.agentknock.subscription.PlaySubscriptionSnapshot
import dev.agentknock.subscription.PlaySubscriptionUpdate
import dev.agentknock.subscription.SubscriptionRepository
import dev.agentknock.subscription.SubscriptionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SubscriptionAccess {
    CHECKING,
    SETUP_REQUIRED,
    FREE,
    ACTIVE,
    UNAVAILABLE,
}

internal enum class PlayStoreAvailability {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

internal enum class GooglePlayPurchaseState {
    NONE,
    PENDING,
    PURCHASED,
}

internal data class SubscriptionNotice(
    val message: String,
    val successful: Boolean,
)

internal data class SubscriptionUiState(
    val access: SubscriptionAccess = SubscriptionAccess.CHECKING,
    val playStore: PlayStoreAvailability = PlayStoreAvailability.CHECKING,
    val offers: List<PlaySubscriptionOffer> = emptyList(),
    val googlePlayPurchase: GooglePlayPurchaseState = GooglePlayPurchaseState.NONE,
    val googlePlayProductId: String? = null,
    val refreshing: Boolean = false,
    val redeeming: Boolean = false,
    val purchasing: Boolean = false,
    val notice: SubscriptionNotice? = null,
)

internal class SubscriptionViewModel(
    private val repository: SubscriptionRepository,
    private val billing: PlaySubscriptionBilling,
    private val awaitStorageReady: suspend () -> Unit,
) : ViewModel() {
    private val operations = Mutex()
    private val _state = MutableStateFlow(SubscriptionUiState())

    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            billing.updates.collect { update ->
                when (update) {
                    PlaySubscriptionUpdate.Changed -> refresh(purchaseUpdate = true)
                    PlaySubscriptionUpdate.Canceled -> _state.update {
                        it.copy(purchasing = false)
                    }
                    PlaySubscriptionUpdate.Failed -> _state.update {
                        it.copy(
                            purchasing = false,
                            notice = SubscriptionNotice(
                                message = "Google Play could not complete the purchase",
                                successful = false,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun refresh() {
        refresh(purchaseUpdate = false)
    }

    fun subscribe(activity: Activity, offerId: PlaySubscriptionOfferId) {
        viewModelScope.launch {
            operations.withLock {
                _state.update { it.copy(purchasing = true, notice = null) }
                when (operation { billing.launchPurchase(activity, offerId) }) {
                    PlaySubscriptionLaunchResult.Started -> Unit
                    PlaySubscriptionLaunchResult.AlreadyOwned -> refreshLocked(
                        purchaseUpdate = false,
                    )
                    PlaySubscriptionLaunchResult.Unavailable,
                    null,
                    -> _state.update {
                        it.copy(
                            purchasing = false,
                            notice = SubscriptionNotice(
                                message = "Google Play is not available right now",
                                successful = false,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun redeem(redemptionToken: String) {
        viewModelScope.launch {
            operations.withLock {
                _state.update { it.copy(redeeming = true, notice = null) }
                val result = operation { repository.redeem(redemptionToken) }
                _state.update { current ->
                    when (result) {
                        is SubscriptionResult.Status -> current.copy(
                            access = if (result.active) {
                                SubscriptionAccess.ACTIVE
                            } else {
                                SubscriptionAccess.FREE
                            },
                            redeeming = false,
                            notice = SubscriptionNotice(
                                message = if (result.active) {
                                    "Subscription access activated"
                                } else {
                                    "The subscription is not active"
                                },
                                successful = result.active,
                            ),
                        )
                        else -> current.copy(
                            access = if (current.access == SubscriptionAccess.CHECKING) {
                                SubscriptionAccess.UNAVAILABLE
                            } else {
                                current.access
                            },
                            redeeming = false,
                            notice = SubscriptionNotice(
                                message = result.redemptionFailureMessage(),
                                successful = false,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun reportInvalidLink() {
        _state.update {
            it.copy(
                notice = SubscriptionNotice(
                    message = "This subscription link is invalid or incomplete",
                    successful = false,
                ),
            )
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun refresh(purchaseUpdate: Boolean) {
        viewModelScope.launch {
            operations.withLock {
                refreshLocked(purchaseUpdate)
            }
        }
    }

    private suspend fun refreshLocked(purchaseUpdate: Boolean) {
        _state.update { it.copy(refreshing = true, purchasing = false) }
        val playResult = operation { billing.query() }
        val snapshot = (playResult as? PlaySubscriptionQueryResult.Success)?.snapshot
        _state.update { current -> current.withPlaySnapshot(snapshot) }

        val purchased = snapshot?.purchases
            ?.filter { it.state == PlayPurchaseState.PURCHASED }
            ?.maxByOrNull { it.purchasedAtMillis }
        val activation = purchased?.let {
            operation { repository.updateFromGooglePlay(it.token) }
        }
        val result = when (activation) {
            is SubscriptionResult.Status -> activation
            else -> operation { repository.status() }
        }
        val activationFailed = purchased != null && activation !is SubscriptionResult.Status
        _state.update { current ->
            current.copy(
                access = result.toAccess(),
                refreshing = false,
                notice = when {
                    activationFailed && result !is SubscriptionResult.Status ->
                        SubscriptionNotice(
                            message = activation.googlePlayFailureMessage(),
                            successful = false,
                        )
                    purchaseUpdate && activation is SubscriptionResult.Status && activation.active ->
                        SubscriptionNotice(
                            message = "Subscription activated",
                            successful = true,
                        )
                    else -> current.notice
                },
            )
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): T? = try {
        awaitStorageReady()
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun SubscriptionUiState.withPlaySnapshot(
    snapshot: PlaySubscriptionSnapshot?,
): SubscriptionUiState {
    val relevant = snapshot?.purchases
        ?.filter { it.productId == GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID }
        .orEmpty()
    val purchase = when {
        relevant.any { it.state == PlayPurchaseState.PURCHASED } ->
            GooglePlayPurchaseState.PURCHASED
        relevant.any { it.state == PlayPurchaseState.PENDING } ->
            GooglePlayPurchaseState.PENDING
        else -> GooglePlayPurchaseState.NONE
    }
    return copy(
        playStore = if (snapshot?.offersAvailable == true) {
            PlayStoreAvailability.AVAILABLE
        } else {
            PlayStoreAvailability.UNAVAILABLE
        },
        offers = snapshot?.offers.orEmpty(),
        googlePlayPurchase = purchase,
        googlePlayProductId = relevant.maxByOrNull { it.purchasedAtMillis }?.productId,
    )
}

internal fun SubscriptionUiState.overviewLabel(): String = when (access) {
    SubscriptionAccess.CHECKING -> "Checking…"
    SubscriptionAccess.SETUP_REQUIRED -> "Finish device setup"
    SubscriptionAccess.FREE -> "Free · AI review requires a subscription"
    SubscriptionAccess.ACTIVE -> "AI review active"
    SubscriptionAccess.UNAVAILABLE -> "Status unavailable"
}

private fun SubscriptionResult?.toAccess(): SubscriptionAccess = when (this) {
    is SubscriptionResult.Status -> if (active) SubscriptionAccess.ACTIVE else SubscriptionAccess.FREE
    SubscriptionResult.NoDevice -> SubscriptionAccess.SETUP_REQUIRED
    else -> SubscriptionAccess.UNAVAILABLE
}

private fun SubscriptionResult?.redemptionFailureMessage(): String = when (this) {
    is SubscriptionResult.Rejected -> if (status == 401) {
        "This subscription link is invalid or no longer available"
    } else {
        "The subscription could not be updated"
    }
    SubscriptionResult.NoDevice -> "Finish device setup before activating a subscription"
    SubscriptionResult.DeviceCredentialsUnavailable,
    SubscriptionResult.DeviceCredentialsCorrupted,
    SubscriptionResult.UnsupportedEncryption,
    -> "Device authentication is unavailable"
    is SubscriptionResult.Unavailable,
    SubscriptionResult.InvalidRelayResponse,
    null,
    -> "The subscription could not be updated"
    is SubscriptionResult.Status -> error("A status is not a failure")
}

private fun SubscriptionResult?.googlePlayFailureMessage(): String = when (this) {
    SubscriptionResult.NoDevice -> "Finish device setup before subscribing"
    SubscriptionResult.DeviceCredentialsUnavailable,
    SubscriptionResult.DeviceCredentialsCorrupted,
    SubscriptionResult.UnsupportedEncryption,
    -> "Device authentication is unavailable"
    is SubscriptionResult.Rejected -> "Google Play could not verify this subscription"
    is SubscriptionResult.Unavailable,
    SubscriptionResult.InvalidRelayResponse,
    null,
    -> "The Google Play purchase could not be verified right now"
    is SubscriptionResult.Status -> error("A status is not a failure")
}
