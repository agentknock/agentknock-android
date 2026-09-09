package dev.agentknock.ui.requests

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockActions
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.RequestConnectionManager
import dev.agentknock.storage.request.RequestDecision
import dev.agentknock.storage.request.RequestDecisionResult
import dev.agentknock.storage.request.RequestInbox
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.ui.requestHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private fun RequestDecisionResult.message(decision: RequestDecision): String =
    when (this) {
        RequestDecisionResult.Decided ->
            when (decision) {
                RequestDecision.APPROVE -> "Approved once"
                RequestDecision.DENY -> "Denied"
                RequestDecision.ALLOW_TEMPORARILY -> "Temporary access allowed"
            }
        RequestDecisionResult.NotPending -> "This request no longer needs a decision"
        RequestDecisionResult.NotFound -> "Request is no longer available"
        RequestDecisionResult.ParentUnavailable -> "The original command request is unavailable"
        RequestDecisionResult.ClientUnavailable -> "The paired client is unavailable"
        RequestDecisionResult.DeniedByCurrentPolicy ->
            "Request denied by its current approval setting"
        RequestDecisionResult.ApprovalChanged ->
            "The secret or approval setting changed; review the request again"
        RequestDecisionResult.SecretChanged ->
            "A requested secret changed; review the request again"
        RequestDecisionResult.SecretChangedSinceInvocation ->
            "The SSH key changed or was renamed after the command began; start the command again"
        is RequestDecisionResult.MissingSecrets -> "Missing secrets: ${names.joinToString()}"
        is RequestDecisionResult.ConflictingVariable -> "Conflicting environment variable: $name"
        is RequestDecisionResult.Invalid -> message
        RequestDecisionResult.SecretUnavailable -> "A secret value is unavailable on this device"
        RequestDecisionResult.SecretCorrupted -> "A secret value could not be authenticated"
        RequestDecisionResult.UnsupportedEncryption -> "A secret value uses unsupported encryption"
        RequestDecisionResult.TemporaryAccessUnavailable ->
            "Temporary access is no longer available"
        RequestDecisionResult.TemporaryAccessNotStarted ->
            "Request approved once, but temporary access could not be started"
    }

internal sealed interface RequestPaneState {
    val requestId: String?

    data object Empty : RequestPaneState {
        override val requestId: String? = null
    }

    data class Loading(override val requestId: String) : RequestPaneState

    data class Ready(
        override val requestId: String,
        val request: InboxRequestDetails,
    ) : RequestPaneState

    data class Missing(override val requestId: String) : RequestPaneState
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class RequestsViewModel(
    savedStateHandle: SavedStateHandle,
    private val actions: AgentknockActions,
    private val inbox: RequestInbox,
    requestSummaries: StateFlow<List<InboxRequestSummary>>,
    private val connection: RequestConnectionManager,
) : ViewModel() {
    private val selectedRequestId =
        savedStateHandle.getMutableStateFlow<String?>(
            SELECTED_REQUEST_ID,
            null,
        )
    private val messageEvents = Channel<String>(Channel.BUFFERED)

    val messages: Flow<String> = messageEvents.receiveAsFlow()
    val requests: StateFlow<List<InboxRequestSummary>> =
        requestSummaries
            .map(List<InboxRequestSummary>::requestHistory)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(READ_MODEL_STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )
    val selection: StateFlow<RequestPaneState> =
        selectedRequestId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(RequestPaneState.Empty)
                } else {
                    inbox
                        .observeRequest(id)
                        .map { request ->
                            request?.let { RequestPaneState.Ready(id, it) }
                                ?: RequestPaneState.Missing(id)
                        }
                        .onStart { emit(RequestPaneState.Loading(id)) }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = RequestPaneState.Empty,
            )
    val syncing: StateFlow<Boolean> = connection.syncing
    val lastSyncResult: StateFlow<RequestSyncResult?> = connection.lastSyncResult

    fun selectRequest(id: String?) {
        selectedRequestId.value = id
    }

    fun refresh() {
        connection.refresh()
    }

    fun decideRequest(
        requestId: String,
        decision: RequestDecision,
    ) {
        viewModelScope.launch {
            messageEvents.send(actions.decideRequest(requestId, decision).message(decision))
        }
    }

    private companion object {
        const val SELECTED_REQUEST_ID = "selected_request_id"
        const val READ_MODEL_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
