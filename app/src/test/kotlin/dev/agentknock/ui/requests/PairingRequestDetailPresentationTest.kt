package dev.agentknock.ui.requests

import dev.agentknock.storage.request.PairingRequestDetails
import dev.agentknock.storage.request.PairingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRequestDetailPresentationTest {
    @Test
    fun `metadata warning is shown near actionable SAS without error status styling`() {
        val warning = "The client details could not be read, but the security code is valid."
        val details = pairingDetails(
            state = PairingState.SAS_VERIFICATION_PENDING,
            error = warning,
        )

        assertEquals(
            PairingWarningNotice(
                title = "Some client details could not be read",
                message = warning,
            ),
            details.warningNotice,
        )
        assertFalse(details.pairingState.usesErrorStatus)
    }

    @Test
    fun `exchange failure uses error status styling instead of a SAS warning`() {
        val details = pairingDetails(
            state = PairingState.EXCHANGE_FAILED,
            error = "The pairing message could not be verified.",
        )

        assertNull(details.warningNotice)
        assertTrue(details.pairingState.usesErrorStatus)
    }

    private fun pairingDetails(
        state: PairingState,
        error: String?,
    ) = PairingRequestDetails(
        pairingState = state,
        clientName = "Client",
        pairingAddress = "pairing-address",
        clientId = "client-id",
        sasOptions = listOf("one", "two", "three"),
        clientSoftware = null,
        platform = null,
        architecture = null,
        hostname = null,
        machineId = null,
        osVersion = null,
        error = error,
        decidedAt = null,
    )
}
