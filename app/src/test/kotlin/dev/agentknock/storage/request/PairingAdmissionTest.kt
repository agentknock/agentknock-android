package dev.agentknock.storage.request

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAdmissionTest {
    @Test
    fun `allows any number of completed pairings`() {
        assertTrue(
            pairingAdmissionAllowed(
                listOf(PairingState.COMPLETED, PairingState.REJECTED, PairingState.COMPLETED),
            ),
        )
    }

    @Test
    fun `temporarily blocks a second unfinished pairing`() {
        for (
            state in listOf(
                PairingState.RECEIVING,
                PairingState.SAS_VERIFICATION_PENDING,
                PairingState.RELAY_ACTIVATION_PENDING,
                PairingState.WAITING_FOR_FINISH,
            )
        ) {
            assertFalse(pairingAdmissionAllowed(listOf(PairingState.COMPLETED, state)))
        }
    }

    @Test
    fun `previous psk has a fixed ten minute overlap`() {
        val createdAt = 1_000_000L

        assertFalse(previousPskEligible(createdAt, createdAt - 1))
        assertTrue(previousPskEligible(createdAt, createdAt + 10 * 60 * 1_000L))
        assertFalse(previousPskEligible(createdAt, createdAt + 10 * 60 * 1_000L + 1))
    }
}
