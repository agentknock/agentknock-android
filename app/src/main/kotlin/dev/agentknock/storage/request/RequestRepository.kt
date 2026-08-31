package dev.agentknock.storage.request

import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.protocol.InvocationProtocol
import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.protocol.GitSignCompletion
import dev.agentknock.protocol.GitSignProtocol
import dev.agentknock.protocol.GitSignRequestMessage
import dev.agentknock.protocol.OpenedPairedRequest
import dev.agentknock.protocol.PairedRequestErrorCode
import dev.agentknock.protocol.PairedRequestProtocol
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.protocol.PairingRemoveProtocol
import dev.agentknock.protocol.SecretListProtocol
import dev.agentknock.protocol.SecretUploadProtocol
import dev.agentknock.protocol.SshAuthenticationCompletion
import dev.agentknock.protocol.SshAuthenticationMessageDetails
import dev.agentknock.protocol.SshAuthenticationProtocol
import dev.agentknock.protocol.SshAuthenticationRequestMessage
import dev.agentknock.review.approvalReviewRequest
import dev.agentknock.review.approvalReviewGitSignRequest
import dev.agentknock.review.approvalReviewSshAuthenticationRequest
import dev.agentknock.review.approvalReviewSecretFacts
import dev.agentknock.relay.ApprovalReviewRequest
import dev.agentknock.relay.RelayClientState
import dev.agentknock.relay.RelayApprovalReviewClient
import dev.agentknock.relay.RelayApprovalReviewDecision
import dev.agentknock.relay.RelayApprovalReviewResult
import dev.agentknock.relay.RelayDeviceClient
import dev.agentknock.relay.RelayDeviceConnection
import dev.agentknock.relay.RelayDeviceConnectionResult
import dev.agentknock.relay.RelayDeviceEvent
import dev.agentknock.relay.RelayDeviceFrame
import dev.agentknock.relay.RelayExchangeState
import dev.agentknock.relay.RelayMessageKind
import dev.agentknock.relay.RelayMessageState
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.storage.runCatchingNonCancellation
import dev.agentknock.storage.secret.RequestedSecretsResult
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SecretValues
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.SshKeyCodec
import dev.agentknock.storage.secret.GitSignatureResult
import dev.agentknock.storage.secret.SshAuthenticationSignatureResult
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
import dev.agentknock.storage.approval.ApprovalPolicyEvaluator
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.requiresAiReview
import dev.agentknock.storage.approval.isFullyApproved
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.device.RelayDeviceCredentialsResult
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal fun reviewedRequestState(responseAvailable: Boolean): InboxRequestState =
    if (responseAvailable) InboxRequestState.WAITING else InboxRequestState.ACTION_REQUIRED

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

private data class ProcessedRelayMessage(val response: JsonElement? = null)

private data class TemporaryGrant(
    val policies: List<SecretApprovalPolicy>,
    val operation: TemporaryAccessOperation,
    val expiresAt: Long,
    val evaluation: ApprovalEvaluation,
    val decisionSource: String,
)

internal fun pairingAdmissionAllowed(existingStates: Iterable<PairingState>): Boolean =
    existingStates.none { it in INCOMPLETE_PAIRING_STATES }

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

internal sealed interface InvocationDecisionResult {
    data object Decided : InvocationDecisionResult

    data object SecretsChanged : InvocationDecisionResult

    data object NotPending : InvocationDecisionResult

    data object NotFound : InvocationDecisionResult

    data class MissingSecrets(val names: List<String>) : InvocationDecisionResult

    data class ConflictingVariable(val name: String) : InvocationDecisionResult

    data class Invalid(val message: String) : InvocationDecisionResult

    data object SecretUnavailable : InvocationDecisionResult

    data object SecretCorrupted : InvocationDecisionResult

    data object UnsupportedEncryption : InvocationDecisionResult

    data object PairingUnavailable : InvocationDecisionResult

    data object TemporaryAccessUnavailable : InvocationDecisionResult

    data object TemporaryAccessNotStarted : InvocationDecisionResult
}

internal sealed interface GitSignDecisionResult {
    data object Decided : GitSignDecisionResult
    data object NotPending : GitSignDecisionResult
    data object NotFound : GitSignDecisionResult
    data object InvocationUnavailable : GitSignDecisionResult
    data object PairingUnavailable : GitSignDecisionResult
    data object ApprovalChanged : GitSignDecisionResult
    data object KeyChanged : GitSignDecisionResult
    data object SecretUnavailable : GitSignDecisionResult
    data object SecretCorrupted : GitSignDecisionResult
    data object UnsupportedEncryption : GitSignDecisionResult
    data object TemporaryAccessUnavailable : GitSignDecisionResult
    data object TemporaryAccessNotStarted : GitSignDecisionResult
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
    private val secrets: SecretRepository,
    private val clients: ClientRepository,
    private val secretManagement: SecretManagementRequests,
    private val invocationRequests: InvocationRequests,
    private val approvalReviewer: RelayApprovalReviewClient,
    private val relay: RelayDeviceClient,
    private val aiReviews: AiReviewCoordinator,
    private val scheduleSynchronization: () -> Unit,
    private val audit: AuditSink,
    private val requestPushRegistration: () -> Unit,
    private val sshKeys: SshKeyCodec = SshKeyCodec(),
    private val pairingProtocol: PairingProtocol = PairingProtocol(),
    private val pairedRequestProtocol: PairedRequestProtocol = PairedRequestProtocol(),
    private val invocationProtocol: InvocationProtocol = InvocationProtocol(),
    private val gitSignProtocol: GitSignProtocol = GitSignProtocol(),
    private val sshAuthenticationProtocol: SshAuthenticationProtocol = SshAuthenticationProtocol(),
    private val pairingRemoveProtocol: PairingRemoveProtocol = PairingRemoveProtocol(),
    private val json: Json = Json,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val operationMutex = Mutex()
    private val pendingChanges = Channel<Unit>(Channel.CONFLATED)
    private val _pushRegistrationState = MutableStateFlow<RelayPushRegistrationState?>(null)

