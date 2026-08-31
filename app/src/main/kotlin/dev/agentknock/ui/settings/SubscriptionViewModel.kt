package dev.agentknock.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    FREE,
    ACTIVE,
    UNAVAILABLE,
}

internal data class SubscriptionNotice(
    val message: String,
    val successful: Boolean,
)

internal data class SubscriptionUiState(
    val access: SubscriptionAccess = SubscriptionAccess.CHECKING,
    val refreshing: Boolean = false,
    val redeeming: Boolean = false,
    val notice: SubscriptionNotice? = null,
)

internal class SubscriptionViewModel(
    private val repository: SubscriptionRepository,
    private val awaitStorageReady: suspend () -> Unit,
) : ViewModel() {
    private val operations = Mutex()
    private val _state = MutableStateFlow(SubscriptionUiState())

    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            operations.withLock {
                _state.update { it.copy(refreshing = true) }
                val result = try {
                    awaitStorageReady()
                    repository.status()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                _state.update { current ->
                    current.copy(
                        access = result.toAccess(),
                        refreshing = false,
                    )
                }
            }
        }
    }

    fun redeem(redemptionToken: String) {
        viewModelScope.launch {
            operations.withLock {
                _state.update { it.copy(redeeming = true, notice = null) }
                val result = try {
                    awaitStorageReady()
                    repository.redeem(redemptionToken)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
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
                                message = result.updateFailureMessage(),
                                successful = false,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun reportInvalidLink() {
        viewModelScope.launch {
            operations.withLock {
                _state.update {
                    it.copy(
                        notice = SubscriptionNotice(
                            message = "This subscription link is invalid or incomplete",
                            successful = false,
                        ),
                    )
                }
            }
        }
    }
}

internal fun SubscriptionUiState.overviewLabel(): String = when (access) {
    SubscriptionAccess.CHECKING -> "Checking…"
    SubscriptionAccess.FREE -> "Free · AI review requires a subscription"
    SubscriptionAccess.ACTIVE -> "AI review active"
    SubscriptionAccess.UNAVAILABLE -> "Status unavailable"
}

private fun SubscriptionResult?.toAccess(): SubscriptionAccess = when (this) {
    is SubscriptionResult.Status -> if (active) SubscriptionAccess.ACTIVE else SubscriptionAccess.FREE
    else -> SubscriptionAccess.UNAVAILABLE
}

private fun SubscriptionResult?.updateFailureMessage(): String = when (this) {
    is SubscriptionResult.Rejected -> if (status == 401) {
        "This subscription link is invalid or no longer available"
    } else {
        "The subscription could not be updated"
    }
    SubscriptionResult.NoDevice -> "Finish device setup before activating a subscription"
    SubscriptionResult.DeviceCredentialsUnavailable,
    SubscriptionResult.DeviceCredentialsCorrupted,
    SubscriptionResult.UnsupportedEncryption -> "Device authentication is unavailable"
    is SubscriptionResult.Unavailable,
    SubscriptionResult.InvalidRelayResponse,
    null -> "The subscription could not be updated"
    is SubscriptionResult.Status -> error("A status is not a failure")
}
