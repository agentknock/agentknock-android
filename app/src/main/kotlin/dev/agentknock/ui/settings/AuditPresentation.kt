package dev.agentknock.ui.settings

import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome

internal enum class AuditCategory(val displayName: String) {
    PAIRING("Pairing"),
    SECRET_USE("Secret use"),
    GIT_SIGN("Git signing"),
    SSH_AUTHENTICATION("SSH authentication"),
    SECRET_LIST("Secret list"),
    SECRET_UPLOAD("Secret upload"),
    CLIENT("Client"),
    SECRET("Secret"),
    DEVICE("Device"),
    VERIFICATION("Verification"),
    APPROVAL("Approval"),
}

internal data class AuditPresentation(
    val title: String,
    val category: AuditCategory,
    val subjectLabel: String? = null,
    val contextLabel: String? = null,
    val detailLabel: String? = null,
    val sensitiveUse: Boolean = false,
)

internal fun AuditEvent.presentation(): AuditPresentation = when (type) {
    AuditEventType.CLIENT_RESUMED -> clientEvent("Client resumed")
    AuditEventType.CLIENT_SUSPENDED -> clientEvent("Client suspended")
    AuditEventType.CLIENT_REVOKED -> clientEvent("Client revoked")
    AuditEventType.CLIENT_PENDING -> clientEvent("Client pending")
    AuditEventType.CLIENT_REMOVAL_UNCONFIRMED -> clientEvent(
        title = "Client removal unconfirmed",
        detailLabel = "Reason",
    )
    AuditEventType.CLIENT_RENAMED -> clientEvent(
        title = "Client renamed",
        detailLabel = "Previous name",
    )
    AuditEventType.CLIENT_INSTRUCTIONS_CHANGED -> clientEvent("Client instructions changed")
    AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED -> clientEvent(
        title = "Client removal confirmation failed",
        detailLabel = "Reason",
    )
    AuditEventType.CLIENT_UNPAIRED_ITSELF -> clientEvent("Client unpaired itself")

    AuditEventType.PAIRING_DECIDED -> AuditPresentation(
        title = when (outcome) {
            AuditOutcome.APPROVED -> "Verification code accepted"
            AuditOutcome.REJECTED -> "Pairing rejected"
            else -> invalidOutcome()
        },
        category = AuditCategory.PAIRING,
        subjectLabel = "Client",
    )
    AuditEventType.PAIRING_REQUESTED -> pairingEvent("Pairing requested")
    AuditEventType.PAIRING_COMPLETED -> pairingEvent("Pairing completed")
    AuditEventType.PAIRING_CONFIRMATION_RECEIVED -> AuditPresentation(
        title = when (outcome) {
            AuditOutcome.COMPLETED -> "Client confirmed pairing"
            AuditOutcome.FAILED -> "Client pairing confirmation failed"
            else -> invalidOutcome()
        },
        category = AuditCategory.PAIRING,
        subjectLabel = "Client",
        detailLabel = if (outcome == AuditOutcome.FAILED) "Reason" else null,
    )

    AuditEventType.SECRET_USE_RECEIVED -> secretUseEvent("Secret use requested")
    AuditEventType.SECRET_USE_AI_REVIEWED -> secretUseEvent(
        title = aiReviewTitle("secret use"),
        detailLabel = "AI explanation",
    )
    AuditEventType.SECRET_USE_DECIDED -> secretUseEvent(
        title = decisionTitle("Secret use", nonSensitiveTitle = "Non-sensitive data provided automatically"),
        detailLabel = decisionDetailLabel(),
        sensitiveUse = outcome == AuditOutcome.APPROVED &&
            decisionSource != AuditDecisionSource.NON_SENSITIVE,
    )
    AuditEventType.SECRET_USE_COMPLETED -> secretUseEvent(
        title = completionTitle(
            completed = "Client received secret data",
            denied = "Client received denial",
            aborted = "Secret use aborted",
            failed = "Secret use confirmation failed",
        ),
        detailLabel = completionDetailLabel(),
    )

    AuditEventType.GIT_SIGN_RECEIVED -> gitSignEvent("Git signature requested")
    AuditEventType.GIT_SIGN_AI_REVIEWED -> gitSignEvent(
        title = aiReviewTitle("Git signing"),
        detailLabel = "AI explanation",
    )
    AuditEventType.GIT_SIGN_DECIDED -> gitSignEvent(
        title = decisionTitle("Git signature"),
        detailLabel = decisionDetailLabel(),
        sensitiveUse = outcome == AuditOutcome.APPROVED,
    )
    AuditEventType.GIT_SIGN_COMPLETED -> gitSignEvent(
        title = completionTitle(
            completed = "Client received Git signature",
            denied = "Client received signature denial",
            aborted = "Git signature request aborted",
            failed = "Git signature confirmation failed",
        ),
        detailLabel = completionDetailLabel(),
    )

    AuditEventType.SSH_AUTHENTICATION_RECEIVED ->
        sshAuthenticationEvent("SSH authentication requested")
    AuditEventType.SSH_AUTHENTICATION_AI_REVIEWED -> sshAuthenticationEvent(
        title = aiReviewTitle("SSH authentication"),
        detailLabel = "AI explanation",
    )
    AuditEventType.SSH_AUTHENTICATION_DECIDED -> sshAuthenticationEvent(
        title = decisionTitle("SSH authentication"),
        detailLabel = decisionDetailLabel(),
        sensitiveUse = outcome == AuditOutcome.APPROVED,
    )
    AuditEventType.SSH_AUTHENTICATION_COMPLETED -> sshAuthenticationEvent(
        title = completionTitle(
            completed = "Client received SSH signature",
            denied = "Client received SSH authentication denial",
            aborted = "SSH authentication request aborted",
            failed = "SSH authentication confirmation failed",
        ),
        detailLabel = completionDetailLabel(),
    )

    AuditEventType.SECRET_LIST_RECEIVED -> AuditPresentation(
        title = "Secret list requested",
        category = AuditCategory.SECRET_LIST,
        subjectLabel = "Result",
        detailLabel = "Secrets",
    )
    AuditEventType.SECRET_LIST_COMPLETED -> AuditPresentation(
        title = if (outcome == AuditOutcome.COMPLETED) {
            "Secret list delivered"
        } else {
            require(outcome == AuditOutcome.FAILED)
            "Secret list confirmation failed"
        },
        category = AuditCategory.SECRET_LIST,
        subjectLabel = "Result",
        detailLabel = failureDetailLabel(),
    )

    AuditEventType.SECRET_UPLOAD_RECEIVED -> AuditPresentation(
        title = if (outcome == AuditOutcome.RECEIVED) {
            "Secret upload received"
        } else {
            require(outcome == AuditOutcome.REJECTED)
            "Secret upload rejected automatically"
        },
        category = AuditCategory.SECRET_UPLOAD,
        subjectLabel = "Upload",
        detailLabel = if (outcome == AuditOutcome.REJECTED) "Reason" else null,
    )
    AuditEventType.SECRET_UPLOAD_DECIDED -> AuditPresentation(
        title = when (outcome) {
            AuditOutcome.APPROVED -> "Secret upload approved"
            AuditOutcome.REJECTED -> "Secret upload rejected"
            else -> invalidOutcome()
        },
        category = AuditCategory.SECRET_UPLOAD,
        subjectLabel = "Upload",
        detailLabel = when {
            outcome == AuditOutcome.APPROVED && detail != null -> "Previous name"
            else -> decisionDetailLabel()
        },
    )
    AuditEventType.SECRET_UPLOAD_COMPLETED -> AuditPresentation(
        title = if (outcome == AuditOutcome.COMPLETED) {
            "Client received upload result"
        } else {
            require(outcome == AuditOutcome.FAILED)
            "Secret upload confirmation failed"
        },
        category = AuditCategory.SECRET_UPLOAD,
        subjectLabel = "Upload",
        detailLabel = failureDetailLabel(),
    )

    AuditEventType.REQUEST_REJECTED -> AuditPresentation(
        title = "Request rejected",
        category = AuditCategory.VERIFICATION,
        detailLabel = "Reason",
    )

    AuditEventType.SECRET_APPROVAL_MODE_CHANGED -> secretEvent(
        title = "Secret approval mode changed",
        detailLabel = "Approval",
    )
    AuditEventType.SECRET_INSTRUCTIONS_CHANGED -> secretEvent("Secret instructions changed")
    AuditEventType.CLIENT_APPROVAL_OVERRIDE_CHANGED -> secretEvent(
        title = "Client approval override changed",
        detailLabel = "Approval",
    )
    AuditEventType.TEMPORARY_ACCESS_ALLOWED -> secretUseEvent(
        title = "Temporary access allowed",
        subjectLabel = "Secret",
        detailLabel = "Access",
    )
    AuditEventType.TEMPORARY_ACCESS_ENDED -> secretUseEvent(
        title = "Temporary access ended",
        subjectLabel = "Secret",
        detailLabel = "Access",
    )
    AuditEventType.SECRET_CREATED -> secretEvent("Secret created")
    AuditEventType.SSH_KEY_CREATED -> secretEvent("SSH key created")
    AuditEventType.SSH_KEY_REPLACED -> secretEvent("SSH key replaced")
    AuditEventType.SSH_PUBLIC_KEY_COMMENT_UPDATED ->
        secretEvent("SSH public key comment updated")
    AuditEventType.SECRET_UPDATED -> secretEvent(
        title = "Secret updated",
        detailLabel = detail?.let { "Previous name" },
    )
    AuditEventType.SECRET_DELETED -> secretEvent("Secret deleted")
    AuditEventType.ENVIRONMENT_VARIABLE_ADDED -> environmentVariableEvent(
        "Environment variable added",
    )
    AuditEventType.ENVIRONMENT_VARIABLE_UPDATED -> environmentVariableEvent(
        "Environment variable updated",
    )
    AuditEventType.ENVIRONMENT_VARIABLE_DELETED -> environmentVariableEvent(
        "Environment variable deleted",
    )

    AuditEventType.NEW_PAIRINGS_RESUMED -> deviceEvent("New pairings resumed")
    AuditEventType.NEW_PAIRINGS_PAUSED -> deviceEvent("New pairings paused")
    AuditEventType.PAIRING_ADDRESS_CLAIMED -> pairingAddressEvent("Pairing address claimed")
    AuditEventType.PAIRING_ADDRESS_CHANGED -> pairingAddressEvent("Pairing address changed")
    AuditEventType.GENERAL_AI_REVIEW_INSTRUCTIONS_CHANGED -> AuditPresentation(
        title = "General AI review instructions changed",
        category = AuditCategory.APPROVAL,
    )
}

