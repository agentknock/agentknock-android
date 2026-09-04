package dev.agentknock.ui.settings

import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditPresentationTest {
    @Test
    fun `every event type has a valid presentation`() {
        AuditEventType.entries.forEach { type ->
            val (outcome, source) = representativeDecision(type)
            val presentation = event(type, outcome, source).presentation()
            assertTrue("Missing title for ${type.code}", presentation.title.isNotBlank())
            assertTrue(
                "Missing category for ${type.code}",
                presentation.category.displayName.isNotBlank(),
            )
        }
    }

    @Test
    fun `decision presentation uses outcome and source without encoded event variants`() {
        val presentation = event(
            type = AuditEventType.SECRET_USE_DECIDED,
            outcome = AuditOutcome.APPROVED,
            source = AuditDecisionSource.AI_REVIEW,
        ).presentation()

        assertEquals("Secret use approved by AI review", presentation.title)
        assertEquals("AI explanation", presentation.detailLabel)
        assertTrue(presentation.sensitiveUse)
    }

    @Test
    fun `non-sensitive use is not included as sensitive use`() {
        val presentation = event(
            type = AuditEventType.SECRET_USE_DECIDED,
            outcome = AuditOutcome.APPROVED,
            source = AuditDecisionSource.NON_SENSITIVE,
        ).presentation()

        assertEquals("Non-sensitive data provided automatically", presentation.title)
        assertFalse(presentation.sensitiveUse)
    }

    @Test
    fun `all operations using sensitive material are included as sensitive use`() {
        assertTrue(
            event(
                type = AuditEventType.SECRET_USE_DECIDED,
                outcome = AuditOutcome.APPROVED,
                source = AuditDecisionSource.USER,
            ).presentation().sensitiveUse,
        )
        assertTrue(
            event(
                type = AuditEventType.GIT_SIGN_DECIDED,
                outcome = AuditOutcome.APPROVED,
                source = AuditDecisionSource.USER,
            ).presentation().sensitiveUse,
        )
        assertTrue(
            event(
                type = AuditEventType.SSH_AUTHENTICATION_DECIDED,
                outcome = AuditOutcome.APPROVED,
                source = AuditDecisionSource.TEMPORARY_ACCESS,
            ).presentation().sensitiveUse,
        )
    }

    @Test
    fun `ssh authentication keeps the key as subject and detail event specific`() {
        val decision = event(
            type = AuditEventType.SSH_AUTHENTICATION_DECIDED,
            outcome = AuditOutcome.APPROVED,
            source = AuditDecisionSource.AI_REVIEW,
            detail = "Repository access is expected.",
        ).presentation()
        val completion = event(
            type = AuditEventType.SSH_AUTHENTICATION_COMPLETED,
            outcome = AuditOutcome.COMPLETED,
            detail = null,
        ).presentation()

        assertEquals("SSH key", decision.subjectLabel)
        assertEquals("Username", decision.contextLabel)
        assertEquals("AI explanation", decision.detailLabel)
        assertEquals("SSH key", completion.subjectLabel)
        assertEquals("Username", completion.contextLabel)
        assertEquals(null, completion.detailLabel)
    }

    @Test
    fun `completion failure retains its operation category`() {
        val presentation = event(
            type = AuditEventType.SECRET_UPLOAD_COMPLETED,
            outcome = AuditOutcome.FAILED,
        ).presentation()

        assertEquals(AuditCategory.SECRET_UPLOAD, presentation.category)
        assertEquals("Reason", presentation.detailLabel)
    }
}

private fun event(
    type: AuditEventType,
    outcome: AuditOutcome,
    source: AuditDecisionSource? = null,
    context: String? = null,
    detail: String? = "Detail",
) = AuditEvent(
    id = 1,
    occurredAt = 1,
    type = type,
    outcome = outcome,
    decisionSource = source,
    subject = "Subject",
    context = context,
    detail = detail,
    expiresAt = null,
    clientId = "client",
    clientName = "Client",
    relayRequestId = "request",
)

