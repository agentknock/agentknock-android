package dev.agentknock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.agentknock.push.RequestNotifications
import dev.agentknock.subscription.SubscriptionRedemptionLink
import dev.agentknock.ui.AgentknockScreen
import dev.agentknock.ui.agentknockViewModelFactory
import dev.agentknock.ui.auth.DeviceAuthenticator
import dev.agentknock.ui.theme.AgentknockTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface ExternalNavigation {
    data class Request(val requestId: String?) : ExternalNavigation

    data class SubscriptionRedemption(val token: String) : ExternalNavigation

    data object InvalidSubscriptionLink : ExternalNavigation
}

internal class MainActivityNavigationState {
    private val _target = MutableStateFlow<ExternalNavigation?>(null)

    val target: StateFlow<ExternalNavigation?> = _target.asStateFlow()

    fun open(target: ExternalNavigation) {
        _target.value = target
    }

    fun consume(target: ExternalNavigation): Boolean = _target.compareAndSet(target, null)

    fun save(): Bundle = Bundle().apply {
        when (val target = _target.value) {
            is ExternalNavigation.Request -> {
                putString(TARGET_KIND_KEY, REQUEST_KIND)
                putBoolean(REQUEST_HAS_ID_KEY, target.requestId != null)
                target.requestId?.let { putString(REQUEST_ID_KEY, it) }
            }
            is ExternalNavigation.SubscriptionRedemption -> Unit
            ExternalNavigation.InvalidSubscriptionLink -> {
                putString(TARGET_KIND_KEY, SUBSCRIPTION_INVALID_LINK_KIND)
            }
            null -> Unit
        }
    }

    fun restore(savedState: Bundle?) {
        if (savedState == null) return
        _target.value = when (savedState.getString(TARGET_KIND_KEY)) {
            REQUEST_KIND -> ExternalNavigation.Request(
                savedState.getString(REQUEST_ID_KEY).takeIf {
                    savedState.getBoolean(REQUEST_HAS_ID_KEY)
                },
            )
            SUBSCRIPTION_INVALID_LINK_KIND -> ExternalNavigation.InvalidSubscriptionLink
            else -> null
        }
    }

    private companion object {
        const val TARGET_KIND_KEY = "target_kind"
        const val REQUEST_KIND = "request"
        const val REQUEST_HAS_ID_KEY = "request_has_id"
        const val REQUEST_ID_KEY = "request_id"
        const val SUBSCRIPTION_INVALID_LINK_KIND = "invalid_link"
    }
}

class MainActivity : FragmentActivity() {
    private val navigation = MainActivityNavigationState()
    private val notificationStateGeneration = MutableStateFlow(0L)
    private val notificationPermissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationStateGeneration.value += 1
        lifecycleScope.launch {
            (application as AgentknockApplication).container.requestNotifications.reconcile()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigation.restore(savedInstanceState?.getBundle(NAVIGATION_STATE_KEY))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        } else {
            // Older Android versions have no recents-only protection API.
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()
        handleIntent(intent)
        val container = (application as AgentknockApplication).container
        val deviceAuthentication = container.deviceAuthentication
        val authenticator = DeviceAuthenticator(this, deviceAuthentication)
        lifecycleScope.launch {
            deviceAuthentication.request.collect { request ->
                authenticator.bind(request)
                if (request != null && deviceAuthentication.claimForLaunch(request)) {
                    authenticator.launch(request)
                }
            }
        }
        val authentication = container.authentication
        val viewModelFactory = agentknockViewModelFactory(application, container)
        setContent {
            AgentknockTheme {
                AgentknockScreen(
                    authenticationRequest = deviceAuthentication.request,
                    sensitiveDataBackgroundGuard = container.sensitiveDataBackgroundGuard,
                    authentication = authentication,
                    externalNavigation = navigation.target,
                    consumeExternalNavigation = ::consumeNavigation,
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
        lifecycleScope.launch {
            (application as AgentknockApplication).container.requestNotifications.reconcile()
        }
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
            when (val link = SubscriptionRedemptionLink.parse(intent.dataString)) {
                is SubscriptionRedemptionLink.Valid -> {
                    navigation.open(ExternalNavigation.SubscriptionRedemption(link.token))
                }
                SubscriptionRedemptionLink.Invalid -> {
                    navigation.open(ExternalNavigation.InvalidSubscriptionLink)
                    scrubLink(intent)
                }
                SubscriptionRedemptionLink.Unrelated -> Unit
            }
        }
        if (
            intent.action == RequestNotifications.OPEN_REQUESTS_ACTION ||
            intent.action == RequestNotifications.OPEN_REQUEST_ACTION
        ) {
            navigation.open(
                ExternalNavigation.Request(
                    intent.getStringExtra(RequestNotifications.REQUEST_ID_EXTRA),
                ),
            )
            intent.action = null
        }
    }

    private fun scrubLink(intent: Intent) {
        intent.action = null
        intent.data = null
    }

    private fun consumeNavigation(target: ExternalNavigation) {
        if (navigation.consume(target) && target is ExternalNavigation.SubscriptionRedemption) {
            scrubLink(intent)
        }
    }

    private companion object {
        const val NAVIGATION_STATE_KEY = "main_activity_navigation"
    }
}