private fun AuditEvent.clientEvent(
    title: String,
    detailLabel: String? = null,
) = AuditPresentation(title, AuditCategory.CLIENT, "Client", detailLabel = detailLabel)

private fun AuditEvent.pairingEvent(title: String) =
    AuditPresentation(title, AuditCategory.PAIRING, "Client")

private fun AuditEvent.secretUseEvent(
    title: String,
    subjectLabel: String = "Secrets",
    detailLabel: String? = null,
    sensitiveUse: Boolean = false,
) = AuditPresentation(
    title = title,
    category = AuditCategory.SECRET_USE,
    subjectLabel = subjectLabel,
    contextLabel = "Command",
    detailLabel = detailLabel,
    sensitiveUse = sensitiveUse,
)

private fun AuditEvent.gitSignEvent(
    title: String,
    detailLabel: String? = null,
    sensitiveUse: Boolean = false,
) = AuditPresentation(
    title = title,
    category = AuditCategory.GIT_SIGN,
    subjectLabel = "SSH key",
    detailLabel = detailLabel,
    sensitiveUse = sensitiveUse,
)

private fun AuditEvent.sshAuthenticationEvent(
    title: String,
    detailLabel: String? = null,
    sensitiveUse: Boolean = false,
) = AuditPresentation(
    title,
    AuditCategory.SSH_AUTHENTICATION,
    "SSH key",
    "Username",
    detailLabel,
    sensitiveUse,
)

