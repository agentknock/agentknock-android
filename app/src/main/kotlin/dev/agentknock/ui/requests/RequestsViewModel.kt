package dev.agentknock.ui.requests

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentKnockApplication
import dev.agentknock.storage.request.CredentialDecisionResult
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.ProfileUploadDecisionResult
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.vault.VaultConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
internal class RequestsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentKnockApplication).container
    private val repository = container.requests
    private val connection = container.requestConnection
    private val selectedRequestId = MutableStateFlow<Long?>(null)

    val requests: StateFlow<List<InboxRequestSummary>> = repository.observeRequests().stateIn(
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
    val configuration: StateFlow<VaultConfiguration?> = container.vault.observeConfiguration()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun selectRequest(id: Long?) {
        selectedRequestId.value = id
    }

    fun refresh() {
        connection.refresh()
    }

    suspend fun chooseSas(requestId: Long, selectedIndex: Int?): PairingDecisionResult {
        container.localStorage.await()
        return repository.chooseSas(requestId, selectedIndex)
    }

    suspend fun rejectPairing(requestId: Long): PairingDecisionResult {
        container.localStorage.await()
        return repository.rejectPairing(requestId)
    }

    suspend fun approveCredentialRequest(requestId: Long): CredentialDecisionResult {
        container.localStorage.await()
        return repository.approveCredentialRequest(requestId)
    }

    suspend fun denyCredentialRequest(requestId: Long): CredentialDecisionResult {
        container.localStorage.await()
        return repository.denyCredentialRequest(requestId)
    }

    suspend fun acceptProfileUpload(
        requestId: Long,
        acceptedName: String,
    ): ProfileUploadDecisionResult {
        container.localStorage.await()
        return repository.acceptProfileUpload(requestId, acceptedName)
    }

    suspend fun rejectProfileUpload(requestId: Long): ProfileUploadDecisionResult {
        container.localStorage.await()
        return repository.rejectProfileUpload(requestId)
    }
}
