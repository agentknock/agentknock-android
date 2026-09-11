package dev.agentknock.relay

import dev.agentknock.review.ApprovalReviewEvidence
import dev.agentknock.review.ApprovalReviewFacts
import dev.agentknock.review.ApprovalReviewInstructions
import dev.agentknock.review.ApprovalReviewOperation
import dev.agentknock.review.ApprovalReviewRequest
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalReviewRetriesTest {
    @Test
    fun `honors server retry delay and repeats the same review request`() = runTest {
        val attempts = mutableListOf<Long>()
        val reviewer = reviewer { deviceId, token, request ->
            assertEquals("device", deviceId)
            assertEquals("token", token)
            assertSame(REQUEST, request)
            attempts += testScheduler.currentTime
            if (attempts.size == 1) temporaryError(429, 7_000) else APPROVED
        }

        assertEquals(APPROVED, reviewer.reviewWithRetries("device", "token", REQUEST))
        assertEquals(listOf(0L, 7_000L), attempts)
    }

    @Test
    fun `retries temporary HTTP errors at most three times with backoff`() = runTest {
        for (status in listOf(408, 425, 429, 500, 502, 503, 504, 599)) {
            val start = testScheduler.currentTime
            val attempts = mutableListOf<Long>()
            val error = temporaryError(status)
            val reviewer = reviewer { _, _, _ ->
                attempts += testScheduler.currentTime - start
                error
            }

            assertEquals(error, reviewer.reviewWithRetries("device", "token", REQUEST))
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), attempts)
        }
    }

    @Test
    fun `a successful third retry is accepted`() = runTest {
        var attempts = 0
        val reviewer = reviewer { _, _, _ ->
            attempts++
            if (attempts == 4) APPROVED else temporaryError(503, 0)
        }

        assertEquals(APPROVED, reviewer.reviewWithRetries("device", "token", REQUEST))
        assertEquals(4, attempts)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `review decisions permanent errors and uncertain outcomes are not retried`() = runTest {
        val results =
            RelayApprovalReviewDecision.entries.map {
                RelayEndpointResult.Success(RelayApprovalReview(it, "Review completed."))
            } +
                listOf(
                    temporaryError(400, 1_000),
                    temporaryError(401, 1_000),
                    RelayEndpointResult.Rejected(402, "SUBSCRIPTION_REQUIRED", null, 1_000),
                    temporaryError(403, 1_000),
                    temporaryError(409, 1_000),
                    RelayEndpointResult.InvalidResponse,
                    RelayEndpointResult.Unavailable(IOException("Response lost")),
                )
        for (result in results) {
            var attempts = 0
            val reviewer = reviewer { _, _, _ ->
                attempts++
                result
            }

            assertSame(result, reviewer.reviewWithRetries("device", "token", REQUEST))
            assertEquals(1, attempts)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a single slow review can use the full shared deadline`() = runTest {
        val reviewer = reviewer { _, _, _ ->
            delay(99_000)
            APPROVED
        }

        assertEquals(APPROVED, reviewer.reviewWithRetries("device", "token", REQUEST))
        assertEquals(99_000L, testScheduler.currentTime)
    }

    @Test
    fun `deadline cancels an in-flight review after 100 seconds`() = runTest {
        var cancelled = false
        val reviewer = reviewer { _, _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }

        val result = reviewer.reviewWithRetries("device", "token", REQUEST)

        assertTrue(result is RelayEndpointResult.Unavailable)
        assertTrue(cancelled)
        assertEquals(100_000L, testScheduler.currentTime)
    }

    @Test
    fun `attempts and retry waits consume one deadline`() = runTest {
        val attempts = mutableListOf<Long>()
        var cancelled = false
        val reviewer = reviewer { _, _, _ ->
            attempts += testScheduler.currentTime
            if (attempts.size == 1) {
                delay(40_000)
                temporaryError(503, 20_000)
            } else {
                try {
                    delay(50_000)
                    APPROVED
                } finally {
                    cancelled = true
                }
            }
        }

        val result = reviewer.reviewWithRetries("device", "token", REQUEST)

        assertTrue(result is RelayEndpointResult.Unavailable)
        assertTrue(cancelled)
        assertEquals(listOf(0L, 60_000L), attempts)
        assertEquals(100_000L, testScheduler.currentTime)
    }

    @Test
    fun `a server delay beyond the deadline never causes an early retry`() = runTest {
        var attempts = 0
        val reviewer = reviewer { _, _, _ ->
            attempts++
            temporaryError(429, Long.MAX_VALUE)
        }

        val result = reviewer.reviewWithRetries("device", "token", REQUEST)

        assertTrue(result is RelayEndpointResult.Unavailable)
        assertEquals(1, attempts)
        assertEquals(100_000L, testScheduler.currentTime)
    }

    @Test
    fun `caller cancellation interrupts retry waiting`() = runTest {
        var attempts = 0
        val reviewer = reviewer { _, _, _ ->
            attempts++
            temporaryError(503, 30_000)
        }
        val review = async { reviewer.reviewWithRetries("device", "token", REQUEST) }
        runCurrent()
        advanceTimeBy(1_000)

        review.cancelAndJoin()

        assertTrue(review.isCancelled)
        assertEquals(1, attempts)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    private fun reviewer(
        respond: suspend (String, String, ApprovalReviewRequest) -> RelayApprovalReviewResult
    ) =
        object : RelayApprovalReviewClient {
            override suspend fun review(
                deviceId: String,
                deviceToken: String,
                request: ApprovalReviewRequest,
            ) = respond(deviceId, deviceToken, request)
        }

    private fun temporaryError(status: Int, retryAfterMillis: Long? = null) =
        RelayEndpointResult.Rejected(status, "TEMPORARY", "Try later", retryAfterMillis)

    private companion object {
        val REQUEST =
            ApprovalReviewRequest(
                ApprovalReviewInstructions("", "", emptyMap()),
                ApprovalReviewFacts("client", ApprovalReviewOperation.INVOCATION),
                ApprovalReviewEvidence(reason = "Inspect the repository"),
            )
        val APPROVED =
            RelayEndpointResult.Success(
                RelayApprovalReview(RelayApprovalReviewDecision.APPROVE, "Allowed")
            )
    }
}