private fun representativeDecision(
    type: AuditEventType,
): Pair<AuditOutcome, AuditDecisionSource?> = when (type) {
    AuditEventType.CLIENT_RESUMED,
    AuditEventType.CLIENT_SUSPENDED,
    AuditEventType.CLIENT_REVOKED,
    AuditEventType.CLIENT_PENDING,
    AuditEventType.CLIENT_RENAMED,
    AuditEventType.CLIENT_INSTRUCTIONS_CHANGED,
    AuditEventType.SECRET_APPROVAL_MODE_CHANGED,
    AuditEventType.SECRET_INSTRUCTIONS_CHANGED,
    AuditEventType.CLIENT_APPROVAL_OVERRIDE_CHANGED,
    AuditEventType.TEMPORARY_ACCESS_ENDED,
    AuditEventType.SECRET_CREATED,
    AuditEventType.SSH_KEY_CREATED,
    AuditEventType.SSH_KEY_REPLACED,
    AuditEventType.SSH_PUBLIC_KEY_COMMENT_UPDATED,
    AuditEventType.SECRET_UPDATED,
    AuditEventType.SECRET_DELETED,
    AuditEventType.ENVIRONMENT_VARIABLE_ADDED,
    AuditEventType.ENVIRONMENT_VARIABLE_UPDATED,
    AuditEventType.ENVIRONMENT_VARIABLE_DELETED,
    AuditEventType.NEW_PAIRINGS_RESUMED,
    AuditEventType.NEW_PAIRINGS_PAUSED,
    AuditEventType.PAIRING_ADDRESS_CLAIMED,
    AuditEventType.PAIRING_ADDRESS_CHANGED,
    AuditEventType.GENERAL_AI_REVIEW_INSTRUCTIONS_CHANGED,
    -> AuditOutcome.CHANGED to null

    AuditEventType.CLIENT_REMOVAL_UNCONFIRMED,
    AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED,
    -> AuditOutcome.FAILED to null

    AuditEventType.CLIENT_UNPAIRED_ITSELF,
    AuditEventType.PAIRING_COMPLETED,
    AuditEventType.PAIRING_CONFIRMATION_RECEIVED,
    AuditEventType.SECRET_USE_COMPLETED,
    AuditEventType.GIT_SIGN_COMPLETED,
    AuditEventType.SSH_AUTHENTICATION_COMPLETED,
    AuditEventType.SECRET_LIST_COMPLETED,
    AuditEventType.SECRET_UPLOAD_COMPLETED,
    -> AuditOutcome.COMPLETED to null

    AuditEventType.PAIRING_DECIDED ->
        AuditOutcome.APPROVED to AuditDecisionSource.USER

    AuditEventType.PAIRING_REQUESTED,
    AuditEventType.SECRET_USE_RECEIVED,
    AuditEventType.GIT_SIGN_RECEIVED,
    AuditEventType.SSH_AUTHENTICATION_RECEIVED,
    AuditEventType.SECRET_LIST_RECEIVED,
    AuditEventType.SECRET_UPLOAD_RECEIVED,
    -> AuditOutcome.RECEIVED to null

    AuditEventType.SECRET_USE_AI_REVIEWED,
    AuditEventType.GIT_SIGN_AI_REVIEWED,
    AuditEventType.SSH_AUTHENTICATION_AI_REVIEWED,
    -> AuditOutcome.DEFERRED to AuditDecisionSource.AI_REVIEW

    AuditEventType.SECRET_USE_DECIDED,
    AuditEventType.GIT_SIGN_DECIDED,
    AuditEventType.SSH_AUTHENTICATION_DECIDED,
    -> AuditOutcome.APPROVED to AuditDecisionSource.USER

    AuditEventType.SECRET_UPLOAD_DECIDED ->
        AuditOutcome.APPROVED to AuditDecisionSource.USER

    AuditEventType.REQUEST_REJECTED ->
        AuditOutcome.REJECTED to AuditDecisionSource.VALIDATION

    AuditEventType.TEMPORARY_ACCESS_ALLOWED ->
        AuditOutcome.APPROVED to AuditDecisionSource.USER
}
