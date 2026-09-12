package dev.agentknock.protocol

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvocationProtocolTest {
    private val protocol = InvocationProtocol()
    private val json = Json

    @Test
    fun `decodes the cli invocation request shape`() {
        val request =
            protocol.decodeRequest(
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
                "executable_hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "executable_mode":"BINARY",
                "stdin":"TERMINAL",
                "stdout":"PIPE",
                "stderr":"TERMINAL"
              },
              "launcher_chain":["sudo","agentknock"]
            }
            """
                    .trimIndent()
                    .encodeToByteArray()
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
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            request.operation.executableHash,
        )
        assertEquals(InvocationExecutableMode.BINARY, request.operation.executableMode)
        assertEquals(InvocationStreamKind.TERMINAL, request.operation.stdin)
        assertEquals(InvocationStreamKind.PIPE, request.operation.stdout)
        assertEquals(InvocationStreamKind.TERMINAL, request.operation.stderr)
        assertEquals(listOf("sudo", "agentknock"), request.launcherChain)
    }

    @Test
    fun `decodes the complete script source sent by the cli`() {
        // From agentknock-cli tests/run.rs, reports_and_executes_a_shebang_script.
        // Preserve the source, including quotes and its final newline, for approval review.
        val contents = "#!/bin/sh\nprintf 'script:%s' \"\$AGENTKNOCK_SCRIPT_TEST\"\n"
        val request =
            protocol.decodeRequest(
                operation(
                        executableMode = "SCRIPT",
                        scriptContents = JsonPrimitive(contents),
                    )
                    .encodeToByteArray()
            )

        assertEquals(contents, request.operation.scriptContents)
    }

    @Test
    fun `accepts absent or null source for existing clients and uncaptured executables`() {
        // Older clients omit source. Current clients also omit it for binaries and scripts
        // over the capture limit; null follows the protocol's existing optional-field handling.
        for (mode in listOf("BINARY", "SCRIPT")) {
            for (contents in listOf(null, JsonNull)) {
                val request =
                    protocol.decodeRequest(
                        operation(executableMode = mode, scriptContents = contents)
                            .encodeToByteArray()
                    )

                assertNull(request.operation.scriptContents)
            }
        }
    }

    @Test
    fun `preserves replacement text while retaining the original script hash`() {
        // CLI capture is limited to 16 KiB of original bytes, before lossy UTF-8 decoding.
        // Replacement characters can expand the wire text beyond that limit, and its hash
        // no longer matches executable_hash (client-device-protocol.md, Invocation).
        val original = ByteArray(16 * 1024) { 0xff.toByte() }
        "#!/bin/sh\n#".encodeToByteArray().copyInto(original)
        val contents = original.decodeToString()
        val hash =
            Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(original))

        val request =
            protocol.decodeRequest(
                operation(
                        executableHash = hash,
                        executableMode = "SCRIPT",
                        scriptContents = JsonPrimitive(contents),
                    )
                    .encodeToByteArray()
            )

        assertEquals(contents, request.operation.scriptContents)
        assertEquals(hash, request.operation.executableHash)
    }

    @Test
    fun `encodes exact approved and denied response variants`() {
        assertEquals(
            json.parseToJsonElement(
                """{"result":"APPROVED","secrets":{"aws-read-only":{"type":"environment","variables":{"AWS_REGION":{"value":"eu-west-1"},"TOKEN":{"value":"secret"}}}}}"""
            ),
            json.parseToJsonElement(
                protocol
                    .approvedResponse(
                        mapOf(
                            "aws-read-only" to
                                InvocationResponseSecret.Environment(
                                    description = "",
                                    environment =
                                        linkedMapOf(
                                            "AWS_REGION" to "eu-west-1",
                                            "TOKEN" to "secret",
                                        ),
                                )
                        )
                    )
                    .decodeToString()
            ),
        )
        assertEquals(
            json.parseToJsonElement(
                """{"result":"APPROVED","secrets":{"production-ssh":{"type":"ssh","public_key":"ssh-ed25519 AAAA example@host"}}}"""
            ),
            json.parseToJsonElement(
                protocol
                    .approvedResponse(
                        mapOf(
                            "production-ssh" to
                                InvocationResponseSecret.Ssh(
                                    description = "",
                                    publicKey = "ssh-ed25519 AAAA example@host",
                                )
                        )
                    )
                    .decodeToString()
            ),
        )
        assertEquals(
            json.parseToJsonElement(
                """{"result":"DENIED","reason":"USER_DENIED","message":"Denied on device."}"""
            ),
            json.parseToJsonElement(
                protocol
                    .deniedResponse(
                        InvocationDenialReason.USER_DENIED,
                        "Denied on device.",
                    )
                    .decodeToString()
            ),
        )
    }

    @Test
    fun `decodes only and omit environment delivery options`() {
        val request =
            protocol.decodeRequest(
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
            """
                    .trimIndent()
                    .encodeToByteArray()
            )

        assertEquals(
            mapOf(
                "github" to
                    InvocationSecretDelivery(
                        InvocationEnvironmentDelivery(only = setOf("GH_HOST", "GH_TOKEN"))
                    ),
                "cloudflare" to
                    InvocationSecretDelivery(
                        InvocationEnvironmentDelivery(omit = setOf("CF_ACCOUNT_ID"))
                    ),
            ),
            request.secretDelivery,
        )
    }

    @Test
    fun `decodes environment variable renaming`() {
        val request =
            decodeWithSecrets(
                """{"github":{"environment":{"only":["GH_HOST","GH_TOKEN"],"rename":{"GH_HOST":"GITHUB_HOST"}}}}"""
            )

        assertEquals(
            mapOf("GH_HOST" to "GITHUB_HOST"),
            request.secretDelivery.getValue("github").environment?.rename,
        )
    }

    @Test
    fun `decodes standard input delivery`() {
        val request =
            decodeWithSecrets(
                """{"github":{"environment":{"only":["GH_TOKEN"],"stdin":"GH_TOKEN"}}}"""
            )

        assertEquals(
            "GH_TOKEN",
            request.secretDelivery.getValue("github").environment?.stdin,
        )
    }

    @Test
    fun `ignores unknown nested members`() {
        val request =
            decodeWithSecrets(
                """{"test":{"future_delivery":{"mode":"new"},"environment":{"only":["TOKEN"],"future_option":true}}}"""
            )

        assertEquals(
            setOf("TOKEN"),
            request.secretDelivery.getValue("test").environment?.only,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects combining only and omit`() {
        decodeWithSecrets("""{"test":{"environment":{"only":["TOKEN"],"omit":["OTHER"]}}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty only set`() {
        decodeWithSecrets("""{"test":{"environment":{"only":[]}}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty omit set`() {
        decodeWithSecrets("""{"test":{"environment":{"omit":[]}}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid environment names`() {
        decodeWithSecrets("""{"test":{"environment":{"omit":["BAD=NAME"]}}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects renaming a variable excluded by only`() {
        decodeWithSecrets(
            """{"test":{"environment":{"only":["TOKEN"],"rename":{"OTHER":"RENAMED"}}}}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects multiple standard input sources`() {
        decodeWithSecrets(
            """{"first":{"environment":{"stdin":"ONE"}},"second":{"environment":{"stdin":"TWO"}}}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects renaming the standard input source`() {
        decodeWithSecrets(
            """{"test":{"environment":{"rename":{"TOKEN":"OTHER"},"stdin":"TOKEN"}}}"""
        )
    }

    @Test
    fun `rejects invalid operation evidence`() {
        val invalidRequests =
            listOf(
                operation(executableHash = "not base64"),
                operation(executableHash = "AA=="),
                operation(executableMode = "NATIVE"),
                operation(stdin = "INHERITED"),
                operation(stdout = "INHERITED"),
                operation(stderr = "INHERITED"),
                operation(launcherChain = listOf("one", "two", "three", "four", "five")),
            )

        invalidRequests.forEach { request ->
            assertTrue(
                "Expected invalid operation evidence to be rejected: $request",
                runCatching { protocol.decodeRequest(request.encodeToByteArray()) }.isFailure,
            )
        }
    }

    private fun decodeWithSecrets(secrets: String): InvocationRequestMessage =
        protocol.decodeRequest(
            """{${testClientSoftwareFields("0.3.0", "0.1.0")},"method":"Invocation","invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","secrets":$secrets,"operation":{"type":"exec","command":"env","arguments":[],"working_directory":"/tmp","executable_path":"/bin/env","executable_mode":"BINARY","stdin":"TERMINAL","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[]}"""
                .encodeToByteArray()
        )

    private fun operation(
        executableHash: String? = null,
        executableMode: String = "BINARY",
        stdin: String = "TERMINAL",
        stdout: String = "TERMINAL",
        stderr: String = "TERMINAL",
        launcherChain: List<String> = emptyList(),
        scriptContents: JsonElement? = null,
    ): String {
        val hashMember = executableHash?.let { "\"executable_hash\":\"$it\"," }.orEmpty()
        val scriptMember = scriptContents?.let { "\"script_contents\":$it," }.orEmpty()
        val launchers = launcherChain.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return """{${testClientSoftwareFields("0.3.0", "0.1.0")},"method":"Invocation","invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","secrets":{"test":{}},"operation":{"type":"exec","command":"env","arguments":[],"working_directory":"/tmp","executable_path":"/bin/env",$hashMember$scriptMember"executable_mode":"$executableMode","stdin":"$stdin","stdout":"$stdout","stderr":"$stderr"},"launcher_chain":$launchers}"""
    }
}
