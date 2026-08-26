package dev.agentknock.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SecretListProtocolTest {
    private val protocol = SecretListProtocol()
    private val json = Json

    @Test
    fun `decodes the cli list request and empty completion`() {
        assertEquals(
            SecretListRequestMessage(testClientSoftware()),
            protocol.decodeRequest(
                """{${testClientSoftwareFields()},"method":"SecretList"}"""
                    .encodeToByteArray(),
            ),
        )
        assertEquals(
            testClientSoftware(),
            protocol.decodeCompletion(
                """{${testClientSoftwareFields()}}""".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `encodes secret metadata without stored values`() {
        val response = protocol.response(
            sortedMapOf(
                "aws-read-only" to SecretListSecret(
                    description = "Read production logs",
                    type = "environment",
                    environmentVariableNames = listOf(
                        "AWS_ACCESS_KEY_ID",
                        "AWS_SECRET_ACCESS_KEY",
                    ),
                ),
                "empty" to SecretListSecret(
                    description = "No variables yet",
                    type = "environment",
                    environmentVariableNames = emptyList(),
                ),
            ),
        )

        assertEquals(
            json.parseToJsonElement(
                """
                {
                  "secrets":{
                    "aws-read-only":{
                      "description":"Read production logs",
                      "type":"environment",
                      "variables":["AWS_ACCESS_KEY_ID","AWS_SECRET_ACCESS_KEY"]
                    },
                    "empty":{
                      "description":"No variables yet",
                      "type":"environment",
                      "variables":[]
                    }
                  }
                }
                """.trimIndent(),
            ),
            json.parseToJsonElement(response.decodeToString()),
        )
    }

    @Test
    fun `encodes an SSH public key separately from environment variables`() {
        val response = protocol.response(
            mapOf(
                "production-ssh" to SecretListSecret(
                    description = "Production host access",
                    type = "ssh",
                    sshPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEexample user@host",
                ),
            ),
        )
        assertEquals(
            json.parseToJsonElement(
                """{"secrets":{"production-ssh":{"description":"Production host access","type":"ssh","public_key":"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEexample user@host"}}}""",
            ),
            json.parseToJsonElement(response.decodeToString()),
        )
    }
}
