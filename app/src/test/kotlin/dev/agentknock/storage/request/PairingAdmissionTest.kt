package dev.agentknock.storage.request

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAdmissionTest {
    @Test
    fun `terminal pairings do not block admission`() {
        assertFalse(PairingState.COMPLETED.blocksAdmission)
        assertFalse(PairingState.REJECTED.blocksAdmission)
    }

    @Test
    fun `temporarily blocks a second unfinished pairing`() {
        for (state in
            listOf(
                PairingState.EXCHANGE_PENDING,
                PairingState.EXCHANGE_FAILED,
                PairingState.SAS_VERIFICATION_PENDING,
                PairingState.WAITING_FOR_FINISH,
            )) {
            assertTrue(state.blocksAdmission)
            assertTrue(state.isRejectable)
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
