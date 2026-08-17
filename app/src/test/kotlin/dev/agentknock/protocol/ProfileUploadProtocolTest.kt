package dev.agentknock.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUploadProtocolTest {
    private val protocol = ProfileUploadProtocol()
    private val json = Json

    @Test
    fun `decodes an environment profile proposal`() {
        val request = protocol.decodeRequest(
            """
            {
              "cli_version":"0.2.0",
              "method":"ProfileUpload",
              "mode":"UPDATE",
              "profile":{
                "name":"aws-read-only",
                "description":"Production read access",
                "type":"environment",
                "variables":{
                  "AWS_REGION":{"value":"eu-north-1"},
                  "AWS_TOKEN":{"value":"secret"}
                }
              }
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals("0.2.0", request.cliVersion)
        assertEquals(ProfileUploadMode.UPDATE, request.mode)
        assertEquals("aws-read-only", request.name)
        assertTrue(request.descriptionProvided)
        assertEquals("Production read access", request.description)
        assertEquals(
            mapOf("AWS_REGION" to "eu-north-1", "AWS_TOKEN" to "secret"),
            request.variables,
        )
    }

    @Test
    fun `distinguishes an omitted description`() {
        val request = protocol.decodeRequest(
            """
            {
              "cli_version":"0.2.0",
              "method":"ProfileUpload",
              "mode":"CREATE",
              "profile":{"name":"new","type":"environment","variables":{}}
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertFalse(request.descriptionProvided)
        assertNull(request.description)
    }

    @Test
    fun `encodes received and rejected results`() {
        assertEquals(
            json.parseToJsonElement("""{"result":"RECEIVED"}"""),
            json.parseToJsonElement(protocol.receivedResponse().decodeToString()),
        )
        assertEquals(
            json.parseToJsonElement(
                """{"result":"REJECTED","message":"Invalid proposal."}""",
            ),
            json.parseToJsonElement(
                protocol.rejectedResponse("Invalid proposal.").decodeToString(),
            ),
        )
    }

    @Test
    fun `decodes the echoed completion`() {
        assertEquals(
            ProfileUploadCompletion("0.2.0", "RECEIVED", null),
            protocol.decodeCompletion(
                """{"cli_version":"0.2.0","result":"RECEIVED"}"""
                    .encodeToByteArray(),
            ),
        )
    }
}
