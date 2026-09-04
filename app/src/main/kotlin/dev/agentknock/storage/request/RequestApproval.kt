package dev.agentknock.storage.request

import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.protocol.InvocationResponseSecret
import dev.agentknock.relay.RelayApprovalReviewClient
import dev.agentknock.relay.RelayApprovalReviewDecision
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.review.ApprovalReviewRequest
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.RequestedSecretApproval
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.auditDataOf
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.secret.EnvironmentVariableSelection
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretValues
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal const val DECISION_SOURCE_USER = "user"
internal const val DECISION_SOURCE_POLICY = "policy"
internal const val DECISION_SOURCE_AI = "ai"
internal const val DECISION_SOURCE_NON_SENSITIVE = "non_sensitive"
internal const val DECISION_SOURCE_TEMPORARY_ACCESS = "temporary_access"
internal const val DECISION_SOURCE_MIXED = "mixed"
internal const val DECISION_SOURCE_VALIDATION = "validation"
internal const val TEMPORARY_ACCESS_DURATION_MILLIS = 4 * 60 * 60 * 1_000L

internal data class AiReviewAttempt(
    val review: AiReview,
    val request: ApprovalReviewRequest?,
)

internal fun reviewedRequestState(responseAvailable: Boolean): InboxRequestState =
    if (responseAvailable) InboxRequestState.WAITING else InboxRequestState.ACTION_REQUIRED

