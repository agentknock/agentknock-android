package dev.agentknock.storage.audit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditRepositoryTest {
    @Test
    fun `records a stable event type with snapshots and correlation ids`() = runTest {
        val dao = FakeAuditDao()
        val repository = AuditRepository(dao, currentTimeMillis = { 1_000_000L })

        repository.record(
            AuditRecord(
                type = AuditEventType.TEMPORARY_ACCESS_ALLOWED,
                outcome = AuditOutcome.APPROVED,
                decisionSource = AuditDecisionSource.USER,
                subject = "Production",
                context = "Git signing",
                detail = "Secret use",
                expiresAt = 2_000_000L,
                clientId = "client-1",
                clientName = "Workstation",
                relayRequestId = "request-1",
            ),
        )

        assertEquals(
            AuditEventEntity(
                id = 1,
                occurredAt = 1_000_000L,
                eventType = "temporary_access_allowed",
                subject = "Production",
                context = "Git signing",
                detail = "Secret use",
                outcome = "approved",
                decisionSource = "user",
                expiresAt = 2_000_000L,
                clientId = "client-1",
                clientName = "Workstation",
                relayRequestId = "request-1",
            ),
            dao.events.value.single(),
        )
        assertEquals(
            AuditEvent(
                id = 1,
                occurredAt = 1_000_000L,
                type = AuditEventType.TEMPORARY_ACCESS_ALLOWED,
                outcome = AuditOutcome.APPROVED,
                decisionSource = AuditDecisionSource.USER,
                subject = "Production",
                context = "Git signing",
                detail = "Secret use",
                expiresAt = 2_000_000L,
                clientId = "client-1",
                clientName = "Workstation",
                relayRequestId = "request-1",
            ),
            repository.observeEvent(1).first(),
        )
    }

    @Test
    fun `all persisted audit codes are unique and round trip`() {
        assertEquals(
            AuditEventType.entries.size,
            AuditEventType.entries.map(AuditEventType::code).toSet().size,
        )
        AuditEventType.entries.forEach { type ->
            assertEquals(type, AuditEventType.fromCode(type.code))
        }

        assertEquals(
            AuditOutcome.entries.size,
            AuditOutcome.entries.map(AuditOutcome::code).toSet().size,
        )
        AuditOutcome.entries.forEach { outcome ->
            assertEquals(outcome, AuditOutcome.fromCode(outcome.code))
        }

        assertEquals(
            AuditDecisionSource.entries.size,
            AuditDecisionSource.entries.map(AuditDecisionSource::code).toSet().size,
        )
        AuditDecisionSource.entries.forEach { source ->
            assertEquals(source, AuditDecisionSource.fromCode(source.code))
        }
    }

    @Test
    fun `appends multiple records at one explicit timestamp`() = runTest {
        val dao = FakeAuditDao()
        val repository = AuditRepository(dao, currentTimeMillis = { error("unused") })

        repository.append(
            records = listOf(
                AuditRecord(AuditEventType.SECRET_UPDATED, AuditOutcome.CHANGED),
                AuditRecord(AuditEventType.SECRET_UPLOAD_DECIDED, AuditOutcome.APPROVED),
            ),
            occurredAt = 123_456L,
        )

        assertEquals(2, dao.events.value.size)
        assertEquals(listOf(123_456L, 123_456L), dao.events.value.map { it.occurredAt })
        assertEquals(
            listOf("secret_updated", "secret_upload_decided"),
            dao.events.value.map { it.eventType },
        )
    }

    @Test
    fun `startup pruning retains events at the one year cutoff`() = runTest {
        val now = 400L * 24 * 60 * 60 * 1000
        val cutoff = now - 365L * 24 * 60 * 60 * 1000
        val dao = FakeAuditDao()
        dao.insertEvents(
            listOf(
                auditEvent(occurredAt = cutoff - 1),
                auditEvent(occurredAt = cutoff),
                auditEvent(occurredAt = cutoff + 1),
            ),
        )
        val repository = AuditRepository(dao, currentTimeMillis = { now })

        assertEquals(1, repository.pruneExpired())

        assertEquals(
            listOf(cutoff, cutoff + 1),
            dao.events.value.map(AuditEventEntity::occurredAt),
        )
    }

    private fun auditEvent(occurredAt: Long) = AuditEventEntity(
        occurredAt = occurredAt,
        eventType = AuditEventType.SECRET_UPDATED.code,
        subject = null,
        context = null,
        detail = null,
        outcome = AuditOutcome.CHANGED.code,
        decisionSource = null,
        expiresAt = null,
        clientId = null,
        clientName = null,
        relayRequestId = null,
    )
}

private class FakeAuditDao : AuditDao {
    val events = MutableStateFlow<List<AuditEventEntity>>(emptyList())

    override fun observeEvents(): Flow<List<AuditEventEntity>> = events

    override fun observeEvent(id: Long): Flow<AuditEventEntity?> =
        events.map { current -> current.find { it.id == id } }

    override suspend fun insertEvents(events: List<AuditEventEntity>) {
        var id = (this.events.value.maxOfOrNull(AuditEventEntity::id) ?: 0) + 1
        this.events.value += events.map { event -> event.copy(id = id++) }
    }

    override suspend fun deleteBefore(cutoff: Long): Int {
        val retained = events.value.filter { it.occurredAt >= cutoff }
        val deleted = events.value.size - retained.size
        events.value = retained
        return deleted
    }
}
