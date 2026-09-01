package dev.agentknock.push

import dev.agentknock.relay.RelayPushRegistrationClient
import dev.agentknock.relay.RelayPushRegistrationResult
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.storage.device.RelayDeviceAuthorizationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationRepositoryTest {
    @Test
    fun `transient relay status classification is shared by push registration`() {
        listOf(408, 425, 429, 500, 503, 599).forEach { status ->
            assertTrue(
                PushRegistrationResult.RelayRejected(status, null, null)
                    .needsAutomaticRetry(),
            )
        }
        listOf(400, 401, 403, 404, 409, 422, 600).forEach { status ->
            assertFalse(
                PushRegistrationResult.RelayRejected(status, null, null)
                    .needsAutomaticRetry(),
            )
        }
    }

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