private fun AuditEvent.secretEvent(
    title: String,
    detailLabel: String? = null,
) = AuditPresentation(
    title = title,
    category = AuditCategory.SECRET,
    subjectLabel = "Secret",
    detailLabel = detailLabel,
)

private fun AuditEvent.environmentVariableEvent(title: String) = AuditPresentation(
    title,
    AuditCategory.SECRET,
    "Environment variable",
    detailLabel = "Secret",
)

private fun AuditEvent.deviceEvent(title: String) =
    AuditPresentation(title, AuditCategory.DEVICE)

private fun AuditEvent.pairingAddressEvent(title: String) =
    AuditPresentation(title, AuditCategory.DEVICE, "Pairing address")

private fun AuditEvent.aiReviewTitle(operation: String): String = when (outcome) {
    AuditOutcome.APPROVED -> "AI review approved $operation"
    AuditOutcome.DENIED -> "AI review denied $operation"
    AuditOutcome.DEFERRED -> "AI review deferred $operation to the user"
    AuditOutcome.FAILED -> "AI review unavailable for $operation"
    else -> invalidOutcome()
}

private fun AuditEvent.decisionTitle(
    operation: String,
    nonSensitiveTitle: String? = null,
): String = when (outcome) {
    AuditOutcome.APPROVED -> when (decisionSource) {
        AuditDecisionSource.USER -> "$operation approved"
        AuditDecisionSource.APPROVAL_SETTINGS -> "$operation approved automatically"
        AuditDecisionSource.AI_REVIEW -> "$operation approved by AI review"
        AuditDecisionSource.TEMPORARY_ACCESS -> "$operation approved using temporary access"
        AuditDecisionSource.MIXED -> "$operation approved by multiple approval sources"
        AuditDecisionSource.NON_SENSITIVE -> nonSensitiveTitle ?: "$operation approved automatically"
        AuditDecisionSource.VALIDATION,
        null,
        -> "$operation approved"
    }
    AuditOutcome.DENIED -> when (decisionSource) {
        AuditDecisionSource.APPROVAL_SETTINGS -> "$operation denied by approval settings"
        AuditDecisionSource.AI_REVIEW -> "$operation denied by AI review"
        else -> "$operation denied"
    }
    AuditOutcome.REJECTED -> "$operation rejected automatically"
    AuditOutcome.FAILED -> "$operation failed"
    else -> invalidOutcome()
}

