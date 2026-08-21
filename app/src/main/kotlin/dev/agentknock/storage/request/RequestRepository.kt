package dev.agentknock.storage.request

import dev.agentknock.protocol.SecretUseCompletion
import dev.agentknock.protocol.SecretUseDenialReason
import dev.agentknock.protocol.SecretUseProtocol
import dev.agentknock.protocol.SecretUseRequestMessage
import dev.agentknock.protocol.OpenedPairedRequest
import dev.agentknock.protocol.PairedRequestKeySource
import dev.agentknock.protocol.PairedRequestProtocol
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.protocol.PairingRemoveProtocol
import dev.agentknock.protocol.SecretListSecret
import dev.agentknock.protocol.SecretListProtocol
import dev.agentknock.protocol.SecretListRequestMessage
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.protocol.SecretUploadProtocol
import dev.agentknock.protocol.SecretUploadRequestMessage
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.presentation.renderSingleLineText
import dev.agentknock.relay.RelayClientState
import dev.agentknock.relay.RelayDeviceClient
import dev.agentknock.relay.RelayDeviceConnection
import dev.agentknock.relay.RelayDeviceConnectionResult
import dev.agentknock.relay.RelayDeviceEvent
import dev.agentknock.relay.RelayDeviceFrame
import dev.agentknock.relay.RelayExchangeState
import dev.agentknock.relay.RelayMessageKind
import dev.agentknock.relay.RelayMessageState
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.protocol.SecretUseResponseSecret
import dev.agentknock.storage.secret.RequestedSecretsResult
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.ApplyEnvironmentSecretUploadResult
import dev.agentknock.storage.secret.EnvironmentSecretUpload
import dev.agentknock.storage.secret.EnvironmentSecretUploadResult
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.audit.AuditCategory
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.NoOpAuditSink
import dev.agentknock.storage.vault.RelayDeviceCredentials
import dev.agentknock.storage.vault.RelayDeviceCredentialsResult
import dev.agentknock.storage.vault.RelayDeviceCredentialSource
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

internal enum class InboxRequestState(val storedName: String) {
    RECEIVING("receiving"),
    ACTION_REQUIRED("action_required"),
    WAITING("waiting"),
    COMPLETED("completed"),
}

internal enum class PairingState(val storedName: String) {
    RECEIVING("receiving"),
    SAS_VERIFICATION_PENDING("sas_verification_pending"),
    RELAY_ACTIVATION_PENDING("relay_activation_pending"),
    WAITING_FOR_FINISH("waiting_for_finish"),
    REJECTED("rejected"),
    ACTIVE("active"),
    VERIFICATION_FAILED("verification_failed"),
}

internal enum class InboxRequestKind {
    PAIRING,
    SECRET_USE,
    SECRET_UPLOAD,
}

internal enum class SecretUseRequestState(val storedName: String) {
    APPROVAL_PENDING("approval_pending"),
    WAITING_FOR_COMPLETION("waiting_for_completion"),
    COMPLETED("completed"),
    VERIFICATION_FAILED("verification_failed"),
}

internal enum class SecretUseDecision(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
}

internal enum class SecretUseCompletionResult(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
    ABORTED("aborted"),
}

internal enum class SecretListRequestState(val storedName: String) {
    WAITING_FOR_COMPLETION("waiting_for_completion"),
    COMPLETED("completed"),
    VERIFICATION_FAILED("verification_failed"),
}

internal enum class SecretUploadRequestState(val storedName: String) {
    REVIEW_PENDING("review_pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    VERIFICATION_FAILED("verification_failed"),
}

private data class IncomingRelayMessage(
    val clientId: String,
    val requestId: String,
    val request: JsonElement? = null,
    val completion: JsonElement? = null,
    val addressId: String? = null,
)

private data class ProcessedRelayMessage(val response: JsonElement? = null)

private data class AcceptedRequestSecrets(
    val requestSecret: RequestSecretEntity,
    val currentPairingSecret: PairingSecretEntity?,
    val previousPairingSecret: PairingSecretEntity?,
)

private fun AcceptedRequestSecrets.withoutRotationUnless(allowed: Boolean): AcceptedRequestSecrets =
    if (allowed) this else copy(currentPairingSecret = null, previousPairingSecret = null)

internal fun pairingAdmissionAllowed(existingStates: Iterable<PairingState>): Boolean =
    existingStates.none { it in INCOMPLETE_PAIRING_STATES }

internal fun previousPskEligible(updatedAt: Long, now: Long): Boolean =
    now >= updatedAt && now - updatedAt <= PREVIOUS_PSK_OVERLAP_MILLIS

internal data class InboxRequestSummary(
    val id: Long,
    val kind: InboxRequestKind,
    val state: InboxRequestState,
    val pairingState: PairingState?,
    val secretUseState: SecretUseRequestState?,
    val secretUseDecision: SecretUseDecision?,
    val secretUseResult: SecretUseCompletionResult?,
    val secretUseCompletionReason: String?,
    val secretListState: SecretListRequestState?,
    val secretUploadState: SecretUploadRequestState?,
    val title: String,
    val clientName: String,
    val secretNames: List<String>,
    val listSummary: String?,
    val command: String?,
    val arguments: List<String>,
    val receivedAt: Long,
    val completedAt: Long?,
)

internal data class PairingRequestDetails(
    val pairingState: PairingState,
    val clientName: String,
    val pairingAddress: String,
    val clientId: String,
    val sasOptions: List<String>,
    val cliVersion: String?,
    val platform: String?,
    val architecture: String?,
    val hostname: String?,
    val machineId: String?,
    val osVersion: String?,
    val error: String?,
    val decidedAt: Long?,
)

internal data class InboxRequestDetails(
    val id: Long,
    val relayRequestId: String,
    val state: InboxRequestState,
    val receivedAt: Long,
    val completedAt: Long?,
    val pairing: PairingRequestDetails?,
    val secretUse: SecretUseRequestDetails?,
    val secretList: SecretListRequestDetails?,
    val secretUpload: SecretUploadRequestDetails?,
)

internal data class SecretListRequestDetails(
    val state: SecretListRequestState,
    val secrets: List<SecretMetadata>,
    val pairingAddress: String,
    val clientId: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    val machineId: String?,
    val osVersion: String?,
    val cliVersion: String,
    val error: String?,
)

internal data class SecretUseRequestDetails(
    val state: SecretUseRequestState,
    val decision: SecretUseDecision?,
    val completionResult: SecretUseCompletionResult?,
    val completionReason: String?,
    val completionMessage: String?,
    val secrets: List<String>,
    val secretDetails: List<SecretMetadata>,
    val missingSecrets: List<String>,
    val reason: String?,
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val executablePath: String,
    val executableHash: String?,
    val executableMode: String,
    val stdinKind: String,
    val stdoutKind: String,
    val stderrKind: String,
    val launcherChain: List<String>,
    val pairingAddress: String,
    val clientId: String,
    val clientName: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    val machineId: String?,
    val osVersion: String?,
    val cliVersion: String,
    val error: String?,
    val decidedAt: Long?,
)

internal data class SecretUploadRequestDetails(
    val state: SecretUploadRequestState,
    val mode: SecretUploadMode,
    val uploadedName: String,
    val approvedName: String?,
    val descriptionProvided: Boolean,
    val description: String?,
    val secretType: String,
    val variableNames: List<String>,
    val variables: List<SecretUploadVariableDetails>,
    val addedVariables: List<String>,
    val changedVariables: List<String>,
    val unchangedVariables: List<String>,
    val removedVariables: List<String>,
    val clientName: String,
    val clientId: String,
    val error: String?,
    val decidedAt: Long?,
)

internal data class SecretUploadVariableDetails(
    val id: String,
    val name: String,
    val sensitive: Boolean,
)

internal sealed interface SecretUploadVariableValue {
    data class Available(val value: String) : SecretUploadVariableValue
    data object NotFound : SecretUploadVariableValue
    data object Unavailable : SecretUploadVariableValue
    data object Corrupted : SecretUploadVariableValue
    data object UnsupportedEncryption : SecretUploadVariableValue
}

internal data class ClientSummary(
    val clientId: String,
    val name: String,
    val hostname: String?,
    val platform: String?,
    val state: RelayClientState,
    val desiredState: RelayClientState?,
    val pairedAt: Long?,
)

internal data class ClientDetails(
    val clientId: String,
    val name: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    val osVersion: String?,
    val machineId: String?,
    val cliVersion: String?,
    val state: RelayClientState,
    val desiredState: RelayClientState?,
    val pairedAt: Long?,
)

internal data class RequestNotification(
    val requestId: Long,
    val title: String,
    val summary: String,
    val details: List<RequestNotificationDetail>,
    val secretUseDecisionAvailable: Boolean,
)

internal data class RequestNotificationDetail(
    val label: String?,
    val value: String,
)

internal sealed interface RequestSyncResult {
    data object Success : RequestSyncResult

    data object NoDevice : RequestSyncResult

    data object DeviceCredentialsUnavailable : RequestSyncResult

    data object DeviceCredentialsCorrupted : RequestSyncResult

    data object UnsupportedDeviceCredentialEncryption : RequestSyncResult

    data class RelayRejected(val status: Int, val message: String?) : RequestSyncResult

    data class RelayUnavailable(val message: String?) : RequestSyncResult

    data object InvalidRelayResponse : RequestSyncResult
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

internal sealed interface SecretUseDecisionResult {
    data object Decided : SecretUseDecisionResult

    data object SecretsChanged : SecretUseDecisionResult

    data object NotPending : SecretUseDecisionResult

    data object NotFound : SecretUseDecisionResult

    data class MissingSecrets(val names: List<String>) : SecretUseDecisionResult

    data class ConflictingVariable(val name: String) : SecretUseDecisionResult

    data object SecretUnavailable : SecretUseDecisionResult

    data object SecretCorrupted : SecretUseDecisionResult

    data object UnsupportedEncryption : SecretUseDecisionResult

