package dev.agentknock.storage.audit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal enum class AuditEventType(val code: String) {
    CLIENT_RESUMED("client_resumed"),
    CLIENT_SUSPENDED("client_suspended"),
    CLIENT_REVOKED("client_revoked"),
    CLIENT_PENDING("client_pending"),
    CLIENT_REMOVAL_UNCONFIRMED("client_removal_unconfirmed"),
    CLIENT_RENAMED("client_renamed"),
    CLIENT_INSTRUCTIONS_CHANGED("client_instructions_changed"),
    CLIENT_REMOVAL_CONFIRMATION_FAILED("client_removal_confirmation_failed"),
    CLIENT_UNPAIRED_ITSELF("client_unpaired_itself"),

    PAIRING_DECIDED("pairing_decided"),
    PAIRING_REQUESTED("pairing_requested"),
    PAIRING_COMPLETED("pairing_completed"),
    PAIRING_CONFIRMATION_RECEIVED("pairing_confirmation_received"),

    SECRET_USE_RECEIVED("secret_use_received"),
    SECRET_USE_AI_REVIEWED("secret_use_ai_reviewed"),
    SECRET_USE_DECIDED("secret_use_decided"),
    SECRET_USE_COMPLETED("secret_use_completed"),

    GIT_SIGN_RECEIVED("git_sign_received"),
    GIT_SIGN_AI_REVIEWED("git_sign_ai_reviewed"),
    GIT_SIGN_DECIDED("git_sign_decided"),
    GIT_SIGN_COMPLETED("git_sign_completed"),

    SSH_AUTHENTICATION_RECEIVED("ssh_authentication_received"),
    SSH_AUTHENTICATION_AI_REVIEWED("ssh_authentication_ai_reviewed"),
    SSH_AUTHENTICATION_DECIDED("ssh_authentication_decided"),
    SSH_AUTHENTICATION_COMPLETED("ssh_authentication_completed"),

    SECRET_LIST_RECEIVED("secret_list_received"),
    SECRET_LIST_COMPLETED("secret_list_completed"),

    SECRET_UPLOAD_RECEIVED("secret_upload_received"),
    SECRET_UPLOAD_DECIDED("secret_upload_decided"),
    SECRET_UPLOAD_COMPLETED("secret_upload_completed"),

    REQUEST_REJECTED("request_rejected"),

    SECRET_APPROVAL_MODE_CHANGED("secret_approval_mode_changed"),
    SECRET_INSTRUCTIONS_CHANGED("secret_instructions_changed"),
    CLIENT_APPROVAL_OVERRIDE_CHANGED("client_approval_override_changed"),
    TEMPORARY_ACCESS_ALLOWED("temporary_access_allowed"),
    TEMPORARY_ACCESS_ENDED("temporary_access_ended"),
    SECRET_CREATED("secret_created"),
    SSH_KEY_CREATED("ssh_key_created"),
    SSH_KEY_REPLACED("ssh_key_replaced"),
    SSH_PUBLIC_KEY_COMMENT_UPDATED("ssh_public_key_comment_updated"),
    SECRET_UPDATED("secret_updated"),
    SECRET_DELETED("secret_deleted"),
    ENVIRONMENT_VARIABLE_ADDED("environment_variable_added"),
    ENVIRONMENT_VARIABLE_UPDATED("environment_variable_updated"),
    ENVIRONMENT_VARIABLE_DELETED("environment_variable_deleted"),

    NEW_PAIRINGS_RESUMED("new_pairings_resumed"),
    NEW_PAIRINGS_PAUSED("new_pairings_paused"),
    PAIRING_ADDRESS_CLAIMED("pairing_address_claimed"),
    PAIRING_ADDRESS_CHANGED("pairing_address_changed"),
    GENERAL_AI_REVIEW_INSTRUCTIONS_CHANGED("general_ai_review_instructions_changed"),
    ;

    companion object {
        fun fromCode(code: String): AuditEventType =
            checkNotNull(entries.find { it.code == code }) { "Unknown audit event type: $code" }
    }
}

