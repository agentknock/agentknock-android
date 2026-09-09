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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal sealed interface AuditHistoryState {
    data object Loading : AuditHistoryState

    data class Loaded(val events: List<AuditEvent>) : AuditHistoryState
}

internal data class AuditDetailResult(val eventId: Long, val event: AuditEvent?)

@OptIn(ExperimentalCoroutinesApi::class)
internal class AuditViewModel(
    audit: AuditRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _selectedEventId =
        savedStateHandle.getMutableStateFlow<Long?>(
            SELECTED_EVENT_ID,
            null,
        )

    val history: StateFlow<AuditHistoryState> =
        audit
            .observeEvents()
            .map<List<AuditEvent>, AuditHistoryState> { events -> AuditHistoryState.Loaded(events) }
            .stateIn(viewModelScope, whileSubscribed, AuditHistoryState.Loading)
    val selectedEventId: StateFlow<Long?> = _selectedEventId.asStateFlow()
    val detail: StateFlow<AuditDetailResult?> =
        _selectedEventId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(null)
                } else {
                    audit.observeEvent(id).map { event -> AuditDetailResult(id, event) }
                }
            }
            .stateIn(viewModelScope, whileSubscribed, null)

    fun selectEvent(id: Long?) {
        _selectedEventId.value = id
    }

    private companion object {
        // The retained replay avoids loading flicker without keeping Room observed off-page.
        val whileSubscribed =
            SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 0,
                replayExpirationMillis = AUDIT_REPLAY_GRACE_MILLIS,
            )

        private const val AUDIT_REPLAY_GRACE_MILLIS = 5_000L
        private const val SELECTED_EVENT_ID = "selected_audit_event_id"
    }
}