internal fun invocationTokenHash(token: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(token)

internal fun InvocationRequestMessage.environmentSelections():
    Map<String, EnvironmentVariableSelection> = secretDelivery.mapNotNull { (secret, delivery) ->
        delivery.environment?.let { environment ->
            secret to EnvironmentVariableSelection(
                only = environment.only,
                omit = environment.omit,
                rename = environment.rename,
                stdin = environment.stdin,
            )
        }
    }.toMap()

internal fun decodeApprovalEvaluation(value: String): ApprovalEvaluation? =
    runCatching { storedJson.decodeFromString<ApprovalEvaluation>(value) }.getOrNull()

internal fun SecretValues.toResponseSecret(): InvocationResponseSecret = when (this) {
    is SecretValues.Environment -> InvocationResponseSecret.Environment(description, environment)
    is SecretValues.Ssh -> InvocationResponseSecret.Ssh(description, publicKey)
}

internal fun SecretApprovalPolicy.toRequestedSecretApproval(): RequestedSecretApproval {
    val activeTemporaryAccess = temporaryAccessExpiresAt?.takeIf {
        mode == SecretApprovalMode.ASK_ME || mode == SecretApprovalMode.ASK_AI
    }
    return RequestedSecretApproval(
        id = secretId,
        name = secretName,
        defaultAction = when {
            activeTemporaryAccess != null -> ApprovalAction.APPROVE
            mode == SecretApprovalMode.DENY -> ApprovalAction.DENY
            mode == SecretApprovalMode.ASK_ME -> ApprovalAction.ASK_ME
            mode == SecretApprovalMode.ASK_AI -> ApprovalAction.ASK_AI
            else -> ApprovalAction.APPROVE
        },
        temporaryAccessEligible = mode == SecretApprovalMode.ASK_ME ||
            mode == SecretApprovalMode.ASK_AI,
        temporaryAccessExpiresAt = activeTemporaryAccess,
        revision = revision,
    )
}

internal fun RequestedSecretDescription.authorizationCommitment(
    policies: List<SecretApprovalPolicy>,
    instructions: AuthorizationInstructionsCommitment? = null,
): AuthorizationCommitment = AuthorizationCommitment(
    secretRevisions = reviewMetadata.associate { secret -> secret.id to secret.revision },
    policies = policies.associate { policy ->
        policy.secretId to AuthorizationPolicyCommitment(
            mode = policy.mode.storedName,
            temporaryAccessExpiresAt = policy.temporaryAccessExpiresAt,
        )
    },
    expectedAbsentSecretNames = missingSecrets.toSet(),
    instructions = instructions,
)

internal fun ApprovalEvaluation.hasSameSecretPolicies(other: ApprovalEvaluation): Boolean =
    secrets.size == other.secrets.size && secrets.zip(other.secrets).all { (stored, current) ->
        stored.secretId == current.secretId &&
            stored.secretName == current.secretName &&
            stored.action == current.action &&
            stored.temporaryAccessEligible == current.temporaryAccessEligible &&
            stored.temporaryAccessExpiresAt == current.temporaryAccessExpiresAt &&
            stored.revision == current.revision
    }

internal fun String.toAuditDecisionSource(): AuditDecisionSource = when (this) {
    DECISION_SOURCE_USER -> AuditDecisionSource.USER
    DECISION_SOURCE_POLICY -> AuditDecisionSource.APPROVAL_SETTINGS
    DECISION_SOURCE_AI -> AuditDecisionSource.AI_REVIEW
    DECISION_SOURCE_NON_SENSITIVE -> AuditDecisionSource.NON_SENSITIVE
    DECISION_SOURCE_TEMPORARY_ACCESS -> AuditDecisionSource.TEMPORARY_ACCESS
    DECISION_SOURCE_MIXED -> AuditDecisionSource.MIXED
    DECISION_SOURCE_VALIDATION -> AuditDecisionSource.VALIDATION
    else -> error("Unknown decision source: $this")
}

internal suspend fun performAiReview(
    reviewer: RelayApprovalReviewClient,
    credentials: RelayDeviceCredentials,
    request: ApprovalReviewRequest,
): AiReview {
    val result = try {
        reviewer.review(credentials.deviceId, credentials.deviceToken, request)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return AiReview(failure = AiReviewFailure.UNAVAILABLE)
    }
    return when (result) {
        is RelayEndpointResult.Success -> AiReview(
            decision = when (result.value.decision) {
                RelayApprovalReviewDecision.APPROVE -> AiReviewDecision.APPROVE
                RelayApprovalReviewDecision.DENY -> AiReviewDecision.DENY
                RelayApprovalReviewDecision.ASK_USER -> AiReviewDecision.ASK_USER
            },
            explanation = result.value.explanation,
        )
        is RelayEndpointResult.Rejected -> AiReview(
            failure = if (
                result.status == 402 || result.code == "SUBSCRIPTION_REQUIRED"
            ) {
                AiReviewFailure.SUBSCRIPTION_REQUIRED
            } else {
                AiReviewFailure.RELAY_REJECTED
            },
            httpStatus = result.status,
            errorCode = result.code,
        )
        is RelayEndpointResult.Unavailable ->
            AiReview(failure = AiReviewFailure.UNAVAILABLE)
        RelayEndpointResult.InvalidResponse ->
            AiReview(failure = AiReviewFailure.INVALID_RESPONSE)
    }
}

internal fun AiReview.auditFailureDetail(): String? = when (failure) {
    AiReviewFailure.SUBSCRIPTION_REQUIRED -> "AI review requires a subscription."
    AiReviewFailure.RELAY_REJECTED -> "The relay rejected AI review."
    AiReviewFailure.UNAVAILABLE -> "AI review was unavailable."
    AiReviewFailure.INVALID_RESPONSE -> "AI review returned an invalid response."
    null -> null
}

internal fun AiReview.auditOutcome(): AuditOutcome = when {
    failure != null -> AuditOutcome.FAILED
    decision == AiReviewDecision.APPROVE -> AuditOutcome.APPROVED
    decision == AiReviewDecision.DENY -> AuditOutcome.DENIED
    decision == AiReviewDecision.ASK_USER -> AuditOutcome.DEFERRED
    else -> AuditOutcome.FAILED
}

internal fun AiReview.auditData() = auditDataOf(
    "ai_decision" to decision?.name?.lowercase(),
    "ai_explanation" to explanation,
    "ai_failure" to failure?.name?.lowercase(),
    "ai_http_status" to httpStatus,
    "ai_error_code" to errorCode,
)

internal fun AiReviewAttempt.auditRequestData() = auditDataOf(
    "ai_review_request" to request?.let {
        storedJson.parseToJsonElement(
            storedJson.encodeToString(ApprovalReviewRequest.serializer(), it),
        )
    },
)

internal fun AiReviewAttempt.auditData(appliedReview: AiReview) =
    review.auditData() + auditRequestData() + auditDataOf(
        "ai_review_service_called" to (request != null),
        "ai_result_applied" to (review == appliedReview),
        "resulting_review_decision" to appliedReview.decision?.name?.lowercase(),
        "resulting_review_explanation" to appliedReview.explanation,
        "resulting_review_failure" to appliedReview.failure?.name?.lowercase(),
    )
