package dev.agentknock.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretUseProtocolTest {
    private val protocol = SecretUseProtocol()
    private val json = Json

    @Test
    fun `decodes the cli secret use request shape`() {
        val request = protocol.decodeRequest(
            """
            {
              "cli_version":"0.1.0",
              "method":"SecretUse",
              "secrets":["aws-read-only","common"],
              "reason":"Inspect production logs",
              "operation":{
                "type":"exec",
                "command":"aws",
                "arguments":["logs","tail","service"],
                "working_directory":"/work",
                "executable_path":"/run/current-system/sw/bin/aws",
                "executable_hash":"4f7f5c6a",
                "executable_mode":"BINARY",
                "stdin":"TERMINAL",
                "stdout":"PIPE",
                "stderr":"TERMINAL"
              },
              "launcher_chain":["sudo","agentknock"]
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals("0.1.0", request.cliVersion)
        assertEquals(listOf("aws-read-only", "common"), request.secrets)
        assertEquals("Inspect production logs", request.reason)
        assertEquals("aws", request.operation.command)
        assertEquals(listOf("logs", "tail", "service"), request.operation.arguments)
        assertEquals("/work", request.operation.workingDirectory)
        assertEquals("/run/current-system/sw/bin/aws", request.operation.executablePath)
        assertEquals("4f7f5c6a", request.operation.executableHash)
        assertEquals("BINARY", request.operation.executableMode)
        assertEquals("TERMINAL", request.operation.stdin)
        assertEquals("PIPE", request.operation.stdout)
        assertEquals("TERMINAL", request.operation.stderr)
        assertEquals(listOf("sudo", "agentknock"), request.launcherChain)
    }

    @Test
    fun `encodes exact approved and denied response variants`() {
        assertEquals(
            json.parseToJsonElement(
                """{"result":"APPROVED","secrets":{"aws-read-only":{"type":"environment","variables":{"AWS_REGION":{"value":"eu-west-1"},"TOKEN":{"value":"secret"}}}}}""",
            ),
            json.parseToJsonElement(
                protocol.approvedResponse(
                    mapOf(
                        "aws-read-only" to SecretUseResponseSecret(
                            description = "",
                            environment = linkedMapOf(
                                "AWS_REGION" to "eu-west-1",
                                "TOKEN" to "secret",
                            ),
                        ),
                    ),
                ).decodeToString(),
            ),
        )
        assertEquals(
            json.parseToJsonElement(
                """{"result":"DENIED","reason":"USER_DENIED","message":"Denied on device."}""",
            ),
            json.parseToJsonElement(
                protocol.deniedResponse(
                    SecretUseDenialReason.USER_DENIED,
                    "Denied on device.",
                ).decodeToString(),
            ),
        )
    }

    @Test
    fun `decodes all cli completion variants`() {
        assertEquals(
            SecretUseCompletion.Approved("0.1.0"),
            protocol.decodeCompletion(
                """{"cli_version":"0.1.0","result":"APPROVED"}""".encodeToByteArray(),
            ),
        )
        assertEquals(
            SecretUseCompletion.Denied("0.1.0", "USER_DENIED", "Denied on device."),
            protocol.decodeCompletion(
                """{"cli_version":"0.1.0","result":"DENIED","reason":"USER_DENIED","message":"Denied on device."}"""
                    .encodeToByteArray(),
            ),
        )
        val aborted = protocol.decodeCompletion(
            """{"cli_version":"0.1.0","result":"ABORTED","reason":"CANCELLED","message":"Cancelled by user."}"""
                .encodeToByteArray(),
        )
        check(aborted is SecretUseCompletion.Aborted)
        assertEquals("CANCELLED", aborted.reason)
        assertEquals("Cancelled by user.", aborted.message)
        assertNull(
            protocol.decodeRequest(
                """{"cli_version":"0.1.0","method":"SecretUse","secrets":["test"],"operation":{"type":"exec","command":"env","arguments":[],"working_directory":"/tmp","executable_path":"/bin/env","executable_mode":"BINARY","stdin":"TERMINAL","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[]}"""
                    .encodeToByteArray(),
            ).reason,
        )
    }
}
