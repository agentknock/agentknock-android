package dev.agentknock.storage.request

import dev.agentknock.protocol.GitSignProtocol
import dev.agentknock.protocol.InvocationProtocol
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
import dev.agentknock.relay.RelayFrameSendResult
import dev.agentknock.relay.RelayMessageKind
import dev.agentknock.relay.RelayMessageState
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.auditDataOf
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.device.DeviceCredentialResult
import dev.agentknock.storage.device.DeviceKeyAccessException
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object ProcessedRelayMessage

internal enum class RequestDecision {
    APPROVE,
    DENY,
    ALLOW_TEMPORARILY,
}

internal sealed interface RequestDecisionResult {
    data object Decided : RequestDecisionResult

    data object NotPending : RequestDecisionResult

    data object NotFound : RequestDecisionResult

    data object ParentUnavailable : RequestDecisionResult

    data object ClientUnavailable : RequestDecisionResult

    data object DeniedByCurrentPolicy : RequestDecisionResult

    data object ApprovalChanged : RequestDecisionResult

    data object SecretChanged : RequestDecisionResult

    data object SecretChangedSinceInvocation : RequestDecisionResult

    data class MissingSecrets(val names: List<String>) : RequestDecisionResult

    data class ConflictingVariable(val name: String) : RequestDecisionResult

    data class Invalid(val message: String) : RequestDecisionResult

    data object SecretUnavailable : RequestDecisionResult

    data object SecretCorrupted : RequestDecisionResult

    data object UnsupportedEncryption : RequestDecisionResult

    data object TemporaryAccessUnavailable : RequestDecisionResult

    data object TemporaryAccessNotStarted : RequestDecisionResult
}

internal fun RequestDecisionResult.asCurrentPolicyDenial(): RequestDecisionResult =
    if (this == RequestDecisionResult.Decided) {
        RequestDecisionResult.DeniedByCurrentPolicy
    } else {
        this
    }

