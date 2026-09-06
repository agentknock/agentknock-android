package dev.agentknock.storage.request

import dev.agentknock.protocol.ApprovalCompletion
import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.SoftwareInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalCompletionStorageTest {
    @Test
    fun `completion storage and audit names retain their published lowercase format`() {
        val software = ClientSoftware(SoftwareInfo("client", "1"), SoftwareInfo("library", "1"))
        assertEquals("approved", ApprovalCompletion.Approved(software).storedResult)
        assertEquals("denied", ApprovalCompletion.Denied(software, "OTHER", "Denied").storedResult)
        assertEquals("aborted", ApprovalCompletion.Aborted(software, "OTHER", "Aborted").storedResult)
    }
}
