package dev.agentknock

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dev.agentknock.push.FirebaseRegistrationWorker
import dev.agentknock.push.PushRegistrationRepository
import dev.agentknock.push.PushServiceIssue
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
): PushRegistrationRepository =
    PushRegistrationRepository(
        deviceAuthorization = deviceAuthorization,
        relay = HttpRelayPushRegistrationClient(transport),
        requestRegistration = { FirebaseRegistrationWorker.enqueue(context) },
    )

internal fun createSubscriptionBilling(context: Context): PlaySubscriptionBilling =
    GooglePlaySubscriptionBilling(context)

internal fun pushServiceIssue(context: Context): PushServiceIssue? {
    val availability = GoogleApiAvailability.getInstance()
    val result = availability.isGooglePlayServicesAvailable(context)
    if (result == ConnectionResult.SUCCESS) return null
    val description =
        when (result) {
            ConnectionResult.SERVICE_MISSING -> "Google Play services is missing"
            ConnectionResult.SERVICE_DISABLED -> "Google Play services is disabled"
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ->
                "Google Play services needs an update"
            ConnectionResult.SERVICE_UPDATING -> "Google Play services is updating"
            else -> "Google Play services is unavailable"
        }
    return PushServiceIssue(
        description,
        availability.getErrorResolutionIntent(context, result, null),
    )
}
