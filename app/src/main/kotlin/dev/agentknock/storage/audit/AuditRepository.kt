package dev.agentknock.storage.audit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal enum class AuditCategory(
    val storedName: String,
    val displayName: String = storedName.replace('_', ' ').replaceFirstChar(Char::uppercase),
) {
    PAIRING("pairing"),
    SECRET_USE("secret_use"),
    GIT_SIGN("git_sign"),
    SSH_AUTHENTICATE("ssh_authenticate", "SSH authentication"),
    SECRET_LIST("secret_list"),
    SECRET_UPLOAD("secret_upload"),
    CLIENT("client"),
    SECRET("secret"),
    DEVICE("device"),
    VERIFICATION("verification"),
    APPROVAL("approval"),
    // Old events retain their stored value, but use the current product terminology.
    RULE("rule", "Approval"),
}

internal enum class AuditOutcome(val storedName: String) {
    RECEIVED("received"),
    APPROVED("approved"),
    DENIED("denied"),
    REJECTED("rejected"),
    ABORTED("aborted"),
    COMPLETED("completed"),
    CHANGED("changed"),
    FAILED("failed"),
}

internal data class AuditRecord(
    val category: AuditCategory,
    val title: String,
    val detail: String = "",
    val outcome: AuditOutcome,
    val clientId: String? = null,
    val relayRequestId: String? = null,
)

internal data class AuditEvent(
    val id: Long,
    val occurredAt: Long,
    val category: AuditCategory,
    val title: String,
    val detail: String,
    val outcome: AuditOutcome,
    val clientId: String?,
    val relayRequestId: String?,
)

internal fun interface AuditSink {
    suspend fun record(record: AuditRecord)
}

internal object NoOpAuditSink : AuditSink {
    override suspend fun record(record: AuditRecord) = Unit
}

internal class AuditRepository(
    private val dao: AuditDao,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AuditSink {
    fun observeEvents(): Flow<List<AuditEvent>> = dao.observeEvents().map { events ->
        events.map { event -> event.toModel() }
    }

    fun observeEvent(id: Long): Flow<AuditEvent?> = dao.observeEvent(id).map { it?.toModel() }

    fun observeCount(): Flow<Int> = dao.observeCount()

    override suspend fun record(record: AuditRecord) {
        val now = currentTimeMillis()
        dao.insertAndPrune(
            event = AuditEventEntity(
                occurredAt = now,
                category = record.category.storedName,
                title = record.title,
                detail = record.detail,
                outcome = record.outcome.storedName,
                clientId = record.clientId,
                relayRequestId = record.relayRequestId,
            ),
            cutoff = now - RETENTION_MILLIS,
        )
    }

    private fun AuditEventEntity.toModel() = AuditEvent(
        id = id,
        occurredAt = occurredAt,
        category = checkNotNull(AuditCategory.entries.find { it.storedName == category }),
        title = storedAuditTitle(title),
        detail = detail,
        outcome = storedAuditOutcome(outcome, title),
        clientId = clientId,
        relayRequestId = relayRequestId,
    )

    private companion object {
        const val RETENTION_MILLIS = 365L * 24 * 60 * 60 * 1000
    }
}

internal fun storedAuditOutcome(outcome: String, title: String): AuditOutcome = when {
    // Releases before the aborted outcome was introduced stored an authenticated client abort
    // as completed. Preserve those rows while presenting their real result.
    outcome == AuditOutcome.COMPLETED.storedName && title.contains("aborted", ignoreCase = true) ->
        AuditOutcome.ABORTED
    else -> checkNotNull(AuditOutcome.entries.find { it.storedName == outcome })
}

internal fun storedAuditTitle(title: String): String = when (title) {
    "Non-sensitive secret use delivered" -> "Non-sensitive data provided automatically"
    "Secret upload receipt confirmed",
    "Client confirmed upload receipt" -> "Client received upload result"
    "Secret use delivered",
    "Client confirmed secret data received" -> "Client received secret data"
    "Git signature delivered",
    "Client confirmed signature received" -> "Client received signature"
    "Secret use denial confirmed",
    "Client confirmed denial received",
    "Client confirmed secret use was denied" -> "Client received denial"
    "Git signature denial confirmed",
    "Client confirmed signature denial received",
    "Client confirmed Git signature was denied" -> "Client received signature denial"
    "Authenticated request rejected" -> "Request rejected"
    else -> title
}
