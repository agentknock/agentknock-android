package dev.agentknock.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileListProtocolTest {
    private val protocol = ProfileListProtocol()
    private val json = Json

    @Test
    fun `decodes the cli list request and empty completion`() {
        assertEquals(
            ProfileListRequestMessage("0.1.0"),
            protocol.decodeRequest(
                """{"cli_version":"0.1.0","method":"List"}""".encodeToByteArray(),
            ),
        )
        assertEquals(
            "0.1.0",
            protocol.decodeCompletion(
                """{"cli_version":"0.1.0"}""".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `encodes profile metadata without stored values`() {
        val response = protocol.response(
            sortedMapOf(
                "aws-read-only" to ProfileListProfile(
                    description = "Read production logs",
                    environmentVariableNames = listOf(
                        "AWS_ACCESS_KEY_ID",
                        "AWS_SECRET_ACCESS_KEY",
                    ),
                ),
                "empty" to ProfileListProfile(
                    description = "No variables yet",
                    environmentVariableNames = emptyList(),
                ),
            ),
        )

        assertEquals(
            json.parseToJsonElement(
                """
                {
                  "profiles":{
                    "aws-read-only":{
                      "description":"Read production logs",
                      "environment":{
                        "AWS_ACCESS_KEY_ID":"STORED",
                        "AWS_SECRET_ACCESS_KEY":"STORED"
                      }
                    },
                    "empty":{
                      "description":"No variables yet",
                      "environment":{}
                    }
                  }
                }
                """.trimIndent(),
            ),
            json.parseToJsonElement(response.decodeToString()),
        )
    }
}
