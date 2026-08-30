package dev.agentknock.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
            assertEquals(
                setOf("instructions", "facts", "evidence"),
                body.keys,
            )
            val instructions = body.getValue("instructions").jsonObject
            assertEquals(
                "Protect production systems.",
                instructions.getValue("general").jsonPrimitive.content,
            )
            assertEquals(
                "Use only for work on this repository.",
                instructions.getValue("client").jsonPrimitive.content,
            )
            assertEquals(
                "Allow reading issues but not publishing releases.",
                instructions.getValue("secrets").jsonObject
                    .getValue("github").jsonPrimitive.content,
            )
            assertEquals(
                JsonNull,
                instructions.getValue("secrets").jsonObject
                    .getValue("git-signing"),
            )
            val facts = body.getValue("facts").jsonObject
            assertEquals("git", facts.getValue("client").jsonPrimitive.content)
            assertEquals("invocation", facts.getValue("operation").jsonPrimitive.content)
            val secrets = facts.getValue("secrets").jsonObject
            val github = secrets.getValue("github").jsonObject
            assertEquals("environment", github.getValue("type").jsonPrimitive.content)
            val variables = github.getValue("environment_variables").jsonObject
            assertEquals(
                JsonNull,
                variables.getValue("GITHUB_TOKEN").jsonObject.getValue("value"),
            )
            assertEquals(
                "https://api.github.com",
                variables.getValue("GITHUB_API_URL").jsonObject
                    .getValue("value").jsonPrimitive.content,
            )
            assertEquals(
                "GITHUB_API_URL",
                variables.getValue("GITHUB_API_URL").jsonObject
                    .getValue("destination").jsonObject
                    .getValue("name").jsonPrimitive.content,
            )
            val evidence = body.getValue("evidence").jsonObject
            assertEquals(setOf("reason", "command"), evidence.keys)
            assertEquals(
                "issue",
                evidence.getValue("command").jsonObject.getValue("argv")
                    .jsonArray[1].jsonPrimitive.content,
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
        instructions = ApprovalReviewInstructions(
            general = "Protect production systems.",
            client = "Use only for work on this repository.",
            secrets = mapOf(
                "github" to "Allow reading issues but not publishing releases.",
                "git-signing" to null,
            ),
        ),
        facts = ApprovalReviewFacts(
            client = "git",
            operation = ApprovalReviewOperation.INVOCATION,
            secrets = linkedMapOf(
                "github" to ApprovalReviewEnvironmentSecretFacts(
                    environmentVariables = linkedMapOf(
                        "GITHUB_TOKEN" to environmentFact("GITHUB_TOKEN", null),
                        "GITHUB_API_URL" to environmentFact(
                            "GITHUB_API_URL",
                            "https://api.github.com",
                        ),
                    ),
                ),
                "git-signing" to ApprovalReviewSshSecretFacts(
                    provides = "public_key",
                ),
            ),
        ),
        evidence = ApprovalReviewEvidence(
            reason = "Inspect an issue",
            command = ApprovalReviewCommandEvidence(
                argv = listOf("gh", "issue", "view", "234"),
                workingDirectory = "/work/project",
                resolvedExecutable = "/run/current-system/sw/bin/gh",
                launcherChain = listOf("agentknock", "shell"),
            ),
        ),
    )

    private fun environmentFact(name: String, value: String?) =
        ApprovalReviewEnvironmentVariableFacts(
            destination = ApprovalReviewEnvironmentDestination(name),
            value = value?.let(::JsonPrimitive) ?: JsonNull,
        )

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
