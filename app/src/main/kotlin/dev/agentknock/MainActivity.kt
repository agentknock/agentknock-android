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
import dev.agentknock.ui.AgentknockScreen
import dev.agentknock.ui.auth.DeviceAuthenticator
import dev.agentknock.ui.theme.AgentknockTheme
import kotlinx.coroutines.flow.MutableStateFlow

internal data class RequestNavigation(
    val generation: Long = 0,
    val requestId: Long? = null,
)

class MainActivity : FragmentActivity() {
    private val requestNavigation = MutableStateFlow(RequestNavigation())
    private val notificationStateGeneration = MutableStateFlow(0L)
    private val notificationPermissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationStateGeneration.value += 1 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        enableEdgeToEdge()
        handleIntent(intent)
        val authenticator = DeviceAuthenticator(this)
        val authentication = (application as AgentknockApplication).container.authentication
        setContent {
            AgentknockTheme {
                AgentknockScreen(
                    authenticate = authenticator::authenticate,
                    authentication = authentication,
                    requestNavigation = requestNavigation,
                    notificationStateGeneration = notificationStateGeneration,
                    requestNotificationPermission = ::requestNotificationPermission,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationStateGeneration.value += 1
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
        if (
            intent.action == RequestNotifications.OPEN_REQUESTS_ACTION ||
            intent.action == RequestNotifications.OPEN_REQUEST_ACTION
        ) {
            requestNavigation.value = RequestNavigation(
                generation = requestNavigation.value.generation + 1,
                requestId = intent.getLongExtra(RequestNotifications.REQUEST_ID_EXTRA, -1L)
                    .takeIf { it >= 0 },
            )
            intent.action = null
        }
    }
}
