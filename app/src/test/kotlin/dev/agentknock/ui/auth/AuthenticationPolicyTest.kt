package dev.agentknock.ui.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationPolicyTest {
    @Test
    fun `an active authentication attempt keeps ownership of its callbacks`() {
        val attempt = AuthenticationAttempt()
        val events = mutableListOf<String>()

        assertTrue(attempt.start({ events += "first success" }, { events += "first: $it" }))
        assertFalse(attempt.start({ events += "second success" }, { events += "second: $it" }))

        attempt.succeed()
        assertEquals(listOf("first success"), events)
        assertTrue(attempt.start({ events += "third success" }, { events += "third: $it" }))
        attempt.fail("cancelled")
        assertEquals(listOf("first success", "third: cancelled"), events)
    }

    @Test
    fun `device lock mode never adds an Agentknock gate`() {
        assertTrue(DeviceAuthenticationMode.DEVICE_LOCK.contentAvailable(authenticated = false))
        assertTrue(
            DeviceAuthenticationMode.DEVICE_LOCK.protectedActionAvailable(
                authenticated = false,
            ),
        )
    }

    @Test
    fun `sensitive values mode leaves content visible but gates protected actions`() {
        assertTrue(
            DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING.contentAvailable(
                authenticated = false,
            ),
        )
        assertFalse(
            DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING.protectedActionAvailable(
                authenticated = false,
            ),
        )
        assertTrue(
            DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING.protectedActionAvailable(
                authenticated = true,
            ),
        )
    }

    @Test
    fun `app lock gates content until the session is authenticated`() {
        assertFalse(DeviceAuthenticationMode.APP_LOCK.contentAvailable(authenticated = false))
        assertTrue(DeviceAuthenticationMode.APP_LOCK.contentAvailable(authenticated = true))
        assertFalse(
            DeviceAuthenticationMode.APP_LOCK.protectedActionAvailable(authenticated = false),
        )
        assertTrue(
            DeviceAuthenticationMode.APP_LOCK.protectedActionAvailable(authenticated = true),
        )
    }
}
