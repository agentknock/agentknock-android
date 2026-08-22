package dev.agentknock.ui.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationPolicyTest {
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