    data object PairingUnavailable : SecretUseDecisionResult
}

internal sealed interface SecretUploadDecisionResult {
    data class Approved(val secretId: String) : SecretUploadDecisionResult
    data object Rejected : SecretUploadDecisionResult
    data object NotPending : SecretUploadDecisionResult
    data object NotFound : SecretUploadDecisionResult
    data class Invalid(val message: String) : SecretUploadDecisionResult
    data object SecretUnavailable : SecretUploadDecisionResult
    data object SecretCorrupted : SecretUploadDecisionResult
    data object UnsupportedEncryption : SecretUploadDecisionResult
}

internal enum class ClientChangeResult {
    CHANGED,
    NOT_FOUND,
    INVALID_STATE,
}

internal class RequestRepository(
    private val dao: RequestDao,
    private val deviceCredentials: RelayDeviceCredentialSource,
    private val secrets: SecretRepository,
    private val relay: RelayDeviceClient,
    private val keyManager: VaultKeyManager,
    private val encryption: AesGcmEncryption,
    private val audit: AuditSink = NoOpAuditSink,
    private val requestPushRegistration: () -> Unit = {},
    private val pairingProtocol: PairingProtocol = PairingProtocol(),
    private val pairedRequestProtocol: PairedRequestProtocol = PairedRequestProtocol(),
    private val secretUseProtocol: SecretUseProtocol = SecretUseProtocol(),
    private val secretListProtocol: SecretListProtocol = SecretListProtocol(),
    private val secretUploadProtocol: SecretUploadProtocol = SecretUploadProtocol(),
    private val pairingRemoveProtocol: PairingRemoveProtocol = PairingRemoveProtocol(),
    private val json: Json = Json,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val operationMutex = Mutex()
    private val connectionMutex = Mutex()
    private val pendingChanges = Channel<Unit>(Channel.CONFLATED)
    private val _pushRegistrationState = MutableStateFlow<RelayPushRegistrationState?>(null)

    val pushRegistrationState: StateFlow<RelayPushRegistrationState?> =
        _pushRegistrationState.asStateFlow()

    fun observeRequests(): Flow<List<InboxRequestSummary>> = combine(
        dao.observeListedRequests(),
        dao.observePairings(),
        dao.observeSecretUseRequests(),
        dao.observeSecretUploadRequests(),
    ) { requests, pairings, secretUseRequests, secretUploadRequests ->
        val pairingByRequest = pairings.associateBy(PairingEntity::requestId)
        val pairingByClient = pairings.associateBy(PairingEntity::clientId)
        val secretUseByRequest = secretUseRequests.associateBy(SecretUseRequestEntity::requestId)
        val secretUploadByRequest = secretUploadRequests.associateBy(
            SecretUploadRequestEntity::requestId,
        )
        requests.mapNotNull { request ->
            when (request.kind) {
                RequestKind.PAIRING.storedName -> {
                    val pairing = pairingByRequest[request.id] ?: return@mapNotNull null
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.PAIRING,
                        state = request.state.toInboxRequestState(),
                        pairingState = pairing.state.toPairingState(),
                        secretUseState = null,
                        secretUseDecision = null,
                        secretUseResult = null,
                        secretUseCompletionReason = null,
                        secretListState = null,
                        secretUploadState = null,
                        title = "Pairing",
                        clientName = pairing.friendlyName ?: pairing.hostname
                            ?: pairing.platform ?: "Unknown client",
                        secretNames = emptyList(),
                        listSummary = null,
                        command = null,
                        arguments = emptyList(),
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                    )
                }
                RequestKind.SECRET_USE.storedName -> {
                    val secretUse = secretUseByRequest[request.id] ?: return@mapNotNull null
                    val requestedSecrets = decodeStringList(secretUse.secretsJson)
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.SECRET_USE,
                        state = request.state.toInboxRequestState(),
                        pairingState = null,
                        secretUseState = secretUse.state.toSecretUseRequestState(),
                        secretUseDecision = secretUse.decision?.toSecretUseDecision(),
                        secretUseResult = secretUse.completionResult
                            ?.toSecretUseCompletionResult(),
                        secretUseCompletionReason = secretUse.completionReason,
                        secretListState = null,
                        secretUploadState = null,
                        title = "Secret use",
                        clientName = secretUse.clientName,
                        secretNames = requestedSecrets,
                        listSummary = null,
                        command = secretUse.command,
                        arguments = decodeStringList(secretUse.argumentsJson),
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                    )
                }
                RequestKind.SECRET_UPLOAD.storedName -> {
                    val upload = secretUploadByRequest[request.id] ?: return@mapNotNull null
                    val pairing = pairingByClient[upload.clientId]
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.SECRET_UPLOAD,
                        state = request.state.toInboxRequestState(),
                        pairingState = null,
                        secretUseState = null,
                        secretUseDecision = null,
                        secretUseResult = null,
                        secretUseCompletionReason = null,
                        secretListState = null,
                        secretUploadState = upload.state.toSecretUploadRequestState(),
                        title = "Secret upload",
                        clientName = pairing?.friendlyName ?: pairing?.hostname ?: "Unknown client",
                        secretNames = listOf(upload.uploadedName),
                        listSummary = upload.listSummary(),
                        command = null,
                        arguments = emptyList(),
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                    )
                }
                else -> null
            }
        }
    }

    private fun SecretUploadRequestEntity.listSummary(): String {
        fun count(json: String): Int = decodeStringList(json).size
        if (mode == SecretUploadMode.UPDATE.wireName) {
            return buildList {
                count(addedVariablesJson).takeIf { it > 0 }?.let { add("$it added") }
                count(changedVariablesJson).takeIf { it > 0 }?.let { add("$it updated") }
                count(removedVariablesJson).takeIf { it > 0 }?.let { add("$it removed") }
            }.ifEmpty {
                listOf("No environment variable changes")
            }.joinToString(" · ")
        }
        val count = count(variableNamesJson)
        return "$count ${if (count == 1) "environment variable" else "environment variables"}"
    }

    fun observeRequest(id: Long): Flow<InboxRequestDetails?> = combine(
        dao.observeRequest(id),
        dao.observePairing(id),
        dao.observeSecretUseRequest(id),
        dao.observeSecretListRequest(id),
        dao.observeSecretUploadRequest(id),
    ) { request, pairing, secretUse, secretList, secretUpload ->
        if (request == null) return@combine null
        InboxRequestDetails(
            id = request.id,
            relayRequestId = request.relayRequestId,
            state = request.state.toInboxRequestState(),
            receivedAt = request.receivedAt,
            completedAt = request.completedAt,
            pairing = pairing?.let {
                PairingRequestDetails(
                    pairingState = it.state.toPairingState(),
                    clientName = it.friendlyName ?: it.hostname ?: it.platform ?: "Unknown client",
                    pairingAddress = it.pairingAddress,
                    clientId = it.clientId,
                    sasOptions = listOfNotNull(
                        it.sasOption0,
                        it.sasOption1,
                        it.sasOption2,
                    ).map(pairingProtocol::formatSas),
                    cliVersion = it.cliVersion,
                    platform = it.platform,
                    architecture = it.architecture,
                    hostname = it.hostname,
                    machineId = it.machineId,
                    osVersion = it.osVersion,
                    error = it.error,
                    decidedAt = it.decidedAt,
                )
            },
            secretUse = secretUse?.let {
                SecretUseRequestDetails(
                    state = it.state.toSecretUseRequestState(),
                    decision = it.decision?.toSecretUseDecision(),
                    completionResult = it.completionResult?.toSecretUseCompletionResult(),
                    completionReason = it.completionReason,
                    completionMessage = it.completionMessage,
                    secrets = decodeStringList(it.secretsJson),
                    secretDetails = json.decodeFromString(it.secretDetailsJson),
                    missingSecrets = decodeStringList(it.missingSecretsJson),
                    reason = it.reason,
                    command = it.command,
                    arguments = decodeStringList(it.argumentsJson),
                    workingDirectory = it.workingDirectory,
                    executablePath = it.executablePath,
                    executableHash = it.executableHash,
                    executableMode = it.executableMode,
                    stdinKind = it.stdinKind,
                    stdoutKind = it.stdoutKind,
                    stderrKind = it.stderrKind,
                    launcherChain = decodeStringList(it.launcherChainJson),
                    pairingAddress = it.pairingAddress,
                    clientId = it.clientId,
                    clientName = it.clientName,
                    hostname = it.hostname,
                    platform = it.platform,
                    architecture = it.architecture,
                    machineId = it.machineId,
                    osVersion = it.osVersion,
                    cliVersion = it.cliVersion,
                    error = it.error,
                    decidedAt = it.decidedAt,
                )
            },
            secretList = secretList?.let {
                SecretListRequestDetails(
                    state = it.state.toSecretListRequestState(),
                    secrets = json.decodeFromString(it.secretsJson),
                    pairingAddress = it.pairingAddress,
                    clientId = it.clientId,
                    hostname = it.hostname,
                    platform = it.platform,
                    architecture = it.architecture,
                    machineId = it.machineId,
                    osVersion = it.osVersion,
                    cliVersion = it.cliVersion,
                    error = it.error,
                )
            },
            secretUpload = secretUpload?.let {
                SecretUploadRequestDetails(
                    state = it.state.toSecretUploadRequestState(),
                    mode = SecretUploadMode.entries.single { mode -> mode.wireName == it.mode },
                    uploadedName = it.uploadedName,
                    approvedName = it.approvedName,
                    descriptionProvided = it.descriptionProvided,
                    description = it.description,
                    secretType = it.secretType,
                    variableNames = decodeStringList(it.variableNamesJson),
                    variables = emptyList(),
                    addedVariables = decodeStringList(it.addedVariablesJson),
                    changedVariables = decodeStringList(it.changedVariablesJson),
                    unchangedVariables = decodeStringList(it.unchangedVariablesJson),
                    removedVariables = decodeStringList(it.removedVariablesJson),
                    clientName = it.clientName,
                    clientId = it.clientId,
                    error = it.error,
                    decidedAt = it.decidedAt,
                )
            },
        )
    }.combine(dao.observeSecretUploadVariables(id)) { details, variables ->
        details?.copy(
            secretUpload = details.secretUpload?.copy(
                variables = variables.map {
                    SecretUploadVariableDetails(it.id, it.name, it.sensitive)
                },
            ),
        )
    }

    fun observeClients(): Flow<List<ClientSummary>> = dao.observePairings().map { pairings ->
        pairings
            .filter { it.state == PairingState.ACTIVE.storedName }
            .mapNotNull { pairing ->
                val state = pairing.relayClientState?.toRelayClientState() ?: return@mapNotNull null
                val desiredState = pairing.desiredRelayClientState?.toRelayClientState()
                if (state == RelayClientState.REVOKED || desiredState == RelayClientState.REVOKED) {
                    return@mapNotNull null
                }
                ClientSummary(
                    clientId = pairing.clientId,
                    name = pairing.friendlyName ?: pairing.hostname ?: "Unnamed client",
                    hostname = pairing.hostname,
                    platform = pairing.platform,
                    state = state,
                    desiredState = desiredState,
                    pairedAt = pairing.completedAt,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun observeClient(clientId: String): Flow<ClientDetails?> =
        dao.observePairingByClientId(clientId).map { pairing ->
            pairing?.takeIf {
                it.state == PairingState.ACTIVE.storedName &&
                    it.relayClientState != RelayClientState.REVOKED.wireName &&
                    it.desiredRelayClientState != RelayClientState.REVOKED.wireName
            }?.let {
                ClientDetails(
                    clientId = it.clientId,
                    name = it.friendlyName ?: it.hostname ?: "Unnamed client",
                    hostname = it.hostname,
                    platform = it.platform,
                    architecture = it.architecture,
                    osVersion = it.osVersion,
                    machineId = it.machineId,
                    cliVersion = it.cliVersion,
                    state = it.relayClientState?.toRelayClientState() ?: RelayClientState.PENDING,
                    desiredState = it.desiredRelayClientState?.toRelayClientState(),
                    pairedAt = it.completedAt,
                )
            }
        }

    fun observeRequestCount(): Flow<Int> = dao.observeListedRequestCount()

    suspend fun clearCompletedHistory(): Int = dao.clearCompletedHistory()

    suspend fun sync(): RequestSyncResult = runConnection(keepConnected = false)

    suspend fun listen(
        onCaughtUp: () -> Unit,
        onInboxChanged: suspend () -> Unit = {},
    ): RequestSyncResult = runConnection(
        keepConnected = true,
        onCaughtUp = onCaughtUp,
        onInboxChanged = onInboxChanged,
    )

    suspend fun pendingNotifications(): List<RequestNotification> =
        dao.getActionRequiredRequests().mapNotNull { request ->
            when (request.kind) {
                RequestKind.SECRET_USE.storedName -> {
                    val secretUse = dao.getSecretUseRequest(request.id) ?: return@mapNotNull null
                    if (secretUse.state != SecretUseRequestState.APPROVAL_PENDING.storedName) {
                        return@mapNotNull null
                    }
                    val secrets = decodeStringList(secretUse.secretsJson)
                    val arguments = decodeStringList(secretUse.argumentsJson)
                    val command = renderShellCommand(secretUse.command, arguments)
                    val clientName = renderSingleLineText(secretUse.clientName)
                    val secretNames = secrets.joinToString(transform = ::renderSingleLineText)
                    RequestNotification(
                        requestId = request.id,
                        title = "Secret use requested",
                        summary = "$clientName requests $secretNames",
                        details = listOfNotNull(
                            RequestNotificationDetail("Client", clientName),
                            secretUse.reason?.takeIf(String::isNotBlank)?.let {
                                RequestNotificationDetail("Reason", renderSingleLineText(it))
                            },
                            RequestNotificationDetail("Command", command),
                            RequestNotificationDetail("Secrets", secretNames),
                        ),
                        secretUseDecisionAvailable = true,
                    )
                }
                RequestKind.PAIRING.storedName -> {
                    val pairing = dao.getPairing(request.id) ?: return@mapNotNull null
                    RequestNotification(
                        requestId = request.id,
                        title = "Pairing requested",
                        summary = renderSingleLineText(
                            pairing.friendlyName ?: pairing.hostname ?:
                                pairing.platform ?: "Unknown client",
                        ),
                        details = listOf(RequestNotificationDetail(null, when (pairing.state.toPairingState()) {
                            PairingState.RECEIVING -> pairing.error ?:
                                "Waiting for the client to complete the secure exchange."
                            PairingState.SAS_VERIFICATION_PENDING -> "Open Agentknock and compare the security code."
                            PairingState.RELAY_ACTIVATION_PENDING,
                            PairingState.WAITING_FOR_FINISH,
                            -> "The pairing is still waiting for the client and can be rejected."
                            PairingState.VERIFICATION_FAILED -> pairing.error ?:
                                "The pairing message could not be verified."
                            else -> "Open Agentknock to review this pairing."
                        })),
                        secretUseDecisionAvailable = false,
                    )
                }
                RequestKind.SECRET_UPLOAD.storedName -> {
                    val upload = dao.getSecretUploadRequest(request.id) ?: return@mapNotNull null
                    if (upload.state != SecretUploadRequestState.REVIEW_PENDING.storedName) {
                        return@mapNotNull null
                    }
                    RequestNotification(
                        requestId = request.id,
                        title = "Secret upload received",
                        summary = "${renderSingleLineText(upload.clientName)} wants to " +
                            "${upload.mode.lowercase()} ${renderSingleLineText(upload.uploadedName)}",
                        details = listOf(
                            RequestNotificationDetail(
                                "Client",
                                renderSingleLineText(upload.clientName),
                            ),
                            RequestNotificationDetail(
                                "Change",
                                "${upload.mode.lowercase().replaceFirstChar(Char::uppercase)} " +
                                    renderSingleLineText(upload.uploadedName),
                            ),
                            RequestNotificationDetail("Type", "Environment variables"),
                            RequestNotificationDetail(
                                "Environment variables",
                                upload.variableNamesJson.let(::decodeStringList)
                                    .joinToString(transform = ::renderSingleLineText),
                            ),
                        ),
                        secretUseDecisionAvailable = false,
                    )
                }
                else -> null
            }
        }

    fun requestSync() {
        pendingChanges.trySend(Unit)
    }

    private suspend fun runConnection(
        keepConnected: Boolean,
        onCaughtUp: () -> Unit = {},
        onInboxChanged: suspend () -> Unit = {},
    ): RequestSyncResult = connectionMutex.withLock {
        val credentials = when (val result = deviceCredentials.activeDeviceCredentials()) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            RelayDeviceCredentialsResult.Missing -> return@withLock RequestSyncResult.NoDevice
            RelayDeviceCredentialsResult.CredentialsUnavailable -> {
                return@withLock RequestSyncResult.DeviceCredentialsUnavailable
            }
            RelayDeviceCredentialsResult.CredentialsCorrupted -> {
                return@withLock RequestSyncResult.DeviceCredentialsCorrupted
            }
            RelayDeviceCredentialsResult.UnsupportedEncryption -> {
                return@withLock RequestSyncResult.UnsupportedDeviceCredentialEncryption
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
                return@withLock RequestSyncResult.RelayRejected(result.status, result.message)
            }
            is RelayDeviceConnectionResult.Unavailable -> {
                return@withLock RequestSyncResult.RelayUnavailable(result.message)
            }
            RelayDeviceConnectionResult.InvalidResponse -> {
                return@withLock RequestSyncResult.InvalidRelayResponse
            }
        }

        try {
            synchronize(
                credentials = credentials,
                connection = connection,
                keepConnected = keepConnected,
                onCaughtUp = onCaughtUp,
                onInboxChanged = onInboxChanged,
            )
        } finally {
            connection.close()
        }
    }

    private suspend fun synchronize(
        credentials: RelayDeviceCredentials,
        connection: RelayDeviceConnection,
        keepConnected: Boolean,
        onCaughtUp: () -> Unit,
        onInboxChanged: suspend () -> Unit,
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
                            val now = currentTimeMillis()
                            if (event.kind == RelayMessageKind.REQUEST) {
                                dao.markRequestAcknowledged(event.requestId, now)
                            } else {
                                dao.markCompletionAcknowledged(event.requestId, now)
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
                                    RelayDeviceFrame.Message(
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
                            dao.markResponseAcknowledged(event.requestId, currentTimeMillis())
                            awaitingResponses -= event.requestId
                        }
                        null
                    }
                    is RelayDeviceEvent.Receipt -> {
                        if (event.kind == RelayMessageKind.RESPONSE) {
                            dao.markResponseAcknowledged(event.requestId, currentTimeMillis())
                            awaitingResponses -= event.requestId
                        }
                        null
                    }
                    is RelayDeviceEvent.ClientState -> {
                        applyClientState(event)
                        if (awaitingClientStates[event.clientId] == event.state) {
                            awaitingClientStates -= event.clientId
                        }
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
                            dao.markResponseAcknowledged(event.requestId, currentTimeMillis())
                            awaitingResponses -= event.requestId
                        }
                        null
                    }
                    is RelayDeviceEvent.Error -> if (event.retryable) {
                        RequestSyncResult.RelayUnavailable(event.message)
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
            onInboxChanged()
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
            for (pairing in dao.getPairings()) {
                if (pairing.deviceIdentityId != credentials.deviceIdentityId) continue
                val desired = pairing.desiredRelayClientState?.toRelayClientState() ?: continue
                if (pairing.relayClientState == desired.wireName) continue
                if (awaitingClientStates[pairing.clientId] == desired) continue
                if (!connection.send(RelayDeviceFrame.SetClientState(pairing.clientId, desired))) {
                    return@withLock RequestSyncResult.RelayUnavailable(
                        "Could not send relay client state.",
                    )
                }
                awaitingClientStates[pairing.clientId] = desired
            }

            for (request in dao.getUnacknowledgedResponses()) {
                if (request.relayRequestId in awaitingResponses) continue
                val pairing = pairingForRequest(request) ?: continue
                if (pairing.deviceIdentityId != credentials.deviceIdentityId) continue
                if (
                    !connection.send(
                        RelayDeviceFrame.Message(
                            clientId = pairing.clientId,
                            requestId = request.relayRequestId,
                            payload = json.parseToJsonElement(checkNotNull(request.responseJson)),
                        ),
                    )
                ) {
                    return@withLock RequestSyncResult.RelayUnavailable(
                        "Could not send relay response.",
                    )
                }
                awaitingResponses += request.relayRequestId
            }

            for (request in dao.getUnsettledRequests()) {
                if (request.requestAcknowledgedAt == null) continue
                if (request.relayRequestId in awaitingStates) continue
                val pairing = pairingForRequest(request) ?: continue
                if (pairing.deviceIdentityId != credentials.deviceIdentityId) continue
                if (
                    !connection.send(
                        RelayDeviceFrame.Resume(pairing.clientId, request.relayRequestId),
                    )
                ) {
                    return@withLock RequestSyncResult.RelayUnavailable(
                        "Could not resume relay exchange.",
                    )
                }
                awaitingStates += request.relayRequestId
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
        val pairing = dao.getPairingByClientId(event.clientId) ?: return
        val now = currentTimeMillis()
        val activated = event.state == RelayClientState.ACTIVE &&
            pairing.state == PairingState.RELAY_ACTIVATION_PENDING.storedName
        val updated = pairing.copy(
                state = if (activated) {
                    PairingState.WAITING_FOR_FINISH.storedName
                } else {
                    pairing.state
                },
                relayClientState = event.state.wireName,
                updatedAt = now,
            )
        if (event.state == RelayClientState.REVOKED) {
            dao.revokePairing(updated)
        } else {
            dao.updatePairing(updated)
        }
        if (pairing.relayClientState != event.state.wireName && !activated) {
            audit.record(
                AuditRecord(
                    category = AuditCategory.CLIENT,
                    title = when (event.state) {
                        RelayClientState.ACTIVE -> "Client resumed"
                        RelayClientState.SUSPENDED -> "Client suspended"
                        RelayClientState.REVOKED -> "Client revoked"
                        RelayClientState.PENDING -> "Client pending"
                    },
                    detail = pairing.friendlyName ?: pairing.hostname ?: pairing.clientId,
                    outcome = AuditOutcome.CHANGED,
                    clientId = pairing.clientId,
                ),
            )
        }
    }

    private suspend fun applyRelayState(event: RelayDeviceEvent.State) {
        val now = currentTimeMillis()
        if (event.request == RelayMessageState.DELIVERED ||
            event.request == RelayMessageState.DISCARDED
        ) {
            dao.markRequestAcknowledged(event.requestId, now)
        }
        if (event.response == RelayMessageState.ACCEPTED ||
            event.response == RelayMessageState.DELIVERED ||
            event.response == RelayMessageState.DISCARDED
        ) {
            dao.markResponseAcknowledged(event.requestId, now)
        }
        if (event.response == RelayMessageState.DELIVERED) {
            val request = dao.getRequestByRelayId(event.requestId)
            if (request?.kind == RequestKind.PAIRING_REMOVE.storedName) {
                val pairing = pairingForRequest(request)
                if (pairing != null) completePairingRemoval(request, pairing, completion = null)
            }
        }
        if (event.completion == RelayMessageState.DELIVERED ||
            event.completion == RelayMessageState.DISCARDED
        ) {
            dao.markCompletionAcknowledged(event.requestId, now)
        }
        if (event.exchange == RelayExchangeState.EXPIRED) {
            expireRequest(event.requestId, now)
        }
    }

    private suspend fun expireRequest(relayRequestId: String, now: Long) {
        val request = dao.getRequestByRelayId(relayRequestId) ?: return
        if (request.completedAt != null) return
        val message = "The relay exchange expired before it completed."
        when (request.kind) {
            RequestKind.PAIRING.storedName -> {
                val pairing = dao.getPairing(request.id) ?: return
                if (pairing.state == PairingState.RECEIVING.storedName) {
                    dao.updatePairingRequest(
                        request.copy(updatedAt = now),
                        pairing.copy(error = message, updatedAt = now),
                    )
                }
            }
            RequestKind.SECRET_USE.storedName -> {
                val secretUse = dao.getSecretUseRequest(request.id) ?: return
                dao.updateSecretUseRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        updatedAt = now,
                        completedAt = now,
                    ),
                    secretUse.copy(
                        state = SecretUseRequestState.VERIFICATION_FAILED.storedName,
                        error = message,
                        updatedAt = now,
                        completedAt = now,
                    ),
                )
            }
            RequestKind.SECRET_LIST.storedName -> {
                val secretList = dao.getSecretListRequest(request.id) ?: return
                dao.updateSecretListRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        updatedAt = now,
                        completedAt = now,
                    ),
                    secretList.copy(
                        state = SecretListRequestState.VERIFICATION_FAILED.storedName,
                        error = message,
                        updatedAt = now,
                        completedAt = now,
                    ),
                )
            }
            RequestKind.SECRET_UPLOAD.storedName -> {
                val upload = dao.getSecretUploadRequest(request.id) ?: return
                val pending = upload.state == SecretUploadRequestState.REVIEW_PENDING.storedName
                dao.updateSecretUploadRequest(
                    request.copy(
                        state = if (pending) {
                            InboxRequestState.ACTION_REQUIRED.storedName
                        } else {
                            InboxRequestState.COMPLETED.storedName
                        },
                        updatedAt = now,
                        completedAt = if (pending) null else now,
                    ),
                    upload.copy(
                        error = message,
                        updatedAt = now,
                        transportCompletedAt = now,
                    ),
                )
            }
            RequestKind.PAIRING_REMOVE.storedName -> {
                dao.updateRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        updatedAt = now,
                        completedAt = now,
                    ),
                )
                val pairing = pairingForRequest(request)
                audit.record(
                    AuditRecord(
                        category = AuditCategory.CLIENT,
                        title = "Client removal unconfirmed",
                        detail = pairing?.friendlyName ?: pairing?.hostname.orEmpty(),
                        outcome = AuditOutcome.FAILED,
                        clientId = pairing?.clientId,
                        relayRequestId = request.relayRequestId,
                    ),
                )
            }
            RequestKind.PAIRING_FINISH.storedName -> {
                dao.updateRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        updatedAt = now,
                        completedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun chooseSas(requestId: Long, selectedIndex: Int?): PairingDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId) ?: return PairingDecisionResult.NOT_FOUND
            val pairing = dao.getPairing(requestId) ?: return PairingDecisionResult.NOT_FOUND
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
                updatedAt = now,
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
                updatedAt = now,
                decidedAt = now,
                completedAt = if (verified) null else now,
            )
            if (verified) {
                dao.updatePairingRequest(updatedRequest, updatedPairing)
            } else {
                dao.rejectPairing(updatedRequest, updatedPairing)
            }
            audit.record(
                AuditRecord(
                    category = AuditCategory.PAIRING,
                    title = if (verified) "Pairing code accepted" else "Pairing rejected",
                    outcome = if (verified) AuditOutcome.APPROVED else AuditOutcome.REJECTED,
                    clientId = pairing.clientId,
                    relayRequestId = request.relayRequestId,
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

    suspend fun rejectPairing(requestId: Long): PairingDecisionResult = operationMutex.withLock {
        val request = dao.getRequestById(requestId) ?: return PairingDecisionResult.NOT_FOUND
        val pairing = dao.getPairing(requestId) ?: return PairingDecisionResult.NOT_FOUND
        if (pairing.state.toPairingState() !in USER_REJECTABLE_PAIRING_STATES) {
            return PairingDecisionResult.NOT_PENDING
        }
        val now = currentTimeMillis()
        dao.rejectPairing(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                updatedAt = now,
                completedAt = now,
            ),
            pairing = pairing.copy(
                state = PairingState.REJECTED.storedName,
                desiredRelayClientState = RelayClientState.REVOKED.wireName,
                updatedAt = now,
                decidedAt = pairing.decidedAt ?: now,
                completedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.PAIRING,
                title = "Pairing rejected",
                outcome = AuditOutcome.REJECTED,
                clientId = pairing.clientId,
                relayRequestId = request.relayRequestId,
            ),
        )
        PairingDecisionResult.REJECTED
    }.also { result ->
        if (result == PairingDecisionResult.REJECTED) requestSync()
    }

    suspend fun approveSecretUseRequest(requestId: Long): SecretUseDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return SecretUseDecisionResult.NotFound
            val secretUseRequest = dao.getSecretUseRequest(requestId)
                ?: return SecretUseDecisionResult.NotFound
            if (secretUseRequest.state != SecretUseRequestState.APPROVAL_PENDING.storedName) {
                return SecretUseDecisionResult.NotPending
            }
            val requestedSecrets = decodeStringList(secretUseRequest.secretsJson)
            val latestDescription = secrets.describeRequestedSecrets(requestedSecrets)
            val storedSecrets = json.decodeFromString<List<SecretMetadata>>(
                secretUseRequest.secretDetailsJson,
            )
            val storedMissingSecrets = decodeStringList(secretUseRequest.missingSecretsJson)
            if (
                latestDescription.secrets != storedSecrets ||
                latestDescription.missingSecrets != storedMissingSecrets
            ) {
                val now = currentTimeMillis()
                dao.updateSecretUseRequest(
                    request = request.copy(updatedAt = now),
                    secretUseRequest = secretUseRequest.copy(
                        secretDetailsJson = json.encodeToString(latestDescription.secrets),
                        missingSecretsJson = encodeStringList(latestDescription.missingSecrets),
                        updatedAt = now,
                    ),
                )
                return SecretUseDecisionResult.SecretsChanged
            }
            val responseSecrets = when (
                val result = secrets.requestedSecrets(requestedSecrets)
            ) {
                is RequestedSecretsResult.Available -> result.secrets.mapValues { (_, secret) ->
                    SecretUseResponseSecret(secret.description, secret.environment)
                }
                is RequestedSecretsResult.MissingSecrets -> {
                    return SecretUseDecisionResult.MissingSecrets(result.names)
                }
                is RequestedSecretsResult.ConflictingVariable -> {
                    return SecretUseDecisionResult.ConflictingVariable(result.name)
                }
                RequestedSecretsResult.SecretUnavailable -> {
                    return SecretUseDecisionResult.SecretUnavailable
                }
                RequestedSecretsResult.SecretCorrupted -> {
                    return SecretUseDecisionResult.SecretCorrupted
                }
                RequestedSecretsResult.UnsupportedEncryption -> {
                    return SecretUseDecisionResult.UnsupportedEncryption
                }
            }
            decideSecretUseRequest(
                request = request,
                secretUseRequest = secretUseRequest,
                decision = SecretUseDecision.APPROVED,
                responsePlaintext = secretUseProtocol.approvedResponse(responseSecrets),
            )
        }.also { result ->
            if (result == SecretUseDecisionResult.Decided) requestSync()
        }

    suspend fun denySecretUseRequest(requestId: Long): SecretUseDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return SecretUseDecisionResult.NotFound
            val secretUseRequest = dao.getSecretUseRequest(requestId)
                ?: return SecretUseDecisionResult.NotFound
            if (secretUseRequest.state != SecretUseRequestState.APPROVAL_PENDING.storedName) {
                return SecretUseDecisionResult.NotPending
            }
            decideSecretUseRequest(
                request = request,
                secretUseRequest = secretUseRequest,
                decision = SecretUseDecision.DENIED,
                responsePlaintext = secretUseProtocol.deniedResponse(
                    SecretUseDenialReason.USER_DENIED,
                    SECRET_USE_DENIAL_MESSAGE,
                ),
            )
        }.also { result ->
            if (result == SecretUseDecisionResult.Decided) requestSync()
        }

    private suspend fun decideSecretUseRequest(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        decision: SecretUseDecision,
        responsePlaintext: ByteArray,
    ): SecretUseDecisionResult {
        val pairingRequestId = secretUseRequest.pairingRequestId
            ?: return SecretUseDecisionResult.PairingUnavailable
        val pairing = dao.getPairing(pairingRequestId)
            ?: return SecretUseDecisionResult.PairingUnavailable
        if (pairing.state != PairingState.ACTIVE.storedName) {
            return SecretUseDecisionResult.PairingUnavailable
        }
        val credentials = credentialsForPairing(pairing)
            ?: return SecretUseDecisionResult.PairingUnavailable
        val clientPsk = decryptRequestPsk(request, pairing)
            ?: return SecretUseDecisionResult.PairingUnavailable
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                plaintext = responsePlaintext,
            )
        }.getOrNull() ?: return SecretUseDecisionResult.PairingUnavailable
        val now = currentTimeMillis()
        dao.updateSecretUseRequest(
            request = request.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = response.toString(),
                responseAcknowledgedAt = null,
                updatedAt = now,
            ),
            secretUseRequest = secretUseRequest.copy(
                state = SecretUseRequestState.WAITING_FOR_COMPLETION.storedName,
                decision = decision.storedName,
                updatedAt = now,
                decidedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET_USE,
                title = if (decision == SecretUseDecision.APPROVED) {
                    "Secret use approved"
                } else {
                    "Secret use denied"
                },
                detail = decodeStringList(secretUseRequest.secretsJson).joinToString(),
                outcome = if (decision == SecretUseDecision.APPROVED) {
                    AuditOutcome.APPROVED
                } else {
                    AuditOutcome.DENIED
                },
                clientId = secretUseRequest.clientId,
                relayRequestId = request.relayRequestId,
            ),
        )
        return SecretUseDecisionResult.Decided
    }

    private suspend fun processRequest(
        credentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
    ): ProcessedRelayMessage? {
        val requestPayload = message.request ?: return null
        val existing = dao.getRequestByRelayId(message.requestId)
        if (existing != null) {
            val pairing = pairingForRequest(existing) ?: return null
            if (pairing.clientId != message.clientId) return null
            return ProcessedRelayMessage(
                response = existing.responseJson?.let(json::parseToJsonElement),
            )
        }

        return processNewRequest(credentials, message, requestPayload)
    }

    suspend fun approveSecretUpload(
        requestId: Long,
        approvedName: String,
    ): SecretUploadDecisionResult = operationMutex.withLock {
        val request = dao.getRequestById(requestId)
            ?: return@withLock SecretUploadDecisionResult.NotFound
        val uploadRequest = dao.getSecretUploadRequest(requestId)
            ?: return@withLock SecretUploadDecisionResult.NotFound
        if (uploadRequest.state != SecretUploadRequestState.REVIEW_PENDING.storedName) {
            return@withLock SecretUploadDecisionResult.NotPending
        }
        val values = sortedMapOf<String, String>()
        val variableRows = dao.getSecretUploadVariables(requestId)
        for (variable in variableRows) {
            when (
                val result = withContext(cryptographyDispatcher) {
                    encryption.decrypt(
                        encrypted = EncryptedValue(
                            formatVersion = variable.encryptionFormat,
                            keyId = variable.encryptionKeyId,
                            nonce = variable.nonce,
                            ciphertext = variable.ciphertext,
                        ),
                        location = secretUploadVariableLocation(
                            variable.id,
                            request.relayRequestId,
                            uploadRequest.clientId,
                            variable.name,
                        ),
                    )
                }
            ) {
                is DecryptionResult.Plaintext -> {
                    values[variable.name] = runCatching {
                        result.value.decodeToString(throwOnInvalidSequence = true)
                    }.getOrElse { return@withLock SecretUploadDecisionResult.SecretCorrupted }
                }
                DecryptionResult.KeyUnavailable -> {
                    return@withLock SecretUploadDecisionResult.SecretUnavailable
                }
                DecryptionResult.AuthenticationFailed -> {
                    return@withLock SecretUploadDecisionResult.SecretCorrupted
                }
                DecryptionResult.UnsupportedFormat -> {
                    return@withLock SecretUploadDecisionResult.UnsupportedEncryption
                }
            }
        }
        val upload = EnvironmentSecretUpload(
            mode = SecretUploadMode.entries.single { it.wireName == uploadRequest.mode },
            name = uploadRequest.uploadedName,
            descriptionProvided = uploadRequest.descriptionProvided,
            description = uploadRequest.description,
            variables = values,
            variableSensitivity = variableRows.associate { it.name to it.sensitive },
        )
        when (
            val result = secrets.applyEnvironmentSecretUpload(upload, approvedName.trim())
        ) {
            is ApplyEnvironmentSecretUploadResult.Invalid -> {
                SecretUploadDecisionResult.Invalid(result.message)
            }
            is ApplyEnvironmentSecretUploadResult.Applied -> {
                val now = currentTimeMillis()
                dao.updateSecretUploadRequest(
                    request = request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        updatedAt = now,
                        completedAt = now,
                    ),
                    secretUpload = uploadRequest.copy(
                        state = SecretUploadRequestState.APPROVED.storedName,
                        approvedName = approvedName.trim(),
                        updatedAt = now,
                        decidedAt = now,
                    ),
                    discardUploadedValues = true,
                )
                audit.record(
                    AuditRecord(
                        category = AuditCategory.SECRET_UPLOAD,
                        title = "Secret upload approved",
                        detail = uploadRequest.uploadedName,
                        outcome = AuditOutcome.APPROVED,
                        clientId = uploadRequest.clientId,
                        relayRequestId = request.relayRequestId,
                    ),
                )
                SecretUploadDecisionResult.Approved(result.secretId)
            }
        }
    }

    suspend fun readSecretUploadVariable(
        requestId: Long,
        variableId: String,
    ): SecretUploadVariableValue = operationMutex.withLock {
        val request = dao.getRequestById(requestId)
            ?: return@withLock SecretUploadVariableValue.NotFound
        val upload = dao.getSecretUploadRequest(requestId)
            ?: return@withLock SecretUploadVariableValue.NotFound
        if (upload.state != SecretUploadRequestState.REVIEW_PENDING.storedName) {
            return@withLock SecretUploadVariableValue.NotFound
        }
        val variable = dao.getSecretUploadVariables(requestId).find { it.id == variableId }
            ?: return@withLock SecretUploadVariableValue.NotFound
        when (
            val result = withContext(cryptographyDispatcher) {
                encryption.decrypt(
                    encrypted = EncryptedValue(
                        formatVersion = variable.encryptionFormat,
                        keyId = variable.encryptionKeyId,
                        nonce = variable.nonce,
                        ciphertext = variable.ciphertext,
                    ),
                    location = secretUploadVariableLocation(
                        variable.id,
                        request.relayRequestId,
                        upload.clientId,
                        variable.name,
                    ),
                )
            }
        ) {
            is DecryptionResult.Plaintext -> runCatching {
                SecretUploadVariableValue.Available(
                    result.value.decodeToString(throwOnInvalidSequence = true),
                )
            }.getOrDefault(SecretUploadVariableValue.Corrupted)
            DecryptionResult.KeyUnavailable -> SecretUploadVariableValue.Unavailable
            DecryptionResult.AuthenticationFailed -> SecretUploadVariableValue.Corrupted
            DecryptionResult.UnsupportedFormat -> SecretUploadVariableValue.UnsupportedEncryption
        }
    }

    suspend fun setSecretUploadVariableSensitivity(
        requestId: Long,
        variableId: String,
        sensitive: Boolean,
    ): Boolean = operationMutex.withLock {
        val upload = dao.getSecretUploadRequest(requestId) ?: return@withLock false
        if (upload.state != SecretUploadRequestState.REVIEW_PENDING.storedName) {
            return@withLock false
        }
        val variable = dao.getSecretUploadVariables(requestId).find { it.id == variableId }
            ?: return@withLock false
        dao.updateSecretUploadVariable(variable.copy(sensitive = sensitive)) == 1
    }

    suspend fun rejectSecretUpload(requestId: Long): SecretUploadDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return@withLock SecretUploadDecisionResult.NotFound
            val upload = dao.getSecretUploadRequest(requestId)
                ?: return@withLock SecretUploadDecisionResult.NotFound
            if (upload.state != SecretUploadRequestState.REVIEW_PENDING.storedName) {
                return@withLock SecretUploadDecisionResult.NotPending
            }
            val now = currentTimeMillis()
            dao.updateSecretUploadRequest(
                request = request.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    updatedAt = now,
                    completedAt = now,
                ),
                secretUpload = upload.copy(
                    state = SecretUploadRequestState.REJECTED.storedName,
                    updatedAt = now,
                    decidedAt = now,
                ),
                discardUploadedValues = true,
            )
            audit.record(
                AuditRecord(
                    category = AuditCategory.SECRET_UPLOAD,
                    title = "Secret upload rejected",
                    detail = upload.uploadedName,
                    outcome = AuditOutcome.REJECTED,
                    clientId = upload.clientId,
                    relayRequestId = request.relayRequestId,
                ),
            )
            SecretUploadDecisionResult.Rejected
        }

    suspend fun renameClient(clientId: String, name: String): ClientChangeResult =
        operationMutex.withLock {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "A client name cannot be empty" }
            val pairing = dao.getPairingByClientId(clientId)
                ?: return@withLock ClientChangeResult.NOT_FOUND
            dao.updatePairing(pairing.copy(friendlyName = trimmed, updatedAt = currentTimeMillis()))
            audit.record(
                AuditRecord(
                    category = AuditCategory.CLIENT,
                    title = "Client renamed",
                    detail = trimmed,
                    outcome = AuditOutcome.CHANGED,
                    clientId = clientId,
                ),
            )
            ClientChangeResult.CHANGED
        }

    suspend fun setClientState(
        clientId: String,
        state: RelayClientState,
    ): ClientChangeResult = operationMutex.withLock {
        if (state == RelayClientState.PENDING) return@withLock ClientChangeResult.INVALID_STATE
        val pairing = dao.getPairingByClientId(clientId)
            ?: return@withLock ClientChangeResult.NOT_FOUND
        val current = pairing.relayClientState?.toRelayClientState()
            ?: return@withLock ClientChangeResult.INVALID_STATE
        val allowed = when (current) {
            RelayClientState.ACTIVE -> state == RelayClientState.SUSPENDED ||
                state == RelayClientState.REVOKED
            RelayClientState.SUSPENDED -> state == RelayClientState.ACTIVE ||
                state == RelayClientState.REVOKED
            RelayClientState.REVOKED -> state == RelayClientState.REVOKED
            RelayClientState.PENDING -> false
        }
        if (!allowed) return@withLock ClientChangeResult.INVALID_STATE
        val updated = pairing.copy(
                desiredRelayClientState = state.wireName,
                updatedAt = currentTimeMillis(),
            )
        if (state == RelayClientState.REVOKED) {
            dao.revokePairing(updated)
        } else {
            dao.updatePairing(updated)
        }
        requestSync()
        ClientChangeResult.CHANGED
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

        val pairing = dao.getPairingByClientId(message.clientId) ?: return null
        if (pairing.deviceIdentityId != credentials.deviceIdentityId) return null
        val pairingState = pairing.state.toPairingState()
        if (
            pairingState != PairingState.ACTIVE &&
            pairingState != PairingState.RELAY_ACTIVATION_PENDING &&
            pairingState != PairingState.WAITING_FOR_FINISH
        ) {
            return null
        }
        if (
            pairingState == PairingState.ACTIVE &&
            (pairing.desiredRelayClientState ?: pairing.relayClientState) !=
            RelayClientState.ACTIVE.wireName
        ) {
            return null
        }
        val clientPsk = decryptClientPsk(pairing, PairingSecretKind.CURRENT_CLIENT_PSK)
            ?: return null
        val previousClientPsk = decryptPreviousClientPsk(pairing)
        val opened = runCatching {
            pairingProtocol.openPairedRequest(
                deviceId = pairing.deviceId,
                requestId = message.requestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                previousClientPsk = previousClientPsk,
                allowRotation = pairingState == PairingState.ACTIVE,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
            )
        }.getOrNull() ?: return null
        val method = runCatching { pairedRequestProtocol.method(opened.plaintext) }.getOrNull()
        if (
            pairingState != PairingState.ACTIVE &&
            method != PairedRequestProtocol.FINISH_PAIRING_METHOD
        ) {
            return null
        }
        val acceptedSecrets = acceptedRequestSecrets(
            pairing = pairing,
            relayRequestId = message.requestId,
            opened = opened,
            currentClientPsk = clientPsk,
            now = now,
        )
        if (method == null) {
            return recordUnsupportedPairedRequest(
                pairing,
                message.requestId,
                requestPayload,
                acceptedSecrets.withoutRotationUnless(pairingState == PairingState.ACTIVE),
                now,
            )
        }
        val storedSecrets = acceptedSecrets.withoutRotationUnless(
            pairingState == PairingState.ACTIVE,
        )
        val processed = if (method == SecretUseProtocol.METHOD) {
            processSecretUseRequest(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = storedSecrets,
                credentials = credentials,
            )
        } else if (method == SecretListProtocol.METHOD) {
            processSecretListRequest(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = storedSecrets,
                credentials = credentials,
            )
        } else if (method == SecretUploadProtocol.METHOD) {
            processSecretUploadRequest(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = storedSecrets,
                credentials = credentials,
            )
        } else if (method == PairingRemoveProtocol.METHOD) {
            processPairingRemoveRequest(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                acceptedSecrets = storedSecrets,
                credentials = credentials,
            )
        } else if (method == PairedRequestProtocol.FINISH_PAIRING_METHOD) {
            processFinishRequest(
                credentials = credentials,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                pairing = pairing,
                opened = opened,
                acceptedSecrets = storedSecrets,
            )
        } else {
            null
        }
        return processed ?: recordUnsupportedPairedRequest(
            pairing,
            message.requestId,
            requestPayload,
            storedSecrets,
            now,
        )
    }

    private suspend fun processSecretUseRequest(
        pairing: PairingEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestSecrets,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        if (pairing.state != PairingState.ACTIVE.storedName) return null
        val contents = runCatching {
            secretUseProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val description = secrets.describeRequestedSecrets(contents.secrets)
        val automaticDenial = automaticSecretUseDenial(contents.secrets)
        val response = if (automaticDenial != null) {
            runCatching {
                pairingProtocol.sealPairedResponse(
                    deviceId = pairing.deviceId,
                    requestId = relayRequestId,
                    clientId = pairing.clientId,
                    clientPsk = opened.clientPsk,
                    devicePrivateKey = credentials.devicePrivateKey,
                    devicePublicKey = credentials.devicePublicKey,
                    request = requestPayload,
                    plaintext = secretUseProtocol.deniedResponse(
                        automaticDenial.first,
                        automaticDenial.second,
                    ),
                )
            }.getOrNull() ?: return null
        } else {
            null
        }
        val now = currentTimeMillis()
        dao.insertSecretUseRequest(
            request = InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = null,
                kind = RequestKind.SECRET_USE.storedName,
                state = if (automaticDenial == null) {
                    InboxRequestState.ACTION_REQUIRED.storedName
                } else {
                    InboxRequestState.WAITING.storedName
                },
                listed = true,
                requestJson = requestPayload.toString(),
                responseJson = response?.toString(),
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = null,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            secretUseRequest = secretUseRequestEntity(
                pairing = pairing,
                contents = contents,
                description = description,
                now = now,
            ).let { secretUseRequest ->
                if (automaticDenial == null) {
                    secretUseRequest
                } else {
                    secretUseRequest.copy(
                        state = SecretUseRequestState.WAITING_FOR_COMPLETION.storedName,
                        decision = SecretUseDecision.DENIED.storedName,
                        completionReason = automaticDenial.first.wireName,
                        completionMessage = automaticDenial.second,
                        decidedAt = now,
                    )
                }
            },
            requestSecret = acceptedSecrets.requestSecret,
            currentPairingSecret = acceptedSecrets.currentPairingSecret,
            previousPairingSecret = acceptedSecrets.previousPairingSecret,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET_USE,
                title = "Secret use requested",
                detail = contents.secrets.joinToString(),
                outcome = AuditOutcome.RECEIVED,
                clientId = pairing.clientId,
                relayRequestId = relayRequestId,
            ),
        )
        if (automaticDenial != null) {
            audit.record(
                AuditRecord(
                    category = AuditCategory.SECRET_USE,
                    title = "Secret use denied automatically",
                    detail = automaticDenial.second,
                    outcome = AuditOutcome.DENIED,
                    clientId = pairing.clientId,
                    relayRequestId = relayRequestId,
                ),
            )
        }
        return ProcessedRelayMessage(response)
    }

    private suspend fun automaticSecretUseDenial(
        secretNames: List<String>,
    ): Pair<SecretUseDenialReason, String>? = when (
        val result = secrets.requestedSecrets(secretNames)
    ) {
        is RequestedSecretsResult.Available -> null
        is RequestedSecretsResult.MissingSecrets ->
            SecretUseDenialReason.INVALID_REQUEST to
                "Missing secrets: ${result.names.joinToString()}"
        is RequestedSecretsResult.ConflictingVariable ->
            SecretUseDenialReason.INVALID_REQUEST to
                "Requested secrets provide conflicting values for the environment variable ${result.name}."
        RequestedSecretsResult.SecretUnavailable ->
            SecretUseDenialReason.OTHER to
                "A requested secret value is unavailable on this device."
        RequestedSecretsResult.SecretCorrupted ->
            SecretUseDenialReason.OTHER to
                "A requested secret value could not be authenticated."
        RequestedSecretsResult.UnsupportedEncryption ->
            SecretUseDenialReason.OTHER to
                "A requested secret value uses an unsupported encryption format."
    }

    private suspend fun processSecretListRequest(
        pairing: PairingEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestSecrets,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        if (pairing.state != PairingState.ACTIVE.storedName) return null
        val contents = runCatching {
            secretListProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val secretMetadata = secrets.listSecretsForClient()
        val responsePlaintext = secretListProtocol.response(
            secretMetadata.associateTo(sortedMapOf()) { secret ->
                secret.name to SecretListSecret(
                    description = secret.description,
                    environmentVariableNames = secret.environmentVariableNames,
                )
            },
        )
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
                deviceId = pairing.deviceId,
                requestId = relayRequestId,
                clientId = pairing.clientId,
                clientPsk = opened.clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
                plaintext = responsePlaintext,
            )
        }.getOrNull() ?: return null
        val now = currentTimeMillis()
        dao.insertSecretListRequest(
            request = InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = null,
                kind = RequestKind.SECRET_LIST.storedName,
                state = InboxRequestState.WAITING.storedName,
                listed = false,
                requestJson = requestPayload.toString(),
                responseJson = response.toString(),
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = null,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            secretListRequest = secretListRequestEntity(
                pairing = pairing,
                contents = contents,
                secretMetadata = secretMetadata,
                now = now,
            ),
            requestSecret = acceptedSecrets.requestSecret,
            currentPairingSecret = acceptedSecrets.currentPairingSecret,
            previousPairingSecret = acceptedSecrets.previousPairingSecret,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET_LIST,
                title = "Secret list requested",
                detail = "${secretMetadata.size} secrets sent to ${pairing.friendlyName ?: pairing.hostname ?: "client"}",
                outcome = AuditOutcome.RECEIVED,
                clientId = pairing.clientId,
                relayRequestId = relayRequestId,
            ),
        )
        return ProcessedRelayMessage(response)
    }

    private suspend fun processSecretUploadRequest(
        pairing: PairingEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestSecrets,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        if (pairing.state != PairingState.ACTIVE.storedName) return null
        val contents = runCatching {
            secretUploadProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val upload = EnvironmentSecretUpload(
            mode = contents.mode,
            name = contents.name,
            descriptionProvided = contents.descriptionProvided,
            description = contents.description,
            variables = contents.variables,
        )
        val validation = secrets.describeEnvironmentSecretUpload(upload)
        val valid = validation as? EnvironmentSecretUploadResult.Valid
        val invalid = validation as? EnvironmentSecretUploadResult.Invalid
        val responsePlaintext = if (valid != null) {
            secretUploadProtocol.receivedResponse()
        } else {
            secretUploadProtocol.rejectedResponse(checkNotNull(invalid).message)
        }
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
                deviceId = pairing.deviceId,
                requestId = relayRequestId,
                clientId = pairing.clientId,
                clientPsk = opened.clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
                plaintext = responsePlaintext,
            )
        }.getOrNull() ?: return null
        val now = currentTimeMillis()
        val encryptedVariables = if (valid == null) {
            emptyList()
        } else {
            contents.variables.map { (name, value) ->
                encryptSecretUploadVariable(
                    relayRequestId = relayRequestId,
                    clientId = pairing.clientId,
                    name = name,
                    value = value,
                    sensitive = valid.summary.variableSensitivity.getValue(name),
                    now = now,
                )
            }
        }
        dao.insertSecretUploadRequest(
            request = InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = null,
                kind = RequestKind.SECRET_UPLOAD.storedName,
                state = if (valid != null) {
                    InboxRequestState.ACTION_REQUIRED.storedName
                } else {
                    InboxRequestState.WAITING.storedName
                },
                listed = valid != null,
                requestJson = requestPayload.toString(),
                responseJson = response.toString(),
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = null,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            secretUpload = SecretUploadRequestEntity(
                requestId = 0,
                pairingRequestId = pairing.requestId,
                clientId = pairing.clientId,
                clientName = pairing.friendlyName ?: pairing.hostname ?: "Unknown client",
                state = if (valid != null) {
                    SecretUploadRequestState.REVIEW_PENDING.storedName
                } else {
                    SecretUploadRequestState.REJECTED.storedName
                },
                cliVersion = contents.cliVersion,
                mode = contents.mode.wireName,
                uploadedName = contents.name,
                approvedName = null,
                descriptionProvided = contents.descriptionProvided,
                description = contents.description,
                secretType = "environment",
                variableNamesJson = encodeStringList(contents.variables.keys.sorted()),
                addedVariablesJson = encodeStringList(valid?.summary?.addedVariables.orEmpty()),
                changedVariablesJson = encodeStringList(valid?.summary?.changedVariables.orEmpty()),
                unchangedVariablesJson = encodeStringList(
                    valid?.summary?.unchangedVariables.orEmpty(),
                ),
                removedVariablesJson = encodeStringList(valid?.summary?.removedVariables.orEmpty()),
                error = invalid?.message,
                transportResult = if (valid != null) {
                    SecretUploadProtocol.RESULT_RECEIVED
                } else {
                    SecretUploadProtocol.RESULT_REJECTED
                },
                transportMessage = invalid?.message,
                createdAt = now,
                updatedAt = now,
                decidedAt = if (valid == null) now else null,
                transportCompletedAt = null,
            ),
            variables = encryptedVariables,
            requestSecret = acceptedSecrets.requestSecret,
            currentPairingSecret = acceptedSecrets.currentPairingSecret,
            previousPairingSecret = acceptedSecrets.previousPairingSecret,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.SECRET_UPLOAD,
                title = if (valid != null) "Secret upload received" else "Secret upload rejected",
                detail = invalid?.message ?: "${contents.mode.wireName.lowercase()} ${contents.name}",
                outcome = if (valid != null) AuditOutcome.RECEIVED else AuditOutcome.REJECTED,
                clientId = pairing.clientId,
                relayRequestId = relayRequestId,
            ),
        )
        return ProcessedRelayMessage(response)
    }

    private suspend fun processPairingRemoveRequest(
        pairing: PairingEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestSecrets,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        if (pairing.state != PairingState.ACTIVE.storedName) return null
        runCatching { pairingRemoveProtocol.decodeRequest(opened.plaintext) }.getOrNull()
            ?: return null
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
                deviceId = pairing.deviceId,
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
                relayRequestId = relayRequestId,
                parentRequestId = pairing.requestId,
                kind = RequestKind.PAIRING_REMOVE.storedName,
                state = InboxRequestState.WAITING.storedName,
                listed = false,
                requestJson = requestPayload.toString(),
                responseJson = response.toString(),
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = null,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            requestSecret = acceptedSecrets.requestSecret,
            pairing = pairing.copy(
                desiredRelayClientState = RelayClientState.REVOKED.wireName,
                updatedAt = now,
            ),
        )
        requestSync()
        return ProcessedRelayMessage(response)
    }

    private suspend fun recordUnsupportedPairedRequest(
        pairing: PairingEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        acceptedSecrets: AcceptedRequestSecrets,
        now: Long,
    ): ProcessedRelayMessage {
        dao.insertHiddenPairedRequest(
            request = InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = pairing.requestId,
                kind = RequestKind.UNKNOWN.storedName,
                state = InboxRequestState.COMPLETED.storedName,
                listed = false,
                requestJson = requestPayload.toString(),
                responseJson = null,
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = now,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            requestSecret = acceptedSecrets.requestSecret,
            currentPairingSecret = acceptedSecrets.currentPairingSecret,
            previousPairingSecret = acceptedSecrets.previousPairingSecret,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.VERIFICATION,
                title = "Authenticated request was not understood",
                outcome = AuditOutcome.REJECTED,
                clientId = pairing.clientId,
                relayRequestId = relayRequestId,
            ),
        )
        return ProcessedRelayMessage()
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
        if (
            !pairingAdmissionAllowed(
                dao.getPairings().map { pairing -> pairing.state.toPairingState() },
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
            relayRequestId = message.requestId,
            parentRequestId = null,
            kind = RequestKind.PAIRING.storedName,
            state = InboxRequestState.RECEIVING.storedName,
            listed = true,
            requestJson = requestPayload.toString(),
            responseJson = response.toString(),
            completionJson = null,
            receivedAt = now,
            updatedAt = now,
            completedAt = null,
            requestAcknowledgedAt = null,
            responseAcknowledgedAt = null,
            completionAcknowledgedAt = null,
        )
        dao.insertPairingRequest(
            request = request,
            pairing = PairingEntity(
                requestId = 0,
                deviceIdentityId = credentials.deviceIdentityId,
                pairingAddress = credentials.address,
                deviceId = credentials.deviceId,
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
                cliVersion = null,
                platform = null,
                architecture = null,
                hostname = null,
                machineId = null,
                osVersion = null,
                error = null,
                createdAt = now,
                updatedAt = now,
                decidedAt = null,
                completedAt = null,
            ),
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.PAIRING,
                title = "Pairing requested",
                outcome = AuditOutcome.RECEIVED,
                clientId = message.clientId,
                relayRequestId = message.requestId,
            ),
        )
        return ProcessedRelayMessage(response)
    }

    private fun secretUseRequestEntity(
        pairing: PairingEntity,
        contents: SecretUseRequestMessage,
        description: RequestedSecretDescription,
        now: Long,
    ) = SecretUseRequestEntity(
        requestId = 0,
        pairingRequestId = pairing.requestId,
        clientId = pairing.clientId,
        clientName = pairing.friendlyName ?: pairing.hostname ?: "Unknown client",
        pairingAddress = pairing.pairingAddress,
        hostname = pairing.hostname,
        platform = pairing.platform,
        architecture = pairing.architecture,
        machineId = pairing.machineId,
        osVersion = pairing.osVersion,
        state = SecretUseRequestState.APPROVAL_PENDING.storedName,
        cliVersion = contents.cliVersion,
        secretsJson = encodeStringList(contents.secrets),
        secretDetailsJson = json.encodeToString(description.secrets),
        missingSecretsJson = encodeStringList(description.missingSecrets),
        reason = contents.reason,
        command = contents.operation.command,
        argumentsJson = encodeStringList(contents.operation.arguments),
        workingDirectory = contents.operation.workingDirectory,
        executablePath = contents.operation.executablePath,
        executableHash = contents.operation.executableHash,
        executableMode = contents.operation.executableMode,
        stdinKind = contents.operation.stdin,
        stdoutKind = contents.operation.stdout,
        stderrKind = contents.operation.stderr,
        launcherChainJson = encodeStringList(contents.launcherChain),
        decision = null,
        completionResult = null,
        completionReason = null,
        completionMessage = null,
        error = null,
        createdAt = now,
        updatedAt = now,
        decidedAt = null,
        completedAt = null,
    )

    private fun secretListRequestEntity(
        pairing: PairingEntity,
        contents: SecretListRequestMessage,
        secretMetadata: List<SecretMetadata>,
        now: Long,
    ) = SecretListRequestEntity(
        requestId = 0,
        pairingRequestId = pairing.requestId,
        clientId = pairing.clientId,
        pairingAddress = pairing.pairingAddress,
        hostname = pairing.hostname,
        platform = pairing.platform,
        architecture = pairing.architecture,
        machineId = pairing.machineId,
        osVersion = pairing.osVersion,
        state = SecretListRequestState.WAITING_FOR_COMPLETION.storedName,
        cliVersion = contents.cliVersion,
        secretsJson = json.encodeToString(secretMetadata),
        error = null,
        createdAt = now,
        updatedAt = now,
        completedAt = null,
    )

    private suspend fun processInitialCompletion(
        credentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        pairing: PairingEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        if (
            pairing.state != PairingState.RECEIVING.storedName &&
            pairing.state != PairingState.VERIFICATION_FAILED.storedName
        ) {
            return ProcessedRelayMessage()
        }
        val now = currentTimeMillis()
        val established = runCatching {
            pairingProtocol.establish(
                deviceId = pairing.deviceId,
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
                    updatedAt = now,
                ),
                pairing = pairing.copy(
                    error = failure.message ?: "The pairing message could not be verified.",
                    updatedAt = now,
                ),
            )
            return null
        }
        val metadata = runCatching {
            pairingProtocol.decodeClientMetadata(result.applicationPlaintext)
        }.getOrNull()
        val choices = pairingProtocol.sasChoices(result.sas)
        val secret = encryptClientPsk(pairing, result.clientPsk, now)
        dao.recordInitialCompletion(
            request = request.copy(
                state = InboxRequestState.ACTION_REQUIRED.storedName,
                completionJson = completion.toString(),
                updatedAt = now,
            ),
            pairing = pairing.copy(
                state = PairingState.SAS_VERIFICATION_PENDING.storedName,
                sasOption0 = choices.values[0],
                sasOption1 = choices.values[1],
                sasOption2 = choices.values[2],
                correctSasIndex = choices.correctIndex,
                cliVersion = metadata?.cliVersion,
                platform = metadata?.platform,
                architecture = metadata?.architecture,
                hostname = metadata?.hostname,
                friendlyName = pairing.friendlyName ?: metadata?.hostname,
                machineId = metadata?.machineId,
                osVersion = metadata?.osVersion,
                error = if (metadata == null) {
                    "The client details could not be read, but the security code is valid."
                } else {
                    null
                },
                updatedAt = now,
            ),
            secret = secret,
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processFinishRequest(
        credentials: RelayDeviceCredentials,
        relayRequestId: String,
        requestPayload: JsonElement,
        pairing: PairingEntity,
        opened: OpenedPairedRequest,
        acceptedSecrets: AcceptedRequestSecrets,
    ): ProcessedRelayMessage? {
        if (pairing.deviceIdentityId != credentials.deviceIdentityId) return null
        if (
            pairing.state != PairingState.RELAY_ACTIVATION_PENDING.storedName &&
            pairing.state != PairingState.WAITING_FOR_FINISH.storedName
        ) return null
        val prepared = runCatching {
            pairingProtocol.prepareFinishResponse(
                deviceId = pairing.deviceId,
                requestId = relayRequestId,
                clientId = pairing.clientId,
                clientPsk = opened.clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
            )
        }.getOrNull() ?: return null
        val now = currentTimeMillis()
        val finishRequest = InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = pairing.requestId,
                kind = RequestKind.PAIRING_FINISH.storedName,
                state = InboxRequestState.COMPLETED.storedName,
                listed = false,
                requestJson = requestPayload.toString(),
                responseJson = prepared.response.toString(),
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = now,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            )
        val rootRequest = dao.getRequestById(pairing.requestId) ?: return null
        dao.finishPairing(
            rootRequest = rootRequest.copy(
                state = InboxRequestState.COMPLETED.storedName,
                updatedAt = now,
                completedAt = now,
            ),
            pairing = pairing.copy(
                state = PairingState.ACTIVE.storedName,
                updatedAt = now,
                completedAt = now,
            ),
            finishRequest = finishRequest,
            requestSecret = acceptedSecrets.requestSecret,
            currentPairingSecret = acceptedSecrets.currentPairingSecret,
            previousPairingSecret = acceptedSecrets.previousPairingSecret,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.PAIRING,
                title = "Pairing completed",
                outcome = AuditOutcome.COMPLETED,
                clientId = pairing.clientId,
                relayRequestId = rootRequest.relayRequestId,
            ),
        )
        return ProcessedRelayMessage(prepared.response)
    }

    private suspend fun processCompletion(
        activeCredentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
    ): ProcessedRelayMessage? {
        val completion = message.completion ?: return null
        val request = dao.getRequestByRelayId(message.requestId) ?: return null
        val requestPairing = pairingForRequest(request) ?: return null
        if (requestPairing.clientId != message.clientId) return null
        val isInitialPairing = request.kind == RequestKind.PAIRING.storedName
        if (isInitialPairing != (message.addressId != null)) return null
        return when (request.kind) {
            RequestKind.PAIRING.storedName -> {
                val pairing = dao.getPairing(request.id) ?: return null
                val credentials = credentialsFor(pairing, activeCredentials) ?: return null
                processInitialCompletion(credentials, request, pairing, completion)
            }
            RequestKind.PAIRING_FINISH.storedName -> {
                processFinishCompletion(activeCredentials, request, completion)
            }
            RequestKind.SECRET_USE.storedName -> {
                processSecretUseCompletion(activeCredentials, request, completion)
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
    }

    private suspend fun processUnknownCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        if (request.completionJson != null) return ProcessedRelayMessage()
        val pairing = pairingForRequest(request) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptRequestPsk(request, pairing) ?: return null
        runCatching {
            pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
        }.getOrNull() ?: return null
        dao.updateRequest(
            request.copy(
                completionJson = completion.toString(),
                completionAcknowledgedAt = null,
                updatedAt = currentTimeMillis(),
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processSecretListCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val secretListRequest = dao.getSecretListRequest(request.id) ?: return null
        if (
            secretListRequest.state == SecretListRequestState.COMPLETED.storedName ||
            secretListRequest.state == SecretListRequestState.VERIFICATION_FAILED.storedName
        ) {
            return ProcessedRelayMessage()
        }
        val pairingRequestId = secretListRequest.pairingRequestId ?: return null
        val pairing = dao.getPairing(pairingRequestId) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptRequestPsk(request, pairing) ?: return null
        val plaintext = runCatching {
            pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
        }.getOrNull() ?: return null
        val decoded = runCatching { secretListProtocol.decodeCompletion(plaintext) }
        val valid = decoded.getOrNull() == secretListRequest.cliVersion
        val now = currentTimeMillis()
        dao.updateSecretListRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                updatedAt = now,
                completedAt = now,
            ),
            secretListRequest = secretListRequest.copy(
                state = if (valid) {
                    SecretListRequestState.COMPLETED.storedName
                } else {
                    SecretListRequestState.VERIFICATION_FAILED.storedName
                },
                error = if (valid) {
                    null
                } else {
                    decoded.exceptionOrNull()?.message
                        ?: "Secret list completion did not match the request."
                },
                updatedAt = now,
                completedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = if (valid) AuditCategory.SECRET_LIST else AuditCategory.VERIFICATION,
                title = if (valid) "Secret list delivered" else "Secret list confirmation failed",
                detail = "${json.decodeFromString<List<SecretMetadata>>(secretListRequest.secretsJson).size} secrets",
                outcome = if (valid) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                clientId = secretListRequest.clientId,
                relayRequestId = request.relayRequestId,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processSecretUploadCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val upload = dao.getSecretUploadRequest(request.id) ?: return null
        if (upload.transportCompletedAt != null) return ProcessedRelayMessage()
        val pairingRequestId = upload.pairingRequestId ?: return null
        val pairing = dao.getPairing(pairingRequestId) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptRequestPsk(request, pairing) ?: return null
        val plaintext = runCatching {
            pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
        }.getOrNull() ?: return null
        val decoded = runCatching { secretUploadProtocol.decodeCompletion(plaintext) }
        val result = decoded.getOrNull()
        val valid = result?.cliVersion == upload.cliVersion &&
            result.result == upload.transportResult &&
            result.message == upload.transportMessage
        val now = currentTimeMillis()
        val uploadStillPending = upload.state == SecretUploadRequestState.REVIEW_PENDING.storedName
        dao.updateSecretUploadRequest(
            request = request.copy(
                state = when {
                    uploadStillPending -> InboxRequestState.ACTION_REQUIRED.storedName
                    else -> InboxRequestState.COMPLETED.storedName
                },
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                updatedAt = now,
                completedAt = request.completedAt ?: if (uploadStillPending) null else now,
            ),
            secretUpload = upload.copy(
                error = if (valid) upload.error else decoded.exceptionOrNull()?.message
                    ?: "The client completion did not match the received upload.",
                updatedAt = now,
                transportCompletedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = if (valid) {
                    AuditCategory.SECRET_UPLOAD
                } else {
                    AuditCategory.VERIFICATION
                },
                title = if (valid) {
                    "Secret upload receipt confirmed"
                } else {
                    "Secret upload confirmation failed"
                },
                detail = upload.uploadedName,
                outcome = if (valid) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                clientId = upload.clientId,
                relayRequestId = request.relayRequestId,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processPairingRemoveCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        if (request.completionJson != null) return ProcessedRelayMessage()
        val pairing = pairingForRequest(request) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptRequestPsk(request, pairing) ?: return null
        val plaintext = runCatching {
            pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
        }.getOrNull() ?: return null
        val verified = runCatching { pairingRemoveProtocol.decodeCompletion(plaintext) }.isSuccess
        if (verified) {
            completePairingRemoval(request, pairing, completion)
        } else {
            val now = currentTimeMillis()
            dao.updateRequest(
                request.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    completionJson = completion.toString(),
                    completionAcknowledgedAt = null,
                    updatedAt = now,
                    completedAt = now,
                ),
            )
            audit.record(
                AuditRecord(
                    category = AuditCategory.VERIFICATION,
                    title = "Client removal confirmation failed",
                    outcome = AuditOutcome.FAILED,
                    clientId = pairing.clientId,
                    relayRequestId = request.relayRequestId,
                ),
            )
        }
        return ProcessedRelayMessage()
    }

    private suspend fun completePairingRemoval(
        request: InboxRequestEntity,
        pairing: PairingEntity,
        completion: JsonElement?,
    ) {
        if (request.completedAt != null && completion == null) return
        val firstCompletion = request.completedAt == null
        val now = currentTimeMillis()
        dao.updateRequest(
            request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion?.toString() ?: request.completionJson,
                completionAcknowledgedAt = if (completion == null) {
                    request.completionAcknowledgedAt
                } else {
                    null
                },
                updatedAt = now,
                completedAt = now,
            ),
        )
        if (pairing.desiredRelayClientState != RelayClientState.REVOKED.wireName) {
            dao.revokePairing(
                pairing.copy(
                    desiredRelayClientState = RelayClientState.REVOKED.wireName,
                    updatedAt = now,
                ),
            )
        }
        if (firstCompletion) {
            audit.record(
                AuditRecord(
                    category = AuditCategory.CLIENT,
                    title = "Client unpaired itself",
                    detail = pairing.friendlyName ?: pairing.hostname ?: pairing.clientId,
                    outcome = AuditOutcome.COMPLETED,
                    clientId = pairing.clientId,
                    relayRequestId = request.relayRequestId,
                ),
            )
        }
        requestSync()
    }

    private suspend fun processSecretUseCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val secretUseRequest = dao.getSecretUseRequest(request.id) ?: return null
        if (
            secretUseRequest.state == SecretUseRequestState.COMPLETED.storedName ||
            secretUseRequest.state == SecretUseRequestState.VERIFICATION_FAILED.storedName
        ) {
            return ProcessedRelayMessage()
        }
        val pairingRequestId = secretUseRequest.pairingRequestId ?: return null
        val pairing = dao.getPairing(pairingRequestId) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptRequestPsk(request, pairing) ?: return null
        val plaintext = runCatching {
            pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
        }.getOrNull() ?: return null
        val decoded = runCatching { secretUseProtocol.decodeCompletion(plaintext) }
        val now = currentTimeMillis()
        val completionResult = decoded.getOrNull()
        val valid = when (completionResult) {
            is SecretUseCompletion.Approved -> {
                secretUseRequest.decision == SecretUseDecision.APPROVED.storedName
            }
            is SecretUseCompletion.Denied -> {
                if (secretUseRequest.decision != SecretUseDecision.DENIED.storedName) {
                    false
                } else {
                    val expectedReason = secretUseRequest.completionReason
                        ?: SecretUseDenialReason.USER_DENIED.wireName
                    val expectedMessage = secretUseRequest.completionMessage
                        ?: SECRET_USE_DENIAL_MESSAGE
                    completionResult.reason == expectedReason &&
                        completionResult.message == expectedMessage
                }
            }
            is SecretUseCompletion.Aborted -> true
            null -> false
        }
        dao.updateSecretUseRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                updatedAt = now,
                completedAt = now,
            ),
            secretUseRequest = secretUseRequest.copy(
                state = if (valid) {
                    SecretUseRequestState.COMPLETED.storedName
                } else {
                    SecretUseRequestState.VERIFICATION_FAILED.storedName
                },
                completionResult = when (completionResult) {
                    is SecretUseCompletion.Approved -> {
                        SecretUseCompletionResult.APPROVED.storedName
                    }
                    is SecretUseCompletion.Denied -> {
                        SecretUseCompletionResult.DENIED.storedName
                    }
                    is SecretUseCompletion.Aborted -> {
                        SecretUseCompletionResult.ABORTED.storedName
                    }
                    null -> null
                },
                completionReason = when (completionResult) {
                    is SecretUseCompletion.Denied -> completionResult.reason
                    is SecretUseCompletion.Aborted -> completionResult.reason
                    else -> null
                },
                completionMessage = when (completionResult) {
                    is SecretUseCompletion.Denied -> completionResult.message
                    is SecretUseCompletion.Aborted -> completionResult.message
                    else -> null
                },
                error = if (valid) {
                    null
                } else {
                    decoded.exceptionOrNull()?.message
                        ?: "Secret use completion did not match the device decision."
                },
                updatedAt = now,
                completedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = if (valid) AuditCategory.SECRET_USE else AuditCategory.VERIFICATION,
                title = when {
                    !valid -> "Secret use confirmation failed"
                    completionResult is SecretUseCompletion.Approved -> "Secret use delivered"
                    completionResult is SecretUseCompletion.Denied -> "Secret use denial confirmed"
                    else -> "Secret use aborted"
                },
                detail = decodeStringList(secretUseRequest.secretsJson).joinToString(),
                outcome = when {
                    !valid -> AuditOutcome.FAILED
                    completionResult is SecretUseCompletion.Denied -> AuditOutcome.DENIED
                    else -> AuditOutcome.COMPLETED
                },
                clientId = secretUseRequest.clientId,
                relayRequestId = request.relayRequestId,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processFinishCompletion(
        activeCredentials: RelayDeviceCredentials,
        finishRequest: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val rootRequestId = finishRequest.parentRequestId ?: return null
        val pairing = dao.getPairing(rootRequestId) ?: return null
        if (finishRequest.completionJson != null) {
            return ProcessedRelayMessage()
        }
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptRequestPsk(finishRequest, pairing) ?: return null
        val plaintext = runCatching {
            pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = finishRequest.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(finishRequest.requestJson),
                completion = completion,
            )
        }.getOrNull() ?: return null
        val accepted = runCatching {
            pairingProtocol.finishCompletionAccepted(plaintext)
        }.getOrDefault(false)
        val now = currentTimeMillis()
        dao.updateRequest(
            finishRequest.copy(
                completionJson = completion.toString(),
                completionAcknowledgedAt = null,
                updatedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = if (accepted) AuditCategory.PAIRING else AuditCategory.VERIFICATION,
                title = if (accepted) {
                    "Client confirmed pairing"
                } else {
                    "Client pairing confirmation was not understood"
                },
                outcome = if (accepted) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                clientId = pairing.clientId,
                relayRequestId = finishRequest.relayRequestId,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun credentialsFor(
        pairing: PairingEntity,
        active: RelayDeviceCredentials,
    ): RelayDeviceCredentials? {
        val deviceIdentityId = pairing.deviceIdentityId ?: return null
        if (deviceIdentityId == active.deviceIdentityId) return active
        return when (val result = deviceCredentials.deviceCredentials(deviceIdentityId)) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            else -> null
        }
    }

    private suspend fun credentialsForPairing(
        pairing: PairingEntity,
    ): RelayDeviceCredentials? {
        val deviceIdentityId = pairing.deviceIdentityId ?: return null
        return when (val result = deviceCredentials.deviceCredentials(deviceIdentityId)) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            else -> null
        }
    }

    private suspend fun pairingForRequest(request: InboxRequestEntity): PairingEntity? =
        when (request.kind) {
            RequestKind.PAIRING.storedName -> dao.getPairing(request.id)
            RequestKind.PAIRING_FINISH.storedName -> {
                request.parentRequestId?.let { dao.getPairing(it) }
            }
            RequestKind.SECRET_USE.storedName -> {
                dao.getSecretUseRequest(request.id)?.pairingRequestId?.let { dao.getPairing(it) }
            }
            RequestKind.SECRET_LIST.storedName -> {
                dao.getSecretListRequest(request.id)?.pairingRequestId?.let { dao.getPairing(it) }
            }
            RequestKind.SECRET_UPLOAD.storedName -> {
                dao.getSecretUploadRequest(request.id)?.pairingRequestId?.let { dao.getPairing(it) }
            }
            RequestKind.PAIRING_REMOVE.storedName -> {
                request.parentRequestId?.let { dao.getPairing(it) }
            }
            RequestKind.UNKNOWN.storedName -> {
                request.parentRequestId?.let { dao.getPairing(it) }
            }
            else -> null
        }

    private suspend fun encryptSecretUploadVariable(
        relayRequestId: String,
        clientId: String,
        name: String,
        value: String,
        sensitive: Boolean,
        now: Long,
    ): SecretUploadVariableEntity {
        val id = newId()
        val key = keyManager.activeKey(VaultKeyPurpose.SECRET_VALUES)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = secretUploadVariableLocation(id, relayRequestId, clientId, name),
                plaintext = value.encodeToByteArray(),
            )
        }
        return SecretUploadVariableEntity(
            id = id,
            requestId = 0,
            name = name,
            sensitive = sensitive,
            encryptionFormat = encrypted.formatVersion,
            encryptionKeyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            createdAt = now,
        )
    }

    private fun secretUploadVariableLocation(
        id: String,
        relayRequestId: String,
        clientId: String,
        name: String,
    ) = EncryptionLocation(
        recordType = "secret_upload_variable",
        recordId = id,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("relay_request_id", relayRequestId),
            EncryptionBinding("client_id", clientId),
            EncryptionBinding("name", name),
        ),
    )

    private suspend fun acceptedRequestSecrets(
        pairing: PairingEntity,
        relayRequestId: String,
        opened: OpenedPairedRequest,
        currentClientPsk: ByteArray,
        now: Long,
    ): AcceptedRequestSecrets {
        val requestSecret = encryptRequestPsk(
            pairing = pairing,
            relayRequestId = relayRequestId,
            clientPsk = opened.clientPsk,
            now = now,
        )
        if (opened.keySource != PairedRequestKeySource.ROTATED) {
            return AcceptedRequestSecrets(requestSecret, null, null)
        }
        val current = dao.getPairingSecret(
            pairing.requestId,
            PairingSecretKind.CURRENT_CLIENT_PSK.storedName,
        ) ?: error("The current client key is unavailable")
        return AcceptedRequestSecrets(
            requestSecret = requestSecret,
            currentPairingSecret = encryptClientPsk(current, pairing, opened.clientPsk, now),
            previousPairingSecret = encryptClientPsk(
                pairing = pairing,
                clientPsk = currentClientPsk,
                now = now,
                kind = PairingSecretKind.PREVIOUS_CLIENT_PSK,
            ),
        )
    }

    private suspend fun encryptRequestPsk(
        pairing: PairingEntity,
        relayRequestId: String,
        clientPsk: ByteArray,
        now: Long,
    ): RequestSecretEntity {
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val id = newId()
        val key = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = requestSecretLocation(id, relayRequestId, pairing.clientId),
                plaintext = clientPsk,
            )
        }
        return RequestSecretEntity(
            id = id,
            requestId = 0,
            encryptionFormat = encrypted.formatVersion,
            encryptionKeyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            createdAt = now,
        )
    }

    private suspend fun decryptRequestPsk(
        request: InboxRequestEntity,
        pairing: PairingEntity,
    ): ByteArray? {
        val secret = dao.getRequestSecret(request.id) ?: return null
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = EncryptedValue(
                    formatVersion = secret.encryptionFormat,
                    keyId = secret.encryptionKeyId,
                    nonce = secret.nonce,
                    ciphertext = secret.ciphertext,
                ),
                location = requestSecretLocation(
                    secret.id,
                    request.relayRequestId,
                    pairing.clientId,
                ),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == CLIENT_PSK_BYTES
        }
    }

    private fun requestSecretLocation(
        secretId: String,
        relayRequestId: String,
        clientId: String,
    ) = EncryptionLocation(
        recordType = "request_secret",
        recordId = secretId,
        fieldName = "client_psk",
        bindings = listOf(
            EncryptionBinding("relay_request_id", relayRequestId),
            EncryptionBinding("client_id", clientId),
        ),
    )

    private suspend fun encryptClientPsk(
        pairing: PairingEntity,
        clientPsk: ByteArray,
        now: Long,
        kind: PairingSecretKind = PairingSecretKind.CURRENT_CLIENT_PSK,
    ): PairingSecretEntity {
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val id = newId()
        val key = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = pairingSecretLocation(id, pairing, kind),
                plaintext = clientPsk,
            )
        }
        return PairingSecretEntity(
            id = id,
            pairingRequestId = pairing.requestId,
            kind = kind.storedName,
            encryptionFormat = encrypted.formatVersion,
            encryptionKeyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun encryptClientPsk(
        existing: PairingSecretEntity,
        pairing: PairingEntity,
        clientPsk: ByteArray,
        now: Long,
    ): PairingSecretEntity {
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val key = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = pairingSecretLocation(
                    existing.id,
                    pairing,
                    PairingSecretKind.entries.single { it.storedName == existing.kind },
                ),
                plaintext = clientPsk,
            )
        }
        return existing.copy(
            encryptionFormat = encrypted.formatVersion,
            encryptionKeyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            updatedAt = now,
        )
    }

    private suspend fun decryptClientPsk(
        pairing: PairingEntity,
        kind: PairingSecretKind = PairingSecretKind.CURRENT_CLIENT_PSK,
    ): ByteArray? {
        val secret = dao.getPairingSecret(
            pairing.requestId,
            kind.storedName,
        )
            ?: return null
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = EncryptedValue(
                    formatVersion = secret.encryptionFormat,
                    keyId = secret.encryptionKeyId,
                    nonce = secret.nonce,
                    ciphertext = secret.ciphertext,
                ),
                location = pairingSecretLocation(secret.id, pairing, kind),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == CLIENT_PSK_BYTES
        }
    }

    private suspend fun decryptPreviousClientPsk(pairing: PairingEntity): ByteArray? {
        val previous = dao.getPairingSecret(
            pairing.requestId,
            PairingSecretKind.PREVIOUS_CLIENT_PSK.storedName,
        ) ?: return null
        if (!previousPskEligible(previous.updatedAt, currentTimeMillis())) return null
        return decryptClientPsk(pairing, PairingSecretKind.PREVIOUS_CLIENT_PSK)
    }

    private fun pairingSecretLocation(
        secretId: String,
        pairing: PairingEntity,
        kind: PairingSecretKind,
    ) = EncryptionLocation(
        recordType = "pairing_secret",
        recordId = secretId,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("kind", kind.storedName),
            EncryptionBinding("client_id", pairing.clientId),
            EncryptionBinding("pairing_request_id", pairing.requestId.toString()),
            EncryptionBinding("device_id", pairing.deviceId),
        ),
    )

    private fun String.toInboxRequestState(): InboxRequestState =
        checkNotNull(InboxRequestState.entries.find { it.storedName == this })

    private fun String.toPairingState(): PairingState =
        checkNotNull(PairingState.entries.find { it.storedName == this })

    private fun String.toRelayClientState(): RelayClientState =
        checkNotNull(RelayClientState.entries.find { it.wireName == this })

    private fun String.toSecretUseRequestState(): SecretUseRequestState =
        checkNotNull(SecretUseRequestState.entries.find { it.storedName == this })

    private fun String.toSecretUseDecision(): SecretUseDecision =
        checkNotNull(SecretUseDecision.entries.find { it.storedName == this })

    private fun String.toSecretUseCompletionResult(): SecretUseCompletionResult =
        checkNotNull(SecretUseCompletionResult.entries.find { it.storedName == this })

    private fun String.toSecretListRequestState(): SecretListRequestState =
        checkNotNull(SecretListRequestState.entries.find { it.storedName == this })

    private fun String.toSecretUploadRequestState(): SecretUploadRequestState =
        checkNotNull(SecretUploadRequestState.entries.find { it.storedName == this })

    private fun encodeStringList(values: List<String>): String =
        json.encodeToString(STRING_LIST_SERIALIZER, values)

    private fun decodeStringList(value: String): List<String> =
        json.decodeFromString(STRING_LIST_SERIALIZER, value)

    private companion object {
        const val CLIENT_PSK_BYTES = 32
        const val IDEMPOTENCY_RETENTION_MILLIS = 25 * 60 * 60 * 1_000L
        const val SECRET_USE_DENIAL_MESSAGE = "Denied on device."
        val STRING_LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}

private enum class RequestKind(val storedName: String) {
    PAIRING("pairing"),
    PAIRING_FINISH("pairing_finish"),
    SECRET_USE("secret_use"),
    SECRET_LIST("secret_list"),
    SECRET_UPLOAD("secret_upload"),
    PAIRING_REMOVE("pairing_remove"),
    UNKNOWN("unknown"),
}

private enum class PairingSecretKind(val storedName: String) {
    CURRENT_CLIENT_PSK("current_client_psk"),
    PREVIOUS_CLIENT_PSK("previous_client_psk"),
}

private val INCOMPLETE_PAIRING_STATES = setOf(
    PairingState.RECEIVING,
    PairingState.SAS_VERIFICATION_PENDING,
    PairingState.RELAY_ACTIVATION_PENDING,
    PairingState.WAITING_FOR_FINISH,
    PairingState.VERIFICATION_FAILED,
)

private val USER_REJECTABLE_PAIRING_STATES = setOf(
    PairingState.RECEIVING,
    PairingState.RELAY_ACTIVATION_PENDING,
    PairingState.WAITING_FOR_FINISH,
    PairingState.VERIFICATION_FAILED,
)

private const val PREVIOUS_PSK_OVERLAP_MILLIS = 10 * 60 * 1_000L