internal fun RequestDecisionResult.asSecretChangedSinceInvocation(): RequestDecisionResult =
    if (this == RequestDecisionResult.Decided) {
        RequestDecisionResult.SecretChangedSinceInvocation
    } else {
        this
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

private sealed interface DurableRelayOperation {
    data class ClientState(
        val clientId: String,
        val desired: RelayClientState,
    ) : DurableRelayOperation

    data class Response(
        val clientId: String,
        val requestId: String,
    ) : DurableRelayOperation

    data class Resume(
        val clientId: String,
        val requestId: String,
    ) : DurableRelayOperation
}

private class DurableRelayState {
    var outstanding: DurableRelayOperation? = null
    val rejectedClientStateMutations = mutableMapOf<String, RelayClientState>()
    val resumedRequestIds = mutableSetOf<String>()

    fun clearClientState(clientId: String) {
        if ((outstanding as? DurableRelayOperation.ClientState)?.clientId == clientId) {
            outstanding = null
        }
    }

    fun clearResponse(clientId: String, requestId: String) {
        val response = outstanding as? DurableRelayOperation.Response ?: return
        if (response.clientId == clientId && response.requestId == requestId) outstanding = null
    }

    fun clearResume(clientId: String, requestId: String) {
        val resume = outstanding as? DurableRelayOperation.Resume ?: return
        if (resume.clientId == clientId && resume.requestId == requestId) outstanding = null
    }

    fun clearExchange(clientId: String, requestId: String) {
        clearResponse(clientId, requestId)
        clearResume(clientId, requestId)
    }

    fun resolveState(event: RelayDeviceEvent.State, ended: Boolean) {
        clearResume(event.clientId, event.requestId)
        if (
            ended ||
                event.response != RelayMessageState.ABSENT ||
                event.exchange == RelayExchangeState.CLOSING
        ) {
            clearResponse(event.clientId, event.requestId)
        }
    }
}

private fun pairedMethodRequestKind(method: String): RequestKind? =
    when (method) {
        InvocationProtocol.METHOD -> RequestKind.SECRET_USE
        GitSignProtocol.METHOD -> RequestKind.GIT_SIGN
        SshAuthenticationProtocol.METHOD -> RequestKind.SSH_AUTHENTICATE
        SecretListProtocol.METHOD -> RequestKind.SECRET_LIST
        SecretUploadProtocol.METHOD -> RequestKind.SECRET_UPLOAD
        PairingRemoveProtocol.METHOD -> RequestKind.PAIRING_REMOVE
        else -> null
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

    fun observeClient(clientId: String): Flow<ClientDetails?> = clients.observeClient(clientId)

    suspend fun recoverInterruptedAiReviews(): Int = operationMutex.withLock {
        dao.recoverInterruptedAiReviews()
    }

    suspend fun pruneExpiredRequestState() = operationMutex.withLock {
        dao.deleteAllSettledHiddenRequests(currentTimeMillis() - IDEMPOTENCY_RETENTION_MILLIS)
    }

    suspend fun sync(): RequestSyncResult = runConnection(keepConnected = false)

    suspend fun listen(onCaughtUp: suspend () -> Unit): RequestSyncResult =
        runConnection(
            keepConnected = true,
            onCaughtUp = onCaughtUp,
        )

    suspend fun decideRequest(
        requestId: String,
        decision: RequestDecision,
    ): RequestDecisionResult =
        operationMutex
            .withLock {
                when (dao.getRequestById(requestId)?.kind) {
                    null -> RequestDecisionResult.NotFound
                    RequestKind.SECRET_USE.storedName ->
                        when (decision) {
                            RequestDecision.APPROVE,
                            RequestDecision.ALLOW_TEMPORARILY ->
                                invocationRequests.approve(
                                    requestId = requestId,
                                    allowTemporaryAccess =
                                        decision == RequestDecision.ALLOW_TEMPORARILY,
                                    openRequest = ::openStoredPairedRequest,
                                    sealResponse = ::sealStoredPairedResponse,
                                )
                            RequestDecision.DENY ->
                                invocationRequests.deny(
                                    requestId,
                                    ::sealStoredPairedResponse,
                                )
                        }
                    RequestKind.GIT_SIGN.storedName ->
                        when (decision) {
                            RequestDecision.APPROVE,
                            RequestDecision.ALLOW_TEMPORARILY ->
                                gitSigningRequests.approve(
                                    requestId = requestId,
                                    allowTemporaryAccess =
                                        decision == RequestDecision.ALLOW_TEMPORARILY,
                                    sealResponse = ::sealStoredPairedResponse,
                                )
                            RequestDecision.DENY ->
                                gitSigningRequests.deny(
                                    requestId,
                                    ::sealStoredPairedResponse,
                                )
                        }
                    RequestKind.SSH_AUTHENTICATE.storedName ->
                        when (decision) {
                            RequestDecision.APPROVE,
                            RequestDecision.ALLOW_TEMPORARILY ->
                                sshAuthenticationRequests.approve(
                                    requestId = requestId,
                                    allowTemporaryAccess =
                                        decision == RequestDecision.ALLOW_TEMPORARILY,
                                    sealResponse = ::sealStoredPairedResponse,
                                )
                            RequestDecision.DENY ->
                                sshAuthenticationRequests.deny(
                                    requestId,
                                    ::sealStoredPairedResponse,
                                )
                        }
                    else -> RequestDecisionResult.NotPending
                }
            }
            .also { result ->
                if (
                    result == RequestDecisionResult.Decided ||
                        result == RequestDecisionResult.DeniedByCurrentPolicy ||
                        result == RequestDecisionResult.SecretChangedSinceInvocation ||
                        result == RequestDecisionResult.TemporaryAccessNotStarted
                ) {
                    requestSync()
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
        review: suspend () -> AiReviewAttempt,
        complete: suspend (AiReviewAttempt) -> Unit,
    ): Boolean =
        aiReviews.launch(requestId) {
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
        val credentials =
            when (val result = deviceCredentials.activeDeviceCredentials()) {
                is DeviceCredentialResult.Available -> result.value
                null -> return RequestSyncResult.NoDevice
                DeviceCredentialResult.Unavailable -> {
                    return RequestSyncResult.DeviceCredentialsUnavailable
                }
                DeviceCredentialResult.Corrupted -> {
                    return RequestSyncResult.DeviceCredentialsCorrupted
                }
                DeviceCredentialResult.UnsupportedEncryption -> {
                    return RequestSyncResult.UnsupportedDeviceCredentialEncryption
                }
            }

        val connection =
            when (
                val result =
                    relay.connect(
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
        } catch (exception: DeviceKeyAccessException) {
            when (exception.failure) {
                DeviceCredentialResult.Unavailable -> RequestSyncResult.DeviceCredentialsUnavailable
                DeviceCredentialResult.Corrupted -> RequestSyncResult.DeviceCredentialsCorrupted
                DeviceCredentialResult.UnsupportedEncryption ->
                    RequestSyncResult.UnsupportedDeviceCredentialEncryption
            }
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
        val durableRelayState = DurableRelayState()
        flushPendingChanges(
                credentials = credentials,
                connection = connection,
                durableRelayState = durableRelayState,
            )
            ?.let {
                return it
            }

        var caughtUp = false
        while (
            keepConnected ||
                !initialSynchronizationComplete(
                    caughtUp,
                    durableRelayState,
                )
        ) {
            val input =
                if (keepConnected) {
                    select<SynchronizationInput> {
                        connection.events.onReceiveCatching {
                            SynchronizationInput.Event(
                                it.getOrNull()
                                    ?: RelayDeviceEvent.Failed("Relay event stream ended.")
                            )
                        }
                        pendingChanges.onReceive { SynchronizationInput.PendingChanges }
                    }
                } else {
                    SynchronizationInput.Event(
                        connection.events.receiveCatching().getOrNull()
                            ?: RelayDeviceEvent.Failed("Relay event stream ended.")
                    )
                }
            if (input == SynchronizationInput.PendingChanges) {
                flushPendingChanges(
                        credentials = credentials,
                        connection = connection,
                        durableRelayState = durableRelayState,
                    )
                    ?.let {
                        return it
                    }
                continue
            }

            val event = (input as SynchronizationInput.Event).event
            if (event == RelayDeviceEvent.CaughtUp) {
                if (!caughtUp) onCaughtUp()
                caughtUp = true
                // An event immediately before CaughtUp can create follow-up work. Always pump
                // here so that work is observed even when its conflated notification was already
                // drained while another durable operation was outstanding.
                flushPendingChanges(
                        credentials = credentials,
                        connection = connection,
                        durableRelayState = durableRelayState,
                    )
                    ?.let {
                        return it
                    }
                pruneExpiredRequestState()
                continue
            }
            val failure = operationMutex.withLock {
                val eventFailure =
                    when (event) {
                        is RelayDeviceEvent.Message -> {
                            val update =
                                when (event.kind) {
                                    RelayMessageKind.REQUEST -> processRequest(credentials, event)
                                    RelayMessageKind.COMPLETION ->
                                        processCompletion(credentials, event)
                                    RelayMessageKind.RESPONSE -> null
                                }
                            val acknowledgedKind =
                                event.kind.takeIf {
                                    it == RelayMessageKind.REQUEST ||
                                        it == RelayMessageKind.COMPLETION
                                }
                            if (acknowledgedKind != null && update == null) {
                                return@withLock RequestSyncResult.RelayUnavailable(
                                    "Could not durably process relay ${acknowledgedKind.wireName}; " +
                                        "reconnecting for replay."
                                )
                            }
                            if (acknowledgedKind != null && update != null) {
                                connection
                                    .send(
                                        RelayDeviceFrame.Acknowledgement(
                                            event.clientId,
                                            event.requestId,
                                            acknowledgedKind,
                                        )
                                    )
                                    .controlFrameFailure("Could not acknowledge relay message.")
                                    ?.let {
                                        return@withLock it
                                    }
                            }
                            if (dao.getRequestById(event.requestId)?.exchangeEndedAt != null) {
                                durableRelayState.clearExchange(event.clientId, event.requestId)
                            }
                            null
                        }
                        is RelayDeviceEvent.Acknowledgement -> {
                            relayRequestOwnershipMismatch(
                                    credentials = credentials,
                                    clientId = event.clientId,
                                    requestId = event.requestId,
                                    eventName = "acknowledgement",
                                )
                                ?.let {
                                    return@withLock it
                                }
                            if (event.kind == RelayMessageKind.RESPONSE) {
                                dao.markResponseOutboxFinished(event.requestId)
                                durableRelayState.clearResponse(event.clientId, event.requestId)
                            }
                            null
                        }
                        is RelayDeviceEvent.Receipt -> {
                            relayRequestOwnershipMismatch(
                                    credentials = credentials,
                                    clientId = event.clientId,
                                    requestId = event.requestId,
                                    eventName = "receipt",
                                )
                                ?.let {
                                    return@withLock it
                                }
                            if (event.kind == RelayMessageKind.RESPONSE) {
                                dao.markResponseOutboxFinished(event.requestId)
                                durableRelayState.clearResponse(event.clientId, event.requestId)
                            }
                            null
                        }
                        is RelayDeviceEvent.ClientState -> {
                            applyClientState(event)
                            durableRelayState.rejectedClientStateMutations.remove(event.clientId)
                            // Every state event is an authoritative answer to the outstanding
                            // mutation. If it differs from the desired state, applyClientState
                            // keeps
                            // that desire and queues a new pass; a terminal REVOKED state clears
                            // it.
                            durableRelayState.clearClientState(event.clientId)
                            null
                        }
                        is RelayDeviceEvent.PushRegistration -> {
                            updatePushRegistrationState(event.state)
                            null
                        }
                        is RelayDeviceEvent.State -> {
                            relayRequestOwnershipMismatch(
                                    credentials = credentials,
                                    clientId = event.clientId,
                                    requestId = event.requestId,
                                    eventName = "state",
                                )
                                ?.let {
                                    return@withLock it
                                }
                            val ended = applyRelayState(event)
                            durableRelayState.resolveState(event, ended)
                            null
                        }
                        is RelayDeviceEvent.Inactive -> {
                            relayRequestOwnershipMismatch(
                                    credentials = credentials,
                                    clientId = event.clientId,
                                    requestId = event.requestId,
                                    eventName = "inactive event",
                                )
                                ?.let {
                                    return@withLock it
                                }
                            val responseWasOutstanding =
                                durableRelayState.outstanding ==
                                    DurableRelayOperation.Response(event.clientId, event.requestId)
                            if (event.kind == RelayMessageKind.RESPONSE) {
                                dao.markResponseOutboxFinished(event.requestId)
                                durableRelayState.clearExchange(event.clientId, event.requestId)
                                if (responseWasOutstanding) {
                                    // A response-specific inactive reply does not associate the
                                    // socket;
                                    // resume once to reconcile the exchange itself.
                                    durableRelayState.resumedRequestIds.remove(event.requestId)
                                }
                            } else if (event.kind == null) {
                                endRequestExchange(
                                    event.requestId,
                                    currentTimeMillis(),
                                    "The relay no longer has this exchange.",
                                )
                                durableRelayState.clearExchange(event.clientId, event.requestId)
                            } else {
                                durableRelayState.clearResume(event.clientId, event.requestId)
                            }
                            null
                        }
                        is RelayDeviceEvent.Error ->
                            handleRelayError(
                                event = event,
                                durableRelayState = durableRelayState,
                            )
                        is RelayDeviceEvent.Closed ->
                            RequestSyncResult.RelayUnavailable(
                                "Relay connection closed (${event.code}): ${event.reason}"
                            )
                        is RelayDeviceEvent.Failed -> {
                            RequestSyncResult.RelayUnavailable(event.message)
                        }
                        RelayDeviceEvent.CaughtUp -> null
                    }
                eventFailure
                    ?: sendNextDurableOperation(
                        credentials = credentials,
                        connection = connection,
                        durableRelayState = durableRelayState,
                    )
            }
            if (failure != null) return failure
            pruneExpiredRequestState()
        }
        return RequestSyncResult.Success
    }

    private suspend fun relayRequestOwnershipMismatch(
        credentials: RelayDeviceCredentials,
        clientId: String,
        requestId: String,
        eventName: String,
    ): RequestSyncResult.RelayRejected? {
        val request = dao.getRequestById(requestId)
        if (
            request?.clientId == clientId &&
                request.deviceIdentityId == credentials.deviceIdentityId
        ) {
            return null
        }
        return RequestSyncResult.RelayRejected(
            status = 0,
            message =
                "Relay protocol mismatch: $eventName does not match this device's " +
                    "stored request.",
        )
    }

    private suspend fun flushPendingChanges(
        credentials: RelayDeviceCredentials,
        connection: RelayDeviceConnection,
        durableRelayState: DurableRelayState,
    ): RequestSyncResult? {
        while (pendingChanges.tryReceive().isSuccess) {
            // Changes made after this drain remain queued for the next pass.
        }
        return operationMutex.withLock {
            sendNextDurableOperation(
                credentials = credentials,
                connection = connection,
                durableRelayState = durableRelayState,
            )
        }
    }

    private suspend fun sendNextDurableOperation(
        credentials: RelayDeviceCredentials,
        connection: RelayDeviceConnection,
        durableRelayState: DurableRelayState,
    ): RequestSyncResult? {
        if (durableRelayState.outstanding != null) return null

        sendNextResponse(
                credentials = credentials,
                connection = connection,
                durableRelayState = durableRelayState,
                pairingRemoval = true,
            )
            ?.let {
                return it
            }
        if (durableRelayState.outstanding != null) return null

        sendNextClientState(
                credentials = credentials,
                connection = connection,
                durableRelayState = durableRelayState,
            )
            ?.let {
                return it
            }
        if (durableRelayState.outstanding != null) return null

        sendNextResponse(
                credentials = credentials,
                connection = connection,
                durableRelayState = durableRelayState,
                pairingRemoval = false,
            )
            ?.let {
                return it
            }
        if (durableRelayState.outstanding != null) return null

        for (request in dao.getOpenExchanges()) {
            if (request.id in durableRelayState.resumedRequestIds) continue
            if (request.deviceIdentityId != credentials.deviceIdentityId) continue
            connection
                .send(RelayDeviceFrame.Resume(request.clientId, request.id))
                .controlFrameFailure("Could not resume relay exchange.")
                ?.let {
                    return it
                }
            durableRelayState.resumedRequestIds += request.id
            durableRelayState.outstanding =
                DurableRelayOperation.Resume(
                    clientId = request.clientId,
                    requestId = request.id,
                )
            return null
        }
        return null
    }

    private suspend fun sendNextResponse(
        credentials: RelayDeviceCredentials,
        connection: RelayDeviceConnection,
        durableRelayState: DurableRelayState,
        pairingRemoval: Boolean,
    ): RequestSyncResult? {
        for (request in dao.getUnfinishedResponseOutboxes()) {
            if (request.deviceIdentityId != credentials.deviceIdentityId) continue
            if ((request.kind == RequestKind.PAIRING_REMOVE.storedName) != pairingRemoval) continue
            sendDurableResponse(
                    connection = connection,
                    clientId = request.clientId,
                    requestId = request.id,
                    response = json.parseToJsonElement(checkNotNull(request.responseJson)),
                    durableRelayState = durableRelayState,
                )
                ?.let {
                    return it
                }
            if (durableRelayState.outstanding != null) return null
        }
        return null
    }

    private suspend fun sendNextClientState(
        credentials: RelayDeviceCredentials,
        connection: RelayDeviceConnection,
        durableRelayState: DurableRelayState,
    ): RequestSyncResult? {
        for (attempt in dao.getPairingAttempts()) {
            val rootRequest = dao.getRequestById(attempt.requestId) ?: continue
            if (rootRequest.deviceIdentityId != credentials.deviceIdentityId) continue
            val desired = attempt.desiredRelayClientState?.toRelayClientState() ?: continue
            if (attempt.relayClientState == desired.wireName) continue
            if (durableRelayState.rejectedClientStateMutations[attempt.clientId] == desired) {
                continue
            }
            connection
                .send(RelayDeviceFrame.SetClientState(attempt.clientId, desired))
                .controlFrameFailure("Could not send relay client state.")
                ?.let {
                    return it
                }
            durableRelayState.outstanding =
                DurableRelayOperation.ClientState(
                    clientId = attempt.clientId,
                    desired = desired,
                )
            return null
        }

        for (client in dao.getClients()) {
            if (client.deviceIdentityId != credentials.deviceIdentityId) continue
            val desired = client.desiredRelayClientState?.toRelayClientState() ?: continue
            if (client.relayClientState == desired.wireName) continue
            if (durableRelayState.rejectedClientStateMutations[client.clientId] == desired) continue
            connection
                .send(RelayDeviceFrame.SetClientState(client.clientId, desired))
                .controlFrameFailure("Could not send relay client state.")
                ?.let {
                    return it
                }
            durableRelayState.outstanding =
                DurableRelayOperation.ClientState(
                    clientId = client.clientId,
                    desired = desired,
                )
            return null
        }
        return null
    }

    private suspend fun handleRelayError(
        event: RelayDeviceEvent.Error,
        durableRelayState: DurableRelayState,
    ): RequestSyncResult? {
        val scope =
            when (val scope = event.scope) {
                RelayDeviceErrorScope.Unscoped -> {
                    if (event.code == "INVALID_CLIENT_STATE" && !event.retryable) {
                        val rejected =
                            durableRelayState.outstanding as? DurableRelayOperation.ClientState
                                ?: return event.toSynchronizationFailure()
                        durableRelayState.outstanding = null
                        durableRelayState.rejectedClientStateMutations[rejected.clientId] =
                            rejected.desired
                        return null
                    }
                    return event.toSynchronizationFailure()
                }
                is RelayDeviceErrorScope.Exchange -> scope
            }
        val matchesOutstandingOperation =
            when (scope.kind) {
                RelayMessageKind.RESPONSE ->
                    durableRelayState.outstanding ==
                        DurableRelayOperation.Response(scope.clientId, scope.requestId)
                null ->
                    durableRelayState.outstanding ==
                        DurableRelayOperation.Resume(scope.clientId, scope.requestId)
                RelayMessageKind.REQUEST,
                RelayMessageKind.COMPLETION -> false
            }
        if (!matchesOutstandingOperation) {
            return RequestSyncResult.RelayRejected(0, event.message)
        }
        if (event.retryable) return event.toSynchronizationFailure()

        when (scope.kind) {
            RelayMessageKind.RESPONSE -> {
                dao.markResponseOutboxFinished(scope.requestId)
                durableRelayState.clearResponse(scope.clientId, scope.requestId)
                durableRelayState.resumedRequestIds.remove(scope.requestId)
            }
            null -> {
                endRequestExchange(scope.requestId, currentTimeMillis(), event.message)
                durableRelayState.clearExchange(scope.clientId, scope.requestId)
            }
            RelayMessageKind.REQUEST,
            RelayMessageKind.COMPLETION -> error("Unsupported scoped relay error was accepted")
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
        durableRelayState: DurableRelayState,
    ): Boolean = caughtUp && durableRelayState.outstanding == null

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
        if (
            event.response == RelayMessageState.ACCEPTED ||
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
        when (request.kind.toRequestKind()) {
            RequestKind.PAIRING -> {
                pairingRequests.endInitialExchange(request.id, now, message)
            }
            RequestKind.SECRET_USE -> {
                invocationRequests.expire(request, message, now)
            }
            RequestKind.GIT_SIGN -> {
                gitSigningRequests.expire(request, message, now)
            }
            RequestKind.SSH_AUTHENTICATE -> {
                sshAuthenticationRequests.expire(request, message, now)
            }
            RequestKind.SECRET_LIST -> {
                secretManagement.expireSecretList(request, message, now)
            }
            RequestKind.SECRET_UPLOAD -> {
                secretManagement.expireSecretUpload(request, message, now)
            }
            RequestKind.PAIRING_REMOVE -> {
                clientRemovalRequests.expire(request, message, now)
            }
            RequestKind.PAIRING_FINISH -> {
                pairingRequests.endFinishExchange(request.id, now, message)
            }
            RequestKind.UNKNOWN -> {
                dao.updateEndedRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        responseOutboxFinished = true,
                        error = request.error ?: message,
                        failureKind = request.failureKind ?: RequestFailureKind.RELAY.storedName,
                        completedAt = request.completedAt ?: now,
                        exchangeEndedAt = now,
                    )
                )
            }
        }
    }

    private suspend fun sendDurableResponse(
        connection: RelayDeviceConnection,
        clientId: String,
        requestId: String,
        response: JsonElement,
        durableRelayState: DurableRelayState,
    ): RequestSyncResult? {
        val firstResult = connection.send(RelayDeviceFrame.Response(clientId, requestId, response))
        if (firstResult == RelayFrameSendResult.Sent) {
            durableRelayState.outstanding = DurableRelayOperation.Response(clientId, requestId)
            // Publishing a response associates this device connection with the exchange.
            durableRelayState.resumedRequestIds += requestId
            return null
        }
        if (firstResult == RelayFrameSendResult.Unavailable) {
            return RequestSyncResult.RelayUnavailable("Could not send relay response.")
        }

        val fallback = replaceOversizedResponse(requestId)
        if (fallback == null) {
            endUndeliverableResponse(requestId)
            return null
        }
        return when (connection.send(RelayDeviceFrame.Response(clientId, requestId, fallback))) {
            RelayFrameSendResult.Sent -> {
                durableRelayState.outstanding = DurableRelayOperation.Response(clientId, requestId)
                durableRelayState.resumedRequestIds += requestId
                null
            }
            RelayFrameSendResult.Unavailable -> {
                RequestSyncResult.RelayUnavailable("Could not send relay response.")
            }
            RelayFrameSendResult.FrameTooLarge -> {
                endUndeliverableResponse(requestId)
                null
            }
        }
    }

    private suspend fun replaceOversizedResponse(requestId: String): JsonElement? {
        val request = dao.getRequestById(requestId) ?: return null
        if (request.exchangeEndedAt != null) return null
        val fallback =
            sealStoredPairedResponsePayload(
                request = request,
                plaintext =
                    pairedRequestProtocol.errorResponse(PairedRequestErrorCode.RESPONSE_TOO_LARGE),
            ) ?: return null
        check(
            dao.updateRequest(
                request.copy(
                    responseJson = fallback.toString(),
                    responseOutboxFinished = false,
                    error = PairedRequestErrorCode.RESPONSE_TOO_LARGE.message,
                    failureKind = RequestFailureKind.DELIVERY.storedName,
                )
            ) == 1
        )
        return fallback
    }

    private suspend fun endUndeliverableResponse(requestId: String) {
        endRequestExchange(
            requestId,
            currentTimeMillis(),
            RESPONSE_FRAME_TOO_LARGE_MESSAGE,
        )
    }

    suspend fun chooseSas(requestId: String, selectedIndex: Int?): PairingDecisionResult =
        operationMutex
            .withLock {
                pairingRequests.chooseSas(requestId, selectedIndex)
            }
            .also { result ->
                if (
                    result == PairingDecisionResult.VERIFIED ||
                        result == PairingDecisionResult.REJECTED
                ) {
                    requestSync()
                }
            }

    suspend fun matchingPendingSas(
        requestId: String,
        selectedIndex: Int,
    ): MatchingPairingSas? = operationMutex.withLock {
        pairingRequests.matchingPendingSas(requestId, selectedIndex)
    }

    suspend fun rejectPairing(requestId: String): PairingDecisionResult =
        operationMutex
            .withLock {
                pairingRequests.reject(requestId)
            }
            .also { result ->
                if (result == PairingDecisionResult.REJECTED) requestSync()
            }

    private suspend fun sealStoredPairedResponse(
        request: InboxRequestEntity,
        plaintext: ByteArray,
    ): JsonElement? {
        val client = dao.getClient(request.clientId) ?: return null
        if (
            client.relayClientState != RelayClientState.ACTIVE.wireName ||
                client.desiredRelayClientState?.let { it != RelayClientState.ACTIVE.wireName } ==
                    true
        ) {
            return null
        }
        return sealStoredPairedResponsePayload(request, plaintext)
    }

    private suspend fun sealStoredPairedResponsePayload(
        request: InboxRequestEntity,
        plaintext: ByteArray,
    ): JsonElement? {
        val credentials = credentialsForRequest(request) ?: return null
        val clientPsk = material.decryptRequestPsk(request) ?: return null
        return runCatching {
            credentials.deviceKey.use { keyPair ->
                pairedRequestProtocol.sealPairedResponse(
                    deviceId = credentials.deviceId,
                    requestId = request.id,
                    clientId = request.clientId,
                    clientPsk = clientPsk,
                    devicePrivateKey = keyPair.privateKey,
                    devicePublicKey = keyPair.publicKey,
                    request = json.parseToJsonElement(request.requestJson),
                    plaintext = plaintext,
                )
            }
        }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    }

    private suspend fun openStoredPairedRequest(request: InboxRequestEntity): ByteArray? {
        val credentials = credentialsForRequest(request) ?: return null
        val clientPsk = material.decryptRequestPsk(request) ?: return null
        val opened =
            runCatching {
                credentials.deviceKey.use { keyPair ->
                    pairedRequestProtocol.openPairedRequest(
                        deviceId = credentials.deviceId,
                        requestId = request.id,
                        clientId = request.clientId,
                        clientPsk = clientPsk,
                        previousClientPsk = null,
                        allowRotation = false,
                        devicePrivateKey = keyPair.privateKey,
                        devicePublicKey = keyPair.publicKey,
                        request = json.parseToJsonElement(request.requestJson),
                    )
                }
            }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: return null
        return opened.plaintext
    }

    private suspend fun processRequest(
        credentials: RelayDeviceCredentials,
        message: RelayDeviceEvent.Message,
    ): ProcessedRelayMessage? {
        val requestPayload = message.payload
        val existing = dao.getRequestById(message.requestId)
        if (existing != null) {
            if (
                existing.clientId != message.clientId ||
                    existing.deviceIdentityId != credentials.deviceIdentityId ||
                    (existing.kind == RequestKind.PAIRING.storedName) != (message.addressId != null)
            ) {
                return ProcessedRelayMessage
            }
            if (existing.exchangeEndedAt != null) return ProcessedRelayMessage
            if (existing.requestJson != requestPayload.toString()) return ProcessedRelayMessage
            if (existing.responseJson != null) {
                dao.reopenResponseOutbox(existing.id)
            }
            return ProcessedRelayMessage
        }

        return processNewRequest(credentials, message)
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
        sensitiveAccessAuthorized: Boolean,
    ): SecretUploadVariableValue = operationMutex.withLock {
        secretManagement.readSecretUploadVariable(
            requestId,
            variableId,
            sensitiveAccessAuthorized,
        )
    }

    suspend fun setSecretUploadVariableSensitivity(
        requestId: String,
        variableId: String,
        sensitive: Boolean,
        sensitivityReductionAuthorized: Boolean,
    ): SecretUploadSensitivityResult = operationMutex.withLock {
        secretManagement.setSecretUploadVariableSensitivity(
            requestId,
            variableId,
            sensitive,
            sensitivityReductionAuthorized,
        )
    }

    suspend fun rejectSecretUpload(requestId: String): SecretUploadDecisionResult =
        operationMutex.withLock {
            secretManagement.rejectSecretUpload(requestId)
        }

    suspend fun renameClient(clientId: String, name: String): ClientChangeResult =
        operationMutex.withLock {
            clients.rename(clientId, name)
        }

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
        message: RelayDeviceEvent.Message,
    ): ProcessedRelayMessage? {
        val requestPayload = message.payload
        val now = currentTimeMillis()
        if (!isFreshRelayRequestId(message.requestId, now)) return ProcessedRelayMessage
        if (message.addressId != null) {
            return startPairing(credentials, message)
        }

        val client = dao.getClient(message.clientId)
        if (client != null) {
            return processActiveClientRequest(credentials, message, client, now)
        }
        val pairing =
            pairingRequests.finishContext(credentials, message.clientId)
                ?: return ProcessedRelayMessage
        val opened =
            runCatching {
                credentials.deviceKey.use { keyPair ->
                    pairedRequestProtocol.openPairedRequest(
                        deviceId = credentials.deviceId,
                        requestId = message.requestId,
                        clientId = pairing.clientId,
                        clientPsk = pairing.clientPsk,
                        previousClientPsk = null,
                        allowRotation = false,
                        devicePrivateKey = keyPair.privateKey,
                        devicePublicKey = keyPair.publicKey,
                        request = requestPayload,
                    )
                }
            }
                .onFailure {
                    if (it is CancellationException || it is DeviceKeyAccessException) throw it
                }
                .getOrNull() ?: return ProcessedRelayMessage
        val acceptedPsks =
            try {
                AcceptedRequestPsks(
                    requestPsk =
                        material.encryptRequestPsk(
                            deviceIdentityId = pairing.deviceIdentityId,
                            clientId = pairing.clientId,
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
            return pairingRequests.receiveFinish(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                plaintext = opened.plaintext,
                requestPsk = acceptedPsks.requestPsk,
                sealResponse = { responsePlaintext ->
                    runCatching {
                        pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
                    }
                        .getOrNull()
                },
            )
        }
        return rejectPendingPairingRequest(
            pairing = pairing,
            relayRequestId = message.requestId,
            requestPayload = requestPayload,
            opened = opened,
            acceptedPsks = acceptedPsks,
            code =
                if (method == null) {
                    PairedRequestErrorCode.INVALID_REQUEST
                } else {
                    PairedRequestErrorCode.INVALID_STATE
                },
        )
    }

    private suspend fun processActiveClientRequest(
        credentials: RelayDeviceCredentials,
        message: RelayDeviceEvent.Message,
        client: ClientEntity,
        now: Long,
    ): ProcessedRelayMessage? {
        val requestPayload = message.payload
        if (client.deviceIdentityId != credentials.deviceIdentityId) return ProcessedRelayMessage
        if (
            client.relayClientState != RelayClientState.ACTIVE.wireName ||
                client.desiredRelayClientState?.let { it != RelayClientState.ACTIVE.wireName } ==
                    true
        ) {
            return ProcessedRelayMessage
        }
        val clientPsk = material.decryptClientPsk(client) ?: return ProcessedRelayMessage
        val previousClientPsk = material.decryptPreviousClientPsk(client)
        val opened =
            runCatching {
                credentials.deviceKey.use { keyPair ->
                    pairedRequestProtocol.openPairedRequest(
                        deviceId = credentials.deviceId,
                        requestId = message.requestId,
                        clientId = client.clientId,
                        clientPsk = clientPsk,
                        previousClientPsk = previousClientPsk,
                        allowRotation = true,
                        devicePrivateKey = keyPair.privateKey,
                        devicePublicKey = keyPair.publicKey,
                        request = requestPayload,
                    )
                }
            }
                .onFailure {
                    if (it is CancellationException || it is DeviceKeyAccessException) throw it
                }
                .getOrNull() ?: return ProcessedRelayMessage
        val acceptedPsks =
            material.acceptedRequestPsks(
                client = client,
                relayRequestId = message.requestId,
                opened = opened,
                currentClientPsk = clientPsk,
                now = now,
            )
        val method = runCatching { pairedRequestProtocol.method(opened.plaintext) }.getOrNull()
        if (method == null) {
            return rejectAuthenticatedRequest(
                client = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedPsks = acceptedPsks,
                code = PairedRequestErrorCode.INVALID_REQUEST,
                now = now,
            )
        }
        if (method == PairedRequestProtocol.FINISH_PAIRING_METHOD) {
            return rejectAuthenticatedRequest(
                client = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedPsks = acceptedPsks,
                code = PairedRequestErrorCode.INVALID_STATE,
                now = now,
            )
        }
        val requestKind =
            pairedMethodRequestKind(method)
                ?: return rejectAuthenticatedRequest(
                    client = client,
                    relayRequestId = message.requestId,
                    requestPayload = requestPayload,
                    opened = opened,
                    acceptedPsks = acceptedPsks,
                    code = PairedRequestErrorCode.UNSUPPORTED_METHOD,
                    now = now,
                )
        val sealResponse: (ByteArray) -> JsonElement? = { responsePlaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
            }
                .getOrNull()
        }
        val processed =
            when (requestKind) {
                RequestKind.SECRET_USE ->
                    invocationRequests.processIncoming(
                        client = client,
                        relayRequestId = message.requestId,
                        requestPayload = requestPayload,
                        plaintext = opened.plaintext,
                        acceptedPsks = acceptedPsks,
                        credentials = credentials,
                        sealResponse = sealResponse,
                        launchAiReview = ::launchAiReview,
                    )
                RequestKind.GIT_SIGN ->
                    gitSigningRequests.processIncoming(
                        client = client,
                        relayRequestId = message.requestId,
                        requestPayload = requestPayload,
                        plaintext = opened.plaintext,
                        acceptedPsks = acceptedPsks,
                        credentials = credentials,
                        sealResponse = sealResponse,
                        launchAiReview = ::launchAiReview,
                    )
                RequestKind.SSH_AUTHENTICATE ->
                    sshAuthenticationRequests.processIncoming(
                        client = client,
                        relayRequestId = message.requestId,
                        requestPayload = requestPayload,
                        plaintext = opened.plaintext,
                        acceptedPsks = acceptedPsks,
                        credentials = credentials,
                        sealResponse = sealResponse,
                        launchAiReview = ::launchAiReview,
                    )
                RequestKind.SECRET_LIST ->
                    processSecretListRequest(
                        client = client,
                        relayRequestId = message.requestId,
                        requestPayload = requestPayload,
                        opened = opened,
                        acceptedPsks = acceptedPsks,
                    )
                RequestKind.SECRET_UPLOAD ->
                    processSecretUploadRequest(
                        client = client,
                        relayRequestId = message.requestId,
                        requestPayload = requestPayload,
                        opened = opened,
                        acceptedPsks = acceptedPsks,
                    )
                RequestKind.PAIRING_REMOVE ->
                    processPairingRemoveRequest(
                        client = client,
                        relayRequestId = message.requestId,
                        requestPayload = requestPayload,
                        opened = opened,
                        acceptedPsks = acceptedPsks,
                    )
                RequestKind.PAIRING,
                RequestKind.PAIRING_FINISH,
                RequestKind.UNKNOWN -> error("$requestKind is not a paired request method")
            }
        return processed
            ?: rejectAuthenticatedRequest(
                client = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedPsks = acceptedPsks,
                code = PairedRequestErrorCode.INVALID_REQUEST,
                now = now,
            )
    }

    private suspend fun processSecretListRequest(
        client: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedPsks: AcceptedRequestPsks,
    ): ProcessedRelayMessage? {
        secretManagement.receiveSecretList(
            client = client,
            relayRequestId = relayRequestId,
            requestPayload = requestPayload,
            plaintext = opened.plaintext,
            acceptedPsks = acceptedPsks,
        ) { responsePlaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
            }
                .getOrNull()
        } ?: return null
        return ProcessedRelayMessage
    }

    private suspend fun processSecretUploadRequest(
        client: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedPsks: AcceptedRequestPsks,
    ): ProcessedRelayMessage? {
        secretManagement.receiveSecretUpload(
            client = client,
            relayRequestId = relayRequestId,
            requestPayload = requestPayload,
            plaintext = opened.plaintext,
            acceptedPsks = acceptedPsks,
        ) { responsePlaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
            }
                .getOrNull()
        } ?: return null
        return ProcessedRelayMessage
    }

    private suspend fun processPairingRemoveRequest(
        client: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedPsks: AcceptedRequestPsks,
    ): ProcessedRelayMessage? {
        clientRemovalRequests.receive(
            client = client,
            relayRequestId = relayRequestId,
            requestPayload = requestPayload,
            plaintext = opened.plaintext,
            requestPsk = acceptedPsks.requestPsk,
        ) { responsePlaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(opened, responsePlaintext)
            }
                .getOrNull()
        } ?: return null
        requestSync()
        return ProcessedRelayMessage
    }

    private suspend fun rejectAuthenticatedRequest(
        client: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedPsks: AcceptedRequestPsks,
        code: PairedRequestErrorCode,
        now: Long,
    ): ProcessedRelayMessage? {
        val response =
            runCatching {
                pairedRequestProtocol.sealPairedResponse(
                    opened = opened,
                    plaintext = pairedRequestProtocol.errorResponse(code),
                )
            }
                .getOrNull() ?: return null
        if (
            !persistRejectedRequest {
                writeTransaction.execute {
                    dao.insertHiddenPairedRequest(
                        request =
                            InboxRequestEntity(
                                id = relayRequestId,
                                parentRequestId = null,
                                deviceIdentityId = client.deviceIdentityId,
                                clientId = client.clientId,
                                clientNameSnapshot = client.name,
                                clientSoftwareJson = client.clientSoftwareJson,
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
                        client = client.copy(lastSeenAt = now),
                        requestPsk = acceptedPsks.requestPsk,
                        currentClientPsk = acceptedPsks.currentClientPsk,
                        previousClientPsk = acceptedPsks.previousClientPsk,
                    )
                    audit.append(
                        records =
                            listOf(
                                rejectedRequestAudit(
                                    client = client,
                                    relayRequestId = relayRequestId,
                                    code = code,
                                    receivedAt = now,
                                )
                            ),
                        occurredAt = now,
                    )
                }
            }
        )
            return null
        return ProcessedRelayMessage
    }

    private suspend fun persistRejectedRequest(persist: suspend () -> Unit): Boolean =
        try {
            persist()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun rejectPendingPairingRequest(
        pairing: PairingFinishContext,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedPsks: AcceptedRequestPsks,
        code: PairedRequestErrorCode,
    ): ProcessedRelayMessage? {
        val response =
            runCatching {
                pairedRequestProtocol.sealPairedResponse(
                    opened = opened,
                    plaintext = pairedRequestProtocol.errorResponse(code),
                )
            }
                .getOrNull() ?: return null
        if (
            !pairingRequests.recordRejectedRequest(
                pairing = pairing,
                relayRequestId = relayRequestId,
                requestPayload = requestPayload,
                responsePayload = response,
                requestPsk = acceptedPsks.requestPsk,
                code = code,
            )
        )
            return null
        return ProcessedRelayMessage
    }

    private fun rejectedRequestAudit(
        client: ClientEntity,
        relayRequestId: String,
        code: PairedRequestErrorCode,
        receivedAt: Long,
    ) =
        AuditRecord(
            type = AuditEventType.REQUEST_REJECTED,
            outcome = AuditOutcome.REJECTED,
            decisionSource = AuditDecisionSource.VALIDATION,
            detail = code.message,
            clientId = client.clientId,
            clientName = client.auditClientName(),
            relayRequestId = relayRequestId,
            data =
                auditDataOf(
                    "device_identity_id" to client.deviceIdentityId,
                    "request_kind" to RequestKind.UNKNOWN.storedName,
                    "client_software" to
                        client.clientSoftwareJson?.let {
                            storedJson.parseToJsonElement(it)
                        },
                    "rejection_code" to code.wireName,
                    "received_at" to receivedAt,
                ),
        )

    private suspend fun startPairing(
        credentials: RelayDeviceCredentials,
        message: RelayDeviceEvent.Message,
    ): ProcessedRelayMessage? {
        if (message.addressId != credentials.addressId || message.clientId != message.requestId) {
            return ProcessedRelayMessage
        }
        if (
            !pairingRequests.start(
                credentials = credentials,
                requestId = message.requestId,
                requestPayload = message.payload,
            )
        )
            return null
        return ProcessedRelayMessage
    }

    private suspend fun processInitialCompletion(
        credentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        return if (pairingRequests.completeInitial(credentials, request.id, completion)) {
            ProcessedRelayMessage
        } else {
            null
        }
    }

    private suspend fun processCompletion(
        activeCredentials: RelayDeviceCredentials,
        message: RelayDeviceEvent.Message,
    ): ProcessedRelayMessage? {
        val completion = message.payload
        val request = dao.getRequestById(message.requestId) ?: return ProcessedRelayMessage
        if (request.clientId != message.clientId) return ProcessedRelayMessage
        if (request.deviceIdentityId != activeCredentials.deviceIdentityId) {
            return ProcessedRelayMessage
        }
        val requestKind = request.kind.toRequestKind()
        val isInitialPairing = requestKind == RequestKind.PAIRING
        if (isInitialPairing != (message.addressId != null)) return ProcessedRelayMessage
        if (request.exchangeEndedAt != null) return ProcessedRelayMessage
        val processed =
            when (requestKind) {
                RequestKind.PAIRING -> {
                    processInitialCompletion(activeCredentials, request, completion)
                }
                RequestKind.PAIRING_FINISH -> {
                    processFinishCompletion(activeCredentials, request, completion)
                }
                RequestKind.SECRET_USE -> {
                    val completed =
                        invocationRequests.complete(request) {
                            openStoredCompletion(activeCredentials, request, completion)
                        }
                    if (completed) ProcessedRelayMessage else null
                }
                RequestKind.GIT_SIGN -> {
                    val completed =
                        gitSigningRequests.complete(request) {
                            openStoredCompletion(activeCredentials, request, completion)
                        }
                    if (completed) ProcessedRelayMessage else null
                }
                RequestKind.SSH_AUTHENTICATE -> {
                    val completed =
                        sshAuthenticationRequests.complete(request) {
                            openStoredCompletion(activeCredentials, request, completion)
                        }
                    if (completed) ProcessedRelayMessage else null
                }
                RequestKind.SECRET_LIST -> {
                    processSecretListCompletion(activeCredentials, request, completion)
                }
                RequestKind.SECRET_UPLOAD -> {
                    processSecretUploadCompletion(activeCredentials, request, completion)
                }
                RequestKind.PAIRING_REMOVE -> {
                    val completed =
                        clientRemovalRequests.complete(request) {
                            openStoredCompletion(activeCredentials, request, completion)
                        }
                    if (completed) ProcessedRelayMessage else null
                }
                RequestKind.UNKNOWN -> {
                    processUnknownCompletion(activeCredentials, request, completion)
                }
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
                error =
                    request.error
                        ?: "The completion could not be verified."
                            .takeIf { opened == CompletionOpenResult.IrrecoverablyInvalid },
                failureKind =
                    request.failureKind
                        ?: RequestFailureKind.VERIFICATION.storedName.takeIf {
                            opened == CompletionOpenResult.IrrecoverablyInvalid
                        },
                completedAt = request.completedAt ?: now,
                exchangeEndedAt = now,
            )
        )
        return ProcessedRelayMessage
    }

    private suspend fun processSecretListCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val processed =
            secretManagement.completeSecretList(request) {
                openStoredCompletion(activeCredentials, request, completion)
            }
        return if (processed) ProcessedRelayMessage else null
    }

    private suspend fun processSecretUploadCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val processed =
            secretManagement.completeSecretUpload(request) {
                openStoredCompletion(activeCredentials, request, completion)
            }
        return if (processed) ProcessedRelayMessage else null
    }

    private suspend fun processFinishCompletion(
        activeCredentials: RelayDeviceCredentials,
        finishRequest: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val opened =
            openStoredCompletion(
                activeCredentials,
                finishRequest,
                completion,
            )
        return if (pairingRequests.completeFinish(finishRequest.id, opened)) {
            ProcessedRelayMessage
        } else {
            null
        }
    }

    private suspend fun credentialsForRequest(
        request: InboxRequestEntity
    ): RelayDeviceCredentials? {
        return when (val result = deviceCredentials.deviceCredentials(request.deviceIdentityId)) {
            is DeviceCredentialResult.Available -> result.value
            else -> null
        }
    }

    private suspend fun openStoredCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): CompletionOpenResult {
        check(request.deviceIdentityId == activeCredentials.deviceIdentityId)
        val clientPsk =
            when (val result = material.decryptRequestPskResult(request)) {
                is DecryptionResult.Plaintext -> result.value
                DecryptionResult.KeyUnavailable -> return CompletionOpenResult.RetryLater
                DecryptionResult.AuthenticationFailed,
                DecryptionResult.UnsupportedFormat ->
                    return CompletionOpenResult.IrrecoverablyInvalid
            }
        val storedRequest =
            try {
                json.parseToJsonElement(request.requestJson)
            } catch (_: SerializationException) {
                return CompletionOpenResult.IrrecoverablyInvalid
            }
        if (storedRequest !is JsonObject) return CompletionOpenResult.IrrecoverablyInvalid
        return try {
            CompletionOpenResult.Opened(
                activeCredentials.deviceKey.use { keyPair ->
                    pairedRequestProtocol.openPairedCompletion(
                        deviceId = activeCredentials.deviceId,
                        requestId = request.id,
                        clientId = request.clientId,
                        clientPsk = clientPsk,
                        devicePrivateKey = keyPair.privateKey,
                        devicePublicKey = keyPair.publicKey,
                        request = storedRequest,
                        completion = completion,
                    )
                }
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
        const val RESPONSE_FRAME_TOO_LARGE_MESSAGE =
            "The encrypted relay response exceeded the maximum frame size."
    }
}

private fun RelayFrameSendResult.controlFrameFailure(
    unavailableMessage: String
): RequestSyncResult? =
    when (this) {
        RelayFrameSendResult.Sent -> null
        RelayFrameSendResult.Unavailable -> RequestSyncResult.RelayUnavailable(unavailableMessage)
        RelayFrameSendResult.FrameTooLarge ->
            RequestSyncResult.InternalFailure("RelayFrameTooLarge")
    }

private fun ClientEntity.auditClientName(): String = name
