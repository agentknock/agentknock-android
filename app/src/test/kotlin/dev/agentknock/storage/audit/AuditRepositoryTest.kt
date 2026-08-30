package dev.agentknock.storage.audit

import org.junit.Assert.assertEquals
import org.junit.Test

class AuditRepositoryTest {
    @Test
    fun `legacy completed abort is presented as aborted`() {
        assertEquals(
            AuditOutcome.ABORTED,
            storedAuditOutcome("completed", "Secret use aborted"),
        )
    }

    @Test
    fun `ordinary completed event remains completed`() {
        assertEquals(
            AuditOutcome.COMPLETED,
            storedAuditOutcome("completed", "Secret list completed"),
        )
    }

    @Test
    fun `legacy protocol-shaped event titles use current user-facing wording`() {
        assertEquals(
            "Client received upload result",
            storedAuditTitle("Secret upload receipt confirmed"),
        )
        assertEquals(
            "Client received secret data",
            storedAuditTitle("Secret use delivered"),
        )
        assertEquals(
            "Client received signature",
            storedAuditTitle("Client confirmed signature received"),
        )
        assertEquals(
            "Request rejected",
            storedAuditTitle("Authenticated request rejected"),
        )
        assertEquals(
            "Unchanged title",
            storedAuditTitle("Unchanged title"),
        )
    }
}
