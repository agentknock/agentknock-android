package dev.agentknock.ui.requests

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestStatus
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.InvocationDecisionResult
import dev.agentknock.storage.request.GitSignDecisionResult
import dev.agentknock.storage.request.SshAuthenticationDecisionResult
import dev.agentknock.storage.request.RequestConnectionManager
import dev.agentknock.storage.request.RequestInbox
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.ui.requestHistory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

internal enum class RequestDecisionAction {
    APPROVE,
    DENY,
    ALLOW_TEMPORARILY,
}

private fun InvocationDecisionResult.message(): String = when (this) {
    InvocationDecisionResult.Decided -> "Decision saved"
    InvocationDecisionResult.SecretsChanged ->
        "A requested secret changed; review the request again"
    InvocationDecisionResult.NotPending -> "This request no longer needs a decision"
    InvocationDecisionResult.NotFound -> "Request is no longer available"
    is InvocationDecisionResult.MissingSecrets -> "Missing secrets: ${names.joinToString()}"
    is InvocationDecisionResult.ConflictingVariable ->
        "Conflicting environment variable: $name"
    is InvocationDecisionResult.Invalid -> message
    InvocationDecisionResult.SecretUnavailable ->
        "A secret value is unavailable on this device"
    InvocationDecisionResult.SecretCorrupted -> "A secret value could not be authenticated"
    InvocationDecisionResult.UnsupportedEncryption ->
        "A secret value uses unsupported encryption"
    InvocationDecisionResult.PairingUnavailable -> "The paired client is unavailable"
    InvocationDecisionResult.TemporaryAccessUnavailable ->
        "Temporary access is no longer available"
    InvocationDecisionResult.TemporaryAccessNotStarted ->
        "Request approved once, but temporary access could not be started"
}

private fun GitSignDecisionResult.message(): String = when (this) {
    GitSignDecisionResult.Decided -> "Decision saved"
    GitSignDecisionResult.NotPending ->
        "This Git signing request no longer needs a decision"
    GitSignDecisionResult.NotFound -> "Git signing request is no longer available"
    GitSignDecisionResult.InvocationUnavailable -> "The original command request is unavailable"
    GitSignDecisionResult.PairingUnavailable -> "The paired client is unavailable"
    GitSignDecisionResult.ApprovalChanged ->
        "The SSH key or approval setting changed; review the request again"
    GitSignDecisionResult.KeyChanged ->
        "The SSH key changed or was renamed after the command began; start the command again"
    GitSignDecisionResult.SecretUnavailable ->
        "The SSH private key is unavailable on this device"
    GitSignDecisionResult.SecretCorrupted ->
        "The SSH private key could not be authenticated"
    GitSignDecisionResult.UnsupportedEncryption ->
        "The SSH private key uses unsupported encryption"
    GitSignDecisionResult.TemporaryAccessUnavailable ->
        "Temporary access is no longer available"
    GitSignDecisionResult.TemporaryAccessNotStarted ->
        "Signature approved once, but temporary access could not be started"
}

