package dev.agentknock.storage.request

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAdmissionTest {
    @Test
    fun `allows any number of completed pairings`() {
        assertTrue(
            pairingAdmissionAllowed(
                listOf(PairingState.ACTIVE, PairingState.REJECTED, PairingState.ACTIVE),
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
                PairingState.VERIFICATION_FAILED,
            )
        ) {
            assertFalse(pairingAdmissionAllowed(listOf(PairingState.ACTIVE, state)))
        }
    }
}
