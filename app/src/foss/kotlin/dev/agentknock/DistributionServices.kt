package dev.agentknock

import android.app.Activity
import android.content.Context
import dev.agentknock.push.PushRegistration
import dev.agentknock.push.PushServiceIssue
import dev.agentknock.relay.RelayHttpTransport
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource
import dev.agentknock.subscription.PlaySubscriptionBilling
import dev.agentknock.subscription.PlaySubscriptionLaunchResult
import dev.agentknock.subscription.PlaySubscriptionOfferId
import dev.agentknock.subscription.PlaySubscriptionQueryResult
import dev.agentknock.subscription.PlaySubscriptionUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

internal const val BACKGROUND_DELIVERY_SUPPORTED = false

@Suppress("UNUSED_PARAMETER")
internal fun createPushRegistration(
    context: Context,
    deviceAuthorization: RelayDeviceAuthorizationSource,
    transport: RelayHttpTransport,
): PushRegistration = ForegroundOnlyPushRegistration

private object ForegroundOnlyPushRegistration : PushRegistration {
    override val registrationState =
        MutableStateFlow<RelayPushRegistrationState?>(null).asStateFlow()

    // The relay still reports its push state, but this build has no push transport to register.
    override fun updateRelayState(state: RelayPushRegistrationState) = Unit
}

internal fun createSubscriptionBilling(
    @Suppress("UNUSED_PARAMETER") context: Context
): PlaySubscriptionBilling = UnsupportedPlayBilling

private object UnsupportedPlayBilling : PlaySubscriptionBilling {
    override val updates = emptyFlow<PlaySubscriptionUpdate>()

    override suspend fun query() = PlaySubscriptionQueryResult.NotSupported

    override suspend fun launchPurchase(activity: Activity, offerId: PlaySubscriptionOfferId) =
        PlaySubscriptionLaunchResult.Unavailable
}

internal fun pushServiceIssue(@Suppress("UNUSED_PARAMETER") context: Context): PushServiceIssue? =
    null
