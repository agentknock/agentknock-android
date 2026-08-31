package dev.agentknock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.agentknock.push.RequestNotifications
import dev.agentknock.subscription.SubscriptionRedemptionLink
import dev.agentknock.ui.AgentknockScreen
import dev.agentknock.ui.agentknockViewModelFactory
import dev.agentknock.ui.auth.DeviceAuthenticator
import dev.agentknock.ui.theme.AgentknockTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RequestNavigation(val requestId: String?)

internal sealed interface SubscriptionNavigation {
    data class Redemption(val token: String) : SubscriptionNavigation

    data object InvalidLink : SubscriptionNavigation
}

internal class MainActivityNavigationState {
    private val _request = MutableStateFlow<RequestNavigation?>(null)
    private val _subscription = MutableStateFlow<SubscriptionNavigation?>(null)

    val request: StateFlow<RequestNavigation?> = _request.asStateFlow()
    val subscription: StateFlow<SubscriptionNavigation?> = _subscription.asStateFlow()

    fun openRequest(requestId: String?) {
        _request.value = RequestNavigation(requestId)
    }

    fun openSubscription(target: SubscriptionNavigation) {
        _subscription.value = target
    }

    fun consumeRequest(target: RequestNavigation) {
        _request.compareAndSet(target, null)
    }

    fun consumeSubscription(target: SubscriptionNavigation) {
        _subscription.compareAndSet(target, null)
    }

    fun save(): Bundle = Bundle().apply {
        _request.value?.let { target ->
            putBoolean(REQUEST_PENDING_KEY, true)
            target.requestId?.let { putString(REQUEST_ID_KEY, it) }
        }
        when (val target = _subscription.value) {
            is SubscriptionNavigation.Redemption -> {
                putString(SUBSCRIPTION_KIND_KEY, SUBSCRIPTION_REDEMPTION_KIND)
                putString(SUBSCRIPTION_TOKEN_KEY, target.token)
            }
            SubscriptionNavigation.InvalidLink -> {
                putString(SUBSCRIPTION_KIND_KEY, SUBSCRIPTION_INVALID_LINK_KIND)
            }
            null -> Unit
        }
    }

    fun restore(savedState: Bundle?) {
        if (savedState == null) return
        if (savedState.getBoolean(REQUEST_PENDING_KEY)) {
            _request.value = RequestNavigation(savedState.getString(REQUEST_ID_KEY))
        }
        _subscription.value = when (savedState.getString(SUBSCRIPTION_KIND_KEY)) {
            SUBSCRIPTION_REDEMPTION_KIND -> savedState.getString(SUBSCRIPTION_TOKEN_KEY)
                ?.let { SubscriptionNavigation.Redemption(it) }
            SUBSCRIPTION_INVALID_LINK_KIND -> SubscriptionNavigation.InvalidLink
            else -> null
        }
    }

    private companion object {
        const val REQUEST_PENDING_KEY = "request_pending"
        const val REQUEST_ID_KEY = "request_id"
        const val SUBSCRIPTION_KIND_KEY = "subscription_kind"
        const val SUBSCRIPTION_TOKEN_KEY = "subscription_token"
        const val SUBSCRIPTION_REDEMPTION_KIND = "redemption"
        const val SUBSCRIPTION_INVALID_LINK_KIND = "invalid_link"
    }
}

class MainActivity : FragmentActivity() {
    private val navigation = MainActivityNavigationState()
    private val notificationStateGeneration = MutableStateFlow(0L)
    private val notificationPermissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationStateGeneration.value += 1 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigation.restore(savedInstanceState?.getBundle(NAVIGATION_STATE_KEY))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        enableEdgeToEdge()
        handleIntent(intent)
        val authenticator = DeviceAuthenticator(this)
        val container = (application as AgentknockApplication).container
        val authentication = container.authentication
        val viewModelFactory = agentknockViewModelFactory(application, container)
        setContent {
            AgentknockTheme {
                AgentknockScreen(
                    authenticate = authenticator::authenticate,
                    authentication = authentication,
                    requestNavigation = navigation.request,
                    consumeRequestNavigation = navigation::consumeRequest,
                    subscriptionNavigation = navigation.subscription,
                    consumeSubscriptionNavigation = navigation::consumeSubscription,
                    notificationStateGeneration = notificationStateGeneration,
                    requestNotificationPermission = ::requestNotificationPermission,
                    viewModelFactory = viewModelFactory,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationStateGeneration.value += 1
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(NAVIGATION_STATE_KEY, navigation.save())
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        (application as AgentknockApplication).container.authentication.onForeground()
    }

    override fun onStop() {
        (application as AgentknockApplication).container.authentication.onBackground()
        super.onStop()
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val recognized = when (val link = SubscriptionRedemptionLink.parse(intent.dataString)) {
                is SubscriptionRedemptionLink.Valid -> {
                    navigation.openSubscription(SubscriptionNavigation.Redemption(link.token))
                    true
                }
                SubscriptionRedemptionLink.Invalid -> {
                    navigation.openSubscription(SubscriptionNavigation.InvalidLink)
                    true
                }
                SubscriptionRedemptionLink.Unrelated -> false
            }
            if (recognized) {
                intent.action = null
                intent.data = null
            }
        }
        if (
            intent.action == RequestNotifications.OPEN_REQUESTS_ACTION ||
            intent.action == RequestNotifications.OPEN_REQUEST_ACTION
        ) {
            navigation.openRequest(intent.getStringExtra(RequestNotifications.REQUEST_ID_EXTRA))
            intent.action = null
        }
    }

    private companion object {
        const val NAVIGATION_STATE_KEY = "main_activity_navigation"
    }
}
