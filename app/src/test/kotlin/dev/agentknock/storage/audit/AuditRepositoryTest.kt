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

}

private class FakeAuditDao : AuditDao {
    val events = MutableStateFlow<List<AuditEventEntity>>(emptyList())

    override fun observeEvents(): Flow<List<AuditEventEntity>> = events

    override fun observeEvent(id: Long): Flow<AuditEventEntity?> =
        events.map { current -> current.find { it.id == id } }

    override fun observeCount(): Flow<Int> = events.map { it.size }

    override suspend fun insertEvent(event: AuditEventEntity): Long {
        val id = (events.value.maxOfOrNull(AuditEventEntity::id) ?: 0) + 1
        events.value += event.copy(id = id)
        return id
    }

    override suspend fun deleteBefore(cutoff: Long): Int {
        val retained = events.value.filter { it.occurredAt >= cutoff }
        val deleted = events.value.size - retained.size
        events.value = retained
        return deleted
    }
}
