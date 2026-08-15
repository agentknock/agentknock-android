package dev.agentknock.ui.requests

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentKnockApplication
import dev.agentknock.storage.request.CredentialDecisionResult
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.RequestSyncResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
internal class RequestsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentKnockApplication).container
    private val repository = container.requests
    private val refreshMutex = Mutex()
    private val selectedRequestId = MutableStateFlow<Long?>(null)
    private val _syncing = MutableStateFlow(false)
    private val _lastSyncResult = MutableStateFlow<RequestSyncResult?>(null)
    private var syncingJob: Job? = null

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
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    val lastSyncResult: StateFlow<RequestSyncResult?> = _lastSyncResult.asStateFlow()

    fun startSyncing() {
        if (syncingJob != null) return
        syncingJob = viewModelScope.launch {
            container.localStorage.await()
            while (isActive) {
                syncOnce()
                delay(SYNC_INTERVAL_MILLIS)
            }
        }
    }

    fun stopSyncing() {
        syncingJob?.cancel()
        syncingJob = null
    }

    fun selectRequest(id: Long?) {
        selectedRequestId.value = id
    }

    fun refresh() {
        viewModelScope.launch { syncOnce() }
    }

    suspend fun chooseSas(requestId: Long, selectedIndex: Int?): PairingDecisionResult {
        val result = repository.chooseSas(requestId, selectedIndex)
        syncOnce()
        return result
    }

    suspend fun rejectPairing(requestId: Long): PairingDecisionResult {
        val result = repository.rejectPairing(requestId)
        syncOnce()
        return result
    }

    suspend fun approveCredentialRequest(requestId: Long): CredentialDecisionResult {
        val result = repository.approveCredentialRequest(requestId)
        if (result == CredentialDecisionResult.Decided) syncOnce()
        return result
    }

    suspend fun denyCredentialRequest(requestId: Long): CredentialDecisionResult {
        val result = repository.denyCredentialRequest(requestId)
        if (result == CredentialDecisionResult.Decided) syncOnce()
        return result
    }

    private suspend fun syncOnce() = refreshMutex.withLock {
        _syncing.value = true
        try {
            _lastSyncResult.value = repository.sync()
        } finally {
            _syncing.value = false
        }
    }

    private companion object {
        const val SYNC_INTERVAL_MILLIS = 3_000L
    }
}
