package dev.agentknock.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayApprovalReviewClientTest {
    @Test
    fun `submits complete context using device authentication`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(
                        """{"decision":"approve","explanation":"The policy allows it."}""",
                    )
                    .build(),
            )

            assertEquals(
                RelayApprovalReviewResult.Reviewed(
                    RelayApprovalReviewDecision.APPROVE,
                    "The policy allows it.",
                ),
                client(server).review(DEVICE_ID, DEVICE_TOKEN, reviewRequest()),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/device/$DEVICE_ID/review", request.target)
            assertEquals("Bearer $DEVICE_TOKEN", request.headers["Authorization"])
            val body = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            assertEquals(1, body.getValue("context_version").jsonPrimitive.content.toInt())
            assertEquals(
                "ask_ai",
                body.getValue("policy").jsonObject.getValue("decision").jsonPrimitive.content,
            )
            val policy = body.getValue("policy").jsonObject
            assertEquals(
                "Protect production systems.",
                policy.getValue("device_instructions").jsonPrimitive.content,
            )
            assertEquals(
                "Use only for work on this repository.",
                policy.getValue("client_instructions").jsonPrimitive.content,
            )
            val secretDecision = policy.getValue("secret_decisions")
                .jsonArray.single().jsonObject
            assertEquals("ask_me", secretDecision.getValue("default_decision").jsonPrimitive.content)
            assertEquals("true", secretDecision.getValue("client_override").jsonPrimitive.content)
            assertEquals(
                "Allow reading issues but not publishing releases.",
                secretDecision.getValue("instructions").jsonPrimitive.content,
            )
            val action = body.getValue("action").jsonObject
            assertEquals("git", action.getValue("client").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals(
                "GITHUB_TOKEN",
                action.getValue("secrets").jsonArray.single().jsonObject
                    .getValue("environment_variables").jsonArray.single().jsonObject
                    .getValue("name").jsonPrimitive.content,
            )
            assertEquals(
                "issue",
                action.getValue("operation").jsonObject.getValue("arguments")
                    .jsonArray.first().jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `returns subscription rejection without interpreting it as a review`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(402)
                    .body(
                        """{"error":"SUBSCRIPTION_REQUIRED","message":"An active subscription is required."}""",
                    )
                    .build(),
            )

            assertEquals(
                RelayApprovalReviewResult.Rejected(
                    status = 402,
                    code = "SUBSCRIPTION_REQUIRED",
                    message = "An active subscription is required.",
                ),
                client(server).review(DEVICE_ID, DEVICE_TOKEN, reviewRequest()),
            )
        }
    }

    @Test
    fun `accepts a reviewer decision to ask the user`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(
                        """{"decision":"ask_user","explanation":"The reason is ambiguous."}""",
                    )
                    .build(),
            )

            assertEquals(
                RelayApprovalReviewResult.Reviewed(
                    RelayApprovalReviewDecision.ASK_USER,
                    "The reason is ambiguous.",
                ),
                client(server).review(DEVICE_ID, DEVICE_TOKEN, reviewRequest()),
            )
        }
    }

    @Test
    fun `accepts a reviewer decision to deny`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(
                        """{"decision":"deny","explanation":"The command is destructive."}""",
                    )
                    .build(),
            )

            assertEquals(
                RelayApprovalReviewResult.Reviewed(
                    RelayApprovalReviewDecision.DENY,
                    "The command is destructive.",
                ),
                client(server).review(DEVICE_ID, DEVICE_TOKEN, reviewRequest()),
            )
        }
    }

    @Test
    fun `rejects malformed successful review responses`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"decision":"approve","explanation":""}""")
                    .build(),
            )

            assertEquals(
                RelayApprovalReviewResult.InvalidResponse,
                client(server).review(DEVICE_ID, DEVICE_TOKEN, reviewRequest()),
            )
        }
    }

    private fun client(server: MockWebServer) = HttpRelayApprovalReviewClient(
        client = OkHttpClient(),
        relayUrl = server.url("/").toString(),
        dispatcher = UnconfinedTestDispatcher(),
    )

    private fun reviewRequest() = ApprovalReviewRequest(
        contextVersion = 1,
        policy = ApprovalReviewPolicy(
            decision = "ask_ai",
            deviceInstructions = "Protect production systems.",
            clientInstructions = "Use only for work on this repository.",
            secretDecisions = listOf(
                ApprovalReviewSecretDecision(
                    secretId = "secret-id",
                    secretName = "github",
                    decision = "ask_ai",
                    defaultDecision = "ask_me",
                    clientOverride = true,
                    instructions = "Allow reading issues but not publishing releases.",
                    matchingRuleIds = listOf("rule-id"),
                    decisiveRuleIds = listOf("rule-id"),
                ),
            ),
            matchingRules = listOf(
                ApprovalReviewRule(
                    id = "rule-id",
                    name = "Review GitHub issue commands",
                    action = "ask_ai",
                    secretIds = listOf("secret-id"),
                    secretNames = listOf("github"),
                    command = listOf("gh", "issue"),
                    commandMatch = "prefix",
                    executablePath = null,
                    executableSha256 = null,
                    workingDirectory = null,
                    createdAtUnixMs = 1,
                    updatedAtUnixMs = 1,
                    expiresAtUnixMs = null,
                    lastMatchedAtUnixMs = null,
                    previousMatchCount = 0,
                ),
            ),
        ),
        action = ApprovalReviewAction(
            requestId = "01K2ENXDTW1P3XAR4J7V7C9D0J",
            requestedAtUnixMs = 2,
            reason = "Inspect an issue",
            containsSensitiveMaterial = true,
            client = ApprovalReviewClient(
                id = "client-id",
                name = "git",
                hostname = "workstation",
                platform = "linux",
                architecture = "x86_64",
                machineId = "machine-id",
                osVersion = "NixOS",
                software = ApprovalReviewSoftware(
                    application = ApprovalReviewSoftwareComponent("agentknock", "0.1.0"),
                    library = ApprovalReviewSoftwareComponent("agentknock", "0.1.0"),
                ),
            ),
            secrets = listOf(
                ApprovalReviewSecret(
                    id = "secret-id",
                    name = "github",
                    description = "GitHub credentials",
                    type = "environment",
                    createdAtUnixMs = 1,
                    updatedAtUnixMs = 1,
                    environmentVariables = listOf(
                        ApprovalReviewEnvironmentVariable(
                            name = "GITHUB_TOKEN",
                            sensitive = true,
                            notes = "Read-only token",
                            createdAtUnixMs = 1,
                            updatedAtUnixMs = 1,
                            valueUpdatedAtUnixMs = 1,
                        ),
                    ),
                    sshKey = null,
                ),
            ),
            operation = ApprovalReviewOperation(
                command = "gh",
                arguments = listOf("issue", "view", "234"),
                workingDirectory = "/work/project",
                executablePath = "/run/current-system/sw/bin/gh",
                executableSha256 = "sha256",
                executableMode = "executable",
                stdin = "terminal",
                stdout = "terminal",
                stderr = "terminal",
            ),
            launcherChain = listOf("agentknock", "shell"),
        ),
    )

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