private fun AuditEvent.completionTitle(
    completed: String,
    denied: String,
    aborted: String,
    failed: String,
): String = when (outcome) {
    AuditOutcome.COMPLETED -> completed
    AuditOutcome.DENIED -> denied
    AuditOutcome.ABORTED -> aborted
    AuditOutcome.FAILED -> failed
    else -> invalidOutcome()
}

private fun AuditEvent.decisionDetailLabel(): String? = when {
    decisionSource == AuditDecisionSource.AI_REVIEW -> "AI explanation"
    outcome == AuditOutcome.DENIED || outcome == AuditOutcome.REJECTED ||
        outcome == AuditOutcome.FAILED -> "Reason"
    else -> null
}

private fun AuditEvent.failureDetailLabel(): String? =
    if (outcome == AuditOutcome.FAILED) "Reason" else null

private fun AuditEvent.completionDetailLabel(): String? =
    if (detail != null) "Reason" else null

private fun AuditEvent.invalidOutcome(): Nothing =
    error("Unexpected outcome $outcome for audit event ${type.code}")

internal fun AuditDecisionSource.displayName(): String = when (this) {
    AuditDecisionSource.USER -> "User"
    AuditDecisionSource.APPROVAL_SETTINGS -> "Approval settings"
    AuditDecisionSource.AI_REVIEW -> "AI review"
    AuditDecisionSource.TEMPORARY_ACCESS -> "Temporary access"
    AuditDecisionSource.MIXED -> "Multiple approval sources"
    AuditDecisionSource.NON_SENSITIVE -> "Non-sensitive data"
    AuditDecisionSource.VALIDATION -> "Request validation"
}
