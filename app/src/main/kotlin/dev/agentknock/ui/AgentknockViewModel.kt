package dev.agentknock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.storage.device.DeviceConfiguration
import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.RequestInbox
import dev.agentknock.ui.auth.DeviceAuthenticationResult
import dev.agentknock.ui.auth.ProtectedActionAuthorizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Read models and lookups owned by the application shell rather than any one section. */
internal class AgentknockViewModel(
    val configuration: StateFlow<DeviceConfiguration?>,
    val requests: StateFlow<List<InboxRequestSummary>>,
    private val inbox: RequestInbox,
    private val protectedActions: ProtectedActionAuthorizer,
) : ViewModel() {
    private val _unlockError = MutableStateFlow<String?>(null)

    val unlockError: StateFlow<String?> = _unlockError.asStateFlow()

    suspend fun findRequestKind(requestId: String): InboxRequestKind? =
        inbox.findRequestKind(requestId)

    fun unlock() {
        if (protectedActionsInFlight) return
        protectedActionsInFlight = true
        _unlockError.value = null
        viewModelScope.launch {
            try {
                val result = protectedActions.authorize("Unlock Agentknock")
                if (result is DeviceAuthenticationResult.Error) {
                    _unlockError.value = result.message
                }
            } finally {
                protectedActionsInFlight = false
            }
        }
    }

    private var protectedActionsInFlight = false
}
