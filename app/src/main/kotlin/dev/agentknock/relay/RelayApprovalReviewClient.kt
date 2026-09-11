package dev.agentknock.relay

import dev.agentknock.review.ApprovalReviewRequest
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class RelayApprovalReviewDecision {
    APPROVE,
    DENY,
    ASK_USER,
}

internal data class RelayApprovalReview(
    val decision: RelayApprovalReviewDecision,
    val explanation: String,
)

internal typealias RelayApprovalReviewResult = RelayEndpointResult<RelayApprovalReview>

internal const val AI_REVIEW_TIMEOUT_MILLIS = 100_000L

internal interface RelayApprovalReviewClient {
    suspend fun review(
        deviceId: String,
        deviceToken: String,
        request: ApprovalReviewRequest,
    ): RelayApprovalReviewResult
}

internal suspend fun RelayApprovalReviewClient.reviewWithRetries(
    deviceId: String,
    deviceToken: String,
    request: ApprovalReviewRequest,
): RelayApprovalReviewResult =
    withTimeoutOrNull(AI_REVIEW_TIMEOUT_MILLIS) {
        var result = review(deviceId, deviceToken, request)
        for (retry in 0 until 3) {
            val retryAfterMillis =
                when (val previous = result) {
                    is RelayEndpointResult.Rejected -> {
                        if (!previous.status.isTransientRelayStatus()) break
                        previous.retryAfterMillis
                    }
                    is RelayEndpointResult.Unavailable -> null
                    else -> break
                }
            delay(retryAfterMillis ?: (1_000L shl retry))
            result = review(deviceId, deviceToken, request)
        }
        result
    } ?: RelayEndpointResult.Unavailable(IOException("AI review exceeded its 100-second deadline"))

internal class HttpRelayApprovalReviewClient(
    private val transport: RelayHttpTransport,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : RelayApprovalReviewClient {
    override suspend fun review(
        deviceId: String,
        deviceToken: String,
        request: ApprovalReviewRequest,
    ): RelayApprovalReviewResult =
        transport
            .post(
                path = "v1/device/$deviceId/review",
                body = json.encodeToString(request),
                bearerToken = deviceToken,
            )
            .decodeSuccess { encoded ->
                val value = json.decodeFromString<ApprovalReviewResponse>(encoded)
                val decision =
                    when (value.decision) {
                        "approve" -> RelayApprovalReviewDecision.APPROVE
                        "deny" -> RelayApprovalReviewDecision.DENY
                        "ask_user" -> RelayApprovalReviewDecision.ASK_USER
                        else -> error("Unknown approval review decision")
                    }
                require(value.explanation.isNotBlank()) {
                    "Approval review explanation is empty"
                }
                RelayApprovalReview(decision, value.explanation)
            }
}

@Serializable
private data class ApprovalReviewResponse(
    val decision: String,
    val explanation: String,
)
