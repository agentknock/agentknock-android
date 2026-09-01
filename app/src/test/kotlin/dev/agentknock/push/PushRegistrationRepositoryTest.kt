package dev.agentknock.push

import dev.agentknock.relay.RelayPushRegistrationClient
import dev.agentknock.relay.RelayPushRegistrationResult
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PushRegistrationRepositoryTest {
    @Test
    fun `relay state is owned here and missing registration requests a refresh`() {
        var refreshes = 0
        val repository = PushRegistrationRepository(
            deviceAuthorization = RelayDeviceAuthorizationSource {
                error("Registration is not part of this test")
            },
            relay = object : RelayPushRegistrationClient {
                override suspend fun register(
                    deviceId: String,
                    deviceToken: String,
                    firebaseInstallationId: String,
                ): RelayPushRegistrationResult = error("Registration is not part of this test")
            },
            requestRegistration = { refreshes += 1 },
        )

        repository.updateRelayState(RelayPushRegistrationState.MISSING)
        assertEquals(RelayPushRegistrationState.MISSING, repository.registrationState.value)
        assertEquals(1, refreshes)

        repository.updateRelayState(RelayPushRegistrationState.INVALID)
        assertEquals(RelayPushRegistrationState.INVALID, repository.registrationState.value)
        assertEquals(2, refreshes)

        repository.updateRelayState(RelayPushRegistrationState.REGISTERED)
        assertEquals(RelayPushRegistrationState.REGISTERED, repository.registrationState.value)
        assertEquals(2, refreshes)
    }
}
