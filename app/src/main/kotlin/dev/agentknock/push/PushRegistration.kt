package dev.agentknock.push

import android.content.Intent
import dev.agentknock.relay.RelayPushRegistrationState
import kotlinx.coroutines.flow.StateFlow

internal interface PushRegistration {
    val registrationState: StateFlow<RelayPushRegistrationState?>

    fun updateRelayState(state: RelayPushRegistrationState)
}

internal data class PushServiceIssue(val description: String, val resolution: Intent?)
