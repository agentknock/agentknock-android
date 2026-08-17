package dev.agentknock.storage.audit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal enum class AuditCategory(val storedName: String) {
    PAIRING("pairing"),
    PROFILE_ACCESS("profile_access"),
    PROFILE_LIST("profile_list"),
    PROFILE_PROPOSAL("profile_proposal"),
    CLIENT("client"),
    PROFILE("profile"),
    DEVICE("device"),
    VERIFICATION("verification"),
}

internal enum class AuditOutcome(val storedName: String) {
    RECEIVED("received"),
    APPROVED("approved"),
    DENIED("denied"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
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
        title = title,
        detail = detail,
        outcome = checkNotNull(AuditOutcome.entries.find { it.storedName == outcome }),
        clientId = clientId,
        relayRequestId = relayRequestId,
    )

    private companion object {
        const val RETENTION_MILLIS = 365L * 24 * 60 * 60 * 1000
    }
}
