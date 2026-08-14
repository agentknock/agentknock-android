package dev.agentknock

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.agentknock.ui.auth.DeviceAuthenticator
import dev.agentknock.ui.profiles.ProfilesScreen
import dev.agentknock.ui.theme.AgentKnockTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val authenticator = DeviceAuthenticator(this)
        setContent {
            AgentKnockTheme {
                ProfilesScreen(
                    authenticate = authenticator::authenticate,
                )
            }
        }
    }
}
