package dev.agentknock

import android.content.Context
import dev.agentknock.push.FirebaseRegistrationWorker
import dev.agentknock.push.PushRegistrationRepository
import dev.agentknock.relay.HttpRelayPushRegistrationClient
import dev.agentknock.relay.RelayHttpTransport
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource
import dev.agentknock.subscription.GooglePlaySubscriptionBilling
import dev.agentknock.subscription.PlaySubscriptionBilling

internal const val BACKGROUND_DELIVERY_SUPPORTED = true

internal fun createPushRegistration(
    context: Context,
    deviceAuthorization: RelayDeviceAuthorizationSource,
    transport: RelayHttpTransport,
): PushRegistrationRepository = PushRegistrationRepository(
    deviceAuthorization = deviceAuthorization,
    relay = HttpRelayPushRegistrationClient(transport),
    requestRegistration = { FirebaseRegistrationWorker.enqueue(context) },
)

internal fun createSubscriptionBilling(context: Context): PlaySubscriptionBilling =
    GooglePlaySubscriptionBilling(context)
