package dev.agentknock.relay

import dev.agentknock.review.ApprovalReviewCommandEvidence
import dev.agentknock.review.ApprovalReviewEnvironmentDelivery
import dev.agentknock.review.ApprovalReviewEnvironmentSecretFacts
import dev.agentknock.review.ApprovalReviewEnvironmentVariableFacts
import dev.agentknock.review.ApprovalReviewEvidence
import dev.agentknock.review.ApprovalReviewFacts
import dev.agentknock.review.ApprovalReviewInstructions
import dev.agentknock.review.ApprovalReviewOperation
import dev.agentknock.review.ApprovalReviewRequest
import dev.agentknock.review.ApprovalReviewSshSecretFacts
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

class RelayApprovalReviewClientTest {
    @Test
    fun `submits complete context using device authentication`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"decision":"approve","explanation":"The policy allows it."}""")
                    .build()
            )

            assertEquals(
                RelayEndpointResult.Success(
                    RelayApprovalReview(
                        RelayApprovalReviewDecision.APPROVE,
                        "The policy allows it.",
                    )
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
                instructions
                    .getValue("secrets")
                    .jsonObject
                    .getValue("github")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(setOf("github"), instructions.getValue("secrets").jsonObject.keys)
            val facts = body.getValue("facts").jsonObject
            assertEquals("git", facts.getValue("client").jsonPrimitive.content)
            assertEquals("invocation", facts.getValue("operation").jsonPrimitive.content)
            val secrets = facts.getValue("secrets").jsonObject
            val github = secrets.getValue("github").jsonObject
            assertEquals("environment", github.getValue("type").jsonPrimitive.content)
            assertEquals(setOf("type"), secrets.getValue("git-signing").jsonObject.keys)
            val variables = github.getValue("variables").jsonObject
            assertEquals(
                setOf("delivery", "target"),
                variables.getValue("GITHUB_TOKEN").jsonObject.keys,
            )
            assertEquals(
                "https://api.github.com",
                variables
                    .getValue("GITHUB_API_URL")
                    .jsonObject
                    .getValue("value")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "GITHUB_API_URL",
                variables
                    .getValue("GITHUB_API_URL")
                    .jsonObject
                    .getValue("target")
                    .jsonPrimitive
                    .content,
            )
            val evidence = body.getValue("evidence").jsonObject
            assertEquals(setOf("reason", "command"), evidence.keys)
            assertEquals(
                "issue",
                evidence
                    .getValue("command")
                    .jsonObject
                    .getValue("argv")
                    .jsonArray[1]
                    .jsonPrimitive
                    .content,
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
                        """{"error":"SUBSCRIPTION_REQUIRED","message":"An active subscription is required."}"""
                    )
                    .build()
            )

            assertEquals(
                RelayEndpointResult.Rejected(
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
                    .body("""{"decision":"ask_user","explanation":"The reason is ambiguous."}""")
                    .build()
            )

            assertEquals(
                RelayEndpointResult.Success(
                    RelayApprovalReview(
                        RelayApprovalReviewDecision.ASK_USER,
                        "The reason is ambiguous.",
                    )
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
                    .body("""{"decision":"deny","explanation":"The command is destructive."}""")
                    .build()
            )

            assertEquals(
                RelayEndpointResult.Success(
                    RelayApprovalReview(
                        RelayApprovalReviewDecision.DENY,
                        "The command is destructive.",
                    )
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
                    .build()
            )

            assertEquals(
                RelayEndpointResult.InvalidResponse,
                client(server).review(DEVICE_ID, DEVICE_TOKEN, reviewRequest()),
            )
        }
    }

    private fun client(server: MockWebServer) =
        HttpRelayApprovalReviewClient(
            transport =
                RelayHttpTransport(
                    client = OkHttpClient(),
                    relayUrl = server.url("/").toString(),
                )
        )

    private fun reviewRequest() =
        ApprovalReviewRequest(
            instructions =
                ApprovalReviewInstructions(
                    general = "Protect production systems.",
                    client = "Use only for work on this repository.",
                    secrets =
                        mapOf("github" to "Allow reading issues but not publishing releases."),
                ),
            facts =
                ApprovalReviewFacts(
                    client = "git",
                    operation = ApprovalReviewOperation.INVOCATION,
                    secrets =
                        linkedMapOf(
                            "github" to
                                ApprovalReviewEnvironmentSecretFacts(
                                    variables =
                                        linkedMapOf(
                                            "GITHUB_TOKEN" to environmentFact("GITHUB_TOKEN", null),
                                            "GITHUB_API_URL" to
                                                environmentFact(
                                                    "GITHUB_API_URL",
                                                    "https://api.github.com",
                                                ),
                                        )
                                ),
                            "git-signing" to ApprovalReviewSshSecretFacts,
                        ),
                ),
            evidence =
                ApprovalReviewEvidence(
                    reason = "Inspect an issue",
                    command =
                        ApprovalReviewCommandEvidence(
                            argv = listOf("gh", "issue", "view", "234"),
                            workingDirectory = "/work/project",
                            resolvedExecutable = "/run/current-system/sw/bin/gh",
                            launcherChain = listOf("agentknock", "shell"),
                        ),
                ),
        )

    private fun environmentFact(name: String, value: String?) =
        ApprovalReviewEnvironmentVariableFacts(
            delivery = ApprovalReviewEnvironmentDelivery.ENVIRONMENT,
            target = name,
            value = value,
        )

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
