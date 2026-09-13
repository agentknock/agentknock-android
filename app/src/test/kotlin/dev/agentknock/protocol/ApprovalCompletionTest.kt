package dev.agentknock.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalCompletionTest {
    private val decoders =
        listOf(
            InvocationProtocol()::decodeCompletion,
            GitSignProtocol()::decodeCompletion,
            SshAuthenticationProtocol()::decodeCompletion,
        )

    @Test
    fun `completion facts retain the validated result and client software`() {
        decoders.forEach { decode ->
            listOf("APPROVED", "DENIED", "ABORTED").forEach { result ->
                val completion =
                    decode(encoded(""""result":"$result","reason":"OTHER","message":"Detail""""))

                assertEquals(testClientSoftware("0.3.0", "0.1.0"), completion.clientSoftware)
                val expected =
                    when (result) {
                        "APPROVED" -> ApprovalCompletion.Approved(completion.clientSoftware)
                        "DENIED" ->
                            ApprovalCompletion.Denied(completion.clientSoftware, "OTHER", "Detail")
                        else ->
                            ApprovalCompletion.Aborted(completion.clientSoftware, "OTHER", "Detail")
                    }
                assertEquals(expected, completion)
                assertEquals(if (result == "APPROVED") null else "OTHER", completion.reason)
                assertEquals(if (result == "APPROVED") null else "Detail", completion.message)
            }
        }
    }

    private fun encoded(fields: String): ByteArray =
        """{${testClientSoftwareFields("0.3.0", "0.1.0")},$fields}""".encodeToByteArray()
}