    val pushRegistrationState: StateFlow<RelayPushRegistrationState?> =
        _pushRegistrationState.asStateFlow()

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
        dao.getUnacknowledgedResponses().isNotEmpty() ||
            dao.getUnsettledRequests().any { it.requestAcknowledged } ||
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
        val awaitingResponses = mutableSetOf<String>()
        val awaitingStates = mutableSetOf<String>()
        flushPendingChanges(
            credentials = credentials,
            connection = connection,
            awaitingClientStates = awaitingClientStates,
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
                    connection.events.onReceive { SynchronizationInput.Event(it) }
                    pendingChanges.onReceive { SynchronizationInput.PendingChanges }
                }
            } else {
                SynchronizationInput.Event(connection.events.receive())
            }
            if (input == SynchronizationInput.PendingChanges) {
                flushPendingChanges(
                    credentials = credentials,
                    connection = connection,
                    awaitingClientStates = awaitingClientStates,
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
                            if (event.kind == RelayMessageKind.REQUEST) {
                                dao.markRequestAcknowledged(event.requestId)
                            } else {
                                dao.markCompletionAcknowledged(event.requestId)
                            }
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
                            awaitingResponses += event.requestId
                        }
                        null
                    }
                    is RelayDeviceEvent.Acknowledgement -> {
                        if (event.kind == RelayMessageKind.RESPONSE) {
                            dao.markResponseAcknowledged(event.requestId)
                            awaitingResponses -= event.requestId
                        }
                        null
                    }
                    is RelayDeviceEvent.Receipt -> {
                        if (event.kind == RelayMessageKind.RESPONSE) {
                            dao.markResponseAcknowledged(event.requestId)
                            awaitingResponses -= event.requestId
                        }
                        null
                    }
                    is RelayDeviceEvent.ClientState -> {
                        applyClientState(event)
                        // Every state event is an authoritative answer to the outstanding
                        // mutation. If it differs from the desired state, applyClientState keeps
                        // that desire and queues a new pass; a terminal REVOKED state clears it.
                        awaitingClientStates -= event.clientId
                        null
                    }
                    is RelayDeviceEvent.PushRegistration -> {
                        _pushRegistrationState.value = event.state
                        if (event.state != RelayPushRegistrationState.REGISTERED) {
                            requestPushRegistration()
                        }
                        null
                    }
                    is RelayDeviceEvent.State -> {
                        applyRelayState(event)
                        awaitingStates -= event.requestId
                        if (event.response != RelayMessageState.ABSENT) {
                            awaitingResponses -= event.requestId
                        }
                        null
                    }
                    is RelayDeviceEvent.Inactive -> {
                        awaitingStates -= event.requestId
                        if (event.kind == RelayMessageKind.RESPONSE || event.kind == null) {
                            dao.markResponseAcknowledged(event.requestId)
                            awaitingResponses -= event.requestId
                        }
                        null
                    }
                    is RelayDeviceEvent.Error -> if (event.retryable) {
                        RequestSyncResult.RelayUnavailable(
                            message = event.message,
                            retryAfterMillis = event.retryAfterMillis,
                        )
                    } else {
                        RequestSyncResult.RelayRejected(0, event.message)
                    }
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
        awaitingResponses: MutableSet<String>,
        awaitingStates: MutableSet<String>,
    ): RequestSyncResult? {
        while (pendingChanges.tryReceive().isSuccess) {
            // Changes made after this drain remain queued for the next pass.
        }
        return operationMutex.withLock {
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
                if (awaitingClientStates[attempt.clientId] == desired) continue
                if (!connection.send(RelayDeviceFrame.SetClientState(attempt.clientId, desired))) {
                    return@withLock RequestSyncResult.RelayUnavailable(
                        "Could not send relay client state.",
                    )
                }
                awaitingClientStates[attempt.clientId] = desired
            }

            for (client in dao.getClients()) {
                if (client.deviceIdentityId != credentials.deviceIdentityId) continue
                val desired = client.desiredRelayClientState?.toRelayClientState() ?: continue
                if (client.relayClientState == desired.wireName) continue
                if (awaitingClientStates[client.clientId] == desired) continue
                if (!connection.send(RelayDeviceFrame.SetClientState(client.clientId, desired))) {
                    return@withLock RequestSyncResult.RelayUnavailable(
                        "Could not send relay client state.",
                    )
                }
                awaitingClientStates[client.clientId] = desired
            }

            for (request in dao.getUnacknowledgedResponses()) {
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
                awaitingResponses += request.id
            }

            for (request in dao.getUnsettledRequests()) {
                if (!request.requestAcknowledged) continue
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
                awaitingStates += request.id
            }
            null
        }
    }

    private fun initialSynchronizationComplete(
        caughtUp: Boolean,
        awaitingClientStates: Map<String, RelayClientState>,
        awaitingResponses: Set<String>,
        awaitingStates: Set<String>,
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
        val attempt = dao.getPairingAttemptByClientId(event.clientId)
        if (attempt != null) {
            val activated = event.state == RelayClientState.ACTIVE &&
                attempt.state == PairingState.RELAY_ACTIVATION_PENDING.storedName
            val updatedAttempt = attempt.copy(
                state = if (activated) {
                    PairingState.WAITING_FOR_FINISH.storedName
                } else {
                    attempt.state
                },
                relayClientState = event.state.wireName,
                desiredRelayClientState = if (event.state == RelayClientState.REVOKED) {
                    null
                } else {
                    attempt.desiredRelayClientState?.takeUnless { it == event.state.wireName }
                },
            )
            dao.updatePairingAttempt(updatedAttempt)
            if (
                updatedAttempt.desiredRelayClientState != null &&
                updatedAttempt.desiredRelayClientState != updatedAttempt.relayClientState
            ) {
                requestSync()
            }
        }
    }

    private suspend fun applyRelayState(event: RelayDeviceEvent.State) {
        val now = currentTimeMillis()
        if (event.request == RelayMessageState.DELIVERED ||
            event.request == RelayMessageState.DISCARDED
        ) {
            dao.markRequestAcknowledged(event.requestId)
        }
        if (event.response == RelayMessageState.ACCEPTED ||
            event.response == RelayMessageState.DELIVERED ||
            event.response == RelayMessageState.DISCARDED
        ) {
            dao.markResponseAcknowledged(event.requestId)
        }
        if (event.response == RelayMessageState.DELIVERED) {
            val request = dao.getRequestById(event.requestId)
            if (request?.kind == RequestKind.PAIRING_REMOVE.storedName) {
                completePairingRemoval(request, completion = null)
            }
        }
        if (event.completion == RelayMessageState.DELIVERED ||
            event.completion == RelayMessageState.DISCARDED
        ) {
            dao.markCompletionAcknowledged(event.requestId)
        }
        if (event.exchange == RelayExchangeState.EXPIRED) {
            expireRequest(event.requestId, now)
        }
    }

    private suspend fun expireRequest(relayRequestId: String, now: Long) {
        val request = dao.getRequestById(relayRequestId) ?: return
        if (request.completedAt != null) return
        val message = "The relay exchange expired before it completed."
        when (request.kind) {
            RequestKind.PAIRING.storedName -> {
                val pairing = dao.getPairingAttempt(request.id) ?: return
                if (pairing.state == PairingState.RECEIVING.storedName) {
                    dao.updatePairingRequest(
                        request.copy(error = message),
                        pairing,
                    )
                }
            }
            RequestKind.SECRET_USE.storedName -> {
                invocationRequests.expire(request, message, now)
            }
            RequestKind.GIT_SIGN.storedName -> {
                val gitSign = dao.getGitSignRequest(request.id) ?: return
                dao.updateGitSignRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        error = message,
                        completedAt = now,
                    ),
                    gitSign,
                )
            }
            RequestKind.SSH_AUTHENTICATE.storedName -> {
                val authentication = dao.getSshAuthenticationRequest(request.id) ?: return
                dao.updateSshAuthenticationRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        error = message,
                        completedAt = now,
                    ),
                    authentication,
                )
            }
            RequestKind.SECRET_LIST.storedName -> {
                secretManagement.expireSecretList(request, message, now)
            }
            RequestKind.SECRET_UPLOAD.storedName -> {
                secretManagement.expireSecretUpload(request, message, now)
            }
            RequestKind.PAIRING_REMOVE.storedName -> {
                dao.updateRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        error = message,
                        completedAt = now,
                    ),
                )
                audit.record(
                    AuditRecord(
                        type = AuditEventType.CLIENT_REMOVAL_UNCONFIRMED,
                        outcome = AuditOutcome.FAILED,
                        subject = request.clientNameSnapshot,
                        detail = message,
                        clientId = request.clientId,
                        clientName = request.clientNameSnapshot,
                        relayRequestId = request.id,
                    ),
                )
            }
            RequestKind.PAIRING_FINISH.storedName -> {
                dao.updateRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        error = message,
                        completedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun chooseSas(requestId: String, selectedIndex: Int?): PairingDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId) ?: return PairingDecisionResult.NOT_FOUND
            val pairing = dao.getPairingAttempt(requestId)
                ?: return PairingDecisionResult.NOT_FOUND
            if (pairing.state != PairingState.SAS_VERIFICATION_PENDING.storedName) {
                return PairingDecisionResult.NOT_PENDING
            }
            val verified = selectedIndex != null && selectedIndex == pairing.correctSasIndex
            val now = currentTimeMillis()
            val updatedRequest = request.copy(
                state = if (verified) {
                    InboxRequestState.WAITING.storedName
                } else {
                    InboxRequestState.COMPLETED.storedName
                },
                listed = verified,
                completedAt = if (verified) null else now,
            )
            val updatedPairing = pairing.copy(
                state = if (verified) {
                    PairingState.RELAY_ACTIVATION_PENDING.storedName
                } else {
                    PairingState.REJECTED.storedName
                },
                desiredRelayClientState = if (verified) {
                    RelayClientState.ACTIVE.wireName
                } else {
                    RelayClientState.REVOKED.wireName
                },
                pendingPsk = pairing.pendingPsk.takeIf { verified },
                decidedAt = now,
            )
            if (verified) {
                dao.updatePairingRequest(updatedRequest, updatedPairing)
            } else {
                dao.rejectPairing(updatedRequest, updatedPairing)
            }
            audit.record(
                AuditRecord(
                    type = AuditEventType.PAIRING_DECIDED,
                    outcome = if (verified) AuditOutcome.APPROVED else AuditOutcome.REJECTED,
                    decisionSource = AuditDecisionSource.USER,
                    subject = pairing.auditClientName(),
                    clientId = pairing.clientId,
                    clientName = pairing.auditClientName(),
                    relayRequestId = request.id,
                ),
            )
            if (verified) PairingDecisionResult.VERIFIED else PairingDecisionResult.REJECTED
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
            val pairing = dao.getPairingAttempt(requestId) ?: return@withLock false
            pairing.state == PairingState.SAS_VERIFICATION_PENDING.storedName &&
                selectedIndex == pairing.correctSasIndex
        }

    suspend fun rejectPairing(requestId: String): PairingDecisionResult = operationMutex.withLock {
        val request = dao.getRequestById(requestId) ?: return PairingDecisionResult.NOT_FOUND
        val pairing = dao.getPairingAttempt(requestId) ?: return PairingDecisionResult.NOT_FOUND
        if (pairing.state.toPairingState() !in USER_REJECTABLE_PAIRING_STATES) {
            return PairingDecisionResult.NOT_PENDING
        }
        val now = currentTimeMillis()
        dao.rejectPairing(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                listed = false,
                completedAt = now,
            ),
            attempt = pairing.copy(
                state = PairingState.REJECTED.storedName,
                desiredRelayClientState = RelayClientState.REVOKED.wireName,
                pendingPsk = null,
                decidedAt = pairing.decidedAt ?: now,
            ),
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.PAIRING_DECIDED,
                outcome = AuditOutcome.REJECTED,
                decisionSource = AuditDecisionSource.USER,
                subject = pairing.auditClientName(),
                clientId = pairing.clientId,
                clientName = pairing.auditClientName(),
                relayRequestId = request.id,
            ),
        )
        PairingDecisionResult.REJECTED
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
            openRequest = ::openStoredInvocationRequest,
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

    private suspend fun openStoredInvocationRequest(
        request: InboxRequestEntity,
    ): InvocationRequestMessage? {
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
        return runCatching {
            invocationProtocol.decodeRequest(opened.plaintext)
        }.getOrNull()
    }

    suspend fun approveGitSignRequest(requestId: String): GitSignDecisionResult =
        approveGitSignRequest(requestId, allowTemporaryAccess = false)

    suspend fun allowGitSignTemporarily(requestId: String): GitSignDecisionResult =
        approveGitSignRequest(requestId, allowTemporaryAccess = true)

    private suspend fun approveGitSignRequest(
        requestId: String,
        allowTemporaryAccess: Boolean,
    ): GitSignDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId) ?: return GitSignDecisionResult.NotFound
            val gitSign = dao.getGitSignRequest(requestId)
                ?: return GitSignDecisionResult.NotFound
            if (
                request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
                gitSign.decision != null
            ) {
                return GitSignDecisionResult.NotPending
            }
            val invocationId = request.parentRequestId
                ?: return GitSignDecisionResult.InvocationUnavailable
            val invocation = dao.getSecretUseRequest(invocationId)
                ?: return GitSignDecisionResult.InvocationUnavailable
            val latestDescription = secrets.describeRequestedSecrets(listOf(gitSign.secretName))
            val policy = secrets.approvalPoliciesForNames(
                listOf(gitSign.secretName),
                request.clientId,
                TemporaryAccessOperation.GIT_SIGN,
            ).singleOrNull() ?: return GitSignDecisionResult.ApprovalChanged
            val currentEvaluation = ApprovalPolicyEvaluator.evaluate(
                listOf(policy.toRequestedSecretApproval()),
            )
            val storedEvaluation = gitSign.approvalEvaluationJson
                ?.let(::decodeApprovalEvaluation)
            if (currentEvaluation.secrets.single().action == ApprovalAction.DENY) {
                return@withLock decideGitSignRequest(
                    request = request,
                    gitSign = gitSign,
                    invocation = invocation,
                    decision = ApprovalDecision.DENIED,
                    responsePlaintext = gitSignProtocol.deniedResponse(
                        InvocationDenialReason.POLICY_DENIED,
                        "Approval settings denied use of the SSH key.",
                    ),
                    decisionSource = DECISION_SOURCE_POLICY,
                )
            }
            if (
                storedEvaluation == null ||
                !storedEvaluation.hasSameSecretPolicies(currentEvaluation)
            ) {
                dao.updateGitSignRequest(
                    request,
                    gitSign.copy(
                        approvalEvaluationJson = json.encodeToString(currentEvaluation),
                    ),
                )
                return GitSignDecisionResult.ApprovalChanged
            }
            val authorization = latestDescription.authorizationCommitment(listOf(policy))
            val expectedPublicKey = storedJson.decodeFromString<List<SecretMetadata>>(
                invocation.secretDetailsJson,
            ).singleOrNull { secret ->
                secret.name == gitSign.secretName && secret.type == SSH_SECRET_TYPE
            }?.sshPublicKey ?: return GitSignDecisionResult.InvocationUnavailable
            val signature = when (
                val result = secrets.signGitMessage(
                    secretName = gitSign.secretName,
                    expectedPublicKey = expectedPublicKey,
                    message = gitSign.message,
                )
            ) {
                is GitSignatureResult.Signed -> result.signature
                GitSignatureResult.NotFound,
                GitSignatureResult.WrongType,
                GitSignatureResult.KeyChanged,
                -> return GitSignDecisionResult.KeyChanged
                GitSignatureResult.SecretUnavailable -> {
                    return GitSignDecisionResult.SecretUnavailable
                }
                GitSignatureResult.UnsupportedEncryption -> {
                    return GitSignDecisionResult.UnsupportedEncryption
                }
                GitSignatureResult.SecretCorrupted,
                -> return GitSignDecisionResult.SecretCorrupted
            }
            val temporaryGrant = if (allowTemporaryAccess) {
                val secretEvaluation = storedEvaluation.secrets.singleOrNull()
                    ?: return GitSignDecisionResult.TemporaryAccessUnavailable
                val aiCanEscalateToTemporaryAccess =
                    storedEvaluation.aiReview?.decision == AiReviewDecision.ASK_USER ||
                        storedEvaluation.aiReview?.failure != null ||
                        storedEvaluation.aiReview == null
                val eligible = secretEvaluation.temporaryAccessExpiresAt == null &&
                    when (policy.mode) {
                        SecretApprovalMode.TEMPORARY ->
                            secretEvaluation.action == ApprovalAction.ASK_ME
                        SecretApprovalMode.ASK_AI ->
                            secretEvaluation.action == ApprovalAction.ASK_AI &&
                                aiCanEscalateToTemporaryAccess
                        else -> false
                    }
                if (!eligible) return GitSignDecisionResult.TemporaryAccessUnavailable
                val expiresAt = currentTimeMillis() + TEMPORARY_ACCESS_DURATION_MILLIS
                TemporaryGrant(
                    policies = listOf(policy),
                    operation = TemporaryAccessOperation.GIT_SIGN,
                    expiresAt = expiresAt,
                    evaluation = storedEvaluation.copy(
                        secrets = listOf(
                            secretEvaluation.copy(temporaryAccessExpiresAt = expiresAt),
                        ),
                    ),
                    decisionSource = DECISION_SOURCE_TEMPORARY_ACCESS,
                )
            } else {
                null
            }
            val decisionResult = decideGitSignRequest(
                request = request,
                gitSign = gitSign,
                invocation = invocation,
                decision = ApprovalDecision.APPROVED,
                responsePlaintext = gitSignProtocol.approvedResponse(signature),
                decisionSource = temporaryGrant?.decisionSource ?: DECISION_SOURCE_USER,
                authorization = authorization,
            )
            if (decisionResult == GitSignDecisionResult.Decided && temporaryGrant != null) {
                val started = runCatchingNonCancellation {
                    secrets.allowTemporaryAccess(
                        policies = temporaryGrant.policies,
                        clientId = request.clientId,
                        operation = temporaryGrant.operation,
                        expiresAt = temporaryGrant.expiresAt,
                    )
                }.getOrDefault(false)
                if (!started) {
                    return@withLock GitSignDecisionResult.TemporaryAccessNotStarted
                }
                val decidedRequest = dao.getRequestById(requestId)
                    ?: return@withLock GitSignDecisionResult.TemporaryAccessNotStarted
                val decidedGitSign = dao.getGitSignRequest(requestId)
                    ?: return@withLock GitSignDecisionResult.TemporaryAccessNotStarted
                dao.updateGitSignRequest(
                    decidedRequest,
                    decidedGitSign.copy(
                        approvalEvaluationJson = json.encodeToString(temporaryGrant.evaluation),
                    ),
                )
            }
            decisionResult
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
            val request = dao.getRequestById(requestId) ?: return GitSignDecisionResult.NotFound
            val gitSign = dao.getGitSignRequest(requestId)
                ?: return GitSignDecisionResult.NotFound
            if (
                request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
                gitSign.decision != null
            ) {
                return GitSignDecisionResult.NotPending
            }
            val invocation = request.parentRequestId?.let { dao.getSecretUseRequest(it) }
                ?: return GitSignDecisionResult.InvocationUnavailable
            decideGitSignRequest(
                request = request,
                gitSign = gitSign,
                invocation = invocation,
                decision = ApprovalDecision.DENIED,
                responsePlaintext = gitSignProtocol.deniedResponse(
                    InvocationDenialReason.USER_DENIED,
                    GIT_SIGN_DENIAL_MESSAGE,
                ),
            )
        }.also { result ->
            if (result == GitSignDecisionResult.Decided) requestSync()
        }

    private suspend fun decideGitSignRequest(
        request: InboxRequestEntity,
        gitSign: GitSignRequestEntity,
        invocation: SecretUseRequestEntity,
        decision: ApprovalDecision,
        responsePlaintext: ByteArray,
        decisionSource: String = DECISION_SOURCE_USER,
        authorization: AuthorizationCommitment? = null,
    ): GitSignDecisionResult {
        require(decision != ApprovalDecision.APPROVED || authorization != null) {
            "An approved Git signature must bind its authorization state"
        }
        val client = dao.getClient(request.clientId)
            ?: return GitSignDecisionResult.PairingUnavailable
        if (
            client.relayClientState != RelayClientState.ACTIVE.wireName ||
            client.desiredRelayClientState?.let { it != RelayClientState.ACTIVE.wireName } == true
        ) {
            return GitSignDecisionResult.PairingUnavailable
        }
        val credentials = credentialsForRequest(request)
            ?: return GitSignDecisionResult.PairingUnavailable
        val clientPsk = material.decryptRequestPsk(request)
            ?: return GitSignDecisionResult.PairingUnavailable
        val response = runCatching {
            pairedRequestProtocol.sealPairedResponse(
                deviceId = credentials.deviceId,
                requestId = request.id,
                clientId = request.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                plaintext = responsePlaintext,
            )
        }.getOrNull() ?: return GitSignDecisionResult.PairingUnavailable
        val now = currentTimeMillis()
        val updatedRequest = request.copy(
            state = InboxRequestState.WAITING.storedName,
            responseJson = response.toString(),
            responseAcknowledged = false,
        )
        val updatedGitSign = gitSign.copy(
            decision = decision.storedName,
            completionReason = if (decision == ApprovalDecision.DENIED) {
                if (decisionSource == DECISION_SOURCE_POLICY) {
                    InvocationDenialReason.POLICY_DENIED.wireName
                } else {
                    InvocationDenialReason.USER_DENIED.wireName
                }
            } else {
                null
            },
            completionMessage = if (decision == ApprovalDecision.DENIED) {
                if (decisionSource == DECISION_SOURCE_POLICY) {
                    "Approval settings denied use of the SSH key."
                } else {
                    GIT_SIGN_DENIAL_MESSAGE
                }
            } else {
                null
            },
            decidedAt = now,
        )
        val persisted = if (decision == ApprovalDecision.APPROVED) {
            dao.updateGitSignRequestIfAuthorized(
                request = updatedRequest,
                gitSignRequest = updatedGitSign,
                authorization = checkNotNull(authorization),
                clientId = request.clientId,
                operation = TemporaryAccessOperation.GIT_SIGN.storedName,
                now = now,
            ) == ConditionalRequestUpdate.APPLIED
        } else {
            dao.updateGitSignRequest(updatedRequest, updatedGitSign)
            true
        }
        if (!persisted) return GitSignDecisionResult.ApprovalChanged
        audit.record(
            AuditRecord(
                type = AuditEventType.GIT_SIGN_DECIDED,
                outcome = if (decision == ApprovalDecision.APPROVED) {
                    AuditOutcome.APPROVED
                } else {
                    AuditOutcome.DENIED
                },
                decisionSource = decisionSource.toAuditDecisionSource(),
                subject = gitSign.secretName,
                clientId = request.clientId,
                clientName = request.clientNameSnapshot,
                relayRequestId = request.id,
            ),
        )
        return GitSignDecisionResult.Decided
    }

    suspend fun approveSshAuthenticationRequest(
        requestId: String,
    ): SshAuthenticationDecisionResult =
        approveSshAuthenticationRequest(requestId, allowTemporaryAccess = false)

    suspend fun allowSshAuthenticationTemporarily(
        requestId: String,
    ): SshAuthenticationDecisionResult =
        approveSshAuthenticationRequest(requestId, allowTemporaryAccess = true)

    private suspend fun approveSshAuthenticationRequest(
        requestId: String,
        allowTemporaryAccess: Boolean,
    ): SshAuthenticationDecisionResult = operationMutex.withLock {
        val request = dao.getRequestById(requestId)
            ?: return SshAuthenticationDecisionResult.NotFound
        val authentication = dao.getSshAuthenticationRequest(requestId)
            ?: return SshAuthenticationDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            authentication.decision != null
        ) {
            return SshAuthenticationDecisionResult.NotPending
        }
        val invocationId = request.parentRequestId
            ?: return SshAuthenticationDecisionResult.InvocationUnavailable
        val invocation = dao.getSecretUseRequest(invocationId)
            ?: return SshAuthenticationDecisionResult.InvocationUnavailable
        val description = secrets.describeRequestedSecrets(listOf(authentication.secretName))
        val policy = secrets.approvalPoliciesForNames(
            listOf(authentication.secretName),
            request.clientId,
            TemporaryAccessOperation.SSH_AUTHENTICATE,
        ).singleOrNull() ?: return SshAuthenticationDecisionResult.ApprovalChanged
        val currentEvaluation = ApprovalPolicyEvaluator.evaluate(
            listOf(policy.toRequestedSecretApproval()),
        )
        val storedEvaluation = authentication.approvalEvaluationJson
            ?.let(::decodeApprovalEvaluation)
        if (currentEvaluation.secrets.single().action == ApprovalAction.DENY) {
            return@withLock decideSshAuthenticationRequest(
                request = request,
                authentication = authentication,
                invocation = invocation,
                decision = ApprovalDecision.DENIED,
                responsePlaintext = sshAuthenticationProtocol.deniedResponse(
                    InvocationDenialReason.POLICY_DENIED,
                    "Approval settings denied SSH authentication.",
                ),
                decisionSource = DECISION_SOURCE_POLICY,
            )
        }
        if (storedEvaluation == null || !storedEvaluation.hasSameSecretPolicies(currentEvaluation)) {
            dao.updateSshAuthenticationRequest(
                request,
                authentication.copy(
                    approvalEvaluationJson = json.encodeToString(currentEvaluation),
                ),
            )
            return SshAuthenticationDecisionResult.ApprovalChanged
        }
        val expectedPublicKey = storedJson.decodeFromString<List<SecretMetadata>>(
            invocation.secretDetailsJson,
        ).singleOrNull { secret ->
            secret.name == authentication.secretName && secret.type == SSH_SECRET_TYPE
        }?.sshPublicKey ?: return SshAuthenticationDecisionResult.InvocationUnavailable
        val publicKey = runCatching {
            sshKeys.importOpenSshPublicKey(expectedPublicKey)
        }.getOrElse { return SshAuthenticationDecisionResult.KeyChanged }
        val parsed = runCatching {
            sshAuthenticationProtocol.validateMessage(
                authentication.message,
                publicKey.blob(),
                publicKey.algorithm.publicName,
            )
        }.getOrElse { return SshAuthenticationDecisionResult.InvalidMessage }
        if (
            parsed.username != authentication.username ||
            parsed.method.wireName != authentication.method ||
            parsed.algorithm.wireName != authentication.algorithm ||
            parsed.hostKeyAlgorithm != authentication.hostKeyAlgorithm ||
            parsed.hostKeyFingerprint != authentication.hostKeyFingerprint
        ) {
            return SshAuthenticationDecisionResult.InvalidMessage
        }
        val signature = when (
            val result = secrets.signSshAuthentication(
                secretName = authentication.secretName,
                expectedPublicKey = expectedPublicKey,
                message = authentication.message,
                algorithm = parsed.algorithm,
            )
        ) {
            is SshAuthenticationSignatureResult.Signed -> result.signature
            SshAuthenticationSignatureResult.NotFound,
            SshAuthenticationSignatureResult.WrongType,
            SshAuthenticationSignatureResult.KeyChanged,
            -> return SshAuthenticationDecisionResult.KeyChanged
            SshAuthenticationSignatureResult.SecretUnavailable ->
                return SshAuthenticationDecisionResult.SecretUnavailable
            SshAuthenticationSignatureResult.UnsupportedEncryption ->
                return SshAuthenticationDecisionResult.UnsupportedEncryption
            SshAuthenticationSignatureResult.SecretCorrupted ->
                return SshAuthenticationDecisionResult.SecretCorrupted
        }
        val temporaryGrant = if (allowTemporaryAccess) {
            val secretEvaluation = storedEvaluation.secrets.singleOrNull()
                ?: return SshAuthenticationDecisionResult.TemporaryAccessUnavailable
            val aiCanEscalateToTemporaryAccess =
                storedEvaluation.aiReview?.decision == AiReviewDecision.ASK_USER ||
                    storedEvaluation.aiReview?.failure != null ||
                    storedEvaluation.aiReview == null
            val eligible = secretEvaluation.temporaryAccessExpiresAt == null &&
                when (policy.mode) {
                    SecretApprovalMode.TEMPORARY ->
                        secretEvaluation.action == ApprovalAction.ASK_ME
                    SecretApprovalMode.ASK_AI ->
                        secretEvaluation.action == ApprovalAction.ASK_AI &&
                            aiCanEscalateToTemporaryAccess
                    else -> false
                }
            if (!eligible) return SshAuthenticationDecisionResult.TemporaryAccessUnavailable
            val expiresAt = currentTimeMillis() + TEMPORARY_ACCESS_DURATION_MILLIS
            TemporaryGrant(
                policies = listOf(policy),
                operation = TemporaryAccessOperation.SSH_AUTHENTICATE,
                expiresAt = expiresAt,
                evaluation = storedEvaluation.copy(
                    secrets = listOf(
                        secretEvaluation.copy(temporaryAccessExpiresAt = expiresAt),
                    ),
                ),
                decisionSource = DECISION_SOURCE_TEMPORARY_ACCESS,
            )
        } else {
            null
        }
        val result = decideSshAuthenticationRequest(
            request = request,
            authentication = authentication,
            invocation = invocation,
            decision = ApprovalDecision.APPROVED,
            responsePlaintext = sshAuthenticationProtocol.approvedResponse(signature),
            decisionSource = temporaryGrant?.decisionSource ?: DECISION_SOURCE_USER,
            authorization = description.authorizationCommitment(listOf(policy)),
        )
        if (result == SshAuthenticationDecisionResult.Decided && temporaryGrant != null) {
            val started = runCatchingNonCancellation {
                secrets.allowTemporaryAccess(
                    policies = temporaryGrant.policies,
                    clientId = request.clientId,
                    operation = temporaryGrant.operation,
                    expiresAt = temporaryGrant.expiresAt,
                )
            }.getOrDefault(false)
            if (!started) return@withLock SshAuthenticationDecisionResult.TemporaryAccessNotStarted
            val decidedRequest = dao.getRequestById(requestId)
                ?: return@withLock SshAuthenticationDecisionResult.TemporaryAccessNotStarted
            val decidedAuthentication = dao.getSshAuthenticationRequest(requestId)
                ?: return@withLock SshAuthenticationDecisionResult.TemporaryAccessNotStarted
            dao.updateSshAuthenticationRequest(
                decidedRequest,
                decidedAuthentication.copy(
                    approvalEvaluationJson = json.encodeToString(temporaryGrant.evaluation),
                ),
            )
        }
        result
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
        val request = dao.getRequestById(requestId)
            ?: return SshAuthenticationDecisionResult.NotFound
        val authentication = dao.getSshAuthenticationRequest(requestId)
            ?: return SshAuthenticationDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            authentication.decision != null
        ) {
            return SshAuthenticationDecisionResult.NotPending
        }
        val invocation = request.parentRequestId?.let { dao.getSecretUseRequest(it) }
            ?: return SshAuthenticationDecisionResult.InvocationUnavailable
        decideSshAuthenticationRequest(
            request = request,
            authentication = authentication,
            invocation = invocation,
            decision = ApprovalDecision.DENIED,
            responsePlaintext = sshAuthenticationProtocol.deniedResponse(
                InvocationDenialReason.USER_DENIED,
                SSH_AUTHENTICATION_DENIAL_MESSAGE,
            ),
        )
    }.also { result ->
        if (result == SshAuthenticationDecisionResult.Decided) requestSync()
    }

    private suspend fun decideSshAuthenticationRequest(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
        invocation: SecretUseRequestEntity,
        decision: ApprovalDecision,
        responsePlaintext: ByteArray,
        decisionSource: String = DECISION_SOURCE_USER,
        authorization: AuthorizationCommitment? = null,
    ): SshAuthenticationDecisionResult {
        require(decision != ApprovalDecision.APPROVED || authorization != null) {
            "Approved SSH authentication must bind its authorization state"
        }
        val client = dao.getClient(request.clientId)
            ?: return SshAuthenticationDecisionResult.PairingUnavailable
        if (
            client.relayClientState != RelayClientState.ACTIVE.wireName ||
            client.desiredRelayClientState?.let { it != RelayClientState.ACTIVE.wireName } == true
        ) {
            return SshAuthenticationDecisionResult.PairingUnavailable
        }
        val credentials = credentialsForRequest(request)
            ?: return SshAuthenticationDecisionResult.PairingUnavailable
        val clientPsk = material.decryptRequestPsk(request)
            ?: return SshAuthenticationDecisionResult.PairingUnavailable
        val response = runCatching {
            pairedRequestProtocol.sealPairedResponse(
                deviceId = credentials.deviceId,
                requestId = request.id,
                clientId = request.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                plaintext = responsePlaintext,
            )
        }.getOrNull() ?: return SshAuthenticationDecisionResult.PairingUnavailable
        val now = currentTimeMillis()
        val updatedRequest = request.copy(
            state = InboxRequestState.WAITING.storedName,
            responseJson = response.toString(),
            responseAcknowledged = false,
        )
        val reason = when {
            decision != ApprovalDecision.DENIED -> null
            decisionSource == DECISION_SOURCE_POLICY -> InvocationDenialReason.POLICY_DENIED.wireName
            else -> InvocationDenialReason.USER_DENIED.wireName
        }
        val message = when {
            decision != ApprovalDecision.DENIED -> null
            decisionSource == DECISION_SOURCE_POLICY ->
                "Approval settings denied SSH authentication."
            else -> SSH_AUTHENTICATION_DENIAL_MESSAGE
        }
        val updatedAuthentication = authentication.copy(
            decision = decision.storedName,
            completionReason = reason,
            completionMessage = message,
            decidedAt = now,
        )
        val persisted = if (decision == ApprovalDecision.APPROVED) {
            dao.updateSshAuthenticationRequestIfAuthorized(
                request = updatedRequest,
                authentication = updatedAuthentication,
                authorization = checkNotNull(authorization),
                clientId = request.clientId,
                operation = TemporaryAccessOperation.SSH_AUTHENTICATE.storedName,
                now = now,
            ) == ConditionalRequestUpdate.APPLIED
        } else {
            dao.updateSshAuthenticationRequest(updatedRequest, updatedAuthentication)
            true
        }
        if (!persisted) return SshAuthenticationDecisionResult.ApprovalChanged
        audit.record(
            AuditRecord(
                type = AuditEventType.SSH_AUTHENTICATION_DECIDED,
                outcome = if (decision == ApprovalDecision.APPROVED) {
                    AuditOutcome.APPROVED
                } else {
                    AuditOutcome.DENIED
                },
                decisionSource = decisionSource.toAuditDecisionSource(),
                subject = authentication.secretName,
                context = authentication.username,
                detail = message,
                clientId = request.clientId,
                clientName = request.clientNameSnapshot,
                relayRequestId = request.id,
            ),
        )
        return SshAuthenticationDecisionResult.Decided
    }

    private suspend fun processRequest(
        credentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
    ): ProcessedRelayMessage? {
        val requestPayload = message.request ?: return null
        var existing = dao.getRequestById(message.requestId)
        if (existing != null) {
            if (
                existing.clientId != message.clientId ||
                existing.deviceIdentityId != credentials.deviceIdentityId ||
                existing.requestJson != requestPayload.toString()
            ) {
                return null
            }
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
        if (!pairingProtocol.validateFreshRequestId(message.requestId, now)) return null
        if (message.addressId != null) {
            if (!pairingProtocol.isInitialRequest(requestPayload)) return null
            return startPairing(credentials, message, requestPayload)
        }

        val client = dao.getClient(message.clientId)
        if (client != null) {
            return processActiveClientRequest(credentials, message, requestPayload, client, now)
        }
        val attempt = dao.getPairingAttemptByClientId(message.clientId) ?: return null
        val rootRequest = dao.getRequestById(attempt.requestId) ?: return null
        if (rootRequest.deviceIdentityId != credentials.deviceIdentityId) return null
        if (
            attempt.state != PairingState.RELAY_ACTIVATION_PENDING.storedName &&
            attempt.state != PairingState.WAITING_FOR_FINISH.storedName
        ) {
            return null
        }
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
        val acceptedPsks = AcceptedRequestPsks(
            requestPsk = material.encryptRequestPsk(
                deviceIdentityId = rootRequest.deviceIdentityId,
                clientId = rootRequest.clientId,
                relayRequestId = message.requestId,
                clientPsk = opened.clientPsk,
            ),
            currentClientPsk = null,
            previousClientPsk = null,
        )
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
            credentials = credentials,
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
                credentials = credentials,
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
                credentials = credentials,
                code = PairedRequestErrorCode.INVALID_STATE,
                now = now,
            )
        }
        val processed = if (method == InvocationProtocol.METHOD) {
            processInvocationRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
            )
        } else if (method == GitSignProtocol.METHOD) {
            processGitSignRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
            )
        } else if (method == SshAuthenticationProtocol.METHOD) {
            processSshAuthenticationRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
            )
        } else if (method == SecretListProtocol.METHOD) {
            processSecretListRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
            )
        } else if (method == SecretUploadProtocol.METHOD) {
            processSecretUploadRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
            )
        } else if (method == PairingRemoveProtocol.METHOD) {
            processPairingRemoveRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
            )
        } else {
            return rejectAuthenticatedRequest(
                pairing = client,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = acceptedSecrets,
                credentials = credentials,
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
            credentials = credentials,
            code = PairedRequestErrorCode.INVALID_REQUEST,
            now = now,
        )
    }

    private suspend fun processInvocationRequest(
        pairing: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        val contents = runCatching {
            invocationProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val environmentSelections = contents.environmentSelections()
        val resolution = secrets.resolveRequestedSecrets(contents.secrets, environmentSelections)
        val description = resolution.description
        val requestedSecrets = resolution.values
        val automaticDenial = automaticSecretUseDenial(requestedSecrets)
        val protectedSecretNames = description.reviewMetadata
            .filter { secret ->
                secret.type == ENVIRONMENT_SECRET_TYPE &&
                    secret.environmentVariables.any { it.sensitive }
            }
            .map { it.name }
        val approvalPolicies = if (automaticDenial == null) {
            secrets.approvalPoliciesForNames(
                protectedSecretNames,
                pairing.clientId,
                TemporaryAccessOperation.INVOCATION,
            )
        } else {
            emptyList()
        }
        val initialApprovalEvaluation = if (automaticDenial == null && protectedSecretNames.isNotEmpty()) {
            val approvals = approvalPolicies.map { it.toRequestedSecretApproval() }
            if (approvals.size == protectedSecretNames.distinct().size) {
                ApprovalPolicyEvaluator.evaluate(approvals)
            } else {
                null
            }
        } else {
            null
        }
        val needsAiReview = initialApprovalEvaluation?.requiresAiReview() == true
        val initialAvailableSecrets =
            (requestedSecrets as? RequestedSecretsResult.Available)?.secrets
        val initialProvidedSecretsJson = initialAvailableSecrets?.let { values ->
            json.encodeToString(approvalReviewSecretFacts(description, values))
        }
        val now = currentTimeMillis()
        val initialRequest = InboxRequestEntity(
            id = relayRequestId,
            parentRequestId = null,
            deviceIdentityId = pairing.deviceIdentityId,
            clientId = pairing.clientId,
            clientNameSnapshot = pairing.name,
            clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
            kind = RequestKind.SECRET_USE.storedName,
            state = if (needsAiReview) {
                InboxRequestState.REVIEWING.storedName
            } else {
                InboxRequestState.ACTION_REQUIRED.storedName
            },
            listed = true,
            requestJson = requestPayload.toString(),
            responseJson = null,
            completionJson = null,
            error = null,
            receivedAt = now,
            completedAt = null,
            requestAcknowledged = false,
            responseAcknowledged = false,
            completionAcknowledged = false,
        )
        val initialSecretUse = secretUseRequestEntity(
            requestId = relayRequestId,
            pairing = pairing,
            contents = contents,
            description = description,
            now = now,
        ).copy(
            approvalEvaluationJson = initialApprovalEvaluation?.let { json.encodeToString(it) },
            // Save the safe display snapshot before a potentially long AI call. It contains
            // metadata, public SSH material, and values explicitly marked non-sensitive only.
            providedSecretsJson = initialProvidedSecretsJson,
        )
        suspend fun finishReview(
            reviewResult: AiReview?,
            requestAlreadyInserted: Boolean,
        ): ProcessedRelayMessage? {
            val currentClient = if (needsAiReview) dao.getClient(pairing.clientId) else pairing
            val clientUnavailable = currentClient == null ||
                currentClient.deviceIdentityId != pairing.deviceIdentityId ||
                currentClient.relayClientState == RelayClientState.REVOKED.wireName ||
                currentClient.desiredRelayClientState == RelayClientState.REVOKED.wireName
            val currentResolution = if (needsAiReview) {
                secrets.resolveRequestedSecrets(contents.secrets, environmentSelections)
            } else {
                resolution
            }
            val currentDescription = currentResolution.description
            val currentRequestedSecrets = currentResolution.values
            val currentAutomaticDenial = automaticSecretUseDenial(currentRequestedSecrets)
            val availableSecrets =
                (currentRequestedSecrets as? RequestedSecretsResult.Available)?.secrets
            val providedSecretsJson = availableSecrets?.let { values ->
                json.encodeToString(approvalReviewSecretFacts(currentDescription, values))
            }
            val currentProtectedSecretNames = currentDescription.reviewMetadata
                .filter { secret ->
                    secret.type == ENVIRONMENT_SECRET_TYPE &&
                        secret.environmentVariables.any { it.sensitive }
                }
                .map { it.name }
            val currentApprovalPolicies = if (needsAiReview) {
                secrets.approvalPoliciesForNames(
                    currentProtectedSecretNames,
                    pairing.clientId,
                    TemporaryAccessOperation.INVOCATION,
                )
            } else {
                approvalPolicies
            }
            val currentApprovalEvaluation = if (needsAiReview) {
                ApprovalPolicyEvaluator.evaluate(
                    currentApprovalPolicies.map { it.toRequestedSecretApproval() },
                )
            } else {
                initialApprovalEvaluation
            }
            val currentCredentials = if (needsAiReview) {
                currentClient?.let { credentialsForClient(it) }
            } else {
                credentials
            }
            val aiInputsChanged = needsAiReview && (
                clientUnavailable ||
                    currentAutomaticDenial != null ||
                    !description.hasSameSecretRevisions(currentDescription) ||
                    currentApprovalEvaluation == null ||
                    !checkNotNull(initialApprovalEvaluation)
                        .hasSameSecretPolicies(currentApprovalEvaluation) ||
                    currentCredentials?.instructions != credentials.instructions ||
                    currentClient.name != pairing.name ||
                    currentClient.instructions != pairing.instructions
                )
            val aiReview = if (aiInputsChanged) {
                AiReview(
                    decision = AiReviewDecision.ASK_USER,
                    explanation = "The client, secret, instructions, or approval settings changed during AI review.",
                )
            } else {
                reviewResult
            }
            val approvalEvaluation = (if (aiInputsChanged) {
                currentApprovalEvaluation
            } else {
                initialApprovalEvaluation
            })?.copy(aiReview = aiReview)
            val authorization = currentDescription.authorizationCommitment(
                policies = currentApprovalPolicies,
                instructions = if (needsAiReview) {
                    currentCredentials?.let { current ->
                        AuthorizationInstructionsCommitment(
                            deviceIdentityId = current.deviceIdentityId,
                            deviceInstructions = current.instructions,
                            clientId = checkNotNull(currentClient).clientId,
                            clientName = currentClient.name,
                            clientInstructions = currentClient.instructions,
                        )
                    }
                } else {
                    null
                },
            )
            val policyDenial = approvalEvaluation
                ?.takeIf { evaluation ->
                    evaluation.secrets.any { it.action == ApprovalAction.DENY }
                }
                ?.let {
                    InvocationDenialReason.POLICY_DENIED to
                        "Approval settings denied access to a requested secret."
                }
            val aiDenial = aiReview
                ?.takeIf { it.decision == AiReviewDecision.DENY }
                ?.let {
                    InvocationDenialReason.POLICY_DENIED to
                        "AI review denied access to a requested secret."
                }
            val aiApproved = aiReview?.decision == AiReviewDecision.APPROVE
            val allProtectedUsesApproved = !aiInputsChanged && approvalEvaluation
                ?.isFullyApproved(aiReview?.decision) == true
            val temporaryAccessUsed = approvalEvaluation?.secrets
                ?.any { it.temporaryAccessExpiresAt != null } == true
            val aiApprovalUsed = approvalEvaluation?.secrets
                ?.any { it.action == ApprovalAction.ASK_AI } == true && aiApproved
            val clientDenial = if (clientUnavailable) {
                InvocationDenialReason.OTHER to
                    "The paired client is no longer available."
            } else {
                null
            }
            val denial = currentAutomaticDenial ?: clientDenial ?: policyDenial ?: aiDenial
            val responsePlaintext = when {
                denial != null -> invocationProtocol.deniedResponse(denial.first, denial.second)
                allProtectedUsesApproved -> {
                    invocationProtocol.approvedResponse(
                        checkNotNull(availableSecrets).mapValues { (_, secret) ->
                            secret.toResponseSecret()
                        },
                    )
                }
                !currentDescription.containsSensitiveMaterial -> {
                    invocationProtocol.approvedResponse(
                        checkNotNull(availableSecrets).mapValues { (_, secret) ->
                            secret.toResponseSecret()
                        },
                    )
                }
                else -> null
            }
            val response = if (responsePlaintext != null) {
                runCatching {
                    pairedRequestProtocol.sealPairedResponse(
                    deviceId = credentials.deviceId,
                        requestId = relayRequestId,
                        clientId = pairing.clientId,
                        clientPsk = opened.clientPsk,
                        devicePrivateKey = credentials.devicePrivateKey,
                        devicePublicKey = credentials.devicePublicKey,
                        request = requestPayload,
                        plaintext = responsePlaintext,
                    )
                }.getOrNull() ?: return null
            } else {
                null
            }
            val automaticDecision = when {
                denial != null -> ApprovalDecision.DENIED
                allProtectedUsesApproved -> ApprovalDecision.APPROVED
                !currentDescription.containsSensitiveMaterial -> ApprovalDecision.APPROVED
                else -> null
            }
            val decidedAt = currentTimeMillis()
            val requestToUpdate = if (requestAlreadyInserted) {
                dao.getRequestById(relayRequestId) ?: return null
            } else {
                initialRequest
            }
            val finalRequest = requestToUpdate.copy(
                state = reviewedRequestState(response != null).storedName,
                responseJson = response?.toString(),
            )
            val currentSecretUse = if (aiInputsChanged) {
                secretUseRequestEntity(
                    requestId = relayRequestId,
                    pairing = pairing,
                    contents = contents,
                    description = currentDescription,
                    now = now,
                )
            } else {
                initialSecretUse
            }
            val finalSecretUse = if (automaticDecision == null) {
                currentSecretUse.copy(
                    approvalEvaluationJson = approvalEvaluation?.let { json.encodeToString(it) },
                    providedSecretsJson = providedSecretsJson,
                )
            } else {
                currentSecretUse.copy(
                    decision = automaticDecision.storedName,
                    decisionSource = if (aiDenial != null) {
                        DECISION_SOURCE_AI
                    } else if (policyDenial != null) {
                        DECISION_SOURCE_POLICY
                    } else if (temporaryAccessUsed && aiApprovalUsed) {
                        DECISION_SOURCE_MIXED
                    } else if (temporaryAccessUsed) {
                        DECISION_SOURCE_TEMPORARY_ACCESS
                    } else if (aiApprovalUsed) {
                        DECISION_SOURCE_AI
                    } else if (allProtectedUsesApproved) {
                        DECISION_SOURCE_POLICY
                    } else if (!currentDescription.containsSensitiveMaterial) {
                        DECISION_SOURCE_NON_SENSITIVE
                    } else {
                        null
                    },
                    approvalEvaluationJson = approvalEvaluation?.let { json.encodeToString(it) },
                    // This snapshot contains only metadata, public SSH material, and values that the
                    // user explicitly marked non-sensitive. Keep it for denied requests as well so
                    // their history still shows the complete, safe-to-display request context.
                    providedSecretsJson = providedSecretsJson,
                    completionReason = denial?.first?.wireName,
                    completionMessage = denial?.second,
                    decidedAt = decidedAt,
                )
            }
            val aiReviewAudit = aiReview?.let { reviewed ->
                AuditRecord(
                    type = AuditEventType.SECRET_USE_AI_REVIEWED,
                    outcome = reviewed.auditOutcome(),
                    decisionSource = AuditDecisionSource.AI_REVIEW,
                    subject = contents.secrets.joinToString(),
                    detail = reviewed.auditExplanation(),
                    clientId = pairing.clientId,
                    clientName = pairing.auditClientName(),
                    relayRequestId = relayRequestId,
                )
            }
            val automaticDecisionAudit = automaticDecision?.let { decision ->
                AuditRecord(
                    type = AuditEventType.SECRET_USE_DECIDED,
                    outcome = when {
                        decision == ApprovalDecision.APPROVED -> AuditOutcome.APPROVED
                        denial?.first == InvocationDenialReason.INVALID_REQUEST -> AuditOutcome.REJECTED
                        aiDenial != null || policyDenial != null -> AuditOutcome.DENIED
                        else -> AuditOutcome.FAILED
                    },
                    decisionSource = when {
                        decision == ApprovalDecision.APPROVED &&
                            !currentDescription.containsSensitiveMaterial ->
                            AuditDecisionSource.NON_SENSITIVE
                        denial?.first == InvocationDenialReason.INVALID_REQUEST ->
                            AuditDecisionSource.VALIDATION
                        aiApprovalUsed && temporaryAccessUsed -> AuditDecisionSource.MIXED
                        aiApproved || aiDenial != null -> AuditDecisionSource.AI_REVIEW
                        temporaryAccessUsed -> AuditDecisionSource.TEMPORARY_ACCESS
                        policyDenial != null || allProtectedUsesApproved ->
                            AuditDecisionSource.APPROVAL_SETTINGS
                        else -> null
                    },
                    subject = contents.secrets.joinToString(),
                    detail = if (aiReview != null && (aiApprovalUsed || aiDenial != null)) {
                        aiReview.auditExplanation()
                    } else {
                        denial?.second
                    },
                    clientId = pairing.clientId,
                    clientName = pairing.auditClientName(),
                    relayRequestId = relayRequestId,
                )
            }
            val persistence = if (requestAlreadyInserted) {
                invocationRequests.finishAiReview(
                    request = finalRequest,
                    secretUseRequest = finalSecretUse,
                    authorization = authorization,
                    aiReviewAudit = checkNotNull(aiReviewAudit),
                    automaticDecisionAudit = automaticDecisionAudit,
                )
            } else {
                invocationRequests.receive(
                    request = finalRequest,
                    secretUseRequest = finalSecretUse,
                    client = pairing.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                    ),
                    acceptedPsks = acceptedSecrets,
                    authorization = authorization.takeIf { automaticDecision != null },
                    automaticDecisionAudit = automaticDecisionAudit,
                )
            }
            when (persistence) {
                ConditionalRequestUpdate.APPLIED -> Unit
                ConditionalRequestUpdate.ACTION_REQUIRED -> return ProcessedRelayMessage()
                ConditionalRequestUpdate.UNAVAILABLE -> return null
            }
            return ProcessedRelayMessage(response)
        }

        if (!needsAiReview) return finishReview(null, requestAlreadyInserted = false)

        withContext(NonCancellable) {
            val received = invocationRequests.receive(
                request = initialRequest,
                secretUseRequest = initialSecretUse,
                client = pairing.copy(
                    clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                    lastSeenAt = now,
                ),
                acceptedPsks = acceptedSecrets,
                authorization = null,
                automaticDecisionAudit = null,
            )
            check(received == ConditionalRequestUpdate.APPLIED)
            if (
                !launchAiReview(
                    requestId = relayRequestId,
                    requestJson = initialRequest.requestJson,
                    review = {
                        requestAiReview(
                            pairing = pairing,
                            contents = contents,
                            description = description,
                            values = checkNotNull(initialAvailableSecrets),
                            evaluation = initialApprovalEvaluation,
                            policies = approvalPolicies,
                            credentials = credentials,
                        )
                    },
                    complete = { finishReview(it, requestAlreadyInserted = true) },
                )
            ) {
                dao.recoverInterruptedAiReview(
                    relayRequestId,
                    initialRequest.requestJson,
                )
            }
        }
        return ProcessedRelayMessage()
    }

    private suspend fun recordGitSignRequested(
        pairing: ClientEntity,
        relayRequestId: String,
        secretName: String,
    ) {
        audit.record(
            AuditRecord(
                type = AuditEventType.GIT_SIGN_RECEIVED,
                outcome = AuditOutcome.RECEIVED,
                subject = secretName,
                clientId = pairing.clientId,
                clientName = pairing.auditClientName(),
                relayRequestId = relayRequestId,
            ),
        )
    }

    private suspend fun recordSshAuthenticationRequested(
        pairing: ClientEntity,
        relayRequestId: String,
        secretName: String,
        username: String,
    ) {
        audit.record(
            AuditRecord(
                type = AuditEventType.SSH_AUTHENTICATION_RECEIVED,
                outcome = AuditOutcome.RECEIVED,
                subject = secretName,
                context = username,
                clientId = pairing.clientId,
                clientName = pairing.auditClientName(),
                relayRequestId = relayRequestId,
            ),
        )
    }

    private suspend fun processGitSignRequest(
        pairing: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        val now = currentTimeMillis()
        val contents = runCatching { gitSignProtocol.decodeRequest(opened.plaintext) }
            .getOrNull() ?: return null
        val invocationRequest = dao.getRequestById(contents.invocationId) ?: return null
        if (invocationRequest.kind != RequestKind.SECRET_USE.storedName) return null
        val invocation = dao.getSecretUseRequest(invocationRequest.id) ?: return null
        val expectedTokenHash = invocation.invocationTokenHash
        if (
            invocationRequest.clientId != pairing.clientId ||
            invocationRequest.deviceIdentityId != pairing.deviceIdentityId ||
            invocation.decision != ApprovalDecision.APPROVED.storedName ||
            invocationRequest.clientSoftwareJson?.let(::decodeClientSoftware) !=
                contents.clientSoftware ||
            !MessageDigest.isEqual(
                expectedTokenHash,
                invocationTokenHash(contents.invocationToken),
            )
        ) {
            return null
        }

        val sshMetadata = storedJson.decodeFromString<List<SecretMetadata>>(
            invocation.secretDetailsJson,
        ).singleOrNull { secret ->
            secret.name == contents.secret &&
                secret.type == SSH_SECRET_TYPE &&
                secret.sshPublicKey != null
        }
        val description = secrets.describeRequestedSecrets(listOf(contents.secret))
        val approvalPolicies = secrets.approvalPoliciesForNames(
            listOf(contents.secret),
            pairing.clientId,
            TemporaryAccessOperation.GIT_SIGN,
        )
        val policy = approvalPolicies.singleOrNull()
        var denial = if (
            sshMetadata == null ||
            description.reviewMetadata.singleOrNull()?.type != SSH_SECRET_TYPE ||
            policy == null
        ) {
            InvocationDenialReason.INVALID_REQUEST to
                "The Git signing request does not match its invocation."
        } else {
            null
        }
        val initialEvaluation = if (denial == null) {
            ApprovalPolicyEvaluator.evaluate(
                listOf(checkNotNull(policy).toRequestedSecretApproval()),
            )
        } else {
            null
        }
        val needsAiReview = initialEvaluation?.requiresAiReview() == true
        val initialRequest = InboxRequestEntity(
            id = relayRequestId,
            parentRequestId = invocationRequest.id,
            deviceIdentityId = pairing.deviceIdentityId,
            clientId = pairing.clientId,
            clientNameSnapshot = pairing.name,
            clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
            kind = RequestKind.GIT_SIGN.storedName,
            state = if (needsAiReview) {
                InboxRequestState.REVIEWING.storedName
            } else {
                InboxRequestState.ACTION_REQUIRED.storedName
            },
            listed = true,
            requestJson = requestPayload.toString(),
            responseJson = null,
            completionJson = null,
            error = null,
            receivedAt = now,
            completedAt = null,
            requestAcknowledged = false,
            responseAcknowledged = false,
            completionAcknowledged = false,
        )
        val initialGitSign = GitSignRequestEntity(
            requestId = relayRequestId,
            secretName = contents.secret,
            message = contents.message,
            repositoryJson = contents.repository?.let { json.encodeToString(it) },
            approvalEvaluationJson = initialEvaluation?.let { json.encodeToString(it) },
            decision = null,
            completionResult = null,
            completionReason = null,
            completionMessage = null,
            decidedAt = null,
        )
        suspend fun finishReview(
            reviewResult: AiReview?,
            requestAlreadyInserted: Boolean,
        ): ProcessedRelayMessage? {
        val currentClient = if (needsAiReview) dao.getClient(pairing.clientId) else pairing
        val clientUnavailable = currentClient == null ||
            currentClient.deviceIdentityId != pairing.deviceIdentityId ||
            currentClient.relayClientState == RelayClientState.REVOKED.wireName ||
            currentClient.desiredRelayClientState == RelayClientState.REVOKED.wireName
        val currentDescription = if (needsAiReview) {
            secrets.describeRequestedSecrets(listOf(contents.secret))
        } else {
            description
        }
        val currentPolicies = if (needsAiReview) {
            secrets.approvalPoliciesForNames(
                listOf(contents.secret),
                pairing.clientId,
                TemporaryAccessOperation.GIT_SIGN,
            )
        } else {
            approvalPolicies
        }
        val currentEvaluation = if (needsAiReview) {
            currentPolicies.singleOrNull()?.let { currentPolicy ->
                ApprovalPolicyEvaluator.evaluate(
                    listOf(currentPolicy.toRequestedSecretApproval()),
                )
            }
        } else {
            initialEvaluation
        }
        val currentCredentials = if (needsAiReview) {
            currentClient?.let { credentialsForClient(it) }
        } else {
            credentials
        }
        val aiInputsChanged = needsAiReview && (
            clientUnavailable ||
                currentEvaluation == null ||
                !checkNotNull(initialEvaluation).hasSameSecretPolicies(currentEvaluation) ||
                currentCredentials?.instructions != credentials.instructions ||
                currentClient.name != pairing.name ||
                currentClient.instructions != pairing.instructions
            )
        val aiReview = if (aiInputsChanged) {
            AiReview(
                decision = AiReviewDecision.ASK_USER,
                explanation = "The client, SSH key, instructions, or approval settings changed during AI review.",
            )
        } else {
            reviewResult
        }
        val evaluation = (if (aiInputsChanged) currentEvaluation else initialEvaluation)
            ?.copy(aiReview = aiReview)
        val authorization = currentDescription.authorizationCommitment(
            policies = currentPolicies,
            instructions = if (needsAiReview) {
                currentCredentials?.let { current ->
                    AuthorizationInstructionsCommitment(
                        deviceIdentityId = current.deviceIdentityId,
                        deviceInstructions = current.instructions,
                        clientId = checkNotNull(currentClient).clientId,
                        clientName = currentClient.name,
                        clientInstructions = currentClient.instructions,
                    )
                }
            } else {
                null
            },
        )
        if (clientUnavailable && denial == null) {
            denial = InvocationDenialReason.OTHER to
                "The paired client is no longer available."
        }
        val approvalSettingsDenied =
            denial == null &&
            evaluation?.secrets?.any { it.action == ApprovalAction.DENY } == true
        if (approvalSettingsDenied) {
            denial = InvocationDenialReason.POLICY_DENIED to
                "Approval settings denied use of the SSH key."
        }
        val aiDenied = denial == null && aiReview?.decision == AiReviewDecision.DENY
        if (aiDenied) {
            denial = InvocationDenialReason.POLICY_DENIED to
                "AI review denied use of the SSH key."
        }
        val shouldApprove = !aiInputsChanged && denial == null &&
            evaluation?.isFullyApproved(aiReview?.decision) == true
        val temporaryAccessUsed = evaluation?.secrets
            ?.any { it.temporaryAccessExpiresAt != null } == true
        var signature: String? = null
        if (shouldApprove) {
            when (
                val result = secrets.signGitMessage(
                    secretName = contents.secret,
                    expectedPublicKey = checkNotNull(sshMetadata?.sshPublicKey),
                    message = contents.message,
                )
            ) {
                is GitSignatureResult.Signed -> signature = result.signature
                GitSignatureResult.NotFound,
                GitSignatureResult.WrongType,
                GitSignatureResult.KeyChanged,
                -> denial = InvocationDenialReason.INVALID_REQUEST to
                    "The SSH key changed after the invocation began."
                GitSignatureResult.SecretUnavailable ->
                    denial = InvocationDenialReason.OTHER to
                        "The SSH private key is unavailable on this device."
                GitSignatureResult.SecretCorrupted ->
                    denial = InvocationDenialReason.OTHER to
                        "The SSH private key could not be authenticated."
                GitSignatureResult.UnsupportedEncryption ->
                    denial = InvocationDenialReason.OTHER to
                        "The SSH private key uses an unsupported encryption format."
            }
        }
        val responsePlaintext = when {
            denial != null -> gitSignProtocol.deniedResponse(denial.first, denial.second)
            signature != null -> gitSignProtocol.approvedResponse(signature)
            else -> null
        }
        val response = responsePlaintext?.let { plaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(
                deviceId = credentials.deviceId,
                    requestId = relayRequestId,
                    clientId = pairing.clientId,
                    clientPsk = opened.clientPsk,
                    devicePrivateKey = credentials.devicePrivateKey,
                    devicePublicKey = credentials.devicePublicKey,
                    request = requestPayload,
                    plaintext = plaintext,
                )
            }.getOrNull() ?: return null
        }
        val decidedAt = currentTimeMillis()
        val automaticDecision = when {
            signature != null -> ApprovalDecision.APPROVED
            denial != null -> ApprovalDecision.DENIED
            else -> null
        }
        val requestToUpdate = if (requestAlreadyInserted) {
            dao.getRequestById(relayRequestId) ?: return null
        } else {
            initialRequest
        }
        val finalRequest = requestToUpdate.copy(
            state = reviewedRequestState(response != null).storedName,
            responseJson = response?.toString(),
        )
        val finalGitSign = initialGitSign.copy(
            decision = automaticDecision?.storedName,
            approvalEvaluationJson = evaluation?.let { json.encodeToString(it) },
            completionReason = denial?.first?.wireName,
            completionMessage = denial?.second,
            decidedAt = automaticDecision?.let { decidedAt },
        )
        if (!requestAlreadyInserted) {
            val inserted = if (automaticDecision == ApprovalDecision.APPROVED) {
                dao.insertGitSignRequestIfAuthorized(
                    request = finalRequest,
                    gitSignRequest = finalGitSign,
                    client = pairing.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                    ),
                    requestPsk = acceptedSecrets.requestPsk,
                    currentClientPsk = acceptedSecrets.currentClientPsk,
                    previousClientPsk = acceptedSecrets.previousClientPsk,
                    authorization = authorization,
                    clientId = pairing.clientId,
                    operation = TemporaryAccessOperation.GIT_SIGN.storedName,
                    now = currentTimeMillis(),
                )
            } else {
                dao.insertGitSignRequest(
                    request = finalRequest,
                    gitSignRequest = finalGitSign,
                    client = pairing.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                    ),
                    requestPsk = acceptedSecrets.requestPsk,
                    currentClientPsk = acceptedSecrets.currentClientPsk,
                    previousClientPsk = acceptedSecrets.previousClientPsk,
                )
                true
            }
            if (automaticDecision == ApprovalDecision.APPROVED && !inserted) return null
            recordGitSignRequested(pairing, relayRequestId, contents.secret)
        } else if (automaticDecision == ApprovalDecision.APPROVED) {
            val update = dao.updateGitSignRequestIfAuthorized(
                request = finalRequest,
                gitSignRequest = finalGitSign,
                authorization = authorization,
                clientId = pairing.clientId,
                operation = TemporaryAccessOperation.GIT_SIGN.storedName,
                now = currentTimeMillis(),
            )
            when (update) {
                ConditionalRequestUpdate.APPLIED -> Unit
                ConditionalRequestUpdate.ACTION_REQUIRED -> return ProcessedRelayMessage()
                ConditionalRequestUpdate.UNAVAILABLE -> return null
            }
        } else {
            dao.updateGitSignRequest(finalRequest, finalGitSign)
        }
        if (aiReview != null) {
            audit.record(
                AuditRecord(
                    type = AuditEventType.GIT_SIGN_AI_REVIEWED,
                    outcome = aiReview.auditOutcome(),
                    decisionSource = AuditDecisionSource.AI_REVIEW,
                    subject = contents.secret,
                    detail = aiReview.auditExplanation(),
                    clientId = pairing.clientId,
                    clientName = pairing.auditClientName(),
                    relayRequestId = relayRequestId,
                ),
            )
        }
        if (automaticDecision != null) {
            audit.record(
                AuditRecord(
                    type = AuditEventType.GIT_SIGN_DECIDED,
                    outcome = when {
                        signature != null -> AuditOutcome.APPROVED
                        aiDenied || approvalSettingsDenied -> AuditOutcome.DENIED
                        denial?.first == InvocationDenialReason.INVALID_REQUEST ->
                            AuditOutcome.REJECTED
                        else -> AuditOutcome.FAILED
                    },
                    decisionSource = when {
                        signature != null && aiReview?.decision == AiReviewDecision.APPROVE &&
                            temporaryAccessUsed -> AuditDecisionSource.MIXED
                        signature != null && aiReview?.decision == AiReviewDecision.APPROVE ->
                            AuditDecisionSource.AI_REVIEW
                        signature != null && temporaryAccessUsed ->
                            AuditDecisionSource.TEMPORARY_ACCESS
                        signature != null -> AuditDecisionSource.APPROVAL_SETTINGS
                        aiDenied -> AuditDecisionSource.AI_REVIEW
                        approvalSettingsDenied -> AuditDecisionSource.APPROVAL_SETTINGS
                        denial?.first == InvocationDenialReason.INVALID_REQUEST ->
                            AuditDecisionSource.VALIDATION
                        else -> null
                    },
                    subject = contents.secret,
                    detail = if (
                        aiReview != null && aiReview.decision != AiReviewDecision.ASK_USER
                    ) {
                        aiReview.auditExplanation()
                    } else {
                        denial?.second
                    },
                    clientId = pairing.clientId,
                    clientName = pairing.auditClientName(),
                    relayRequestId = relayRequestId,
                ),
            )
        }
        return ProcessedRelayMessage(response)
        }

        if (!needsAiReview) return finishReview(null, requestAlreadyInserted = false)

        withContext(NonCancellable) {
            dao.insertGitSignRequest(
                request = initialRequest,
                gitSignRequest = initialGitSign,
                client = pairing.copy(
                    clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                    lastSeenAt = now,
                ),
                requestPsk = acceptedSecrets.requestPsk,
                currentClientPsk = acceptedSecrets.currentClientPsk,
                previousClientPsk = acceptedSecrets.previousClientPsk,
            )
            check(
                launchAiReview(
                    requestId = relayRequestId,
                    requestJson = initialRequest.requestJson,
                    review = {
                        requestGitSignAiReview(
                            pairing = pairing,
                            contents = contents,
                            invocation = invocation,
                            parentElapsedSeconds = if (now >= invocationRequest.receivedAt) {
                                (now - invocationRequest.receivedAt) / 1_000
                            } else {
                                null
                            },
                            evaluation = initialEvaluation,
                            policies = approvalPolicies,
                            credentials = credentials,
                        )
                    },
                    complete = { finishReview(it, requestAlreadyInserted = true) },
                ),
            )
            recordGitSignRequested(pairing, relayRequestId, contents.secret)
        }
        return ProcessedRelayMessage()
    }

    private suspend fun processSshAuthenticationRequest(
        pairing: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        val now = currentTimeMillis()
        val contents = runCatching {
            sshAuthenticationProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val invocationRequest = dao.getRequestById(contents.invocationId) ?: return null
        if (invocationRequest.kind != RequestKind.SECRET_USE.storedName) return null
        val invocation = dao.getSecretUseRequest(invocationRequest.id) ?: return null
        val expectedTokenHash = invocation.invocationTokenHash
        if (
            invocationRequest.clientId != pairing.clientId ||
            invocationRequest.deviceIdentityId != pairing.deviceIdentityId ||
            invocation.decision != ApprovalDecision.APPROVED.storedName ||
            invocationRequest.clientSoftwareJson?.let(::decodeClientSoftware) !=
                contents.clientSoftware ||
            !MessageDigest.isEqual(
                expectedTokenHash,
                invocationTokenHash(contents.invocationToken),
            )
        ) {
            return null
        }
        val sshMetadata = storedJson.decodeFromString<List<SecretMetadata>>(
            invocation.secretDetailsJson,
        ).singleOrNull { secret ->
            secret.name == contents.secret &&
                secret.type == SSH_SECRET_TYPE &&
                secret.sshPublicKey != null
        } ?: return null
        val expectedPublicKey = runCatching {
            sshKeys.importOpenSshPublicKey(checkNotNull(sshMetadata.sshPublicKey))
        }.getOrNull() ?: return null
        val messageDetails = runCatching {
            sshAuthenticationProtocol.validateMessage(
                message = contents.message,
                expectedPublicKeyBlob = expectedPublicKey.blob(),
                expectedKeyAlgorithm = expectedPublicKey.algorithm.publicName,
            )
        }.getOrNull() ?: return null

        val description = secrets.describeRequestedSecrets(listOf(contents.secret))
        val approvalPolicies = secrets.approvalPoliciesForNames(
            listOf(contents.secret),
            pairing.clientId,
            TemporaryAccessOperation.SSH_AUTHENTICATE,
        )
        val policy = approvalPolicies.singleOrNull()
        var denial = if (
            description.reviewMetadata.singleOrNull()?.type != SSH_SECRET_TYPE || policy == null
        ) {
            InvocationDenialReason.INVALID_REQUEST to
                "The SSH authentication request does not match its invocation."
        } else {
            null
        }
        val initialEvaluation = if (denial == null) {
            ApprovalPolicyEvaluator.evaluate(
                listOf(checkNotNull(policy).toRequestedSecretApproval()),
            )
        } else {
            null
        }
        val needsAiReview = initialEvaluation?.requiresAiReview() == true
        val initialRequest = InboxRequestEntity(
            id = relayRequestId,
            parentRequestId = invocationRequest.id,
            deviceIdentityId = pairing.deviceIdentityId,
            clientId = pairing.clientId,
            clientNameSnapshot = pairing.name,
            clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
            kind = RequestKind.SSH_AUTHENTICATE.storedName,
            state = if (needsAiReview) {
                InboxRequestState.REVIEWING.storedName
            } else {
                InboxRequestState.ACTION_REQUIRED.storedName
            },
            listed = true,
            requestJson = requestPayload.toString(),
            responseJson = null,
            completionJson = null,
            error = null,
            receivedAt = now,
            completedAt = null,
            requestAcknowledged = false,
            responseAcknowledged = false,
            completionAcknowledged = false,
        )
        val initialAuthentication = SshAuthenticationRequestEntity(
            requestId = relayRequestId,
            secretName = contents.secret,
            message = contents.message,
            username = messageDetails.username,
            method = messageDetails.method.wireName,
            algorithm = messageDetails.algorithm.wireName,
            hostKeyAlgorithm = messageDetails.hostKeyAlgorithm,
            hostKeyFingerprint = messageDetails.hostKeyFingerprint,
            approvalEvaluationJson = initialEvaluation?.let { json.encodeToString(it) },
            decision = null,
            completionResult = null,
            completionReason = null,
            completionMessage = null,
            decidedAt = null,
        )
        suspend fun finishReview(
            reviewResult: AiReview?,
            requestAlreadyInserted: Boolean,
        ): ProcessedRelayMessage? {
            val currentClient = if (needsAiReview) dao.getClient(pairing.clientId) else pairing
            val clientUnavailable = currentClient == null ||
                currentClient.deviceIdentityId != pairing.deviceIdentityId ||
                currentClient.relayClientState == RelayClientState.REVOKED.wireName ||
                currentClient.desiredRelayClientState == RelayClientState.REVOKED.wireName
            val currentDescription = if (needsAiReview) {
                secrets.describeRequestedSecrets(listOf(contents.secret))
            } else {
                description
            }
            val currentPolicies = if (needsAiReview) {
                secrets.approvalPoliciesForNames(
                    listOf(contents.secret),
                    pairing.clientId,
                    TemporaryAccessOperation.SSH_AUTHENTICATE,
                )
            } else {
                approvalPolicies
            }
            val currentEvaluation = if (needsAiReview) {
                currentPolicies.singleOrNull()?.let { currentPolicy ->
                    ApprovalPolicyEvaluator.evaluate(
                        listOf(currentPolicy.toRequestedSecretApproval()),
                    )
                }
            } else {
                initialEvaluation
            }
            val currentCredentials = if (needsAiReview) {
                currentClient?.let { credentialsForClient(it) }
            } else {
                credentials
            }
            val aiInputsChanged = needsAiReview && (
                clientUnavailable ||
                    currentEvaluation == null ||
                    !checkNotNull(initialEvaluation).hasSameSecretPolicies(currentEvaluation) ||
                    currentCredentials?.instructions != credentials.instructions ||
                    currentClient.name != pairing.name ||
                    currentClient.instructions != pairing.instructions
                )
            val aiReview = if (aiInputsChanged) {
                AiReview(
                    decision = AiReviewDecision.ASK_USER,
                    explanation = "The client, SSH key, instructions, or approval settings changed during AI review.",
                )
            } else {
                reviewResult
            }
            val evaluation = (if (aiInputsChanged) currentEvaluation else initialEvaluation)
                ?.copy(aiReview = aiReview)
            val authorization = currentDescription.authorizationCommitment(
                policies = currentPolicies,
                instructions = if (needsAiReview) {
                    currentCredentials?.let { current ->
                        AuthorizationInstructionsCommitment(
                            deviceIdentityId = current.deviceIdentityId,
                            deviceInstructions = current.instructions,
                            clientId = checkNotNull(currentClient).clientId,
                            clientName = currentClient.name,
                            clientInstructions = currentClient.instructions,
                        )
                    }
                } else {
                    null
                },
            )
            if (clientUnavailable && denial == null) {
                denial = InvocationDenialReason.OTHER to
                    "The paired client is no longer available."
            }
            val approvalSettingsDenied =
                denial == null &&
                evaluation?.secrets?.any { it.action == ApprovalAction.DENY } == true
            if (approvalSettingsDenied) {
                denial = InvocationDenialReason.POLICY_DENIED to
                    "Approval settings denied SSH authentication."
            }
            val aiDenied = denial == null && aiReview?.decision == AiReviewDecision.DENY
            if (aiDenied) {
                denial = InvocationDenialReason.POLICY_DENIED to
                    "AI review denied SSH authentication."
            }
            val shouldApprove = !aiInputsChanged && denial == null &&
                evaluation?.isFullyApproved(aiReview?.decision) == true
            val temporaryAccessUsed = evaluation?.secrets
                ?.any { it.temporaryAccessExpiresAt != null } == true
            var signature: ByteArray? = null
            if (shouldApprove) {
                when (
                    val result = secrets.signSshAuthentication(
                        secretName = contents.secret,
                        expectedPublicKey = checkNotNull(sshMetadata.sshPublicKey),
                        message = contents.message,
                        algorithm = messageDetails.algorithm,
                    )
                ) {
                    is SshAuthenticationSignatureResult.Signed -> signature = result.signature
                    SshAuthenticationSignatureResult.NotFound,
                    SshAuthenticationSignatureResult.WrongType,
                    SshAuthenticationSignatureResult.KeyChanged,
                    -> denial = InvocationDenialReason.INVALID_REQUEST to
                        "The SSH key changed after the invocation began."
                    SshAuthenticationSignatureResult.SecretUnavailable ->
                        denial = InvocationDenialReason.OTHER to
                            "The SSH private key is unavailable on this device."
                    SshAuthenticationSignatureResult.SecretCorrupted ->
                        denial = InvocationDenialReason.OTHER to
                            "The SSH private key could not be authenticated."
                    SshAuthenticationSignatureResult.UnsupportedEncryption ->
                        denial = InvocationDenialReason.OTHER to
                            "The SSH private key uses an unsupported encryption format."
                }
            }
            val responsePlaintext = when {
                denial != null -> sshAuthenticationProtocol.deniedResponse(
                    denial.first,
                    denial.second,
                )
                signature != null -> sshAuthenticationProtocol.approvedResponse(signature)
                else -> null
            }
            val response = responsePlaintext?.let { plaintext ->
                runCatching {
                    pairedRequestProtocol.sealPairedResponse(
                deviceId = credentials.deviceId,
                        requestId = relayRequestId,
                        clientId = pairing.clientId,
                        clientPsk = opened.clientPsk,
                        devicePrivateKey = credentials.devicePrivateKey,
                        devicePublicKey = credentials.devicePublicKey,
                        request = requestPayload,
                        plaintext = plaintext,
                    )
                }.getOrNull() ?: return null
            }
            val decidedAt = currentTimeMillis()
            val automaticDecision = when {
                signature != null -> ApprovalDecision.APPROVED
                denial != null -> ApprovalDecision.DENIED
                else -> null
            }
            val requestToUpdate = if (requestAlreadyInserted) {
                dao.getRequestById(relayRequestId) ?: return null
            } else {
                initialRequest
            }
            val finalRequest = requestToUpdate.copy(
                state = reviewedRequestState(response != null).storedName,
                responseJson = response?.toString(),
            )
            val finalAuthentication = initialAuthentication.copy(
                decision = automaticDecision?.storedName,
                approvalEvaluationJson = evaluation?.let { json.encodeToString(it) },
                completionReason = denial?.first?.wireName,
                completionMessage = denial?.second,
                decidedAt = automaticDecision?.let { decidedAt },
            )
            if (!requestAlreadyInserted) {
                val inserted = if (automaticDecision == ApprovalDecision.APPROVED) {
                    dao.insertSshAuthenticationRequestIfAuthorized(
                        request = finalRequest,
                        authentication = finalAuthentication,
                        client = pairing.copy(
                            clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                            lastSeenAt = now,
                        ),
                        requestPsk = acceptedSecrets.requestPsk,
                        currentClientPsk = acceptedSecrets.currentClientPsk,
                        previousClientPsk = acceptedSecrets.previousClientPsk,
                        authorization = authorization,
                        clientId = pairing.clientId,
                        operation = TemporaryAccessOperation.SSH_AUTHENTICATE.storedName,
                        now = currentTimeMillis(),
                    )
                } else {
                    dao.insertSshAuthenticationRequest(
                        request = finalRequest,
                        authentication = finalAuthentication,
                        client = pairing.copy(
                            clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                            lastSeenAt = now,
                        ),
                        requestPsk = acceptedSecrets.requestPsk,
                        currentClientPsk = acceptedSecrets.currentClientPsk,
                        previousClientPsk = acceptedSecrets.previousClientPsk,
                    )
                    true
                }
                if (automaticDecision == ApprovalDecision.APPROVED && !inserted) {
                    return null
                }
                recordSshAuthenticationRequested(
                    pairing = pairing,
                    relayRequestId = relayRequestId,
                    secretName = contents.secret,
                    username = messageDetails.username,
                )
            } else if (automaticDecision == ApprovalDecision.APPROVED) {
                val update = dao.updateSshAuthenticationRequestIfAuthorized(
                    request = finalRequest,
                    authentication = finalAuthentication,
                    authorization = authorization,
                    clientId = pairing.clientId,
                    operation = TemporaryAccessOperation.SSH_AUTHENTICATE.storedName,
                    now = currentTimeMillis(),
                )
                when (update) {
                    ConditionalRequestUpdate.APPLIED -> Unit
                    ConditionalRequestUpdate.ACTION_REQUIRED -> return ProcessedRelayMessage()
                    ConditionalRequestUpdate.UNAVAILABLE -> return null
                }
            } else {
                dao.updateSshAuthenticationRequest(finalRequest, finalAuthentication)
            }
            if (aiReview != null) {
                audit.record(
                    AuditRecord(
                        type = AuditEventType.SSH_AUTHENTICATION_AI_REVIEWED,
                        outcome = aiReview.auditOutcome(),
                        decisionSource = AuditDecisionSource.AI_REVIEW,
                        subject = contents.secret,
                        context = messageDetails.username,
                        detail = aiReview.auditExplanation(),
                        clientId = pairing.clientId,
                        clientName = pairing.auditClientName(),
                        relayRequestId = relayRequestId,
                    ),
                )
            }
            if (automaticDecision != null) {
                audit.record(
                    AuditRecord(
                        type = AuditEventType.SSH_AUTHENTICATION_DECIDED,
                        outcome = when {
                            signature != null -> AuditOutcome.APPROVED
                            aiDenied || approvalSettingsDenied -> AuditOutcome.DENIED
                            denial?.first == InvocationDenialReason.INVALID_REQUEST ->
                                AuditOutcome.REJECTED
                            else -> AuditOutcome.FAILED
                        },
                        decisionSource = when {
                            signature != null && aiReview?.decision == AiReviewDecision.APPROVE &&
                                temporaryAccessUsed -> AuditDecisionSource.MIXED
                            signature != null && aiReview?.decision == AiReviewDecision.APPROVE ->
                                AuditDecisionSource.AI_REVIEW
                            signature != null && temporaryAccessUsed ->
                                AuditDecisionSource.TEMPORARY_ACCESS
                            signature != null -> AuditDecisionSource.APPROVAL_SETTINGS
                            aiDenied -> AuditDecisionSource.AI_REVIEW
                            approvalSettingsDenied -> AuditDecisionSource.APPROVAL_SETTINGS
                            denial?.first == InvocationDenialReason.INVALID_REQUEST ->
                                AuditDecisionSource.VALIDATION
                            else -> null
                        },
                        subject = contents.secret,
                        context = messageDetails.username,
                        detail = aiReview?.takeIf {
                            it.decision != AiReviewDecision.ASK_USER
                        }?.auditExplanation() ?: denial?.second,
                        clientId = pairing.clientId,
                        clientName = pairing.auditClientName(),
                        relayRequestId = relayRequestId,
                    ),
                )
            }
            return ProcessedRelayMessage(response)
        }

        if (!needsAiReview) return finishReview(null, requestAlreadyInserted = false)

        withContext(NonCancellable) {
            dao.insertSshAuthenticationRequest(
                request = initialRequest,
                authentication = initialAuthentication,
                client = pairing.copy(
                    clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                    lastSeenAt = now,
                ),
                requestPsk = acceptedSecrets.requestPsk,
                currentClientPsk = acceptedSecrets.currentClientPsk,
                previousClientPsk = acceptedSecrets.previousClientPsk,
            )
            check(
                launchAiReview(
                    requestId = relayRequestId,
                    requestJson = initialRequest.requestJson,
                    review = {
                        requestSshAuthenticationAiReview(
                            pairing = pairing,
                            contents = contents,
                            messageDetails = messageDetails,
                            invocation = invocation,
                            parentElapsedSeconds = if (now >= invocationRequest.receivedAt) {
                                (now - invocationRequest.receivedAt) / 1_000
                            } else {
                                null
                            },
                            evaluation = checkNotNull(initialEvaluation),
                            policies = approvalPolicies,
                            credentials = credentials,
                        )
                    },
                    complete = { finishReview(it, requestAlreadyInserted = true) },
                ),
            )
            recordSshAuthenticationRequested(
                pairing = pairing,
                relayRequestId = relayRequestId,
                secretName = contents.secret,
                username = messageDetails.username,
            )
        }
        return ProcessedRelayMessage()
    }

    private suspend fun automaticSecretUseDenial(
        result: RequestedSecretsResult,
    ): Pair<InvocationDenialReason, String>? = when (result) {
        is RequestedSecretsResult.Available -> null
        is RequestedSecretsResult.MissingSecrets ->
            InvocationDenialReason.INVALID_REQUEST to
                "Missing secrets: ${result.names.joinToString()}"
        is RequestedSecretsResult.ConflictingVariable ->
            InvocationDenialReason.INVALID_REQUEST to
                "Requested secrets provide conflicting values for the environment variable ${result.name}."
        is RequestedSecretsResult.MissingEnvironmentVariables ->
            InvocationDenialReason.INVALID_REQUEST to
                "Secret ${result.secretName} has no ${result.names.joinToString()} variable."
        is RequestedSecretsResult.EnvironmentOptionsForSshSecret ->
            InvocationDenialReason.INVALID_REQUEST to
                "Secret ${result.secretName} is not an environment-variable secret."
        RequestedSecretsResult.MultipleSshKeys ->
            InvocationDenialReason.INVALID_REQUEST to
                "A request can use at most one SSH key."
        RequestedSecretsResult.UnsupportedSecretType ->
            InvocationDenialReason.INVALID_REQUEST to
                "A requested secret type is unsupported."
        RequestedSecretsResult.SecretUnavailable ->
            InvocationDenialReason.OTHER to
                "A requested secret value is unavailable on this device."
        RequestedSecretsResult.SecretCorrupted ->
            InvocationDenialReason.OTHER to
                "A requested secret value could not be authenticated."
        RequestedSecretsResult.UnsupportedEncryption ->
            InvocationDenialReason.OTHER to
                "A requested secret value uses an unsupported encryption format."
    }

    private suspend fun requestAiReview(
        pairing: ClientEntity,
        contents: InvocationRequestMessage,
        description: RequestedSecretDescription,
        values: Map<String, SecretValues>,
        evaluation: ApprovalEvaluation,
        policies: List<SecretApprovalPolicy>,
        credentials: RelayDeviceCredentials,
    ): AiReview {
        val request = approvalReviewRequest(
            client = pairing,
            contents = contents,
            description = description,
            values = values,
            evaluation = evaluation,
            policies = policies,
            deviceInstructions = credentials.instructions,
        )
        return performAiReview(approvalReviewer, credentials, request)
    }

    private suspend fun requestGitSignAiReview(
        pairing: ClientEntity,
        contents: GitSignRequestMessage,
        invocation: SecretUseRequestEntity,
        parentElapsedSeconds: Long?,
        evaluation: ApprovalEvaluation,
        policies: List<SecretApprovalPolicy>,
        credentials: RelayDeviceCredentials,
    ): AiReview {
        val elapsedSeconds = parentElapsedSeconds ?: return AiReview(
            decision = AiReviewDecision.ASK_USER,
            explanation = "The relative timing of the parent invocation is unavailable.",
        )
        if (contents.message.size > MAX_AI_REVIEW_GIT_CONTENT_BYTES) {
            return AiReview(
                decision = AiReviewDecision.ASK_USER,
                explanation = "The exact Git signing content is too large for AI review.",
            )
        }
        val signedContent = runCatching {
            contents.message.decodeToString(throwOnInvalidSequence = true)
        }.getOrNull() ?: return AiReview(
            decision = AiReviewDecision.ASK_USER,
            explanation = "The exact Git signing content is not valid UTF-8.",
        )
        val invocationSecrets = invocation.providedSecretsJson?.let { stored ->
            decodeStoredApprovalReviewSecretFacts(stored)
        } ?: return AiReview(
            decision = AiReviewDecision.ASK_USER,
            explanation = "The parent invocation context is unavailable.",
        )
        val request = approvalReviewGitSignRequest(
            client = pairing,
            contents = contents,
            signedContent = signedContent,
            invocation = invocation,
            invocationSecrets = invocationSecrets,
            parentElapsedSeconds = elapsedSeconds,
            evaluation = evaluation,
            policies = policies,
            deviceInstructions = credentials.instructions,
        )
        return performAiReview(approvalReviewer, credentials, request)
    }

    private suspend fun requestSshAuthenticationAiReview(
        pairing: ClientEntity,
        contents: SshAuthenticationRequestMessage,
        messageDetails: SshAuthenticationMessageDetails,
        invocation: SecretUseRequestEntity,
        parentElapsedSeconds: Long?,
        evaluation: ApprovalEvaluation,
        policies: List<SecretApprovalPolicy>,
        credentials: RelayDeviceCredentials,
    ): AiReview {
        val elapsedSeconds = parentElapsedSeconds ?: return AiReview(
            decision = AiReviewDecision.ASK_USER,
            explanation = "The relative timing of the parent invocation is unavailable.",
        )
        val invocationSecrets = invocation.providedSecretsJson?.let {
            decodeStoredApprovalReviewSecretFacts(it)
        } ?: return AiReview(
            decision = AiReviewDecision.ASK_USER,
            explanation = "The parent invocation context is unavailable.",
        )
        val request = approvalReviewSshAuthenticationRequest(
            client = pairing,
            secretName = contents.secret,
            details = messageDetails,
            invocation = invocation,
            invocationSecrets = invocationSecrets,
            parentElapsedSeconds = elapsedSeconds,
            evaluation = evaluation,
            policies = policies,
            deviceInstructions = credentials.instructions,
        )
        return performAiReview(approvalReviewer, credentials, request)
    }

    private suspend fun performAiReview(
        reviewer: RelayApprovalReviewClient,
        credentials: RelayDeviceCredentials,
        request: ApprovalReviewRequest,
    ): AiReview {
        val result = try {
            reviewer.review(credentials.deviceId, credentials.deviceToken, request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AiReview(failure = AiReviewFailure.UNAVAILABLE)
        }
        return when (result) {
            is RelayApprovalReviewResult.Reviewed -> AiReview(
                decision = when (result.decision) {
                    RelayApprovalReviewDecision.APPROVE -> AiReviewDecision.APPROVE
                    RelayApprovalReviewDecision.DENY -> AiReviewDecision.DENY
                    RelayApprovalReviewDecision.ASK_USER -> AiReviewDecision.ASK_USER
                },
                explanation = result.explanation,
            )
            is RelayApprovalReviewResult.Rejected -> AiReview(
                failure = if (
                    result.status == 402 || result.code == "SUBSCRIPTION_REQUIRED"
                ) {
                    AiReviewFailure.SUBSCRIPTION_REQUIRED
                } else {
                    AiReviewFailure.RELAY_REJECTED
                },
                httpStatus = result.status,
                errorCode = result.code,
            )
            is RelayApprovalReviewResult.Unavailable ->
                AiReview(failure = AiReviewFailure.UNAVAILABLE)
            RelayApprovalReviewResult.InvalidResponse ->
                AiReview(failure = AiReviewFailure.INVALID_RESPONSE)
        }
    }

    private suspend fun processSecretListRequest(
        pairing: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        val response = secretManagement.receiveSecretList(
            client = pairing,
            relayRequestId = relayRequestId,
            requestPayload = requestPayload,
            plaintext = opened.plaintext,
            acceptedPsks = acceptedSecrets,
        ) { responsePlaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(
                    deviceId = credentials.deviceId,
                    requestId = relayRequestId,
                    clientId = pairing.clientId,
                    clientPsk = opened.clientPsk,
                    devicePrivateKey = credentials.devicePrivateKey,
                    devicePublicKey = credentials.devicePublicKey,
                    request = requestPayload,
                    plaintext = responsePlaintext,
                )
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
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        val response = secretManagement.receiveSecretUpload(
            client = pairing,
            relayRequestId = relayRequestId,
            requestPayload = requestPayload,
            plaintext = opened.plaintext,
            acceptedPsks = acceptedSecrets,
        ) { responsePlaintext ->
            runCatching {
                pairedRequestProtocol.sealPairedResponse(
                    deviceId = credentials.deviceId,
                    requestId = relayRequestId,
                    clientId = pairing.clientId,
                    clientPsk = opened.clientPsk,
                    devicePrivateKey = credentials.devicePrivateKey,
                    devicePublicKey = credentials.devicePublicKey,
                    request = requestPayload,
                    plaintext = responsePlaintext,
                )
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
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        val clientSoftware = runCatching {
            pairingRemoveProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val response = runCatching {
            pairedRequestProtocol.sealPairedResponse(
                deviceId = credentials.deviceId,
                requestId = relayRequestId,
                clientId = pairing.clientId,
                clientPsk = opened.clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
                plaintext = pairingRemoveProtocol.response(),
            )
        }.getOrNull() ?: return null
        val now = currentTimeMillis()
        dao.insertPairingRemoval(
            request = InboxRequestEntity(
                id = relayRequestId,
                parentRequestId = null,
                deviceIdentityId = pairing.deviceIdentityId,
                clientId = pairing.clientId,
                clientNameSnapshot = pairing.name,
                clientSoftwareJson = encodeClientSoftware(clientSoftware),
                kind = RequestKind.PAIRING_REMOVE.storedName,
                state = InboxRequestState.WAITING.storedName,
                listed = false,
                requestJson = requestPayload.toString(),
                responseJson = response.toString(),
                completionJson = null,
                error = null,
                receivedAt = now,
                completedAt = null,
                requestAcknowledged = false,
                responseAcknowledged = false,
                completionAcknowledged = false,
            ),
            requestPsk = acceptedSecrets.requestPsk,
            client = pairing.copy(
                desiredRelayClientState = RelayClientState.REVOKED.wireName,
                lastSeenAt = now,
            ),
        )
        requestSync()
        return ProcessedRelayMessage(response)
    }

    private suspend fun rejectAuthenticatedRequest(
        pairing: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestPsks,
        credentials: RelayDeviceCredentials,
        code: PairedRequestErrorCode,
        now: Long,
    ): ProcessedRelayMessage? {
        val response = runCatching {
            pairedRequestProtocol.sealPairedResponse(
                deviceId = credentials.deviceId,
                requestId = relayRequestId,
                clientId = pairing.clientId,
                clientPsk = opened.clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
                plaintext = pairedRequestProtocol.errorResponse(code),
            )
        }.getOrNull() ?: return null
        if (!persistRejectedRequest {
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
                    completionJson = null,
                    error = code.message,
                    receivedAt = now,
                    completedAt = now,
                    requestAcknowledged = false,
                    responseAcknowledged = false,
                    completionAcknowledged = false,
                ),
                client = pairing.copy(lastSeenAt = now),
                requestPsk = acceptedSecrets.requestPsk,
                currentClientPsk = acceptedSecrets.currentClientPsk,
                previousClientPsk = acceptedSecrets.previousClientPsk,
            )
        }) return null
        audit.record(
            AuditRecord(
                type = AuditEventType.REQUEST_REJECTED,
                outcome = AuditOutcome.REJECTED,
                decisionSource = AuditDecisionSource.VALIDATION,
                detail = code.message,
                clientId = pairing.clientId,
                clientName = pairing.auditClientName(),
                relayRequestId = relayRequestId,
            ),
        )
        return ProcessedRelayMessage(response)
    }

    private suspend fun rejectPendingPairingRequest(
        attempt: PairingAttemptEntity,
        rootRequest: InboxRequestEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedPsks: AcceptedRequestPsks,
        credentials: RelayDeviceCredentials,
        code: PairedRequestErrorCode,
        now: Long,
    ): ProcessedRelayMessage? {
        val response = runCatching {
            pairedRequestProtocol.sealPairedResponse(
                deviceId = credentials.deviceId,
                requestId = relayRequestId,
                clientId = attempt.clientId,
                clientPsk = opened.clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
                plaintext = pairedRequestProtocol.errorResponse(code),
            )
        }.getOrNull() ?: return null
        if (!persistRejectedRequest {
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
                    completionJson = null,
                    error = code.message,
                    receivedAt = now,
                    completedAt = now,
                    requestAcknowledged = false,
                    responseAcknowledged = false,
                    completionAcknowledged = false,
                ),
                client = null,
                requestPsk = acceptedPsks.requestPsk,
                currentClientPsk = null,
                previousClientPsk = null,
            )
        }) return null
        audit.record(
            AuditRecord(
                type = AuditEventType.REQUEST_REJECTED,
                outcome = AuditOutcome.REJECTED,
                decisionSource = AuditDecisionSource.VALIDATION,
                detail = code.message,
                clientId = rootRequest.clientId,
                clientName = attempt.auditClientName(),
                relayRequestId = relayRequestId,
            ),
        )
        return ProcessedRelayMessage(response)
    }

    private suspend fun startPairing(
        credentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
        requestPayload: JsonElement,
    ): ProcessedRelayMessage? {
        if (!pairingProtocol.validateInitialRequest(requestPayload)) return null
        if (message.addressId != credentials.addressId || message.clientId != message.requestId) {
            return null
        }
        if (dao.getClientById(message.clientId) != null) return null
        if (
            !pairingAdmissionAllowed(
                dao.getPairingAttempts().map { pairing -> pairing.state.toPairingState() },
            )
        ) {
            return null
        }

        val deviceRandom = pairingProtocol.generateDeviceRandom()
        val response = pairingProtocol.initialResponse(
            deviceId = credentials.deviceId,
            devicePublicKey = credentials.devicePublicKey,
            deviceRandom = deviceRandom,
        )
        val now = currentTimeMillis()
        val request = InboxRequestEntity(
            id = message.requestId,
            parentRequestId = null,
            deviceIdentityId = credentials.deviceIdentityId,
            clientId = message.clientId,
            clientNameSnapshot = message.clientId,
            clientSoftwareJson = null,
            kind = RequestKind.PAIRING.storedName,
            state = InboxRequestState.WAITING.storedName,
            listed = true,
            requestJson = requestPayload.toString(),
            responseJson = response.toString(),
            completionJson = null,
            error = null,
            receivedAt = now,
            completedAt = null,
            requestAcknowledged = false,
            responseAcknowledged = false,
            completionAcknowledged = false,
        )
        dao.insertPairingRequest(
            request = request,
            attempt = PairingAttemptEntity(
                requestId = message.requestId,
                pairingAddress = credentials.address,
                clientId = message.clientId,
                friendlyName = null,
                deviceRandom = deviceRandom,
                desiredRelayClientState = null,
                relayClientState = RelayClientState.PENDING.wireName,
                state = PairingState.RECEIVING.storedName,
                sasOption0 = null,
                sasOption1 = null,
                sasOption2 = null,
                correctSasIndex = null,
                platform = null,
                architecture = null,
                hostname = null,
                machineId = null,
                osVersion = null,
                pendingPsk = null,
                decidedAt = null,
            ),
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.PAIRING_REQUESTED,
                outcome = AuditOutcome.RECEIVED,
                clientId = message.clientId,
                relayRequestId = message.requestId,
            ),
        )
        return ProcessedRelayMessage(response)
    }

    private fun secretUseRequestEntity(
        requestId: String,
        pairing: ClientEntity,
        contents: InvocationRequestMessage,
        description: RequestedSecretDescription,
        now: Long,
    ) = SecretUseRequestEntity(
        requestId = requestId,
        hostname = pairing.hostname,
        platform = pairing.platform,
        architecture = pairing.architecture,
        machineId = pairing.machineId,
        osVersion = pairing.osVersion,
        invocationTokenHash = invocationTokenHash(contents.invocationToken),
        containsSensitiveMaterial = description.containsSensitiveMaterial,
        secretsJson = encodeStringList(contents.secrets),
        secretDetailsJson = json.encodeToString(description.secrets),
        providedSecretsJson = null,
        missingSecretsJson = encodeStringList(description.missingSecrets),
        reason = contents.reason,
        command = contents.operation.command,
        argumentsJson = encodeStringList(contents.operation.arguments),
        workingDirectory = contents.operation.workingDirectory,
        executablePath = contents.operation.executablePath,
        executableHash = contents.operation.executableHash,
        executableMode = contents.operation.executableMode.wireName,
        stdinKind = contents.operation.stdin.wireName,
        stdoutKind = contents.operation.stdout.wireName,
        stderrKind = contents.operation.stderr.wireName,
        launcherChainJson = encodeStringList(contents.launcherChain),
        decision = null,
        decisionSource = null,
        approvalEvaluationJson = null,
        completionResult = null,
        completionReason = null,
        completionMessage = null,
        decidedAt = null,
    )

    private suspend fun processInitialCompletion(
        credentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        pairing: PairingAttemptEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        if (
            pairing.state != PairingState.RECEIVING.storedName
        ) {
            return ProcessedRelayMessage()
        }
        val now = currentTimeMillis()
        val established = runCatching {
            pairingProtocol.establish(
                deviceId = credentials.deviceId,
                clientId = pairing.clientId,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                deviceRandom = pairing.deviceRandom,
                initialRequest = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
        }
        val result = established.getOrElse { failure ->
            dao.updatePairingRequest(
                request = request.copy(
                    error = failure.message ?: "The pairing message could not be verified.",
                ),
                attempt = pairing,
            )
            return null
        }
        val metadata = runCatching {
            pairingProtocol.decodeClientMetadata(result.applicationPlaintext)
        }.getOrNull()
        val choices = pairingProtocol.sasChoices(result.sas)
        val completedAttempt = material.withEncryptedPendingPsk(
            attempt = pairing.copy(
                state = PairingState.SAS_VERIFICATION_PENDING.storedName,
                sasOption0 = choices.values[0],
                sasOption1 = choices.values[1],
                sasOption2 = choices.values[2],
                correctSasIndex = choices.correctIndex,
                platform = metadata?.platform,
                architecture = metadata?.architecture,
                hostname = metadata?.hostname,
                friendlyName = pairing.friendlyName ?: metadata?.hostname,
                machineId = metadata?.machineId,
                osVersion = metadata?.osVersion,
            ),
            request = request,
            clientPsk = result.clientPsk,
        )
        dao.recordInitialCompletion(
            request = request.copy(
                state = InboxRequestState.ACTION_REQUIRED.storedName,
                clientSoftwareJson = metadata?.clientSoftware?.let(::encodeClientSoftware),
                completionJson = completion.toString(),
                responseAcknowledged = true,
                error = if (metadata == null) {
                    "The client details could not be read, but the security code is valid."
                } else {
                    null
                },
            ),
            attempt = completedAttempt,
        )
        return ProcessedRelayMessage()
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
        if (
            pairing.state != PairingState.RELAY_ACTIVATION_PENDING.storedName &&
            pairing.state != PairingState.WAITING_FOR_FINISH.storedName
        ) return null
        val relayClientState = pairing.relayClientState.toRelayClientState()
        if (
            relayClientState == RelayClientState.PENDING &&
            pairing.desiredRelayClientState != RelayClientState.ACTIVE.wireName
        ) return null
        val prepared = runCatching {
            pairingProtocol.prepareFinishResponse(
                deviceId = credentials.deviceId,
                requestId = relayRequestId,
                clientId = pairing.clientId,
                clientPsk = opened.clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
            )
        }.getOrNull() ?: return null
        val now = currentTimeMillis()
        val clientName = pairing.auditClientName()
        val finishRequest = InboxRequestEntity(
            id = relayRequestId,
            parentRequestId = pairing.requestId,
            deviceIdentityId = rootRequest.deviceIdentityId,
            clientId = rootRequest.clientId,
            clientNameSnapshot = clientName,
            clientSoftwareJson = rootRequest.clientSoftwareJson,
            kind = RequestKind.PAIRING_FINISH.storedName,
            state = InboxRequestState.COMPLETED.storedName,
            listed = false,
            requestJson = requestPayload.toString(),
            responseJson = prepared.response.toString(),
            completionJson = null,
            error = null,
            receivedAt = now,
            completedAt = now,
            requestAcknowledged = false,
            responseAcknowledged = false,
            completionAcknowledged = false,
        )
        val client = if (relayClientState == RelayClientState.REVOKED) {
            null
        } else {
            ClientEntity(
                clientId = pairing.clientId,
                deviceIdentityId = rootRequest.deviceIdentityId,
                name = clientName,
                instructions = "",
                desiredRelayClientState = pairing.desiredRelayClientState
                    ?.takeUnless { it == relayClientState.wireName },
                relayClientState = relayClientState.wireName,
                clientSoftwareJson = rootRequest.clientSoftwareJson,
                platform = pairing.platform,
                architecture = pairing.architecture,
                hostname = pairing.hostname,
                machineId = pairing.machineId,
                osVersion = pairing.osVersion,
                pairedAt = now,
                lastSeenAt = now,
            )
        }
        val clientPsk = client?.let { material.encryptClientPsk(it, opened.clientPsk, now) }
        dao.finishPairing(
            rootRequest = rootRequest.copy(
                state = InboxRequestState.COMPLETED.storedName,
                listed = false,
                completedAt = now,
            ),
            attempt = pairing.copy(
                state = PairingState.COMPLETED.storedName,
                relayClientState = relayClientState.wireName,
                desiredRelayClientState = null,
                pendingPsk = null,
            ),
            client = client,
            clientPsk = clientPsk,
            finishRequest = finishRequest,
            requestPsk = acceptedSecrets.requestPsk,
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.PAIRING_COMPLETED,
                outcome = AuditOutcome.COMPLETED,
                subject = pairing.auditClientName(),
                clientId = pairing.clientId,
                clientName = pairing.auditClientName(),
                relayRequestId = rootRequest.id,
            ),
        )
        return ProcessedRelayMessage(prepared.response)
    }

    private suspend fun processCompletion(
        activeCredentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
    ): ProcessedRelayMessage? {
        val completion = message.completion ?: return null
        val request = dao.getRequestById(message.requestId) ?: return null
        if (request.clientId != message.clientId) return null
        val isInitialPairing = request.kind == RequestKind.PAIRING.storedName
        if (isInitialPairing != (message.addressId != null)) return null
        val processed = when (request.kind) {
            RequestKind.PAIRING.storedName -> {
                val pairing = dao.getPairingAttempt(request.id) ?: return null
                val credentials = credentialsForRequest(request, activeCredentials) ?: return null
                processInitialCompletion(credentials, request, pairing, completion)
            }
            RequestKind.PAIRING_FINISH.storedName -> {
                processFinishCompletion(activeCredentials, request, completion)
            }
            RequestKind.SECRET_USE.storedName -> {
                val completed = invocationRequests.complete(request, completion) {
                    openStoredCompletion(activeCredentials, request, completion)
                }
                if (completed) ProcessedRelayMessage() else null
            }
            RequestKind.GIT_SIGN.storedName -> {
                processGitSignCompletion(activeCredentials, request, completion)
            }
            RequestKind.SSH_AUTHENTICATE.storedName -> {
                processSshAuthenticationCompletion(activeCredentials, request, completion)
            }
            RequestKind.SECRET_LIST.storedName -> {
                processSecretListCompletion(activeCredentials, request, completion)
            }
            RequestKind.SECRET_UPLOAD.storedName -> {
                processSecretUploadCompletion(activeCredentials, request, completion)
            }
            RequestKind.PAIRING_REMOVE.storedName -> {
                processPairingRemoveCompletion(activeCredentials, request, completion)
            }
            RequestKind.UNKNOWN.storedName -> {
                processUnknownCompletion(activeCredentials, request, completion)
            }
            else -> null
        }
        if (processed != null) dao.deleteCompletedRequestPsk(request.id)
        return processed
    }

    private suspend fun processUnknownCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        if (request.completionJson != null) return ProcessedRelayMessage()
        openStoredCompletion(activeCredentials, request, completion) ?: return null
        val now = currentTimeMillis()
        dao.updateRequest(
            request.copy(
                completionJson = completion.toString(),
                responseAcknowledged = true,
                completionAcknowledged = false,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processSecretListCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val processed = secretManagement.completeSecretList(request, completion) {
            openStoredCompletion(activeCredentials, request, completion)
        }
        return if (processed) ProcessedRelayMessage() else null
    }

    private suspend fun processSecretUploadCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val processed = secretManagement.completeSecretUpload(request, completion) {
            openStoredCompletion(activeCredentials, request, completion)
        }
        return if (processed) ProcessedRelayMessage() else null
    }
    private suspend fun processPairingRemoveCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        if (request.completionJson != null) return ProcessedRelayMessage()
        val plaintext = openStoredCompletion(activeCredentials, request, completion) ?: return null
        val verification = runCatching { pairingRemoveProtocol.decodeCompletion(plaintext) }
        if (verification.isSuccess) {
            completePairingRemoval(request, completion)
        } else {
            val now = currentTimeMillis()
            dao.updateRequest(
                request.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    completionJson = completion.toString(),
                    responseAcknowledged = true,
                    completionAcknowledged = false,
                    completedAt = now,
                ),
            )
            audit.record(
                AuditRecord(
                    type = AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED,
                    outcome = AuditOutcome.FAILED,
                    subject = request.clientNameSnapshot,
                    detail = verification.exceptionOrNull()?.message
                        ?: "Client removal completion could not be verified.",
                    clientId = request.clientId,
                    clientName = request.clientNameSnapshot,
                    relayRequestId = request.id,
                ),
            )
        }
        return ProcessedRelayMessage()
    }

    private suspend fun completePairingRemoval(
        request: InboxRequestEntity,
        completion: JsonElement?,
    ) {
        if (request.completedAt != null && completion == null) return
        val firstCompletion = request.completedAt == null
        val now = currentTimeMillis()
        dao.updateRequest(
            request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion?.toString() ?: request.completionJson,
                responseAcknowledged = true,
                completionAcknowledged = if (completion == null) {
                    request.completionAcknowledged
                } else {
                    false
                },
                completedAt = now,
            ),
        )
        val client = dao.getClient(request.clientId)
        if (client != null && client.desiredRelayClientState != RelayClientState.REVOKED.wireName) {
            dao.revokeClient(
                client.copy(
                    desiredRelayClientState = RelayClientState.REVOKED.wireName,
                ),
            )
        }
        dao.deleteTemporaryAccessGrantsForClient(request.clientId)
        if (firstCompletion) {
            audit.record(
                AuditRecord(
                    type = AuditEventType.CLIENT_UNPAIRED_ITSELF,
                    outcome = AuditOutcome.COMPLETED,
                    subject = request.clientNameSnapshot,
                    clientId = request.clientId,
                    clientName = request.clientNameSnapshot,
                    relayRequestId = request.id,
                ),
            )
        }
        requestSync()
    }

    private suspend fun processGitSignCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val gitSign = dao.getGitSignRequest(request.id) ?: return null
        if (request.completedAt != null) return ProcessedRelayMessage()
        val invocationId = request.parentRequestId ?: return null
        val invocation = dao.getSecretUseRequest(invocationId) ?: return null
        val plaintext = openStoredCompletion(activeCredentials, request, completion) ?: return null
        val decoded = runCatching { gitSignProtocol.decodeCompletion(plaintext) }
        val completionResult = decoded.getOrNull()
        val softwareMatches = completionResult?.clientSoftware ==
            request.clientSoftwareJson?.let(::decodeClientSoftware)
        val valid = softwareMatches && when (completionResult) {
            is GitSignCompletion.Approved -> {
                gitSign.decision == ApprovalDecision.APPROVED.storedName
            }
            is GitSignCompletion.Denied -> {
                if (gitSign.decision != ApprovalDecision.DENIED.storedName) {
                    false
                } else {
                    completionResult.reason == (
                        gitSign.completionReason ?: InvocationDenialReason.USER_DENIED.wireName
                    ) && completionResult.message == (
                        gitSign.completionMessage ?: GIT_SIGN_DENIAL_MESSAGE
                    )
                }
            }
            is GitSignCompletion.Aborted -> true
            null -> false
        }
        val now = currentTimeMillis()
        val error = if (valid) null else decoded.exceptionOrNull()?.message
            ?: "Git signing completion did not match the device decision."
        dao.updateGitSignRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion.toString(),
                responseAcknowledged = true,
                completionAcknowledged = false,
                error = error,
                completedAt = now,
            ),
            gitSignRequest = gitSign.copy(
                completionResult = when (completionResult) {
                    is GitSignCompletion.Approved -> ApprovalCompletionResult.APPROVED.storedName
                    is GitSignCompletion.Denied -> ApprovalCompletionResult.DENIED.storedName
                    is GitSignCompletion.Aborted -> ApprovalCompletionResult.ABORTED.storedName
                    null -> null
                },
                completionReason = when (completionResult) {
                    is GitSignCompletion.Denied -> completionResult.reason
                    is GitSignCompletion.Aborted -> completionResult.reason
                    else -> null
                },
                completionMessage = when (completionResult) {
                    is GitSignCompletion.Denied -> completionResult.message
                    is GitSignCompletion.Aborted -> completionResult.message
                    else -> null
                },
            ),
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.GIT_SIGN_COMPLETED,
                outcome = when {
                    !valid -> AuditOutcome.FAILED
                    completionResult is GitSignCompletion.Approved -> AuditOutcome.COMPLETED
                    completionResult is GitSignCompletion.Denied -> AuditOutcome.DENIED
                    else -> AuditOutcome.ABORTED
                },
                subject = gitSign.secretName,
                detail = if (!valid) error else {
                    when (completionResult) {
                        is GitSignCompletion.Denied -> completionResult.message
                        is GitSignCompletion.Aborted -> completionResult.message
                        else -> null
                    }
                },
                clientId = request.clientId,
                clientName = request.clientNameSnapshot,
                relayRequestId = request.id,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processSshAuthenticationCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val authentication = dao.getSshAuthenticationRequest(request.id) ?: return null
        if (request.completedAt != null) return ProcessedRelayMessage()
        val invocationId = request.parentRequestId ?: return null
        val invocation = dao.getSecretUseRequest(invocationId) ?: return null
        val plaintext = openStoredCompletion(activeCredentials, request, completion) ?: return null
        val decoded = runCatching { sshAuthenticationProtocol.decodeCompletion(plaintext) }
        val completionResult = decoded.getOrNull()
        val softwareMatches = completionResult?.clientSoftware ==
            request.clientSoftwareJson?.let(::decodeClientSoftware)
        val valid = softwareMatches && when (completionResult) {
            is SshAuthenticationCompletion.Approved ->
                authentication.decision == ApprovalDecision.APPROVED.storedName
            is SshAuthenticationCompletion.Denied -> {
                authentication.decision == ApprovalDecision.DENIED.storedName &&
                    completionResult.reason == (
                        authentication.completionReason
                            ?: InvocationDenialReason.USER_DENIED.wireName
                    ) &&
                    completionResult.message == (
                        authentication.completionMessage
                            ?: SSH_AUTHENTICATION_DENIAL_MESSAGE
                    )
            }
            is SshAuthenticationCompletion.Aborted -> true
            null -> false
        }
        val now = currentTimeMillis()
        val error = if (valid) null else decoded.exceptionOrNull()?.message
            ?: "SSH authentication completion did not match the device decision."
        dao.updateSshAuthenticationRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion.toString(),
                responseAcknowledged = true,
                completionAcknowledged = false,
                error = error,
                completedAt = now,
            ),
            authentication = authentication.copy(
                completionResult = when (completionResult) {
                    is SshAuthenticationCompletion.Approved ->
                        ApprovalCompletionResult.APPROVED.storedName
                    is SshAuthenticationCompletion.Denied ->
                        ApprovalCompletionResult.DENIED.storedName
                    is SshAuthenticationCompletion.Aborted ->
                        ApprovalCompletionResult.ABORTED.storedName
                    null -> null
                },
                completionReason = when (completionResult) {
                    is SshAuthenticationCompletion.Denied -> completionResult.reason
                    is SshAuthenticationCompletion.Aborted -> completionResult.reason
                    else -> null
                },
                completionMessage = when (completionResult) {
                    is SshAuthenticationCompletion.Denied -> completionResult.message
                    is SshAuthenticationCompletion.Aborted -> completionResult.message
                    else -> null
                },
            ),
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.SSH_AUTHENTICATION_COMPLETED,
                outcome = when {
                    !valid -> AuditOutcome.FAILED
                    completionResult is SshAuthenticationCompletion.Approved ->
                        AuditOutcome.COMPLETED
                    completionResult is SshAuthenticationCompletion.Denied -> AuditOutcome.DENIED
                    else -> AuditOutcome.ABORTED
                },
                subject = authentication.secretName,
                context = authentication.username,
                detail = if (!valid) error else {
                    when (completionResult) {
                        is SshAuthenticationCompletion.Denied -> completionResult.message
                        is SshAuthenticationCompletion.Aborted -> completionResult.message
                        else -> null
                    }
                },
                clientId = request.clientId,
                clientName = request.clientNameSnapshot,
                relayRequestId = request.id,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processFinishCompletion(
        activeCredentials: RelayDeviceCredentials,
        finishRequest: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        if (finishRequest.completionJson != null) {
            return ProcessedRelayMessage()
        }
        val plaintext = openStoredCompletion(
            activeCredentials,
            finishRequest,
            completion,
        ) ?: return null
        val accepted = runCatching {
            pairingProtocol.finishCompletionAccepted(plaintext)
        }.getOrDefault(false)
        val now = currentTimeMillis()
        dao.updateRequest(
            finishRequest.copy(
                completionJson = completion.toString(),
                responseAcknowledged = true,
                completionAcknowledged = false,
            ),
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.PAIRING_CONFIRMATION_RECEIVED,
                outcome = if (accepted) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                subject = finishRequest.clientNameSnapshot,
                detail = if (accepted) null else "The client did not accept the pairing.",
                clientId = finishRequest.clientId,
                clientName = finishRequest.clientNameSnapshot,
                relayRequestId = finishRequest.id,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun credentialsForRequest(
        request: InboxRequestEntity,
        active: RelayDeviceCredentials,
    ): RelayDeviceCredentials? {
        if (request.deviceIdentityId == active.deviceIdentityId) return active
        return when (
            val result = deviceCredentials.deviceCredentials(request.deviceIdentityId)
        ) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            else -> null
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

    private suspend fun credentialsForClient(client: ClientEntity): RelayDeviceCredentials? {
        return when (val result = deviceCredentials.deviceCredentials(client.deviceIdentityId)) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            else -> null
        }
    }

    private suspend fun openStoredCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ByteArray? {
        val credentials = credentialsForRequest(request, activeCredentials) ?: return null
        val clientPsk = material.decryptRequestPsk(request) ?: return null
        return runCatching {
            pairedRequestProtocol.openPairedCompletion(
                deviceId = credentials.deviceId,
                requestId = request.id,
                clientId = request.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
        }.getOrNull()
    }

    private fun String.toPairingState(): PairingState =
        checkNotNull(PairingState.entries.find { it.storedName == this })

    private fun String.toRelayClientState(): RelayClientState =
        checkNotNull(RelayClientState.entries.find { it.wireName == this })

    private fun encodeStringList(values: List<String>): String =
        json.encodeToString(STRING_LIST_SERIALIZER, values)

    private fun invocationTokenHash(token: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token)

    private fun encodeClientSoftware(value: ClientSoftware): String = json.encodeToString(value)

    private fun decodeClientSoftware(value: String): ClientSoftware? =
        runCatching { storedJson.decodeFromString<ClientSoftware>(value) }.getOrNull()

    private companion object {
        const val IDEMPOTENCY_RETENTION_MILLIS = 25 * 60 * 60 * 1_000L
        const val GIT_SIGN_DENIAL_MESSAGE = "Git signature denied on device."
        const val SSH_AUTHENTICATION_DENIAL_MESSAGE = "SSH authentication denied on device."
        const val MAX_AI_REVIEW_GIT_CONTENT_BYTES = 128 * 1024
        val STRING_LIST_SERIALIZER = ListSerializer(String.serializer())
    }

    private fun RequestedSecretDescription.hasSameSecretRevisions(
        other: RequestedSecretDescription,
    ): Boolean = reviewMetadata.associate { it.id to it.revision } ==
        other.reviewMetadata.associate { it.id to it.revision }

}

private fun AiReview.auditExplanation(): String = explanation
    ?.trim()
    ?.let { text ->
        val labels = when (decision) {
            AiReviewDecision.APPROVE -> listOf("Approve:", "Approved:")
            AiReviewDecision.DENY -> listOf("Deny:", "Denied:")
            AiReviewDecision.ASK_USER -> listOf("Ask:", "Ask user:")
            null -> emptyList()
        }
        labels.firstOrNull { text.startsWith(it, ignoreCase = true) }
            ?.let { text.drop(it.length).trimStart() }
            ?: text
    }
    ?.replace("**", "")
    ?.replace("`", "")
    ?.takeIf(String::isNotBlank)
    ?: "AI review did not provide an explanation."

private fun AiReview.auditOutcome(): AuditOutcome = when {
    failure != null -> AuditOutcome.FAILED
    decision == AiReviewDecision.APPROVE -> AuditOutcome.APPROVED
    decision == AiReviewDecision.DENY -> AuditOutcome.DENIED
    decision == AiReviewDecision.ASK_USER -> AuditOutcome.DEFERRED
    else -> AuditOutcome.FAILED
}

private fun PairingAttemptEntity.auditClientName(): String =
    sequenceOf(friendlyName, hostname, clientId)
        .filterNotNull()
        .first(String::isNotBlank)

private fun ClientEntity.auditClientName(): String = name

private val INCOMPLETE_PAIRING_STATES = setOf(
    PairingState.RECEIVING,
    PairingState.SAS_VERIFICATION_PENDING,
    PairingState.RELAY_ACTIVATION_PENDING,
    PairingState.WAITING_FOR_FINISH,
)

private val USER_REJECTABLE_PAIRING_STATES = setOf(
    PairingState.RECEIVING,
    PairingState.SAS_VERIFICATION_PENDING,
    PairingState.RELAY_ACTIVATION_PENDING,
    PairingState.WAITING_FOR_FINISH,
)
