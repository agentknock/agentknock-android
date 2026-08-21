package dev.agentknock.ui.requests

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockApplication
import dev.agentknock.storage.request.SecretUseDecisionResult
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.SecretUploadDecisionResult
import dev.agentknock.storage.request.SecretUploadVariableValue
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.vault.DeviceConfiguration
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
    private val container = (application as AgentknockApplication).container
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
    val configuration: StateFlow<DeviceConfiguration?> = container.vault.observeConfiguration()
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

    suspend fun approveSecretUseRequest(requestId: Long): SecretUseDecisionResult {
        container.localStorage.await()
        return repository.approveSecretUseRequest(requestId)
    }

    suspend fun denySecretUseRequest(requestId: Long): SecretUseDecisionResult {
        container.localStorage.await()
        return repository.denySecretUseRequest(requestId)
    }

    suspend fun approveSecretUpload(
        requestId: Long,
        approvedName: String,
    ): SecretUploadDecisionResult {
        container.localStorage.await()
        return repository.approveSecretUpload(requestId, approvedName)
    }

    suspend fun rejectSecretUpload(requestId: Long): SecretUploadDecisionResult {
        container.localStorage.await()
        return repository.rejectSecretUpload(requestId)
    }

    suspend fun readSecretUploadVariable(
        requestId: Long,
        variableId: String,
    ): SecretUploadVariableValue {
        container.localStorage.await()
        return repository.readSecretUploadVariable(requestId, variableId)
    }

    suspend fun setSecretUploadVariableSensitivity(
        requestId: Long,
        variableId: String,
        sensitive: Boolean,
    ): Boolean {
        container.localStorage.await()
        return repository.setSecretUploadVariableSensitivity(
            requestId,
            variableId,
            sensitive,
        )
    }
}
