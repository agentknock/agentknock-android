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
import dev.agentknock.storage.request.SshAuthenticationDecisionResult
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
    private val selectedRequestId = MutableStateFlow<String?>(null)

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
    val selection: StateFlow<String?> = selectedRequestId.asStateFlow()
    val selectedRequest: StateFlow<InboxRequestDetails?> = selectedRequestId
        .flatMapLatest { id -> id?.let(repository::observeRequest) ?: flowOf(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
    val syncing: StateFlow<Boolean> = connection.syncing
    val lastSyncResult: StateFlow<RequestSyncResult?> = connection.lastSyncResult
    fun selectRequest(id: String?) {
        selectedRequestId.value = id
    }

    fun refresh() {
        connection.refresh()
    }

    suspend fun approveSecretUseRequest(requestId: String): SecretUseDecisionResult {
        container.localStorage.await()
        return repository.approveSecretUseRequest(requestId)
    }

    suspend fun denySecretUseRequest(requestId: String): SecretUseDecisionResult {
        container.localStorage.await()
        return repository.denySecretUseRequest(requestId)
    }

    suspend fun allowSecretUseTemporarily(requestId: String): SecretUseDecisionResult {
        container.localStorage.await()
        return repository.allowSecretUseTemporarily(requestId)
    }

    suspend fun approveGitSignRequest(requestId: String): GitSignDecisionResult {
        container.localStorage.await()
        return repository.approveGitSignRequest(requestId)
    }

    suspend fun denyGitSignRequest(requestId: String): GitSignDecisionResult {
        container.localStorage.await()
        return repository.denyGitSignRequest(requestId)
    }

    suspend fun allowGitSignTemporarily(requestId: String): GitSignDecisionResult {
        container.localStorage.await()
        return repository.allowGitSignTemporarily(requestId)
    }

    suspend fun approveSshAuthenticationRequest(
        requestId: String,
    ): SshAuthenticationDecisionResult {
        container.localStorage.await()
        return repository.approveSshAuthenticationRequest(requestId)
    }

    suspend fun denySshAuthenticationRequest(
        requestId: String,
    ): SshAuthenticationDecisionResult {
        container.localStorage.await()
        return repository.denySshAuthenticationRequest(requestId)
    }

    suspend fun allowSshAuthenticationTemporarily(
        requestId: String,
    ): SshAuthenticationDecisionResult {
        container.localStorage.await()
        return repository.allowSshAuthenticationTemporarily(requestId)
    }

}
