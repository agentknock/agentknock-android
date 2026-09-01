package dev.agentknock.storage.request

import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.protocol.InvocationProtocol
import dev.agentknock.protocol.GitSignProtocol
import dev.agentknock.protocol.OpenedPairedRequest
import dev.agentknock.protocol.PairedRequestErrorCode
import dev.agentknock.protocol.PairedRequestProtocol
import dev.agentknock.protocol.PairingRemoveProtocol
import dev.agentknock.protocol.SecretListProtocol
import dev.agentknock.protocol.SecretUploadProtocol
import dev.agentknock.protocol.SshAuthenticationProtocol
import dev.agentknock.protocol.isFreshRelayRequestId
import dev.agentknock.relay.RelayClientState
import dev.agentknock.relay.RelayDeviceClient
import dev.agentknock.relay.RelayDeviceConnection
import dev.agentknock.relay.RelayDeviceConnectionResult
import dev.agentknock.relay.RelayDeviceErrorScope
import dev.agentknock.relay.RelayDeviceEvent
import dev.agentknock.relay.RelayDeviceFrame
import dev.agentknock.relay.RelayExchangeState
import dev.agentknock.relay.RelayMessageKind
import dev.agentknock.relay.RelayMessageState
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.device.RelayDeviceCredentialsResult
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.WriteTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException

internal suspend fun persistRejectedRequest(persist: suspend () -> Unit): Boolean = try {
    persist()
    true
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

private data class IncomingRelayMessage(
    val clientId: String,
    val requestId: String,
    val request: JsonElement? = null,
    val completion: JsonElement? = null,
    val addressId: String? = null,
)

internal data class ProcessedRelayMessage(val response: JsonElement? = null)

internal inline fun <T> decodeWireCompletionOrNull(decode: () -> T): T? = try {
    decode()
} catch (_: SerializationException) {
    null
}

internal sealed interface RequestSyncResult {
    data object Success : RequestSyncResult

    data object NoDevice : RequestSyncResult

    data object DeviceCredentialsUnavailable : RequestSyncResult

    data object DeviceCredentialsCorrupted : RequestSyncResult

    data object UnsupportedDeviceCredentialEncryption : RequestSyncResult

    data class RelayRejected(val status: Int, val message: String?) : RequestSyncResult

    data class RelayUnavailable(
        val message: String?,
        val retryAfterMillis: Long? = null,
    ) : RequestSyncResult {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0)
        }
    }

    data class InternalFailure(val type: String) : RequestSyncResult
}

private sealed interface SynchronizationInput {
    data class Event(val event: RelayDeviceEvent) : SynchronizationInput

    data object PendingChanges : SynchronizationInput
}

internal enum class PairingDecisionResult {
    VERIFIED,
    REJECTED,
    NOT_PENDING,
    NOT_FOUND,
}

internal sealed interface SshAuthenticationDecisionResult {
    data object Decided : SshAuthenticationDecisionResult
    data object NotPending : SshAuthenticationDecisionResult
    data object NotFound : SshAuthenticationDecisionResult
    data object InvocationUnavailable : SshAuthenticationDecisionResult
    data object PairingUnavailable : SshAuthenticationDecisionResult
    data object ApprovalChanged : SshAuthenticationDecisionResult
    data object KeyChanged : SshAuthenticationDecisionResult
    data object InvalidMessage : SshAuthenticationDecisionResult
    data object SecretUnavailable : SshAuthenticationDecisionResult
    data object SecretCorrupted : SshAuthenticationDecisionResult
    data object UnsupportedEncryption : SshAuthenticationDecisionResult
    data object TemporaryAccessUnavailable : SshAuthenticationDecisionResult
    data object TemporaryAccessNotStarted : SshAuthenticationDecisionResult
}

