package dev.agentknock.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.storage.request.InvocationDecisionResult
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.request.RequestConnectionManager
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.RequestInbox
import dev.agentknock.storage.request.GitSignDecisionResult
import dev.agentknock.storage.request.SshAuthenticationDecisionResult
import dev.agentknock.ui.requestHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

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
    private val repository: RequestRepository,
    private val inbox: RequestInbox,
    private val connection: RequestConnectionManager,
    private val awaitStorageReady: suspend () -> Unit,
) : ViewModel() {
    private val selectedRequestId = MutableStateFlow<String?>(null)

    val allRequests: StateFlow<List<InboxRequestSummary>> = inbox.observeRequests().stateIn(
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
    val selection: StateFlow<RequestPaneState> = selectedRequestId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(RequestPaneState.Empty)
            } else {
                inbox.observeRequest(id)
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

    suspend fun approveSecretUseRequest(requestId: String): InvocationDecisionResult {
        awaitStorageReady()
        return repository.approveSecretUseRequest(requestId)
    }

    suspend fun denySecretUseRequest(requestId: String): InvocationDecisionResult {
        awaitStorageReady()
        return repository.denySecretUseRequest(requestId)
    }

    suspend fun allowSecretUseTemporarily(requestId: String): InvocationDecisionResult {
        awaitStorageReady()
        return repository.allowSecretUseTemporarily(requestId)
    }

    suspend fun approveGitSignRequest(requestId: String): GitSignDecisionResult {
        awaitStorageReady()
        return repository.approveGitSignRequest(requestId)
    }

    suspend fun denyGitSignRequest(requestId: String): GitSignDecisionResult {
        awaitStorageReady()
        return repository.denyGitSignRequest(requestId)
    }

    suspend fun allowGitSignTemporarily(requestId: String): GitSignDecisionResult {
        awaitStorageReady()
        return repository.allowGitSignTemporarily(requestId)
    }

    suspend fun approveSshAuthenticationRequest(
        requestId: String,
    ): SshAuthenticationDecisionResult {
        awaitStorageReady()
        return repository.approveSshAuthenticationRequest(requestId)
    }

    suspend fun denySshAuthenticationRequest(
        requestId: String,
    ): SshAuthenticationDecisionResult {
        awaitStorageReady()
        return repository.denySshAuthenticationRequest(requestId)
    }

    suspend fun allowSshAuthenticationTemporarily(
        requestId: String,
    ): SshAuthenticationDecisionResult {
        awaitStorageReady()
        return repository.allowSshAuthenticationTemporarily(requestId)
    }

}
