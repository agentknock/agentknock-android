package dev.agentknock.push

import dev.agentknock.relay.RelayPushRegistrationState
import kotlinx.coroutines.flow.StateFlow

internal interface PushRegistration {
    val registrationState: StateFlow<RelayPushRegistrationState?>

    fun updateRelayState(state: RelayPushRegistrationState)
}
