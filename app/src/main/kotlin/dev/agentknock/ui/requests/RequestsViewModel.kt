package dev.agentknock.ui.requests

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentKnockApplication
import dev.agentknock.storage.request.CredentialDecisionResult
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.PollInboxResult
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
    private val _polling = MutableStateFlow(false)
    private val _lastPollResult = MutableStateFlow<PollInboxResult?>(null)
    private var pollingJob: Job? = null

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
    val polling: StateFlow<Boolean> = _polling.asStateFlow()
    val lastPollResult: StateFlow<PollInboxResult?> = _lastPollResult.asStateFlow()

    fun startPolling() {
        if (pollingJob != null) return
        pollingJob = viewModelScope.launch {
            container.localStorage.await()
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun selectRequest(id: Long?) {
        selectedRequestId.value = id
    }

    fun refresh() {
        viewModelScope.launch { pollOnce() }
    }

    suspend fun chooseSas(requestId: Long, selectedIndex: Int?): PairingDecisionResult {
        val result = repository.chooseSas(requestId, selectedIndex)
        pollOnce()
        return result
    }

    suspend fun rejectPairing(requestId: Long): PairingDecisionResult {
        val result = repository.rejectPairing(requestId)
        pollOnce()
        return result
    }

    suspend fun approveCredentialRequest(requestId: Long): CredentialDecisionResult {
        val result = repository.approveCredentialRequest(requestId)
        if (result == CredentialDecisionResult.Decided) pollOnce()
        return result
    }

    suspend fun denyCredentialRequest(requestId: Long): CredentialDecisionResult {
        val result = repository.denyCredentialRequest(requestId)
        if (result == CredentialDecisionResult.Decided) pollOnce()
        return result
    }

    private suspend fun pollOnce() = refreshMutex.withLock {
        _polling.value = true
        try {
            _lastPollResult.value = repository.poll()
        } finally {
            _polling.value = false
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 3_000L
    }
}
