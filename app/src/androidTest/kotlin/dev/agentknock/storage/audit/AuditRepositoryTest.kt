package dev.agentknock.storage.audit

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.AgentknockDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuditRepositoryTest {
    private lateinit var database: AgentknockDatabase
    private val dao
        get() = database.auditDao()

    @Before
    fun openDatabase() {
        database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AgentknockDatabase::class.java,
                )
                .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun recordsAStableEventTypeWithSnapshotsAndCorrelationIds() = runTest {
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
            )
        )

        val expected =
            AuditEventEntity(
                id = 1,
                occurredAt = 1_000_000L,
                eventType = "temporary_access_allowed",
                outcome = "approved",
                decisionSource = "user",
                clientId = "client-1",
                relayRequestId = "request-1",
                bodyJson =
                    "{\"subject\":\"Production\",\"context\":\"Git signing\"," +
                        "\"detail\":\"Secret use\",\"expires_at\":2000000," +
                        "\"client_name\":\"Workstation\"}",
            )
        val stored = dao.observeEvents().first().sortedBy { it.id }.single()
        assertEquals(expected.copy(bodyJson = ""), stored.copy(bodyJson = ""))
        assertEquals(
            Json.parseToJsonElement(expected.bodyJson),
            Json.parseToJsonElement(stored.bodyJson),
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
                data = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
            repository.observeEvent(1).first(),
        )
    }

    @Test
    fun persistedAuditCodesRemainUnambiguous() {
        assertEquals(
            AuditEventType.entries.size,
            AuditEventType.entries.map(AuditEventType::code).toSet().size,
        )

        assertEquals(
            AuditOutcome.entries.size,
            AuditOutcome.entries.map(AuditOutcome::code).toSet().size,
        )

        assertEquals(
            AuditDecisionSource.entries.size,
            AuditDecisionSource.entries.map(AuditDecisionSource::code).toSet().size,
        )
    }

    @Test
    fun appendsMultipleRecordsAtOneExplicitTimestamp() = runTest {
        val repository = AuditRepository(dao, currentTimeMillis = { error("unused") })

        repository.append(
            records =
                listOf(
                    AuditRecord(AuditEventType.SECRET_UPDATED, AuditOutcome.CHANGED),
                    AuditRecord(AuditEventType.SECRET_UPLOAD_DECIDED, AuditOutcome.APPROVED),
                ),
            occurredAt = 123_456L,
        )

        assertEquals(2, dao.observeEvents().first().sortedBy { it.id }.size)
        assertEquals(
            listOf(123_456L, 123_456L),
            dao.observeEvents().first().sortedBy { it.id }.map { it.occurredAt },
        )
        assertEquals(
            listOf("secret_updated", "secret_upload_decided"),
            dao.observeEvents().first().sortedBy { it.id }.map { it.eventType },
        )
    }

    @Test
    fun startupPruningRetainsEventsAtTheOneYearCutoff() = runTest {
        val now = 400L * 24 * 60 * 60 * 1000
        val cutoff = now - 365L * 24 * 60 * 60 * 1000
        dao.insertEvents(
            listOf(
                auditEvent(occurredAt = cutoff - 1),
                auditEvent(occurredAt = cutoff),
                auditEvent(occurredAt = cutoff + 1),
            )
        )
        val repository = AuditRepository(dao, currentTimeMillis = { now })

        assertEquals(1, repository.pruneExpired())

        assertEquals(
            listOf(cutoff, cutoff + 1),
            dao.observeEvents().first().sortedBy { it.id }.map(AuditEventEntity::occurredAt),
        )
    }

    private fun auditEvent(occurredAt: Long) =
        AuditEventEntity(
            occurredAt = occurredAt,
            eventType = AuditEventType.SECRET_UPDATED.code,
            outcome = AuditOutcome.CHANGED.code,
            decisionSource = null,
            clientId = null,
            relayRequestId = null,
            bodyJson = "{}",
        )
}
