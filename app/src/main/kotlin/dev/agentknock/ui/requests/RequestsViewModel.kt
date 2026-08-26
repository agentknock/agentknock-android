package dev.agentknock.ui.requests

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockApplication
import dev.agentknock.storage.request.SecretUseDecisionResult
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.request.GitSignDecisionResult
import dev.agentknock.ui.requestHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
internal class RequestsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentknockApplication).container
    private val repository = container.requests
    private val connection = container.requestConnection
    private val selectedRequestId = MutableStateFlow<Long?>(null)

    val allRequests: StateFlow<List<InboxRequestSummary>> = repository.observeRequests().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )
    val requests: StateFlow<List<InboxRequestSummary>> = allRequests
        .map(List<InboxRequestSummary>::requestHistory)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )
    val selection: StateFlow<Long?> = selectedRequestId.asStateFlow()
    val selectedRequest: StateFlow<InboxRequestDetails?> = selectedRequestId
        .flatMapLatest { id -> id?.let(repository::observeRequest) ?: flowOf(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
    val syncing: StateFlow<Boolean> = connection.syncing
    val lastSyncResult: StateFlow<RequestSyncResult?> = connection.lastSyncResult
    fun selectRequest(id: Long?) {
        selectedRequestId.value = id
    }

    fun refresh() {
        connection.refresh()
    }

    suspend fun approveSecretUseRequest(requestId: Long): SecretUseDecisionResult {
        container.localStorage.await()
        return repository.approveSecretUseRequest(requestId)
    }

    suspend fun denySecretUseRequest(requestId: Long): SecretUseDecisionResult {
        container.localStorage.await()
        return repository.denySecretUseRequest(requestId)
    }

    suspend fun approveGitSignRequest(requestId: Long): GitSignDecisionResult {
        container.localStorage.await()
        return repository.approveGitSignRequest(requestId)
    }

    suspend fun denyGitSignRequest(requestId: Long): GitSignDecisionResult {
        container.localStorage.await()
        return repository.denyGitSignRequest(requestId)
    }

}