internal class RequestRepository(
    private val dao: RequestDao,
    private val material: RequestMaterialStore,
    private val deviceCredentials: RelayDeviceCredentialSource,
    private val clients: ClientRepository,
    private val secretManagement: SecretManagementRequests,
    private val invocationRequests: InvocationRequests,
    private val gitSigningRequests: GitSigningRequests,
    private val sshAuthenticationRequests: SshAuthenticationRequests,
    private val pairingRequests: PairingRequests,
    private val clientRemovalRequests: ClientRemovalRequests,
    private val relay: RelayDeviceClient,
    private val aiReviews: AiReviewCoordinator,
    private val scheduleSynchronization: () -> Unit,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val updatePushRegistrationState: (RelayPushRegistrationState) -> Unit,
    private val pairedRequestProtocol: PairedRequestProtocol = PairedRequestProtocol(),
    private val json: Json = Json,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val operationMutex = Mutex()
    private val pendingChanges = Channel<Unit>(Channel.CONFLATED)
    fun observeClients(): Flow<List<ClientSummary>> = clients.observeClients()

    fun observeClient(clientId: String): Flow<ClientDetails?> = clients.observeClient(clientId)

    suspend fun recoverInterruptedAiReviews(): Int = operationMutex.withLock {
        dao.recoverInterruptedAiReviews()
    }

    suspend fun sync(): RequestSyncResult = runConnection(keepConnected = false)

    suspend fun listen(
        onCaughtUp: suspend () -> Unit,
    ): RequestSyncResult = runConnection(
        keepConnected = true,
        onCaughtUp = onCaughtUp,
    )

    suspend fun approvePendingRequest(requestId: String) {
        when (dao.getRequestById(requestId)?.kind) {
            RequestKind.SECRET_USE.storedName -> approveSecretUseRequest(requestId)
            RequestKind.GIT_SIGN.storedName -> approveGitSignRequest(requestId)
            RequestKind.SSH_AUTHENTICATE.storedName ->
                approveSshAuthenticationRequest(requestId)
        }
    }

    suspend fun denyPendingRequest(requestId: String) {
        when (dao.getRequestById(requestId)?.kind) {
            RequestKind.SECRET_USE.storedName -> denySecretUseRequest(requestId)
            RequestKind.GIT_SIGN.storedName -> denyGitSignRequest(requestId)
            RequestKind.SSH_AUTHENTICATE.storedName -> denySshAuthenticationRequest(requestId)
        }
    }

    fun requestSync() {
        pendingChanges.trySend(Unit)
        scheduleSynchronization()
    }

    suspend fun hasPendingRelayWork(): Boolean = operationMutex.withLock {
        dao.getUnfinishedResponseOutboxes().isNotEmpty() ||
            dao.getOpenExchanges().isNotEmpty() ||
            dao.getClients().any { client ->
                client.desiredRelayClientState != null &&
                    client.desiredRelayClientState != client.relayClientState
            } ||
            dao.getPairingAttempts().any { attempt ->
                attempt.desiredRelayClientState != null &&
                    attempt.desiredRelayClientState != attempt.relayClientState
            }
    }

    private fun launchAiReview(
        requestId: String,
        requestJson: String,
        review: suspend () -> AiReview,
        complete: suspend (AiReview) -> Unit,
    ): Boolean = aiReviews.launch(requestId) {
        try {
            val result = review()
            operationMutex.withLock {
                val current = dao.getRequestById(requestId)
                if (
                    current?.requestJson == requestJson &&
                    current.state == InboxRequestState.REVIEWING.storedName
                ) {
                    complete(result)
                    dao.recoverInterruptedAiReview(
                        requestId = requestId,
                        requestJson = requestJson,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            operationMutex.withLock {
                dao.recoverInterruptedAiReview(
                    requestId = requestId,
                    requestJson = requestJson,
                )
            }
        } finally {
            requestSync()
        }
    }

    private suspend fun runConnection(
        keepConnected: Boolean,
        onCaughtUp: suspend () -> Unit = {},
    ): RequestSyncResult {
        val credentials = when (val result = deviceCredentials.activeDeviceCredentials()) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            RelayDeviceCredentialsResult.Missing -> return RequestSyncResult.NoDevice
            RelayDeviceCredentialsResult.CredentialsUnavailable -> {
                return RequestSyncResult.DeviceCredentialsUnavailable
            }
            RelayDeviceCredentialsResult.CredentialsCorrupted -> {
                return RequestSyncResult.DeviceCredentialsCorrupted
            }
            RelayDeviceCredentialsResult.UnsupportedEncryption -> {
                return RequestSyncResult.UnsupportedDeviceCredentialEncryption
            }
        }

        val connection = when (
            val result = relay.connect(
                deviceId = credentials.deviceId,
                deviceToken = credentials.deviceToken,
            )
        ) {
            is RelayDeviceConnectionResult.Connected -> result.connection
            is RelayDeviceConnectionResult.Rejected -> {
                return RequestSyncResult.RelayRejected(result.status, result.message)
            }
            is RelayDeviceConnectionResult.Unavailable -> {
                return RequestSyncResult.RelayUnavailable(
                    message = result.message,
                    retryAfterMillis = result.retryAfterMillis,
                )
            }
        }

        return try {
            synchronize(
                credentials = credentials,
                connection = connection,
                keepConnected = keepConnected,
                onCaughtUp = onCaughtUp,
            )
        } finally {
            connection.close()
        }
    }

    private suspend fun synchronize(
        credentials: RelayDeviceCredentials,
        connection: RelayDeviceConnection,
        keepConnected: Boolean,
        onCaughtUp: suspend () -> Unit,
    ): RequestSyncResult {
        val awaitingClientStates = mutableMapOf<String, RelayClientState>()
        val rejectedClientStateMutations = mutableMapOf<String, RelayClientState>()
        val awaitingResponses = mutableMapOf<String, String>()
        val awaitingStates = mutableMapOf<String, String>()
        flushPendingChanges(
            credentials = credentials,
            connection = connection,
            awaitingClientStates = awaitingClientStates,
            rejectedClientStateMutations = rejectedClientStateMutations,
            awaitingResponses = awaitingResponses,
            awaitingStates = awaitingStates,
        )?.let { return it }

        var caughtUp = false
        while (keepConnected || !initialSynchronizationComplete(
                caughtUp,
                awaitingClientStates,
                awaitingResponses,
                awaitingStates,
            )
        ) {
            val input = if (keepConnected) {
                select<SynchronizationInput> {
                    connection.events.onReceiveCatching {
                        SynchronizationInput.Event(
                            it.getOrNull()
                                ?: RelayDeviceEvent.Failed("Relay event stream ended."),
                        )
                    }
                    pendingChanges.onReceive { SynchronizationInput.PendingChanges }
                }
            } else {
                SynchronizationInput.Event(
                    connection.events.receiveCatching().getOrNull()
                        ?: RelayDeviceEvent.Failed("Relay event stream ended."),
                )
            }
            if (input == SynchronizationInput.PendingChanges) {
                flushPendingChanges(
                    credentials = credentials,
                    connection = connection,
                    awaitingClientStates = awaitingClientStates,
                    rejectedClientStateMutations = rejectedClientStateMutations,
                    awaitingResponses = awaitingResponses,
                    awaitingStates = awaitingStates,
                )?.let { return it }
                continue
            }

            val event = (input as SynchronizationInput.Event).event
            if (event == RelayDeviceEvent.CaughtUp) {
                if (!caughtUp) onCaughtUp()
                caughtUp = true
                // An event immediately before CaughtUp can create follow-up work. In
                // particular, a locally rejected pending pairing can race with a late ACTIVE
                // state and must be revoked on this connection rather than waiting for another
                // sync trigger.
                if (pendingChanges.tryReceive().isSuccess) {
                    flushPendingChanges(
                        credentials = credentials,
                        connection = connection,
                        awaitingClientStates = awaitingClientStates,
                        rejectedClientStateMutations = rejectedClientStateMutations,
                        awaitingResponses = awaitingResponses,
                        awaitingStates = awaitingStates,
                    )?.let { return it }
                }
                continue
            }
            val failure = operationMutex.withLock {
                when (event) {
                    is RelayDeviceEvent.Message -> {
                        val update = when (event.kind) {
                            RelayMessageKind.REQUEST -> processRequest(
                                credentials,
                                IncomingRelayMessage(
                                    clientId = event.clientId,
                                    requestId = event.requestId,
                                    request = event.payload,
                                    addressId = event.addressId,
                                ),
                            )
                            RelayMessageKind.COMPLETION -> processCompletion(
                                credentials,
                                IncomingRelayMessage(
                                    clientId = event.clientId,
                                    requestId = event.requestId,
                                    completion = event.payload,
                                    addressId = event.addressId,
                                ),
                            )
                            RelayMessageKind.RESPONSE -> null
                        }
                        val acknowledgedKind = event.kind.takeIf {
                            update != null && (
                                it == RelayMessageKind.REQUEST ||
                                    it == RelayMessageKind.COMPLETION
                            )
                        }
                        if (acknowledgedKind != null) {
                            if (
                                !connection.send(
                                    RelayDeviceFrame.Acknowledgement(
                                        event.clientId,
                                        event.requestId,
                                        acknowledgedKind,
                                    ),
                                )
                            ) {
                                return@withLock RequestSyncResult.RelayUnavailable(
                                    "Could not acknowledge relay message.",
                                )
                            }
                        }
                        if (update?.response != null) {
                            if (
                                !connection.send(
                                    RelayDeviceFrame.Response(
                                        event.clientId,
                                        event.requestId,
                                        update.response,
                                    ),
                                )
                            ) {
                                return@withLock RequestSyncResult.RelayUnavailable(
                                    "Could not send relay response.",
                                )
                            }
                            awaitingResponses[event.requestId] = event.clientId
                        }
                        if (dao.getRequestById(event.requestId)?.exchangeEndedAt != null) {
                            awaitingResponses.remove(event.requestId)
                            awaitingStates.remove(event.requestId)
                        }
                        null
                    }
                    is RelayDeviceEvent.Acknowledgement -> {
                        if (event.kind == RelayMessageKind.RESPONSE) {
                            dao.markResponseOutboxFinished(event.requestId)
                            awaitingResponses.remove(event.requestId)
                        }
                        null
                    }
                    is RelayDeviceEvent.Receipt -> {
                        if (event.kind == RelayMessageKind.RESPONSE) {
                            dao.markResponseOutboxFinished(event.requestId)
                            awaitingResponses.remove(event.requestId)
                        }
                        null
                    }
                    is RelayDeviceEvent.ClientState -> {
                        applyClientState(event)
                        rejectedClientStateMutations.remove(event.clientId)
                        // Every state event is an authoritative answer to the outstanding
                        // mutation. If it differs from the desired state, applyClientState keeps
                        // that desire and queues a new pass; a terminal REVOKED state clears it.
                        if (awaitingClientStates.remove(event.clientId) != null) {
                            sendNextClientState(
                                credentials = credentials,
                                connection = connection,
                                awaitingClientStates = awaitingClientStates,
                                rejectedClientStateMutations = rejectedClientStateMutations,
                            )?.let { return@withLock it }
                        }
                        null
                    }
                    is RelayDeviceEvent.PushRegistration -> {
                        updatePushRegistrationState(event.state)
                        null
                    }
                    is RelayDeviceEvent.State -> {
                        val ended = applyRelayState(event)
                        awaitingStates.remove(event.requestId)
                        if (
                            event.response != RelayMessageState.ABSENT ||
                            event.exchange == RelayExchangeState.CLOSING
                        ) {
                            awaitingResponses.remove(event.requestId)
                        }
                        if (ended) {
                            awaitingResponses.remove(event.requestId)
                            awaitingStates.remove(event.requestId)
                        }
                        null
                    }
                    is RelayDeviceEvent.Inactive -> {
                        awaitingStates.remove(event.requestId)
                        if (event.kind == RelayMessageKind.RESPONSE) {
                            dao.markResponseOutboxFinished(event.requestId)
                            awaitingResponses.remove(event.requestId)
                        } else if (event.kind == null) {
                            endRequestExchange(
                                event.requestId,
                                currentTimeMillis(),
                                "The relay no longer has this exchange.",
                            )
                            awaitingResponses.remove(event.requestId)
                            awaitingStates.remove(event.requestId)
                        }
                        null
                    }
                    is RelayDeviceEvent.Error -> handleRelayError(
                        event = event,
                        credentials = credentials,
                        connection = connection,
                        awaitingClientStates = awaitingClientStates,
                        rejectedClientStateMutations = rejectedClientStateMutations,
                        awaitingResponses = awaitingResponses,
                        awaitingStates = awaitingStates,
                    )
                    is RelayDeviceEvent.Closed -> RequestSyncResult.RelayUnavailable(
                        "Relay connection closed (${event.code}): ${event.reason}",
                    )
                    is RelayDeviceEvent.Failed -> {
                        RequestSyncResult.RelayUnavailable(event.message)
                    }
                    RelayDeviceEvent.CaughtUp -> null
                }
            }
            if (failure != null) return failure
            operationMutex.withLock {
                dao.deleteSettledHiddenRequests(
                    currentTimeMillis() - IDEMPOTENCY_RETENTION_MILLIS,
                )
            }
        }
        return RequestSyncResult.Success
    }

    private suspend fun flushPendingChanges(
        credentials: RelayDeviceCredentials,
        connection: RelayDeviceConnection,
        awaitingClientStates: MutableMap<String, RelayClientState>,
        rejectedClientStateMutations: Map<String, RelayClientState>,
        awaitingResponses: MutableMap<String, String>,
        awaitingStates: MutableMap<String, String>,
    ): RequestSyncResult? {
        while (pendingChanges.tryReceive().isSuccess) {
            // Changes made after this drain remain queued for the next pass.
        }
        return operationMutex.withLock {
            sendNextClientState(
                credentials = credentials,
                connection = connection,
                awaitingClientStates = awaitingClientStates,
                rejectedClientStateMutations = rejectedClientStateMutations,
            )?.let { return@withLock it }

            for (request in dao.getUnfinishedResponseOutboxes()) {
                if (request.id in awaitingResponses) continue
                if (request.deviceIdentityId != credentials.deviceIdentityId) continue
                if (
                    !connection.send(
                        RelayDeviceFrame.Response(
                            clientId = request.clientId,
                            requestId = request.id,
                            payload = json.parseToJsonElement(checkNotNull(request.responseJson)),
                        ),
                    )
                ) {
                    return@withLock RequestSyncResult.RelayUnavailable(
                        "Could not send relay response.",
                    )
                }
                awaitingResponses[request.id] = request.clientId
            }

            for (request in dao.getOpenExchanges()) {
                if (request.id in awaitingStates) continue
                if (request.deviceIdentityId != credentials.deviceIdentityId) continue
                if (
                    !connection.send(
                        RelayDeviceFrame.Resume(request.clientId, request.id),
                    )
                ) {
                    return@withLock RequestSyncResult.RelayUnavailable(
                        "Could not resume relay exchange.",
                    )
                }
                awaitingStates[request.id] = request.clientId
            }
            null
        }
    }

    private suspend fun sendNextClientState(
        credentials: RelayDeviceCredentials,
        connection: RelayDeviceConnection,
        awaitingClientStates: MutableMap<String, RelayClientState>,
        rejectedClientStateMutations: Map<String, RelayClientState>,
    ): RequestSyncResult? {
        if (awaitingClientStates.isNotEmpty()) return null

        for (attempt in dao.getPairingAttempts()) {
            val rootRequest = dao.getRequestById(attempt.requestId) ?: continue
            if (rootRequest.deviceIdentityId != credentials.deviceIdentityId) continue
            if (
                attempt.state == PairingState.REJECTED.storedName &&
                attempt.relayClientState == RelayClientState.PENDING.wireName &&
                attempt.desiredRelayClientState == RelayClientState.REVOKED.wireName
            ) {
                // A pending candidate cannot be revoked yet. Preserve the desired state so a
                // late activation acknowledgement is followed by revocation.
                continue
            }
            val desired = attempt.desiredRelayClientState?.toRelayClientState() ?: continue
            if (attempt.relayClientState == desired.wireName) continue
            if (rejectedClientStateMutations[attempt.clientId] == desired) continue
            if (!connection.send(RelayDeviceFrame.SetClientState(attempt.clientId, desired))) {
                return RequestSyncResult.RelayUnavailable("Could not send relay client state.")
            }
            awaitingClientStates[attempt.clientId] = desired
            return null
        }

        for (client in dao.getClients()) {
            if (client.deviceIdentityId != credentials.deviceIdentityId) continue
            val desired = client.desiredRelayClientState?.toRelayClientState() ?: continue
            if (client.relayClientState == desired.wireName) continue
            if (rejectedClientStateMutations[client.clientId] == desired) continue
            if (!connection.send(RelayDeviceFrame.SetClientState(client.clientId, desired))) {
                return RequestSyncResult.RelayUnavailable("Could not send relay client state.")
            }
            awaitingClientStates[client.clientId] = desired
            return null
        }
        return null
    }

    private suspend fun handleRelayError(
        event: RelayDeviceEvent.Error,
        credentials: RelayDeviceCredentials,
        connection: RelayDeviceConnection,
        awaitingClientStates: MutableMap<String, RelayClientState>,
        rejectedClientStateMutations: MutableMap<String, RelayClientState>,
        awaitingResponses: MutableMap<String, String>,
        awaitingStates: MutableMap<String, String>,
    ): RequestSyncResult? {
        val scope = when (val scope = event.scope) {
            RelayDeviceErrorScope.Unscoped -> {
                if (event.code == "INVALID_CLIENT_STATE" && !event.retryable) {
                    val rejected = awaitingClientStates.entries.singleOrNull()
                        ?: return event.toSynchronizationFailure()
                    awaitingClientStates.remove(rejected.key)
                    rejectedClientStateMutations[rejected.key] = rejected.value
                    return sendNextClientState(
                        credentials = credentials,
                        connection = connection,
                        awaitingClientStates = awaitingClientStates,
                        rejectedClientStateMutations = rejectedClientStateMutations,
                    )
                }
                return event.toSynchronizationFailure()
            }
            is RelayDeviceErrorScope.Exchange -> scope
        }
        val matchesOutstandingOperation = when (scope.kind) {
            RelayMessageKind.RESPONSE -> awaitingResponses[scope.requestId] == scope.clientId
            null -> awaitingStates[scope.requestId] == scope.clientId
            RelayMessageKind.REQUEST,
            RelayMessageKind.COMPLETION,
            -> false
        }
        if (!matchesOutstandingOperation) {
            return RequestSyncResult.RelayRejected(0, event.message)
        }
        if (event.retryable) return event.toSynchronizationFailure()

        when (scope.kind) {
            RelayMessageKind.RESPONSE -> {
                dao.markResponseOutboxFinished(scope.requestId)
                awaitingResponses.remove(scope.requestId)
            }
            null -> {
                endRequestExchange(scope.requestId, currentTimeMillis(), event.message)
                awaitingResponses.remove(scope.requestId)
                awaitingStates.remove(scope.requestId)
            }
            RelayMessageKind.REQUEST,
            RelayMessageKind.COMPLETION,
            -> error("Unsupported scoped relay error was accepted")
        }
        return null
    }

    private fun RelayDeviceEvent.Error.toSynchronizationFailure(): RequestSyncResult =
        if (retryable) {
            RequestSyncResult.RelayUnavailable(
                message = message,
                retryAfterMillis = retryAfterMillis,
            )
        } else {
            RequestSyncResult.RelayRejected(0, message)
        }

    private fun initialSynchronizationComplete(
        caughtUp: Boolean,
        awaitingClientStates: Map<String, RelayClientState>,
        awaitingResponses: Map<String, String>,
        awaitingStates: Map<String, String>,
    ): Boolean = caughtUp &&
        awaitingClientStates.isEmpty() &&
        awaitingResponses.isEmpty() &&
        awaitingStates.isEmpty()

    private suspend fun applyClientState(event: RelayDeviceEvent.ClientState) {
        // Apply the independently retryable durable-client transaction first. If its audit insert
        // fails, no pairing-attempt state is advanced; replaying the relay state can retry both.
        // If the later attempt update fails, replay is also safe because applying an unchanged
        // durable state does not emit another audit event.
        clients.applyRelayState(event.clientId, event.state)
        val client = dao.getClient(event.clientId)
        if (
            client?.desiredRelayClientState != null &&
            client.desiredRelayClientState != client.relayClientState
        ) {
            requestSync()
        }
        if (pairingRequests.applyRelayClientState(event.clientId, event.state)) requestSync()
    }

    private suspend fun applyRelayState(event: RelayDeviceEvent.State): Boolean {
        val now = currentTimeMillis()
        if (event.response == RelayMessageState.ACCEPTED ||
            event.response == RelayMessageState.DELIVERED ||
            event.response == RelayMessageState.DISCARDED
        ) {
            dao.markResponseOutboxFinished(event.requestId)
        }
        return when (event.exchange) {
            RelayExchangeState.OPEN -> false
            RelayExchangeState.CLOSING -> {
                dao.markResponseOutboxFinished(event.requestId)
                false
            }
            RelayExchangeState.SETTLED -> {
                endRequestExchange(
                    event.requestId,
                    now,
                    "The relay exchange ended before its completion could be processed.",
                )
                true
            }
            RelayExchangeState.EXPIRED -> {
                endRequestExchange(
                    event.requestId,
                    now,
                    "The relay exchange expired before it completed.",
                )
                true
            }
        }
    }

    private suspend fun endRequestExchange(
        relayRequestId: String,
        now: Long,
        message: String,
    ) {
        val request = dao.getRequestById(relayRequestId) ?: return
        if (request.exchangeEndedAt != null) return
        when (request.kind) {
            RequestKind.PAIRING.storedName -> {
                pairingRequests.endInitialExchange(request.id, now, message)
            }
            RequestKind.SECRET_USE.storedName -> {
                invocationRequests.expire(request, message, now)
            }
            RequestKind.GIT_SIGN.storedName -> {
                gitSigningRequests.expire(request, message, now)
            }
            RequestKind.SSH_AUTHENTICATE.storedName -> {
                sshAuthenticationRequests.expire(request, message, now)
            }
            RequestKind.SECRET_LIST.storedName -> {
                secretManagement.expireSecretList(request, message, now)
            }
            RequestKind.SECRET_UPLOAD.storedName -> {
                secretManagement.expireSecretUpload(request, message, now)
            }
            RequestKind.PAIRING_REMOVE.storedName -> {
                clientRemovalRequests.expire(request, message, now)
            }
            RequestKind.PAIRING_FINISH.storedName -> {
                pairingRequests.endFinishExchange(request.id, now, message)
            }
            RequestKind.UNKNOWN.storedName -> {
                dao.updateEndedRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        responseOutboxFinished = true,
                        error = request.error ?: message,
                        completedAt = request.completedAt ?: now,
                        exchangeEndedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun chooseSas(requestId: String, selectedIndex: Int?): PairingDecisionResult =
        operationMutex.withLock {
            pairingRequests.chooseSas(requestId, selectedIndex)
        }.also { result ->
            if (
                result == PairingDecisionResult.VERIFIED ||
                result == PairingDecisionResult.REJECTED
            ) {
                requestSync()
            }
        }

    suspend fun isMatchingPendingSas(requestId: String, selectedIndex: Int): Boolean =
        operationMutex.withLock {
            pairingRequests.isMatchingPendingSas(requestId, selectedIndex)
        }

    suspend fun rejectPairing(requestId: String): PairingDecisionResult = operationMutex.withLock {
        pairingRequests.reject(requestId)
    }.also { result ->
        if (result == PairingDecisionResult.REJECTED) requestSync()
    }

    suspend fun approveSecretUseRequest(requestId: String): InvocationDecisionResult =
        decideSecretUseRequest(requestId, allowTemporaryAccess = false)

    suspend fun allowSecretUseTemporarily(requestId: String): InvocationDecisionResult =
        decideSecretUseRequest(requestId, allowTemporaryAccess = true)

    private suspend fun decideSecretUseRequest(
        requestId: String,
        allowTemporaryAccess: Boolean,
    ): InvocationDecisionResult = operationMutex.withLock {
        invocationRequests.approve(
            requestId = requestId,
            allowTemporaryAccess = allowTemporaryAccess,
            openRequest = ::openStoredPairedRequest,
            sealResponse = ::sealInvocationResponse,
        )
    }.also { result ->
        if (
            result == InvocationDecisionResult.Decided ||
            result == InvocationDecisionResult.TemporaryAccessNotStarted
        ) {
            requestSync()
        }
    }

    suspend fun denySecretUseRequest(requestId: String): InvocationDecisionResult =
        operationMutex.withLock {
            invocationRequests.deny(requestId, ::sealInvocationResponse)
        }.also { result ->
            if (result == InvocationDecisionResult.Decided) requestSync()
        }

    private suspend fun sealInvocationResponse(
        request: InboxRequestEntity,
        plaintext: ByteArray,
    ): JsonElement? {
        val client = dao.getClient(request.clientId) ?: return null
        if (
            client.relayClientState != RelayClientState.ACTIVE.wireName ||
            client.desiredRelayClientState?.let { it != RelayClientState.ACTIVE.wireName } == true
        ) {
            return null
        }
        val credentials = credentialsForRequest(request) ?: return null
        val clientPsk = material.decryptRequestPsk(request) ?: return null
        return runCatching {
            pairedRequestProtocol.sealPairedResponse(
                deviceId = credentials.deviceId,
                requestId = request.id,
                clientId = request.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                plaintext = plaintext,
            )
        }.getOrNull()
    }

    private suspend fun openStoredPairedRequest(
        request: InboxRequestEntity,
    ): ByteArray? {
        val credentials = credentialsForRequest(request) ?: return null
        val clientPsk = material.decryptRequestPsk(request) ?: return null
        val opened = runCatching {
            pairedRequestProtocol.openPairedRequest(
                deviceId = credentials.deviceId,
                requestId = request.id,
                clientId = request.clientId,
                clientPsk = clientPsk,
                previousClientPsk = null,
                allowRotation = false,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
            )
        }.getOrNull() ?: return null
        return opened.plaintext
    }

    suspend fun approveGitSignRequest(requestId: String): GitSignDecisionResult =
        decideGitSignRequest(requestId, allowTemporaryAccess = false)

    suspend fun allowGitSignTemporarily(requestId: String): GitSignDecisionResult =
        decideGitSignRequest(requestId, allowTemporaryAccess = true)

    private suspend fun decideGitSignRequest(
        requestId: String,
        allowTemporaryAccess: Boolean,
    ): GitSignDecisionResult = operationMutex.withLock {
        gitSigningRequests.approve(
            requestId = requestId,
            allowTemporaryAccess = allowTemporaryAccess,
            sealResponse = ::sealInvocationResponse,
        )
    }.also { result ->
        if (
            result == GitSignDecisionResult.Decided ||
            result == GitSignDecisionResult.TemporaryAccessNotStarted
        ) {
            requestSync()
        }
    }

    suspend fun denyGitSignRequest(requestId: String): GitSignDecisionResult =
        operationMutex.withLock {
            gitSigningRequests.deny(requestId, ::sealInvocationResponse)
        }.also { result ->
            if (result == GitSignDecisionResult.Decided) requestSync()
        }

    suspend fun approveSshAuthenticationRequest(
        requestId: String,
    ): SshAuthenticationDecisionResult =
        decideSshAuthenticationRequest(requestId, allowTemporaryAccess = false)

    suspend fun allowSshAuthenticationTemporarily(
        requestId: String,
    ): SshAuthenticationDecisionResult =
        decideSshAuthenticationRequest(requestId, allowTemporaryAccess = true)

    private suspend fun decideSshAuthenticationRequest(
        requestId: String,
        allowTemporaryAccess: Boolean,
    ): SshAuthenticationDecisionResult = operationMutex.withLock {
        sshAuthenticationRequests.approve(
            requestId = requestId,
            allowTemporaryAccess = allowTemporaryAccess,
            sealResponse = ::sealInvocationResponse,
        )
    }.also { result ->
        if (
            result == SshAuthenticationDecisionResult.Decided ||
            result == SshAuthenticationDecisionResult.TemporaryAccessNotStarted
        ) {
            requestSync()
        }
    }

    suspend fun denySshAuthenticationRequest(
        requestId: String,
    ): SshAuthenticationDecisionResult = operationMutex.withLock {
        sshAuthenticationRequests.deny(requestId, ::sealInvocationResponse)
    }.also { result ->
        if (result == SshAuthenticationDecisionResult.Decided) requestSync()
    }

    private suspend fun processRequest(
        credentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
    ): ProcessedRelayMessage? {
        val requestPayload = message.request ?: return null
        val existing = dao.getRequestById(message.requestId)
        if (existing != null) {
            if (
                existing.clientId != message.clientId ||
                existing.deviceIdentityId != credentials.deviceIdentityId ||
                (existing.kind == RequestKind.PAIRING.storedName) != (message.addressId != null)
            ) {
                return null
            }
            if (existing.exchangeEndedAt != null) return ProcessedRelayMessage()
            if (existing.requestJson != requestPayload.toString()) return null
            return ProcessedRelayMessage(
                response = existing.responseJson?.let(json::parseToJsonElement),
            )
        }

        return processNewRequest(credentials, message, requestPayload)
    }

    suspend fun approveSecretUpload(
        requestId: String,
        approvedName: String,
    ): SecretUploadDecisionResult = operationMutex.withLock {
        secretManagement.approveSecretUpload(requestId, approvedName)
    }

    suspend fun readSecretUploadVariable(
        requestId: String,
        variableId: String,
    ): SecretUploadVariableValue = operationMutex.withLock {
        secretManagement.readSecretUploadVariable(requestId, variableId)
    }

    suspend fun setSecretUploadVariableSensitivity(
        requestId: String,
        variableId: String,
        sensitive: Boolean,
    ): Boolean = operationMutex.withLock {
        secretManagement.setSecretUploadVariableSensitivity(requestId, variableId, sensitive)
    }

    suspend fun rejectSecretUpload(requestId: String): SecretUploadDecisionResult =
        operationMutex.withLock { secretManagement.rejectSecretUpload(requestId) }

    suspend fun renameClient(clientId: String, name: String): ClientChangeResult =
        operationMutex.withLock { clients.rename(clientId, name) }

    suspend fun saveClientInstructions(
        clientId: String,
        instructions: String,
    ): ClientChangeResult = operationMutex.withLock {
        clients.saveInstructions(clientId, instructions)
    }

    suspend fun setClientState(
        clientId: String,
        state: RelayClientState,
    ): ClientChangeResult {
        val result = operationMutex.withLock {
            clients.setDesiredRelayState(clientId, state)
        }
        if (result == ClientChangeResult.CHANGED) requestSync()
        return result
    }

    private suspend fun processNewRequest(
        credentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
        requestPayload: JsonElement,
    ): ProcessedRelayMessage? {
        val now = currentTimeMillis()
        if (!isFreshRelayRequestId(message.requestId, now)) return null
        if (message.addressId != null) {
            if (!pairingRequests.isInitialRequest(requestPayload)) return null
            return startPairing(credentials, message, requestPayload)
        }

        val client = dao.getClient(message.clientId)
        if (client != null) {
            return processActiveClientRequest(credentials, message, requestPayload, client, now)
        }
        val attempt = dao.getPairingAttempt(message.clientId) ?: return null
        val rootRequest = dao.getRequestById(attempt.requestId) ?: return null
        if (rootRequest.deviceIdentityId != credentials.deviceIdentityId) return null
        if (!attempt.state.toPairingState().acceptsFinishRequest) return null
        val clientPsk = material.decryptPendingPsk(attempt, rootRequest) ?: return null
        val opened = runCatching {
            pairedRequestProtocol.openPairedRequest(
                deviceId = credentials.deviceId,
                requestId = message.requestId,
                clientId = attempt.clientId,
                clientPsk = clientPsk,
                previousClientPsk = null,
                allowRotation = false,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
            )
        }.getOrNull() ?: return null
        val acceptedPsks = try {
            AcceptedRequestPsks(
                requestPsk = material.encryptRequestPsk(
                    deviceIdentityId = rootRequest.deviceIdentityId,
                    clientId = rootRequest.clientId,
                    relayRequestId = message.requestId,
                    clientPsk = opened.clientPsk,
                ),
                currentClientPsk = null,
                previousClientPsk = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val method = runCatching { pairedRequestProtocol.method(opened.plaintext) }.getOrNull()
        if (method == PairedRequestProtocol.FINISH_PAIRING_METHOD) {
            return processFinishRequest(
                credentials = credentials,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                pairing = attempt,
                opened = opened,
                acceptedSecrets = acceptedPsks,
            )
        }
        return rejectPendingPairingRequest(
            attempt = attempt,
            rootRequest = rootRequest,
            relayRequestId = message.requestId,
            requestPayload = requestPayload,
            opened = opened,
            acceptedPsks = acceptedPsks,
            code = if (method == null) {
                PairedRequestErrorCode.INVALID_REQUEST
            } else {
                PairedRequestErrorCode.INVALID_STATE
            },
            now = now,
        )
    }

    private suspend fun processActiveClientRequest(
        credentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
        requestPayload: JsonElement,
        client: ClientEntity,
        now: Long,
    ): ProcessedRelayMessage? {
        if (client.deviceIdentityId != credentials.deviceIdentityId) return null
        if (
            client.relayClientState != RelayClientState.ACTIVE.wireName ||
            client.desiredRelayClientState?.let { it != RelayClientState.ACTIVE.wireName } == true
        ) {
            return null
        }
        val clientPsk = material.decryptClientPsk(client) ?: return null
        val previousClientPsk = material.decryptPreviousClientPsk(client)
        val opened = runCatching {
            pairedRequestProtocol.openPairedRequest(
                deviceId = credentials.deviceId,
                requestId = message.requestId,
                clientId = client.clientId,
                clientPsk = clientPsk,
                previousClientPsk = previousClientPsk,
                allowRotation = true,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
            )
        }.getOrNull() ?: return null
        val acceptedSecrets = material.acceptedRequestPsks(
            client = client,
            relayRequestId = message.requestId,
            opened = opened,
            currentClientPsk = clientPsk,
            now = now,
        )
        val method = runCatching { pairedRequestProtocol.method(opened.plaintext) }.getOrNull()
        if (method == null) {
            return rejectAuthenticatedRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                code = PairedRequestErrorCode.INVALID_REQUEST,
                now = now,
            )
        }
        if (method == PairedRequestProtocol.FINISH_PAIRING_METHOD) {
            return rejectAuthenticatedRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                code = PairedRequestErrorCode.INVALID_STATE,
                now = now,
            )
        }
        val processed = if (method == InvocationProtocol.METHOD) {
            invocationRequests.processIncoming(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                plaintext = opened.plaintext,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
                sealResponse = { responsePlaintext ->
                    runCatching {
                        pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
                    }.getOrNull()
                },
                launchAiReview = ::launchAiReview,
            )
        } else if (method == GitSignProtocol.METHOD) {
            gitSigningRequests.processIncoming(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                plaintext = opened.plaintext,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
                sealResponse = { responsePlaintext ->
                    runCatching {
                        pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
                    }.getOrNull()
                },
                launchAiReview = ::launchAiReview,
            )
        } else if (method == SshAuthenticationProtocol.METHOD) {
            sshAuthenticationRequests.processIncoming(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                plaintext = opened.plaintext,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
                sealResponse = { responsePlaintext ->
                    runCatching {
                        pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
                    }.getOrNull()
                },
                launchAiReview = ::launchAiReview,
            )
        } else if (method == SecretListProtocol.METHOD) {
            processSecretListRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
            )
        } else if (method == SecretUploadProtocol.METHOD) {
            processSecretUploadRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
            )
        } else if (method == PairingRemoveProtocol.METHOD) {
            processPairingRemoveRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
            )
        } else {
            return rejectAuthenticatedRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                code = PairedRequestErrorCode.UNSUPPORTED_METHOD,
                now = now,
            )
        }
        return processed ?: rejectAuthenticatedRequest(
            pairing = client,
            relayRequestId = message.requestId,
            requestPayload = requestPayload,
            opened = opened,
            acceptedSecrets = acceptedSecrets,
            code = PairedRequestErrorCode.INVALID_REQUEST,
            now = now,
        )
    }


    private suspend fun processSecretListRequest(
        pairing: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
    ): ProcessedRelayMessage? {
        val response = secretManagement.receiveSecretList(
            client = pairing,
            relayRequestId = relayRequestId,
            requestPayload = requestPayload,
            plaintext = opened.plaintext,
            acceptedPsks = acceptedSecrets,
        ) { responsePlaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
            }.getOrNull()
        } ?: return null
        return ProcessedRelayMessage(response)
    }

    private suspend fun processSecretUploadRequest(
        pairing: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
    ): ProcessedRelayMessage? {
        val response = secretManagement.receiveSecretUpload(
            client = pairing,
            relayRequestId = relayRequestId,
            requestPayload = requestPayload,
            plaintext = opened.plaintext,
            acceptedPsks = acceptedSecrets,
        ) { responsePlaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
            }.getOrNull()
        } ?: return null
        return ProcessedRelayMessage(response)
    }
    private suspend fun processPairingRemoveRequest(
        pairing: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
    ): ProcessedRelayMessage? {
        val response = clientRemovalRequests.receive(
            client = pairing,
            relayRequestId = relayRequestId,
            requestPayload = requestPayload,
            plaintext = opened.plaintext,
            requestPsk = acceptedSecrets.requestPsk,
        ) { responsePlaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
            }.getOrNull()
        } ?: return null
        requestSync()
        return ProcessedRelayMessage(response)
    }

    private suspend fun rejectAuthenticatedRequest(
        pairing: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
        code: PairedRequestErrorCode,
        now: Long,
    ): ProcessedRelayMessage? {
        val response = runCatching {
            pairedRequestProtocol.sealPairedResponse(
                opened = opened,
                plaintext = pairedRequestProtocol.errorResponse(code),
            )
        }.getOrNull() ?: return null
        if (!persistRejectedRequest {
            writeTransaction.execute {
                dao.insertHiddenPairedRequest(
                    request = InboxRequestEntity(
                        id = relayRequestId,
                        parentRequestId = null,
                        deviceIdentityId = pairing.deviceIdentityId,
                        clientId = pairing.clientId,
                        clientNameSnapshot = pairing.name,
                        clientSoftwareJson = pairing.clientSoftwareJson,
                        kind = RequestKind.UNKNOWN.storedName,
                        state = InboxRequestState.COMPLETED.storedName,
                        listed = false,
                        requestJson = requestPayload.toString(),
                        responseJson = response.toString(),
                        error = code.message,
                        receivedAt = now,
                        completedAt = now,
                        exchangeEndedAt = null,
                        responseOutboxFinished = false,
                    ),
                    client = pairing.copy(lastSeenAt = now),
                    requestPsk = acceptedSecrets.requestPsk,
                    currentClientPsk = acceptedSecrets.currentClientPsk,
                    previousClientPsk = acceptedSecrets.previousClientPsk,
                )
                audit.append(
                    records = listOf(
                        rejectedRequestAudit(
                            clientId = pairing.clientId,
                            clientName = pairing.auditClientName(),
                            relayRequestId = relayRequestId,
                            code = code,
                        ),
                    ),
                    occurredAt = now,
                )
            }
        }) return null
        return ProcessedRelayMessage(response)
    }

    private suspend fun rejectPendingPairingRequest(
        attempt: PairingAttemptEntity,
        rootRequest: InboxRequestEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedPsks: AcceptedRequestPsks,
        code: PairedRequestErrorCode,
        now: Long,
    ): ProcessedRelayMessage? {
        val response = runCatching {
            pairedRequestProtocol.sealPairedResponse(
                opened = opened,
                plaintext = pairedRequestProtocol.errorResponse(code),
            )
        }.getOrNull() ?: return null
        if (!persistRejectedRequest {
            writeTransaction.execute {
                dao.insertHiddenPairedRequest(
                    request = InboxRequestEntity(
                        id = relayRequestId,
                        parentRequestId = rootRequest.id,
                        deviceIdentityId = rootRequest.deviceIdentityId,
                        clientId = rootRequest.clientId,
                        clientNameSnapshot = attempt.auditClientName(),
                        clientSoftwareJson = rootRequest.clientSoftwareJson,
                        kind = RequestKind.UNKNOWN.storedName,
                        state = InboxRequestState.COMPLETED.storedName,
                        listed = false,
                        requestJson = requestPayload.toString(),
                        responseJson = response.toString(),
                        error = code.message,
                        receivedAt = now,
                        completedAt = now,
                        exchangeEndedAt = null,
                        responseOutboxFinished = false,
                    ),
                    client = null,
                    requestPsk = acceptedPsks.requestPsk,
                    currentClientPsk = null,
                    previousClientPsk = null,
                )
                audit.append(
                    records = listOf(
                        rejectedRequestAudit(
                            clientId = rootRequest.clientId,
                            clientName = attempt.auditClientName(),
                            relayRequestId = relayRequestId,
                            code = code,
                        ),
                    ),
                    occurredAt = now,
                )
            }
        }) return null
        return ProcessedRelayMessage(response)
    }

    private fun rejectedRequestAudit(
        clientId: String,
        clientName: String,
        relayRequestId: String,
        code: PairedRequestErrorCode,
    ) = AuditRecord(
        type = AuditEventType.REQUEST_REJECTED,
        outcome = AuditOutcome.REJECTED,
        decisionSource = AuditDecisionSource.VALIDATION,
        detail = code.message,
        clientId = clientId,
        clientName = clientName,
        relayRequestId = relayRequestId,
    )

    private suspend fun startPairing(
        credentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
        requestPayload: JsonElement,
    ): ProcessedRelayMessage? {
        if (message.addressId != credentials.addressId || message.clientId != message.requestId) {
            return null
        }
        val response = pairingRequests.start(
            credentials = credentials,
            requestId = message.requestId,
            requestPayload = requestPayload,
        ) ?: return null
        return ProcessedRelayMessage(response)
    }

    private suspend fun processInitialCompletion(
        credentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        return if (pairingRequests.completeInitial(credentials, request.id, completion)) {
            ProcessedRelayMessage()
        } else {
            null
        }
    }

    private suspend fun processFinishRequest(
        credentials: RelayDeviceCredentials,
        relayRequestId: String,
        requestPayload: JsonElement,
        pairing: PairingAttemptEntity,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
    ): ProcessedRelayMessage? {
        val rootRequest = dao.getRequestById(pairing.requestId) ?: return null
        if (rootRequest.deviceIdentityId != credentials.deviceIdentityId) return null
        if (!pairing.state.toPairingState().acceptsFinishRequest) return null
        val relayClientState = pairing.relayClientState.toRelayClientState()
        if (
            relayClientState == RelayClientState.PENDING &&
            pairing.desiredRelayClientState != RelayClientState.ACTIVE.wireName
        ) return null
        val response = runCatching {
            pairingRequests.prepareFinishResponse(
                opened = opened,
            )
        }.getOrNull() ?: return null
        val promoted = try {
            pairingRequests.promoteFinish(
                relayRequestId = relayRequestId,
                requestPayload = requestPayload,
                responsePayload = response,
                pairingRequestId = pairing.requestId,
                clientPsk = opened.clientPsk,
                requestPsk = acceptedSecrets.requestPsk,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return ProcessedRelayMessage(response).takeIf { promoted }
    }

    private suspend fun processCompletion(
        activeCredentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
    ): ProcessedRelayMessage? {
        val completion = message.completion ?: return null
        val request = dao.getRequestById(message.requestId) ?: return null
        if (request.clientId != message.clientId) return null
        if (request.deviceIdentityId != activeCredentials.deviceIdentityId) return null
        val isInitialPairing = request.kind == RequestKind.PAIRING.storedName
        if (isInitialPairing != (message.addressId != null)) return null
        if (request.exchangeEndedAt != null) return ProcessedRelayMessage()
        val processed = when (request.kind) {
            RequestKind.PAIRING.storedName -> {
                processInitialCompletion(activeCredentials, request, completion)
            }
            RequestKind.PAIRING_FINISH.storedName -> {
                processFinishCompletion(activeCredentials, request, completion)
            }
            RequestKind.SECRET_USE.storedName -> {
                val completed = invocationRequests.complete(request) {
                    openStoredCompletion(activeCredentials, request, completion)
                }
                if (completed) ProcessedRelayMessage() else null
            }
            RequestKind.GIT_SIGN.storedName -> {
                val completed = gitSigningRequests.complete(request) {
                    openStoredCompletion(activeCredentials, request, completion)
                }
                if (completed) ProcessedRelayMessage() else null
            }
            RequestKind.SSH_AUTHENTICATE.storedName -> {
                val completed = sshAuthenticationRequests.complete(request) {
                    openStoredCompletion(activeCredentials, request, completion)
                }
                if (completed) ProcessedRelayMessage() else null
            }
            RequestKind.SECRET_LIST.storedName -> {
                processSecretListCompletion(activeCredentials, request, completion)
            }
            RequestKind.SECRET_UPLOAD.storedName -> {
                processSecretUploadCompletion(activeCredentials, request, completion)
            }
            RequestKind.PAIRING_REMOVE.storedName -> {
                val completed = clientRemovalRequests.complete(request) {
                    openStoredCompletion(activeCredentials, request, completion)
                }
                if (completed) ProcessedRelayMessage() else null
            }
            RequestKind.UNKNOWN.storedName -> {
                processUnknownCompletion(activeCredentials, request, completion)
            }
            else -> null
        }
        return processed
    }

    private suspend fun processUnknownCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val opened = openStoredCompletion(activeCredentials, request, completion)
        if (opened == CompletionOpenResult.RetryLater) return null
        val now = currentTimeMillis()
        dao.updateEndedRequest(
            request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                responseOutboxFinished = true,
                error = request.error ?: "The completion could not be verified."
                    .takeIf { opened == CompletionOpenResult.IrrecoverablyInvalid },
                completedAt = request.completedAt ?: now,
                exchangeEndedAt = now,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processSecretListCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val processed = secretManagement.completeSecretList(request) {
            openStoredCompletion(activeCredentials, request, completion)
        }
        return if (processed) ProcessedRelayMessage() else null
    }

    private suspend fun processSecretUploadCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val processed = secretManagement.completeSecretUpload(request) {
            openStoredCompletion(activeCredentials, request, completion)
        }
        return if (processed) ProcessedRelayMessage() else null
    }
    private suspend fun processFinishCompletion(
        activeCredentials: RelayDeviceCredentials,
        finishRequest: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val opened = openStoredCompletion(
            activeCredentials,
            finishRequest,
            completion,
        )
        return if (pairingRequests.completeFinish(finishRequest.id, opened)) {
            ProcessedRelayMessage()
        } else {
            null
        }
    }

    private suspend fun credentialsForRequest(
        request: InboxRequestEntity,
    ): RelayDeviceCredentials? {
        return when (val result = deviceCredentials.deviceCredentials(request.deviceIdentityId)) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            else -> null
        }
    }

    private suspend fun openStoredCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): CompletionOpenResult {
        check(request.deviceIdentityId == activeCredentials.deviceIdentityId)
        val clientPsk = when (val result = material.decryptRequestPskResult(request)) {
            is DecryptionResult.Plaintext -> result.value
            DecryptionResult.KeyUnavailable -> return CompletionOpenResult.RetryLater
            DecryptionResult.AuthenticationFailed,
            DecryptionResult.UnsupportedFormat,
            -> return CompletionOpenResult.IrrecoverablyInvalid
        }
        val storedRequest = try {
            json.parseToJsonElement(request.requestJson)
        } catch (_: SerializationException) {
            return CompletionOpenResult.IrrecoverablyInvalid
        }
        if (storedRequest !is JsonObject) return CompletionOpenResult.IrrecoverablyInvalid
        return try {
            CompletionOpenResult.Opened(
                pairedRequestProtocol.openPairedCompletion(
                    deviceId = activeCredentials.deviceId,
                    requestId = request.id,
                    clientId = request.clientId,
                    clientPsk = clientPsk,
                    devicePrivateKey = activeCredentials.devicePrivateKey,
                    devicePublicKey = activeCredentials.devicePublicKey,
                    request = storedRequest,
                    completion = completion,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure.isIrrecoverableCompletionFailure()) {
                CompletionOpenResult.IrrecoverablyInvalid
            } else {
                CompletionOpenResult.RetryLater
            }
        }
    }

    private fun String.toRelayClientState(): RelayClientState =
        checkNotNull(RelayClientState.entries.find { it.wireName == this })

    private companion object {
        const val IDEMPOTENCY_RETENTION_MILLIS = 25 * 60 * 60 * 1_000L
    }

}

private fun ClientEntity.auditClientName(): String = name
