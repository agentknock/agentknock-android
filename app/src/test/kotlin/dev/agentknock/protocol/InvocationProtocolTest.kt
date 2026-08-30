package dev.agentknock.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvocationProtocolTest {
    private val protocol = InvocationProtocol()
    private val json = Json

    @Test
    fun `decodes the cli invocation request shape`() {
        val request = protocol.decodeRequest(
            """
            {
              ${testClientSoftwareFields("0.2.0", "0.1.0")},
              "method":"Invocation",
              "invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
              "secrets":{"aws-read-only":{},"common":{}},
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

        assertEquals(testClientSoftware("0.2.0", "0.1.0"), request.clientSoftware)
        assertArrayEquals(ByteArray(32), request.invocationToken)
        assertEquals(listOf("aws-read-only", "common"), request.secrets)
        assertEquals(
            mapOf(
                "aws-read-only" to InvocationSecretDelivery(),
                "common" to InvocationSecretDelivery(),
            ),
            request.secretDelivery,
        )
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
                        "aws-read-only" to InvocationResponseSecret.Environment(
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
                """{"result":"APPROVED","secrets":{"production-ssh":{"type":"ssh","public_key":"ssh-ed25519 AAAA example@host"}}}""",
            ),
            json.parseToJsonElement(
                protocol.approvedResponse(
                    mapOf(
                        "production-ssh" to InvocationResponseSecret.Ssh(
                            description = "",
                            publicKey = "ssh-ed25519 AAAA example@host",
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
                    InvocationDenialReason.USER_DENIED,
                    "Denied on device.",
                ).decodeToString(),
            ),
        )
    }

    @Test
    fun `decodes all cli completion variants`() {
        assertEquals(
            InvocationCompletion.Approved(testClientSoftware("0.2.0", "0.1.0")),
            protocol.decodeCompletion(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")},"result":"APPROVED"}"""
                    .encodeToByteArray(),
            ),
        )
        assertEquals(
            InvocationCompletion.Denied(
                testClientSoftware("0.2.0", "0.1.0"),
                "USER_DENIED",
                "Denied on device.",
            ),
            protocol.decodeCompletion(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")},"result":"DENIED","reason":"USER_DENIED","message":"Denied on device."}"""
                    .encodeToByteArray(),
            ),
        )
        val aborted = protocol.decodeCompletion(
            """{${testClientSoftwareFields("0.2.0", "0.1.0")},"result":"ABORTED","reason":"CANCELLED","message":"Cancelled by user."}"""
                .encodeToByteArray(),
        )
        check(aborted is InvocationCompletion.Aborted)
        assertEquals("CANCELLED", aborted.reason)
        assertEquals("Cancelled by user.", aborted.message)
        assertNull(
            protocol.decodeRequest(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")},"method":"Invocation","invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","secrets":{"test":{}},"operation":{"type":"exec","command":"env","arguments":[],"working_directory":"/tmp","executable_path":"/bin/env","executable_mode":"BINARY","stdin":"TERMINAL","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[]}"""
                    .encodeToByteArray(),
            ).reason,
        )
    }

    @Test
    fun `decodes only and omit environment delivery options`() {
        val request = protocol.decodeRequest(
            """
            {
              ${testClientSoftwareFields("0.3.0", "0.1.0")},
              "method":"Invocation",
              "invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
              "secrets":{
                "github":{"environment":{"only":["GH_HOST","GH_TOKEN"]}},
                "cloudflare":{"environment":{"omit":["CF_ACCOUNT_ID"]}}
              },
              "operation":{
                "type":"exec",
                "command":"gh",
                "arguments":["auth","status"],
                "working_directory":"/work",
                "executable_path":"/usr/bin/gh",
                "executable_mode":"BINARY",
                "stdin":"TERMINAL",
                "stdout":"TERMINAL",
                "stderr":"TERMINAL"
              },
              "launcher_chain":[]
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(
            mapOf(
                "github" to InvocationSecretDelivery(
                    InvocationEnvironmentDelivery(only = setOf("GH_HOST", "GH_TOKEN")),
                ),
                "cloudflare" to InvocationSecretDelivery(
                    InvocationEnvironmentDelivery(omit = setOf("CF_ACCOUNT_ID")),
                ),
            ),
            request.secretDelivery,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects combining only and omit`() {
        decodeWithSecrets(
            """{"test":{"environment":{"only":["TOKEN"],"omit":["OTHER"]}}}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty only set`() {
        decodeWithSecrets("""{"test":{"environment":{"only":[]}}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid environment names`() {
        decodeWithSecrets("""{"test":{"environment":{"omit":["BAD=NAME"]}}}""")
    }

    private fun decodeWithSecrets(secrets: String) {
        protocol.decodeRequest(
            """{${testClientSoftwareFields("0.3.0", "0.1.0")},"method":"Invocation","invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","secrets":$secrets,"operation":{"type":"exec","command":"env","arguments":[],"working_directory":"/tmp","executable_path":"/bin/env","executable_mode":"BINARY","stdin":"TERMINAL","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[]}"""
                .encodeToByteArray(),
        )
    }
}
