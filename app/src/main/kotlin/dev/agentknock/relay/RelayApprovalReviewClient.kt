package dev.agentknock.relay

import dev.agentknock.review.ApprovalReviewRequest
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

internal interface RelayApprovalReviewClient {
    suspend fun review(
        deviceId: String,
        deviceToken: String,
        request: ApprovalReviewRequest,
    ): RelayApprovalReviewResult
}

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
    ): RelayApprovalReviewResult = transport.post(
        path = "v1/device/$deviceId/review",
        body = json.encodeToString(request),
        bearerToken = deviceToken,
    )
        .decodeSuccess { encoded ->
            val value = json.decodeFromString<ApprovalReviewResponse>(encoded)
            val decision = when (value.decision) {
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
