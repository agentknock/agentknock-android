package dev.agentknock.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal sealed interface AuditHistoryState {
    data object Loading : AuditHistoryState
    data class Loaded(val events: List<AuditEvent>) : AuditHistoryState
}

internal sealed interface AuditDetailState {
    data object None : AuditDetailState
    data class Loading(val eventId: Long) : AuditDetailState
    data class Loaded(val eventId: Long, val event: AuditEvent?) : AuditDetailState
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class AuditViewModel(
    audit: AuditRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _selectedEventId = savedStateHandle.getMutableStateFlow<Long?>(
        SELECTED_EVENT_ID,
        null,
    )

    val history: StateFlow<AuditHistoryState> = audit.observeEvents()
        .map<List<AuditEvent>, AuditHistoryState> { events -> AuditHistoryState.Loaded(events) }
        .stateIn(viewModelScope, whileSubscribed, AuditHistoryState.Loading)
    val selectedEventId: StateFlow<Long?> = _selectedEventId.asStateFlow()
    private val observedDetail: StateFlow<AuditDetailState> = _selectedEventId.flatMapLatest { id ->
        id?.let { eventId ->
            audit.observeEvent(eventId).map<AuditEvent?, AuditDetailState> { event ->
                AuditDetailState.Loaded(eventId, event)
            }
        } ?: flowOf(AuditDetailState.None)
    }.stateIn(viewModelScope, whileSubscribed, AuditDetailState.None)
    val detail: StateFlow<AuditDetailState> = combine(
        _selectedEventId,
        observedDetail,
    ) { eventId, observed ->
        when {
            eventId == null -> AuditDetailState.None
            observed is AuditDetailState.Loaded && observed.eventId == eventId -> observed
            else -> AuditDetailState.Loading(eventId)
        }
    }.stateIn(viewModelScope, whileSubscribed, AuditDetailState.None)

    fun selectEvent(id: Long?) {
        _selectedEventId.value = id
    }

    private companion object {
        // The retained replay avoids loading flicker without keeping Room observed off-page.
        val whileSubscribed = SharingStarted.WhileSubscribed(
            stopTimeoutMillis = 0,
            replayExpirationMillis = AUDIT_REPLAY_GRACE_MILLIS,
        )

        private const val AUDIT_REPLAY_GRACE_MILLIS = 5_000L
        private const val SELECTED_EVENT_ID = "selected_audit_event_id"
    }
}