internal enum class AuditOutcome(val code: String) {
    RECEIVED("received"),
    APPROVED("approved"),
    DENIED("denied"),
    REJECTED("rejected"),
    ABORTED("aborted"),
    COMPLETED("completed"),
    CHANGED("changed"),
    FAILED("failed"),
    DEFERRED("deferred"),
    ;

    companion object {
        fun fromCode(code: String): AuditOutcome =
            checkNotNull(entries.find { it.code == code }) { "Unknown audit outcome: $code" }
    }
}

internal enum class AuditDecisionSource(val code: String) {
    USER("user"),
    APPROVAL_SETTINGS("approval_settings"),
    AI_REVIEW("ai_review"),
    TEMPORARY_ACCESS("temporary_access"),
    MIXED("mixed"),
    NON_SENSITIVE("non_sensitive"),
    VALIDATION("validation"),
    ;

    companion object {
        fun fromCode(code: String): AuditDecisionSource =
            checkNotNull(entries.find { it.code == code }) { "Unknown audit decision source: $code" }
    }
}

internal data class AuditRecord(
    val type: AuditEventType,
    val outcome: AuditOutcome,
    val decisionSource: AuditDecisionSource? = null,
    val subject: String? = null,
    val context: String? = null,
    val detail: String? = null,
    val expiresAt: Long? = null,
    val clientId: String? = null,
    val clientName: String? = null,
    val relayRequestId: String? = null,
)

internal data class AuditEvent(
    val id: Long,
    val occurredAt: Long,
    val type: AuditEventType,
    val outcome: AuditOutcome,
    val decisionSource: AuditDecisionSource?,
    val subject: String?,
    val context: String?,
    val detail: String?,
    val expiresAt: Long?,
    val clientId: String?,
    val clientName: String?,
    val relayRequestId: String?,
)

internal interface AuditSink {
    suspend fun record(record: AuditRecord)

    suspend fun append(records: List<AuditRecord>, occurredAt: Long)
}

internal object NoOpAuditSink : AuditSink {
    override suspend fun record(record: AuditRecord) = Unit

    override suspend fun append(records: List<AuditRecord>, occurredAt: Long) = Unit
}

internal class AuditRepository(
    private val dao: AuditDao,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AuditSink {
    fun observeEvents(): Flow<List<AuditEvent>> = dao.observeEvents().map { events ->
        events.map { event -> event.toModel() }
    }

    fun observeEvent(id: Long): Flow<AuditEvent?> = dao.observeEvent(id).map { it?.toModel() }

    suspend fun pruneExpired(): Int =
        dao.deleteBefore(currentTimeMillis() - RETENTION_MILLIS)

    override suspend fun record(record: AuditRecord) {
        append(listOf(record), currentTimeMillis())
    }

    override suspend fun append(records: List<AuditRecord>, occurredAt: Long) {
        require(records.isNotEmpty())
        dao.insertAndPrune(
            events = records.map { record -> record.toEntity(occurredAt) },
            cutoff = occurredAt - RETENTION_MILLIS,
        )
    }

    private fun AuditRecord.toEntity(occurredAt: Long) = AuditEventEntity(
        occurredAt = occurredAt,
        eventType = type.code,
        subject = subject,
        context = context,
        detail = detail,
        outcome = outcome.code,
        decisionSource = decisionSource?.code,
        expiresAt = expiresAt,
        clientId = clientId,
        clientName = clientName,
        relayRequestId = relayRequestId,
    )

    private fun AuditEventEntity.toModel(): AuditEvent = AuditEvent(
        id = id,
        occurredAt = occurredAt,
        type = AuditEventType.fromCode(eventType),
        outcome = AuditOutcome.fromCode(outcome),
        decisionSource = decisionSource?.let(AuditDecisionSource::fromCode),
        subject = subject,
        context = context,
        detail = detail,
        expiresAt = expiresAt,
        clientId = clientId,
        clientName = clientName,
        relayRequestId = relayRequestId,
    )

    private companion object {
        const val RETENTION_MILLIS = 365L * 24 * 60 * 60 * 1000
    }
}
