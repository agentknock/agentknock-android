package dev.agentknock.ui.settings

import dev.agentknock.storage.audit.AuditDao
import dev.agentknock.storage.audit.AuditEventEntity
import dev.agentknock.storage.audit.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loads only while observed and replays page state when observation resumes`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val dao = TrackingAuditDao()
            val viewModel = AuditViewModel(AuditRepository(dao))

            assertEquals(AuditHistoryState.Loading, viewModel.history.value)
            assertEquals(AuditDetailState.None, viewModel.detail.value)
            assertEquals(0, dao.historyCollectors)
            assertEquals(0, dao.detailCollectors)

            val historyCollection = backgroundScope.launch(dispatcher) {
                viewModel.history.collect {}
            }
            val detailCollection = backgroundScope.launch(dispatcher) {
                viewModel.detail.collect {}
            }
            assertEquals(1, dao.historyCollectors)
            assertEquals(0, dao.detailCollectors)
            assertEquals(AuditHistoryState.Loading, viewModel.history.value)

            val event = auditEvent(id = 7)
            dao.emitHistory(listOf(event))
            val loadedHistory = viewModel.history.value as AuditHistoryState.Loaded
            assertEquals(listOf(7L), loadedHistory.events.map { it.id })

            viewModel.selectEvent(7)
            assertEquals(7L, viewModel.selectedEventId.value)
            assertEquals(AuditDetailState.Loading(7), viewModel.detail.value)
            assertEquals(1, dao.detailCollectors)

            dao.emitDetail(event)
            val loadedDetail = viewModel.detail.value as AuditDetailState.Loaded
            assertEquals(7L, loadedDetail.event?.id)

            historyCollection.cancelAndJoin()
            detailCollection.cancelAndJoin()
            assertEquals(0, dao.historyCollectors)
            assertEquals(0, dao.detailCollectors)
            assertEquals(loadedHistory, viewModel.history.value)
            assertEquals(loadedDetail, viewModel.detail.value)

            var replayedHistory: AuditHistoryState? = null
            var replayedDetail: AuditDetailState? = null
            val recreatedHistoryCollection = backgroundScope.launch(dispatcher) {
                viewModel.history.collect { state ->
                    if (replayedHistory == null) replayedHistory = state
                }
            }
            val recreatedDetailCollection = backgroundScope.launch(dispatcher) {
                viewModel.detail.collect { state ->
                    if (replayedDetail == null) replayedDetail = state
                }
            }

            assertEquals(loadedHistory, replayedHistory)
            assertEquals(loadedDetail, replayedDetail)
            assertEquals(7L, viewModel.selectedEventId.value)
            assertEquals(1, dao.historyCollectors)
            assertEquals(1, dao.detailCollectors)

            recreatedHistoryCollection.cancelAndJoin()
            recreatedDetailCollection.cancelAndJoin()
            assertEquals(0, dao.historyCollectors)
            assertEquals(0, dao.detailCollectors)

            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(AuditHistoryState.Loading, viewModel.history.value)
            assertEquals(AuditDetailState.None, viewModel.detail.value)
            assertEquals(7L, viewModel.selectedEventId.value)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class TrackingAuditDao : AuditDao {
    private val history = MutableSharedFlow<List<AuditEventEntity>>()
    private val detail = MutableSharedFlow<AuditEventEntity?>()
    var historyCollectors = 0
    var detailCollectors = 0

    suspend fun emitHistory(events: List<AuditEventEntity>) {
        history.emit(events)
    }

    suspend fun emitDetail(event: AuditEventEntity?) {
        detail.emit(event)
    }

    override fun observeEvents(): Flow<List<AuditEventEntity>> = flow {
        historyCollectors++
        try {
            emitAll(history)
        } finally {
            historyCollectors--
        }
    }

    override fun observeEvent(id: Long): Flow<AuditEventEntity?> = flow {
        detailCollectors++
        try {
            emitAll(detail)
        } finally {
            detailCollectors--
        }
    }

    override suspend fun insertEvents(events: List<AuditEventEntity>) = error("Not used")

    override suspend fun deleteBefore(cutoff: Long): Int = error("Not used")
}

private fun auditEvent(id: Long) = AuditEventEntity(
    id = id,
    occurredAt = 1_000,
    eventType = "secret_updated",
    subject = "Production",
    context = null,
    detail = null,
    outcome = "changed",
    decisionSource = "user",
    expiresAt = null,
    clientId = null,
    clientName = null,
    relayRequestId = null,
)
