package dev.agentknock.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.agentknock.storage.audit.AuditDao
import dev.agentknock.storage.audit.AuditEventEntity
import dev.agentknock.storage.audit.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuditViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restores the selected event from saved state`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var originalToClose: AuditViewModel? = null
        var restoredToClose: AuditViewModel? = null
        try {
            val original =
                AuditViewModel(
                        AuditRepository(TrackingAuditDao()),
                        SavedStateHandle(),
                    )
                    .also {
                        originalToClose = it
                        it.selectEvent(7L)
                    }

            val restored =
                AuditViewModel(
                        AuditRepository(TrackingAuditDao()),
                        SavedStateHandle(
                            mapOf("selected_audit_event_id" to original.selectedEventId.value)
                        ),
                    )
                    .also { restoredToClose = it }

            assertEquals(7L, original.selectedEventId.value)
            assertEquals(7L, restored.selectedEventId.value)
        } finally {
            originalToClose?.viewModelScope?.cancel()
            restoredToClose?.viewModelScope?.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loads only while observed and replays page state when observation resumes`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var viewModelToClose: AuditViewModel? = null
        try {
            val dao = TrackingAuditDao()
            val viewModel =
                AuditViewModel(
                        AuditRepository(dao),
                        SavedStateHandle(),
                    )
                    .also { viewModelToClose = it }

            assertEquals(AuditHistoryState.Loading, viewModel.history.value)
            assertNull(viewModel.detail.value)
            assertEquals(0, dao.historyCollectors)
            assertEquals(0, dao.detailCollectors)

            val historyCollection =
                backgroundScope.launch(dispatcher) {
                    viewModel.history.collect {}
                }
            val detailCollection =
                backgroundScope.launch(dispatcher) {
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
            assertNull(viewModel.detailForSelection())
            assertEquals(1, dao.detailCollectors)

            dao.emitDetail(7, event)
            val loadedDetail = checkNotNull(viewModel.detailForSelection())
            assertEquals(7L, loadedDetail.eventId)
            assertEquals(7L, loadedDetail.event?.id)

            historyCollection.cancelAndJoin()
            detailCollection.cancelAndJoin()
            assertEquals(0, dao.historyCollectors)
            assertEquals(0, dao.detailCollectors)
            assertEquals(loadedHistory, viewModel.history.value)
            assertEquals(loadedDetail, viewModel.detail.value)

            var replayedHistory: AuditHistoryState? = null
            val replayedDetails = mutableListOf<AuditDetailResult?>()
            val recreatedHistoryCollection =
                backgroundScope.launch(dispatcher) {
                    viewModel.history.collect { state ->
                        if (replayedHistory == null) replayedHistory = state
                    }
                }
            val recreatedDetailCollection =
                backgroundScope.launch(dispatcher) {
                    viewModel.detail.collect { replayedDetails += it }
                }

            assertEquals(loadedHistory, replayedHistory)
            assertEquals(listOf(loadedDetail), replayedDetails)
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
            assertNull(viewModel.detail.value)
            assertEquals(7L, viewModel.selectedEventId.value)
        } finally {
            viewModelToClose?.viewModelScope?.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `selection changes hide the previous result until the selected query finishes`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val dao = TrackingAuditDao()
        val viewModel = AuditViewModel(AuditRepository(dao), SavedStateHandle())
        try {
            backgroundScope.launch(dispatcher) { viewModel.detail.collect {} }
            viewModel.selectEvent(7)
            dao.emitDetail(7, auditEvent(7))

            viewModel.selectEvent(8)

            assertEquals(8L, viewModel.selectedEventId.value)
            assertEquals(7L, viewModel.detail.value?.eventId)
            assertNull(viewModel.detailForSelection())
            assertEquals(setOf(8L), dao.observedEventIds)

            dao.emitDetail(7, auditEvent(7))
            assertNull(viewModel.detailForSelection())

            dao.emitDetail(8, null)
            val missing = viewModel.detailForSelection()
            assertNotNull(missing)
            assertEquals(8L, missing?.eventId)
            assertNull(missing?.event)

            viewModel.selectEvent(null)
            assertNull(viewModel.selectedEventId.value)
            assertNull(viewModel.detail.value)
            assertEquals(0, dao.detailCollectors)
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `off-screen selection changes cannot display the retained result on resubscribe`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val dao = TrackingAuditDao()
            val viewModel = AuditViewModel(AuditRepository(dao), SavedStateHandle())
            try {
                val originalCollection =
                    backgroundScope.launch(dispatcher) {
                        viewModel.detail.collect {}
                    }
                viewModel.selectEvent(7)
                dao.emitDetail(7, auditEvent(7))
                originalCollection.cancelAndJoin()

                viewModel.selectEvent(8)
                assertEquals(7L, viewModel.detail.value?.eventId)
                assertNull(viewModel.detailForSelection())
                assertEquals(0, dao.detailCollectors)

                val displayed = mutableListOf<AuditDetailResult?>()
                val resumedCollection =
                    backgroundScope.launch(dispatcher) {
                        viewModel.detail.collect { displayed += viewModel.detailForSelection() }
                    }
                assertEquals(listOf<AuditDetailResult?>(null), displayed)
                assertEquals(setOf(8L), dao.observedEventIds)

                dao.emitDetail(8, auditEvent(8))
                assertEquals(8L, displayed.last()?.eventId)
                assertEquals(8L, displayed.last()?.event?.id)

                resumedCollection.cancelAndJoin()
                viewModel.selectEvent(null)
                assertNull(viewModel.detailForSelection())
                backgroundScope.launch(dispatcher) { viewModel.detail.collect {} }
                assertNull(viewModel.detail.value)
                assertEquals(0, dao.detailCollectors)
            } finally {
                viewModel.viewModelScope.cancel()
                runCurrent()
                Dispatchers.resetMain()
            }
        }
}

private fun AuditViewModel.detailForSelection(): AuditDetailResult? =
    detail.value?.takeIf { it.eventId == selectedEventId.value }

private class TrackingAuditDao : AuditDao {
    private val history = MutableSharedFlow<List<AuditEventEntity>>()
    private val details = mutableMapOf<Long, MutableSharedFlow<AuditEventEntity?>>()
    var historyCollectors = 0
    var detailCollectors = 0
    val observedEventIds = mutableSetOf<Long>()

    suspend fun emitHistory(events: List<AuditEventEntity>) {
        history.emit(events)
    }

    suspend fun emitDetail(eventId: Long, event: AuditEventEntity?) {
        details.getOrPut(eventId) { MutableSharedFlow() }.emit(event)
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
        observedEventIds += id
        try {
            emitAll(details.getOrPut(id) { MutableSharedFlow() })
        } finally {
            detailCollectors--
            observedEventIds -= id
        }
    }

    override suspend fun insertEvents(events: List<AuditEventEntity>) = error("Not used")

    override suspend fun deleteBefore(cutoff: Long): Int = error("Not used")
}

private fun auditEvent(id: Long) =
    AuditEventEntity(
        id = id,
        occurredAt = 1_000,
        eventType = "secret_updated",
        outcome = "changed",
        decisionSource = "user",
        clientId = null,
        relayRequestId = null,
        bodyJson = "{\"subject\":\"Production\"}",
    )
