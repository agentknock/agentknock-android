package dev.agentknock.protocol

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun `denied and aborted completions still require reason and message`() {
        decoders.forEach { decode ->
            listOf("DENIED", "ABORTED").forEach { result ->
                listOf(""""reason":"OTHER"""", """"message":"Detail"""").forEach { detail ->
                    assertThrows(SerializationException::class.java) {
                        decode(encoded(""""result":"$result",$detail"""))
                    }
                }
            }
        }
    }

    @Test
    fun `only signing protocols reject signatures in approved completions`() {
        val withSignature = encoded(""""result":"APPROVED","signature":"signed"""")
        assertEquals(
            ApprovalCompletion.Approved(testClientSoftware("0.3.0", "0.1.0")),
            InvocationProtocol().decodeCompletion(withSignature),
        )
        listOf(GitSignProtocol()::decodeCompletion, SshAuthenticationProtocol()::decodeCompletion)
            .forEach { decode ->
                assertThrows(SerializationException::class.java) { decode(withSignature) }
                assertNull(decode(encoded(""""result":"APPROVED","signature":null""")).reason)
            }
    }

    private fun encoded(fields: String): ByteArray =
        """{${testClientSoftwareFields("0.3.0", "0.1.0")},$fields}""".encodeToByteArray()
}
