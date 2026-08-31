package dev.agentknock.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretUploadProtocolTest {
    private val protocol = SecretUploadProtocol()
    private val json = Json

    @Test
    fun `decodes an environment secret upload`() {
        val request = protocol.decodeRequest(
            """
            {
              ${testClientSoftwareFields("0.2.0", "0.1.0")},
              "method":"SecretUpload",
              "mode":"UPDATE",
              "secret":{
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

        assertEquals(testClientSoftware("0.2.0", "0.1.0"), request.clientSoftware)
        assertEquals(SecretUploadMode.UPDATE, request.mode)
        assertEquals("aws-read-only", request.name)
        assertTrue(request.descriptionProvided)
        assertEquals("Production read access", request.description)
        val contents = request.contents as SecretUploadContents.Environment
        assertEquals(
            mapOf("AWS_REGION" to "eu-north-1", "AWS_TOKEN" to "secret"),
            contents.variables,
        )
    }

    @Test
    fun `distinguishes an omitted description`() {
        val request = protocol.decodeRequest(
            """
            {
              ${testClientSoftwareFields("0.2.0", "0.1.0")},
              "method":"SecretUpload",
              "mode":"CREATE",
              "secret":{"name":"new","type":"environment","variables":{"TOKEN":{"value":"value"}}}
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertFalse(request.descriptionProvided)
        assertNull(request.description)
    }

    @Test
    fun `decodes an SSH private key upload without treating it as environment data`() {
        val request = protocol.decodeRequest(
            """
            {
              ${testClientSoftwareFields("0.2.0", "0.1.0")},
              "method":"SecretUpload",
              "mode":"CREATE",
              "secret":{
                "name":"production-ssh",
                "type":"ssh",
                "private_key":"-----BEGIN OPENSSH PRIVATE KEY-----\nexample\n-----END OPENSSH PRIVATE KEY-----"
              }
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nexample\n-----END OPENSSH PRIVATE KEY-----",
            (request.contents as SecretUploadContents.Ssh).privateKey,
        )
    }

    @Test
    fun `decodes an explicit null description without turning it into text`() {
        val request = protocol.decodeRequest(
            """
            {
              ${testClientSoftwareFields("0.2.0", "0.1.0")},
              "method":"SecretUpload",
              "mode":"UPDATE",
              "secret":{
                "name":"existing",
                "description":null,
                "type":"environment",
                "variables":{"TOKEN":{"value":"value"}}
              }
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertTrue(request.descriptionProvided)
        assertNull(request.description)
    }

    @Test
    fun `rejects non-string fields instead of coercing them`() {
        val invalidRequests = listOf(
            """{"app_info":{"name":"agentknock","version":2},"lib_info":{"name":"agentknock","version":"0.1.0"},"method":"SecretUpload","mode":"CREATE","secret":{"name":"new","type":"environment","variables":{"TOKEN":{"value":"value"}}}}""",
            """{${testClientSoftwareFields("0.2.0", "0.1.0")},"method":"SecretUpload","mode":"CREATE","secret":{"name":"new","type":"environment","variables":{"TOKEN":{"value":false}}}}""",
            """{${testClientSoftwareFields("0.2.0", "0.1.0")},"method":"SecretUpload","mode":"CREATE","secret":{"name":"new","description":3,"type":"environment","variables":{"TOKEN":{"value":"value"}}}}""",
        )

        invalidRequests.forEach { request ->
            assertTrue(
                "Expected a non-string field to be rejected: $request",
                runCatching { protocol.decodeRequest(request.encodeToByteArray()) }.isFailure,
            )
        }
    }

    @Test
    fun `rejects structurally empty uploads`() {
        val invalidRequests = listOf(
            """{${testClientSoftwareFields("0.3.0", "0.1.0")},"method":"SecretUpload","mode":"UPDATE","secret":{"name":"environment","type":"environment","variables":{}}}""",
            """{${testClientSoftwareFields("0.3.0", "0.1.0")},"method":"SecretUpload","mode":"UPDATE","secret":{"name":"ssh","type":"ssh"}}""",
            """{${testClientSoftwareFields("0.3.0", "0.1.0")},"method":"SecretUpload","mode":"UPDATE","secret":{"name":"ssh","type":"ssh","private_key":null}}""",
        )

        invalidRequests.forEach { request ->
            assertTrue(
                "Expected an empty upload to be rejected: $request",
                runCatching { protocol.decodeRequest(request.encodeToByteArray()) }.isFailure,
            )
        }
    }

    @Test
    fun `encodes received and rejected results`() {
        assertEquals(
            json.parseToJsonElement("""{"result":"RECEIVED"}"""),
            json.parseToJsonElement(protocol.receivedResponse().decodeToString()),
        )
        assertEquals(
            json.parseToJsonElement(
                """{"result":"REJECTED","message":"Invalid upload."}""",
            ),
            json.parseToJsonElement(
                protocol.rejectedResponse("Invalid upload.").decodeToString(),
            ),
        )
    }

    @Test
    fun `decodes the echoed completion`() {
        assertEquals(
            SecretUploadCompletion(
                testClientSoftware("0.2.0", "0.1.0"),
                "RECEIVED",
                null,
            ),
            protocol.decodeCompletion(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")},"result":"RECEIVED"}"""
                    .encodeToByteArray(),
            ),
        )
    }
}
