package dev.agentknock.storage.request

import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.protocol.InvocationResponseSecret
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.RequestedSecretApproval
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.secret.EnvironmentVariableSelection
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretValues
import kotlinx.serialization.decodeFromString

internal const val DECISION_SOURCE_USER = "user"
internal const val DECISION_SOURCE_POLICY = "policy"
internal const val DECISION_SOURCE_AI = "ai"
internal const val DECISION_SOURCE_NON_SENSITIVE = "non_sensitive"
internal const val DECISION_SOURCE_TEMPORARY_ACCESS = "temporary_access"
internal const val DECISION_SOURCE_MIXED = "mixed"
internal const val TEMPORARY_ACCESS_DURATION_MILLIS = 4 * 60 * 60 * 1_000L

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
        mode == SecretApprovalMode.TEMPORARY || mode == SecretApprovalMode.ASK_AI
    }
    return RequestedSecretApproval(
        id = secretId,
        name = secretName,
        defaultAction = when {
            activeTemporaryAccess != null -> ApprovalAction.APPROVE
            mode == SecretApprovalMode.DENY -> ApprovalAction.DENY
            mode == SecretApprovalMode.ASK_ME -> ApprovalAction.ASK_ME
            mode == SecretApprovalMode.TEMPORARY -> ApprovalAction.ASK_ME
            mode == SecretApprovalMode.ASK_AI -> ApprovalAction.ASK_AI
            else -> ApprovalAction.APPROVE
        },
        temporaryAccessEligible = mode == SecretApprovalMode.TEMPORARY ||
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
    else -> error("Unknown decision source: $this")
}
