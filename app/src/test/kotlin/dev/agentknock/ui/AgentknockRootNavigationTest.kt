package dev.agentknock.ui

import dev.agentknock.storage.device.DeviceIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentknockRootNavigationTest {
    @Test
    fun `address editor stays open while its active identity is unchanged`() {
        assertFalse(
            addressEditorCompleted(
                originalIdentityId = IDENTITY_ID,
                originalAddress = ORIGINAL_ADDRESS,
                active = identity(),
            ),
        )
    }

    @Test
    fun `address editor completes when the same identity changes address`() {
        assertTrue(
            addressEditorCompleted(
                originalIdentityId = IDENTITY_ID,
                originalAddress = ORIGINAL_ADDRESS,
                active = identity(address = "silent-forest-cloud"),
            ),
        )
    }

    @Test
    fun `address editor completes when the active identity is replaced`() {
        assertTrue(
            addressEditorCompleted(
                originalIdentityId = IDENTITY_ID,
                originalAddress = ORIGINAL_ADDRESS,
                active = identity(id = "replacement"),
            ),
        )
    }

    @Test
    fun `address editor waits for configuration to load after process recreation`() {
        assertFalse(
            addressEditorCompleted(
                originalIdentityId = IDENTITY_ID,
                originalAddress = ORIGINAL_ADDRESS,
                active = null,
            ),
        )
    }

    @Test
    fun `address editor cannot complete without an original identity`() {
        assertFalse(
            addressEditorCompleted(
                originalIdentityId = null,
                originalAddress = null,
                active = identity(),
            ),
        )
    }

    private fun identity(
        id: String = IDENTITY_ID,
        address: String = ORIGINAL_ADDRESS,
    ) = DeviceIdentity(
        id = id,
        address = address,
        deviceId = "device",
        credentialsAvailable = true,
        pairingEnabled = true,
        createdAt = 1L,
        instructions = "",
    )

    private companion object {
        const val IDENTITY_ID = "identity"
        const val ORIGINAL_ADDRESS = "amber-river-maple"
    }
}