private fun SshAuthenticationDecisionResult.message(): String = when (this) {
    SshAuthenticationDecisionResult.Decided -> "Decision saved"
    SshAuthenticationDecisionResult.NotPending ->
        "This SSH authentication request no longer needs a decision"
    SshAuthenticationDecisionResult.NotFound ->
        "SSH authentication request is no longer available"
    SshAuthenticationDecisionResult.InvocationUnavailable ->
        "The original command request is unavailable"
    SshAuthenticationDecisionResult.PairingUnavailable ->
        "The paired client is unavailable"
    SshAuthenticationDecisionResult.ApprovalChanged ->
        "The SSH key or approval setting changed; review the request again"
    SshAuthenticationDecisionResult.KeyChanged ->
        "The SSH key changed or was renamed after the command began; start the command again"
    SshAuthenticationDecisionResult.InvalidMessage ->
        "The SSH authentication data changed or is invalid; start the command again"
    SshAuthenticationDecisionResult.SecretUnavailable ->
        "The SSH private key is unavailable on this device"
    SshAuthenticationDecisionResult.SecretCorrupted ->
        "The SSH private key could not be authenticated"
    SshAuthenticationDecisionResult.UnsupportedEncryption ->
        "The SSH private key uses unsupported encryption"
    SshAuthenticationDecisionResult.TemporaryAccessUnavailable ->
        "Temporary access is no longer available"
    SshAuthenticationDecisionResult.TemporaryAccessNotStarted ->
        "Authentication approved once, but temporary access could not be started"
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
    private val repository: RequestRepository,
    private val inbox: RequestInbox,
    requestSummaries: StateFlow<List<InboxRequestSummary>>,
    private val connection: RequestConnectionManager,
    private val awaitStorageReady: suspend () -> Unit,
) : ViewModel() {
    private val selectedRequestId = savedStateHandle.getMutableStateFlow<String?>(
        SELECTED_REQUEST_ID,
        null,
    )
    private val messageEvents = Channel<String>(Channel.BUFFERED)

    val messages: Flow<String> = messageEvents.receiveAsFlow()
    val requests: StateFlow<List<InboxRequestSummary>> = requestSummaries
        .map(List<InboxRequestSummary>::requestHistory)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(READ_MODEL_STOP_TIMEOUT_MILLIS),
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

    fun decideRequest(request: InboxRequestSummary, action: RequestDecisionAction) {
        val approval = request.status as? InboxRequestStatus.Approval ?: return
        if (
            !request.userDecisionAvailable ||
            approval.state != ApprovalRequestState.APPROVAL_PENDING
        ) {
            return
        }
        decideRequest(request.id, request.kind, action)
    }

    fun decideRequest(request: InboxRequestDetails, action: RequestDecisionAction) {
        if (!request.userDecisionAvailable) return
        val kind = when (request.content) {
            is InboxRequestContent.SecretUse -> InboxRequestKind.SECRET_USE
            is InboxRequestContent.GitSign -> InboxRequestKind.GIT_SIGN
            is InboxRequestContent.SshAuthentication -> InboxRequestKind.SSH_AUTHENTICATE
            else -> return
        }
        decideRequest(request.id, kind, action)
    }

    private fun decideRequest(
        requestId: String,
        kind: InboxRequestKind,
        action: RequestDecisionAction,
    ) {
        if (
            kind != InboxRequestKind.SECRET_USE &&
            kind != InboxRequestKind.GIT_SIGN &&
            kind != InboxRequestKind.SSH_AUTHENTICATE
        ) {
            return
        }
        viewModelScope.launch {
            awaitStorageReady()
            val message = when (kind) {
                InboxRequestKind.SECRET_USE -> when (action) {
                    RequestDecisionAction.APPROVE ->
                        repository.approveSecretUseRequest(requestId).message()
                    RequestDecisionAction.DENY ->
                        repository.denySecretUseRequest(requestId).message()
                    RequestDecisionAction.ALLOW_TEMPORARILY ->
                        repository.allowSecretUseTemporarily(requestId).message()
                }
                InboxRequestKind.GIT_SIGN -> when (action) {
                    RequestDecisionAction.APPROVE ->
                        repository.approveGitSignRequest(requestId).message()
                    RequestDecisionAction.DENY ->
                        repository.denyGitSignRequest(requestId).message()
                    RequestDecisionAction.ALLOW_TEMPORARILY ->
                        repository.allowGitSignTemporarily(requestId).message()
                }
                InboxRequestKind.SSH_AUTHENTICATE -> when (action) {
                    RequestDecisionAction.APPROVE ->
                        repository.approveSshAuthenticationRequest(requestId).message()
                    RequestDecisionAction.DENY ->
                        repository.denySshAuthenticationRequest(requestId).message()
                    RequestDecisionAction.ALLOW_TEMPORARILY ->
                        repository.allowSshAuthenticationTemporarily(requestId).message()
                }
            }
            messageEvents.send(message)
        }
    }

    private companion object {
        const val SELECTED_REQUEST_ID = "selected_request_id"
        const val READ_MODEL_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
