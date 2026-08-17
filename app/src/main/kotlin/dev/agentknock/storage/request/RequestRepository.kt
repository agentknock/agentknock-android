package dev.agentknock.storage.request

import dev.agentknock.protocol.CredentialCompletion
import dev.agentknock.protocol.CredentialDenialReason
import dev.agentknock.protocol.CredentialProtocol
import dev.agentknock.protocol.CredentialRequestMessage
import dev.agentknock.protocol.OpenedPairedRequest
import dev.agentknock.protocol.PairedRequestProtocol
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.protocol.PairingRemoveProtocol
import dev.agentknock.protocol.ProfileListProfile
import dev.agentknock.protocol.ProfileListProtocol
import dev.agentknock.protocol.ProfileListRequestMessage
import dev.agentknock.protocol.ProfileUploadMode
import dev.agentknock.protocol.ProfileUploadProtocol
import dev.agentknock.protocol.ProfileUploadRequestMessage
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
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import dev.agentknock.protocol.CredentialResponseProfile
import dev.agentknock.storage.profile.CredentialProfilesResult
import dev.agentknock.storage.profile.CredentialProfileMetadata
import dev.agentknock.storage.profile.CredentialProfileDescription
import dev.agentknock.storage.profile.ApplyEnvironmentProfileProposalResult
import dev.agentknock.storage.profile.EnvironmentProfileProposal
import dev.agentknock.storage.profile.EnvironmentProfileProposalResult
import dev.agentknock.storage.profile.ProfileRepository
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
    CREDENTIAL,
    PROFILE_UPLOAD,
}

internal enum class CredentialRequestState(val storedName: String) {
    APPROVAL_PENDING("approval_pending"),
    WAITING_FOR_COMPLETION("waiting_for_completion"),
    COMPLETED("completed"),
    VERIFICATION_FAILED("verification_failed"),
}

internal enum class CredentialDecision(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
}

internal enum class CredentialCompletionResult(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
    ABORTED("aborted"),
}

internal enum class ProfileListRequestState(val storedName: String) {
    WAITING_FOR_COMPLETION("waiting_for_completion"),
    COMPLETED("completed"),
    VERIFICATION_FAILED("verification_failed"),
}

internal enum class ProfileUploadRequestState(val storedName: String) {
    REVIEW_PENDING("review_pending"),
    ACCEPTED("accepted"),
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

internal fun pairingAdmissionAllowed(existingStates: Iterable<PairingState>): Boolean =
    existingStates.none { it in INCOMPLETE_PAIRING_STATES }

internal data class InboxRequestSummary(
    val id: Long,
    val kind: InboxRequestKind,
    val state: InboxRequestState,
    val pairingState: PairingState?,
    val credentialState: CredentialRequestState?,
    val credentialDecision: CredentialDecision?,
    val credentialResult: CredentialCompletionResult?,
    val profileListState: ProfileListRequestState?,
    val profileUploadState: ProfileUploadRequestState?,
    val title: String,
    val subtitle: String,
    val receivedAt: Long,
    val completedAt: Long?,
)

internal data class PairingRequestDetails(
    val pairingState: PairingState,
    val vaultAddress: String,
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
    val credential: CredentialRequestDetails?,
    val profileList: ProfileListRequestDetails?,
    val profileUpload: ProfileUploadRequestDetails?,
)

internal data class ProfileListRequestDetails(
    val state: ProfileListRequestState,
    val profiles: List<CredentialProfileMetadata>,
    val vaultAddress: String,
    val clientId: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    val machineId: String?,
    val osVersion: String?,
    val cliVersion: String,
    val error: String?,
)

internal data class CredentialRequestDetails(
    val state: CredentialRequestState,
    val decision: CredentialDecision?,
    val completionResult: CredentialCompletionResult?,
    val completionReason: String?,
    val completionMessage: String?,
    val profiles: List<String>,
    val profileDetails: List<CredentialProfileMetadata>,
    val missingProfiles: List<String>,
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
    val vaultAddress: String,
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

internal data class ProfileUploadRequestDetails(
    val state: ProfileUploadRequestState,
    val mode: ProfileUploadMode,
    val proposedName: String,
    val acceptedName: String?,
    val descriptionProvided: Boolean,
    val description: String?,
    val profileType: String,
    val variableNames: List<String>,
    val addedVariables: List<String>,
    val changedVariables: List<String>,
    val unchangedVariables: List<String>,
    val removedVariables: List<String>,
    val clientName: String,
    val clientId: String,
    val error: String?,
    val decidedAt: Long?,
)

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

internal sealed interface RequestSyncResult {
    data object Success : RequestSyncResult

    data object NoVault : RequestSyncResult

    data object VaultSecretsUnavailable : RequestSyncResult

    data object VaultSecretsCorrupted : RequestSyncResult

    data object UnsupportedVaultEncryption : RequestSyncResult

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

internal sealed interface CredentialDecisionResult {
    data object Decided : CredentialDecisionResult

    data object ProfilesChanged : CredentialDecisionResult

    data object NotPending : CredentialDecisionResult

    data object NotFound : CredentialDecisionResult

    data class MissingProfiles(val names: List<String>) : CredentialDecisionResult

    data class ConflictingVariable(val name: String) : CredentialDecisionResult

    data object SecretUnavailable : CredentialDecisionResult

    data object SecretCorrupted : CredentialDecisionResult

    data object UnsupportedEncryption : CredentialDecisionResult

    data object PairingUnavailable : CredentialDecisionResult
}

internal sealed interface ProfileUploadDecisionResult {
    data class Accepted(val profileId: String) : ProfileUploadDecisionResult
    data object Rejected : ProfileUploadDecisionResult
    data object NotPending : ProfileUploadDecisionResult
    data object NotFound : ProfileUploadDecisionResult
    data class Invalid(val message: String) : ProfileUploadDecisionResult
    data object SecretUnavailable : ProfileUploadDecisionResult
    data object SecretCorrupted : ProfileUploadDecisionResult
    data object UnsupportedEncryption : ProfileUploadDecisionResult
}

internal enum class ClientChangeResult {
    CHANGED,
    NOT_FOUND,
    INVALID_STATE,
}

internal class RequestRepository(
    private val dao: RequestDao,
    private val deviceCredentials: RelayDeviceCredentialSource,
    private val profiles: ProfileRepository,
    private val relay: RelayDeviceClient,
    private val keyManager: LocalEncryptionKeyManager,
    private val encryption: AesGcmEncryption,
    private val audit: AuditSink = NoOpAuditSink,
    private val requestPushRegistration: () -> Unit = {},
    private val pairingProtocol: PairingProtocol = PairingProtocol(),
    private val pairedRequestProtocol: PairedRequestProtocol = PairedRequestProtocol(),
    private val credentialProtocol: CredentialProtocol = CredentialProtocol(),
    private val profileListProtocol: ProfileListProtocol = ProfileListProtocol(),
    private val profileUploadProtocol: ProfileUploadProtocol = ProfileUploadProtocol(),
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
        dao.observeCredentialRequests(),
        dao.observeProfileUploadRequests(),
    ) { requests, pairings, credentialRequests, profileUploadRequests ->
        val pairingByRequest = pairings.associateBy(PairingEntity::requestId)
        val pairingByClient = pairings.associateBy(PairingEntity::clientId)
        val credentialByRequest = credentialRequests.associateBy(CredentialRequestEntity::requestId)
        val profileUploadByRequest = profileUploadRequests.associateBy(
            ProfileUploadRequestEntity::requestId,
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
                        credentialState = null,
                        credentialDecision = null,
                        credentialResult = null,
                        profileListState = null,
                        profileUploadState = null,
                        title = pairing.friendlyName ?: pairing.hostname
                            ?: pairing.platform?.let { platform ->
                                pairing.architecture?.let { "$platform · $it" } ?: platform
                            }
                            ?: "Unknown client",
                        subtitle = pairing.vaultAddress,
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                    )
                }
                RequestKind.CREDENTIAL.storedName -> {
                    val credential = credentialByRequest[request.id] ?: return@mapNotNull null
                    val requestedProfiles = decodeStringList(credential.profilesJson)
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.CREDENTIAL,
                        state = request.state.toInboxRequestState(),
                        pairingState = null,
                        credentialState = credential.state.toCredentialRequestState(),
                        credentialDecision = credential.decision?.toCredentialDecision(),
                        credentialResult = credential.completionResult
                            ?.toCredentialCompletionResult(),
                        profileListState = null,
                        profileUploadState = null,
                        title = credential.command,
                        subtitle = listOf(
                            credential.clientName,
                            requestedProfiles.joinToString(),
                        ).filter(String::isNotBlank).joinToString(" · "),
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                    )
                }
                RequestKind.PROFILE_UPLOAD.storedName -> {
                    val upload = profileUploadByRequest[request.id] ?: return@mapNotNull null
                    val pairing = pairingByClient[upload.clientId]
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.PROFILE_UPLOAD,
                        state = request.state.toInboxRequestState(),
                        pairingState = null,
                        credentialState = null,
                        credentialDecision = null,
                        credentialResult = null,
                        profileListState = null,
                        profileUploadState = upload.state.toProfileUploadRequestState(),
                        title = "${upload.mode.lowercase().replaceFirstChar(Char::uppercase)} ${upload.proposedName}",
                        subtitle = pairing?.friendlyName ?: pairing?.hostname ?: "Unknown client",
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                    )
                }
                else -> null
            }
        }
    }

    fun observeRequest(id: Long): Flow<InboxRequestDetails?> = combine(
        dao.observeRequest(id),
        dao.observePairing(id),
        dao.observeCredentialRequest(id),
        dao.observeProfileListRequest(id),
        dao.observeProfileUploadRequest(id),
    ) { request, pairing, credential, profileList, profileUpload ->
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
                    vaultAddress = it.vaultAddress,
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
            credential = credential?.let {
                CredentialRequestDetails(
                    state = it.state.toCredentialRequestState(),
                    decision = it.decision?.toCredentialDecision(),
                    completionResult = it.completionResult?.toCredentialCompletionResult(),
                    completionReason = it.completionReason,
                    completionMessage = it.completionMessage,
                    profiles = decodeStringList(it.profilesJson),
                    profileDetails = json.decodeFromString(it.profileDetailsJson),
                    missingProfiles = decodeStringList(it.missingProfilesJson),
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
                    vaultAddress = it.vaultAddress,
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
            profileList = profileList?.let {
                ProfileListRequestDetails(
                    state = it.state.toProfileListRequestState(),
                    profiles = json.decodeFromString(it.profilesJson),
                    vaultAddress = it.vaultAddress,
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
            profileUpload = profileUpload?.let {
                ProfileUploadRequestDetails(
                    state = it.state.toProfileUploadRequestState(),
                    mode = ProfileUploadMode.entries.single { mode -> mode.wireName == it.mode },
                    proposedName = it.proposedName,
                    acceptedName = it.acceptedName,
                    descriptionProvided = it.descriptionProvided,
                    description = it.description,
                    profileType = it.profileType,
                    variableNames = decodeStringList(it.variableNamesJson),
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
    }

    fun observeClients(): Flow<List<ClientSummary>> = dao.observePairings().map { pairings ->
        pairings
            .filter { it.state == PairingState.ACTIVE.storedName }
            .mapNotNull { pairing ->
                val state = pairing.relayClientState?.toRelayClientState() ?: return@mapNotNull null
                ClientSummary(
                    clientId = pairing.clientId,
                    name = pairing.friendlyName ?: pairing.hostname ?: "Unnamed client",
                    hostname = pairing.hostname,
                    platform = pairing.platform,
                    state = state,
                    desiredState = pairing.desiredRelayClientState?.toRelayClientState(),
                    pairedAt = pairing.completedAt,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun observeClient(clientId: String): Flow<ClientDetails?> =
        dao.observePairingByClientId(clientId).map { pairing ->
            pairing?.takeIf { it.state == PairingState.ACTIVE.storedName }?.let {
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

    suspend fun listen(onCaughtUp: () -> Unit): RequestSyncResult = runConnection(
        keepConnected = true,
        onCaughtUp = onCaughtUp,
    )

    fun requestSync() {
        pendingChanges.trySend(Unit)
    }

    private suspend fun runConnection(
        keepConnected: Boolean,
        onCaughtUp: () -> Unit = {},
    ): RequestSyncResult = connectionMutex.withLock {
        val credentials = when (val result = deviceCredentials.activeDeviceCredentials()) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            RelayDeviceCredentialsResult.Missing -> return@withLock RequestSyncResult.NoVault
            RelayDeviceCredentialsResult.SecretsUnavailable -> {
                return@withLock RequestSyncResult.VaultSecretsUnavailable
            }
            RelayDeviceCredentialsResult.SecretsCorrupted -> {
                return@withLock RequestSyncResult.VaultSecretsCorrupted
            }
            RelayDeviceCredentialsResult.UnsupportedEncryption -> {
                return@withLock RequestSyncResult.UnsupportedVaultEncryption
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
                            it == RelayMessageKind.REQUEST ||
                                it == RelayMessageKind.COMPLETION
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
            operationMutex.withLock { dao.deleteSettledHiddenRequests() }
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
                if (pairing.vaultIdentityId != credentials.vaultIdentityId) continue
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
                if (pairing.vaultIdentityId != credentials.vaultIdentityId) continue
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
                if (pairing.vaultIdentityId != credentials.vaultIdentityId) continue
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
        dao.updatePairing(
            pairing.copy(
                state = if (activated) {
                    PairingState.WAITING_FOR_FINISH.storedName
                } else {
                    pairing.state
                },
                relayClientState = event.state.wireName,
                updatedAt = now,
            ),
        )
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
                dao.updatePairingRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        updatedAt = now,
                        completedAt = now,
                    ),
                    pairing.copy(
                        state = PairingState.VERIFICATION_FAILED.storedName,
                        error = message,
                        updatedAt = now,
                        completedAt = now,
                    ),
                )
            }
            RequestKind.CREDENTIAL.storedName -> {
                val credential = dao.getCredentialRequest(request.id) ?: return
                dao.updateCredentialRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        updatedAt = now,
                        completedAt = now,
                    ),
                    credential.copy(
                        state = CredentialRequestState.VERIFICATION_FAILED.storedName,
                        error = message,
                        updatedAt = now,
                        completedAt = now,
                    ),
                )
            }
            RequestKind.PROFILE_LIST.storedName -> {
                val profileList = dao.getProfileListRequest(request.id) ?: return
                dao.updateProfileListRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        updatedAt = now,
                        completedAt = now,
                    ),
                    profileList.copy(
                        state = ProfileListRequestState.VERIFICATION_FAILED.storedName,
                        error = message,
                        updatedAt = now,
                        completedAt = now,
                    ),
                )
            }
            RequestKind.PROFILE_UPLOAD.storedName -> {
                val upload = dao.getProfileUploadRequest(request.id) ?: return
                val pending = upload.state == ProfileUploadRequestState.REVIEW_PENDING.storedName
                dao.updateProfileUploadRequest(
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
                val rootId = request.parentRequestId ?: return
                val root = dao.getRequestById(rootId) ?: return
                val pairing = dao.getPairing(rootId) ?: return
                dao.updatePairingRequest(
                    root.copy(state = InboxRequestState.ACTION_REQUIRED.storedName, updatedAt = now),
                    pairing.copy(
                        state = PairingState.VERIFICATION_FAILED.storedName,
                        error = message,
                        updatedAt = now,
                    ),
                )
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
            dao.updatePairingRequest(
                request = request.copy(
                    state = if (verified) {
                        InboxRequestState.WAITING.storedName
                    } else {
                        InboxRequestState.COMPLETED.storedName
                    },
                    updatedAt = now,
                    completedAt = if (verified) null else now,
                ),
                pairing = pairing.copy(
                    state = if (verified) {
                        PairingState.RELAY_ACTIVATION_PENDING.storedName
                    } else {
                        PairingState.REJECTED.storedName
                    },
                    desiredRelayClientState = if (verified) {
                        RelayClientState.ACTIVE.wireName
                    } else {
                        pairing.desiredRelayClientState
                    },
                    updatedAt = now,
                    decidedAt = now,
                    completedAt = if (verified) null else now,
                ),
            )
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
            if (result == PairingDecisionResult.VERIFIED) requestSync()
        }

    suspend fun rejectPairing(requestId: Long): PairingDecisionResult = operationMutex.withLock {
        val request = dao.getRequestById(requestId) ?: return PairingDecisionResult.NOT_FOUND
        val pairing = dao.getPairing(requestId) ?: return PairingDecisionResult.NOT_FOUND
        if (pairing.state.toPairingState() !in USER_REJECTABLE_PAIRING_STATES) {
            return PairingDecisionResult.NOT_PENDING
        }
        val now = currentTimeMillis()
        dao.updatePairingRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                updatedAt = now,
                completedAt = now,
            ),
            pairing = pairing.copy(
                state = PairingState.REJECTED.storedName,
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
    }

    suspend fun approveCredentialRequest(requestId: Long): CredentialDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return CredentialDecisionResult.NotFound
            val credentialRequest = dao.getCredentialRequest(requestId)
                ?: return CredentialDecisionResult.NotFound
            if (credentialRequest.state != CredentialRequestState.APPROVAL_PENDING.storedName) {
                return CredentialDecisionResult.NotPending
            }
            val requestedProfiles = decodeStringList(credentialRequest.profilesJson)
            val latestDescription = profiles.describeCredentialProfiles(requestedProfiles)
            val storedProfiles = json.decodeFromString<List<CredentialProfileMetadata>>(
                credentialRequest.profileDetailsJson,
            )
            val storedMissingProfiles = decodeStringList(credentialRequest.missingProfilesJson)
            if (
                latestDescription.profiles != storedProfiles ||
                latestDescription.missingProfiles != storedMissingProfiles
            ) {
                val now = currentTimeMillis()
                dao.updateCredentialRequest(
                    request = request.copy(updatedAt = now),
                    credentialRequest = credentialRequest.copy(
                        profileDetailsJson = json.encodeToString(latestDescription.profiles),
                        missingProfilesJson = encodeStringList(latestDescription.missingProfiles),
                        updatedAt = now,
                    ),
                )
                return CredentialDecisionResult.ProfilesChanged
            }
            val responseProfiles = when (
                val result = profiles.credentialProfiles(requestedProfiles)
            ) {
                is CredentialProfilesResult.Available -> result.profiles.mapValues { (_, profile) ->
                    CredentialResponseProfile(profile.description, profile.environment)
                }
                is CredentialProfilesResult.MissingProfiles -> {
                    return CredentialDecisionResult.MissingProfiles(result.names)
                }
                is CredentialProfilesResult.ConflictingVariable -> {
                    return CredentialDecisionResult.ConflictingVariable(result.name)
                }
                CredentialProfilesResult.SecretUnavailable -> {
                    return CredentialDecisionResult.SecretUnavailable
                }
                CredentialProfilesResult.SecretCorrupted -> {
                    return CredentialDecisionResult.SecretCorrupted
                }
                CredentialProfilesResult.UnsupportedEncryption -> {
                    return CredentialDecisionResult.UnsupportedEncryption
                }
            }
            decideCredentialRequest(
                request = request,
                credentialRequest = credentialRequest,
                decision = CredentialDecision.APPROVED,
                responsePlaintext = credentialProtocol.approvedResponse(responseProfiles),
            )
        }.also { result ->
            if (result == CredentialDecisionResult.Decided) requestSync()
        }

    suspend fun denyCredentialRequest(requestId: Long): CredentialDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return CredentialDecisionResult.NotFound
            val credentialRequest = dao.getCredentialRequest(requestId)
                ?: return CredentialDecisionResult.NotFound
            if (credentialRequest.state != CredentialRequestState.APPROVAL_PENDING.storedName) {
                return CredentialDecisionResult.NotPending
            }
            decideCredentialRequest(
                request = request,
                credentialRequest = credentialRequest,
                decision = CredentialDecision.DENIED,
                responsePlaintext = credentialProtocol.deniedResponse(
                    CredentialDenialReason.USER_DENIED,
                    CREDENTIAL_DENIAL_MESSAGE,
                ),
            )
        }.also { result ->
            if (result == CredentialDecisionResult.Decided) requestSync()
        }

    private suspend fun decideCredentialRequest(
        request: InboxRequestEntity,
        credentialRequest: CredentialRequestEntity,
        decision: CredentialDecision,
        responsePlaintext: ByteArray,
    ): CredentialDecisionResult {
        val pairingRequestId = credentialRequest.pairingRequestId
            ?: return CredentialDecisionResult.PairingUnavailable
        val pairing = dao.getPairing(pairingRequestId)
            ?: return CredentialDecisionResult.PairingUnavailable
        if (pairing.state != PairingState.ACTIVE.storedName) {
            return CredentialDecisionResult.PairingUnavailable
        }
        val credentials = credentialsForPairing(pairing)
            ?: return CredentialDecisionResult.PairingUnavailable
        val clientPsk = decryptClientPsk(pairing)
            ?: return CredentialDecisionResult.PairingUnavailable
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
        }.getOrNull() ?: return CredentialDecisionResult.PairingUnavailable
        val now = currentTimeMillis()
        dao.updateCredentialRequest(
            request = request.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = response.toString(),
                responseAcknowledgedAt = null,
                updatedAt = now,
            ),
            credentialRequest = credentialRequest.copy(
                state = CredentialRequestState.WAITING_FOR_COMPLETION.storedName,
                decision = decision.storedName,
                updatedAt = now,
                decidedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.PROFILE_ACCESS,
                title = if (decision == CredentialDecision.APPROVED) {
                    "Profile access approved"
                } else {
                    "Profile access denied"
                },
                detail = decodeStringList(credentialRequest.profilesJson).joinToString(),
                outcome = if (decision == CredentialDecision.APPROVED) {
                    AuditOutcome.APPROVED
                } else {
                    AuditOutcome.DENIED
                },
                clientId = credentialRequest.clientId,
                relayRequestId = request.relayRequestId,
            ),
        )
        return CredentialDecisionResult.Decided
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

    suspend fun acceptProfileUpload(
        requestId: Long,
        acceptedName: String,
    ): ProfileUploadDecisionResult = operationMutex.withLock {
        val request = dao.getRequestById(requestId)
            ?: return@withLock ProfileUploadDecisionResult.NotFound
        val upload = dao.getProfileUploadRequest(requestId)
            ?: return@withLock ProfileUploadDecisionResult.NotFound
        if (upload.state != ProfileUploadRequestState.REVIEW_PENDING.storedName) {
            return@withLock ProfileUploadDecisionResult.NotPending
        }
        val values = sortedMapOf<String, String>()
        for (variable in dao.getProfileUploadVariables(requestId)) {
            when (
                val result = withContext(cryptographyDispatcher) {
                    encryption.decrypt(
                        encrypted = EncryptedValue(
                            formatVersion = variable.encryptionFormat,
                            keyId = variable.encryptionKeyId,
                            nonce = variable.nonce,
                            ciphertext = variable.ciphertext,
                        ),
                        location = profileUploadVariableLocation(
                            variable.id,
                            request.relayRequestId,
                            upload.clientId,
                            variable.name,
                        ),
                    )
                }
            ) {
                is DecryptionResult.Plaintext -> {
                    values[variable.name] = runCatching {
                        result.value.decodeToString(throwOnInvalidSequence = true)
                    }.getOrElse { return@withLock ProfileUploadDecisionResult.SecretCorrupted }
                }
                DecryptionResult.KeyUnavailable -> {
                    return@withLock ProfileUploadDecisionResult.SecretUnavailable
                }
                DecryptionResult.AuthenticationFailed -> {
                    return@withLock ProfileUploadDecisionResult.SecretCorrupted
                }
                DecryptionResult.UnsupportedFormat -> {
                    return@withLock ProfileUploadDecisionResult.UnsupportedEncryption
                }
            }
        }
        val proposal = EnvironmentProfileProposal(
            mode = ProfileUploadMode.entries.single { it.wireName == upload.mode },
            name = upload.proposedName,
            descriptionProvided = upload.descriptionProvided,
            description = upload.description,
            variables = values,
        )
        when (
            val result = profiles.applyEnvironmentProfileProposal(proposal, acceptedName.trim())
        ) {
            is ApplyEnvironmentProfileProposalResult.Invalid -> {
                ProfileUploadDecisionResult.Invalid(result.message)
            }
            is ApplyEnvironmentProfileProposalResult.Applied -> {
                val now = currentTimeMillis()
                dao.updateProfileUploadRequest(
                    request = request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        updatedAt = now,
                        completedAt = now,
                    ),
                    profileUpload = upload.copy(
                        state = ProfileUploadRequestState.ACCEPTED.storedName,
                        acceptedName = acceptedName.trim(),
                        updatedAt = now,
                        decidedAt = now,
                    ),
                )
                audit.record(
                    AuditRecord(
                        category = AuditCategory.PROFILE_PROPOSAL,
                        title = "Profile proposal accepted",
                        detail = upload.proposedName,
                        outcome = AuditOutcome.ACCEPTED,
                        clientId = upload.clientId,
                        relayRequestId = request.relayRequestId,
                    ),
                )
                ProfileUploadDecisionResult.Accepted(result.profileId)
            }
        }
    }

    suspend fun rejectProfileUpload(requestId: Long): ProfileUploadDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return@withLock ProfileUploadDecisionResult.NotFound
            val upload = dao.getProfileUploadRequest(requestId)
                ?: return@withLock ProfileUploadDecisionResult.NotFound
            if (upload.state != ProfileUploadRequestState.REVIEW_PENDING.storedName) {
                return@withLock ProfileUploadDecisionResult.NotPending
            }
            val now = currentTimeMillis()
            dao.updateProfileUploadRequest(
                request = request.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    updatedAt = now,
                    completedAt = now,
                ),
                profileUpload = upload.copy(
                    state = ProfileUploadRequestState.REJECTED.storedName,
                    updatedAt = now,
                    decidedAt = now,
                ),
            )
            audit.record(
                AuditRecord(
                    category = AuditCategory.PROFILE_PROPOSAL,
                    title = "Profile proposal rejected",
                    detail = upload.proposedName,
                    outcome = AuditOutcome.REJECTED,
                    clientId = upload.clientId,
                    relayRequestId = request.relayRequestId,
                ),
            )
            ProfileUploadDecisionResult.Rejected
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
        dao.updatePairing(
            pairing.copy(
                desiredRelayClientState = state.wireName,
                updatedAt = currentTimeMillis(),
            ),
        )
        requestSync()
        ClientChangeResult.CHANGED
    }

    suspend fun revokeAllClientsAfterAddressChange() = operationMutex.withLock {
        val now = currentTimeMillis()
        var changed = 0
        dao.getPairings().forEach { pairing ->
            val state = pairing.relayClientState?.toRelayClientState()
            if (pairing.state == PairingState.ACTIVE.storedName && state != RelayClientState.REVOKED) {
                dao.updatePairing(
                    pairing.copy(
                        desiredRelayClientState = RelayClientState.REVOKED.wireName,
                        updatedAt = now,
                    ),
                )
                changed += 1
            }
        }
        if (changed > 0) {
            audit.record(
                AuditRecord(
                    category = AuditCategory.DEVICE,
                    title = "Clients revoked after address change",
                    detail = "$changed clients scheduled for revocation",
                    outcome = AuditOutcome.CHANGED,
                ),
            )
            requestSync()
        }
    }

    private suspend fun processNewRequest(
        credentials: RelayDeviceCredentials,
        message: IncomingRelayMessage,
        requestPayload: JsonElement,
    ): ProcessedRelayMessage? {
        if (message.addressId != null) {
            if (!pairingProtocol.isInitialRequest(requestPayload)) return null
            return startPairing(credentials, message, requestPayload)
        }

        val pairing = dao.getPairingByClientId(message.clientId) ?: return null
        if (pairing.vaultIdentityId != credentials.vaultIdentityId) return null
        val clientPsk = decryptClientPsk(pairing) ?: return null
        val opened = runCatching {
            pairingProtocol.openPairedRequest(
                deviceId = pairing.deviceId,
                requestId = message.requestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
            )
        }.getOrNull() ?: return null
        val method = runCatching { pairedRequestProtocol.method(opened.plaintext) }.getOrNull()
            ?: return null
        if (method == CredentialProtocol.CREDENTIAL_REQUEST_METHOD) {
            return processCredentialRequest(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                currentClientPsk = clientPsk,
            )
        }
        if (method == ProfileListProtocol.LIST_METHOD) {
            return processProfileListRequest(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                currentClientPsk = clientPsk,
                credentials = credentials,
            )
        }
        if (method == ProfileUploadProtocol.METHOD) {
            return processProfileUploadRequest(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                currentClientPsk = clientPsk,
                credentials = credentials,
            )
        }
        if (method == PairingRemoveProtocol.METHOD) {
            return processPairingRemoveRequest(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                currentClientPsk = clientPsk,
                credentials = credentials,
            )
        }
        if (method != PairedRequestProtocol.FINISH_PAIRING_METHOD) return null
        return processFinishRequest(
            credentials = credentials,
            relayRequestId = message.requestId,
            requestPayload = requestPayload,
            clientId = message.clientId,
        )
    }

    private suspend fun processCredentialRequest(
        pairing: PairingEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        currentClientPsk: ByteArray,
    ): ProcessedRelayMessage? {
        if (pairing.state != PairingState.ACTIVE.storedName) return null
        val contents = runCatching {
            credentialProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val description = profiles.describeCredentialProfiles(contents.profiles)
        val now = currentTimeMillis()
        val currentSecret = dao.getPairingSecret(
            pairing.requestId,
            PairingSecretKind.CLIENT_PSK.storedName,
        ) ?: return null
        val rotatedSecret = if (opened.clientPsk.contentEquals(currentClientPsk)) {
            null
        } else {
            encryptClientPsk(currentSecret, pairing, opened.clientPsk, now)
        }
        dao.insertCredentialRequest(
            request = InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = null,
                kind = RequestKind.CREDENTIAL.storedName,
                state = InboxRequestState.ACTION_REQUIRED.storedName,
                listed = true,
                requestJson = requestPayload.toString(),
                responseJson = null,
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = null,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            credentialRequest = credentialRequestEntity(
                pairing = pairing,
                contents = contents,
                description = description,
                now = now,
            ),
            rotatedPairingSecret = rotatedSecret,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.PROFILE_ACCESS,
                title = "Profile access requested",
                detail = contents.profiles.joinToString(),
                outcome = AuditOutcome.RECEIVED,
                clientId = pairing.clientId,
                relayRequestId = relayRequestId,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processProfileListRequest(
        pairing: PairingEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        currentClientPsk: ByteArray,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        if (pairing.state != PairingState.ACTIVE.storedName) return null
        val contents = runCatching {
            profileListProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val profileMetadata = profiles.listCredentialProfiles()
        val responsePlaintext = profileListProtocol.response(
            profileMetadata.associateTo(sortedMapOf()) { profile ->
                profile.name to ProfileListProfile(
                    description = profile.description,
                    environmentVariableNames = profile.environmentVariableNames,
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
        val currentSecret = dao.getPairingSecret(
            pairing.requestId,
            PairingSecretKind.CLIENT_PSK.storedName,
        ) ?: return null
        val rotatedSecret = if (opened.clientPsk.contentEquals(currentClientPsk)) {
            null
        } else {
            encryptClientPsk(currentSecret, pairing, opened.clientPsk, now)
        }
        dao.insertProfileListRequest(
            request = InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = null,
                kind = RequestKind.PROFILE_LIST.storedName,
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
            profileListRequest = profileListRequestEntity(
                pairing = pairing,
                contents = contents,
                profileMetadata = profileMetadata,
                now = now,
            ),
            rotatedPairingSecret = rotatedSecret,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.PROFILE_LIST,
                title = "Profile list requested",
                detail = "${profileMetadata.size} profiles sent to ${pairing.friendlyName ?: pairing.hostname ?: "client"}",
                outcome = AuditOutcome.RECEIVED,
                clientId = pairing.clientId,
                relayRequestId = relayRequestId,
            ),
        )
        return ProcessedRelayMessage(response)
    }

    private suspend fun processProfileUploadRequest(
        pairing: PairingEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        currentClientPsk: ByteArray,
        credentials: RelayDeviceCredentials,
    ): ProcessedRelayMessage? {
        if (pairing.state != PairingState.ACTIVE.storedName) return null
        val contents = runCatching {
            profileUploadProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val proposal = EnvironmentProfileProposal(
            mode = contents.mode,
            name = contents.name,
            descriptionProvided = contents.descriptionProvided,
            description = contents.description,
            variables = contents.variables,
        )
        val validation = profiles.describeEnvironmentProfileProposal(proposal)
        val valid = validation as? EnvironmentProfileProposalResult.Valid
        val invalid = validation as? EnvironmentProfileProposalResult.Invalid
        val responsePlaintext = if (valid != null) {
            profileUploadProtocol.receivedResponse()
        } else {
            profileUploadProtocol.rejectedResponse(checkNotNull(invalid).message)
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
        val currentSecret = dao.getPairingSecret(
            pairing.requestId,
            PairingSecretKind.CLIENT_PSK.storedName,
        ) ?: return null
        val rotatedSecret = if (opened.clientPsk.contentEquals(currentClientPsk)) {
            null
        } else {
            encryptClientPsk(currentSecret, pairing, opened.clientPsk, now)
        }
        val encryptedVariables = if (valid == null) {
            emptyList()
        } else {
            contents.variables.map { (name, value) ->
                encryptProfileUploadVariable(
                    relayRequestId = relayRequestId,
                    clientId = pairing.clientId,
                    name = name,
                    value = value,
                    now = now,
                )
            }
        }
        dao.insertProfileUploadRequest(
            request = InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = null,
                kind = RequestKind.PROFILE_UPLOAD.storedName,
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
            profileUpload = ProfileUploadRequestEntity(
                requestId = 0,
                pairingRequestId = pairing.requestId,
                clientId = pairing.clientId,
                clientName = pairing.friendlyName ?: pairing.hostname ?: "Unknown client",
                state = if (valid != null) {
                    ProfileUploadRequestState.REVIEW_PENDING.storedName
                } else {
                    ProfileUploadRequestState.REJECTED.storedName
                },
                cliVersion = contents.cliVersion,
                mode = contents.mode.wireName,
                proposedName = contents.name,
                acceptedName = null,
                descriptionProvided = contents.descriptionProvided,
                description = contents.description,
                profileType = "environment",
                variableNamesJson = encodeStringList(contents.variables.keys.sorted()),
                addedVariablesJson = encodeStringList(valid?.summary?.addedVariables.orEmpty()),
                changedVariablesJson = encodeStringList(valid?.summary?.changedVariables.orEmpty()),
                unchangedVariablesJson = encodeStringList(
                    valid?.summary?.unchangedVariables.orEmpty(),
                ),
                removedVariablesJson = encodeStringList(valid?.summary?.removedVariables.orEmpty()),
                error = invalid?.message,
                transportResult = if (valid != null) {
                    ProfileUploadProtocol.RESULT_RECEIVED
                } else {
                    ProfileUploadProtocol.RESULT_REJECTED
                },
                transportMessage = invalid?.message,
                createdAt = now,
                updatedAt = now,
                decidedAt = if (valid == null) now else null,
                transportCompletedAt = null,
            ),
            variables = encryptedVariables,
            rotatedPairingSecret = rotatedSecret,
        )
        audit.record(
            AuditRecord(
                category = AuditCategory.PROFILE_PROPOSAL,
                title = if (valid != null) "Profile proposal received" else "Profile proposal rejected",
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
        currentClientPsk: ByteArray,
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
        val currentSecret = dao.getPairingSecret(
            pairing.requestId,
            PairingSecretKind.CLIENT_PSK.storedName,
        ) ?: return null
        val rotatedSecret = if (opened.clientPsk.contentEquals(currentClientPsk)) {
            null
        } else {
            encryptClientPsk(currentSecret, pairing, opened.clientPsk, now)
        }
        dao.insertHiddenPairedRequest(
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
            rotatedPairingSecret = rotatedSecret,
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
                vaultIdentityId = credentials.vaultIdentityId,
                vaultAddress = credentials.address,
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
                title = "Pairing request received",
                outcome = AuditOutcome.RECEIVED,
                clientId = message.clientId,
                relayRequestId = message.requestId,
            ),
        )
        return ProcessedRelayMessage(response)
    }

    private fun credentialRequestEntity(
        pairing: PairingEntity,
        contents: CredentialRequestMessage,
        description: CredentialProfileDescription,
        now: Long,
    ) = CredentialRequestEntity(
        requestId = 0,
        pairingRequestId = pairing.requestId,
        clientId = pairing.clientId,
        clientName = pairing.friendlyName ?: pairing.hostname ?: "Unknown client",
        vaultAddress = pairing.vaultAddress,
        hostname = pairing.hostname,
        platform = pairing.platform,
        architecture = pairing.architecture,
        machineId = pairing.machineId,
        osVersion = pairing.osVersion,
        state = CredentialRequestState.APPROVAL_PENDING.storedName,
        cliVersion = contents.cliVersion,
        profilesJson = encodeStringList(contents.profiles),
        profileDetailsJson = json.encodeToString(description.profiles),
        missingProfilesJson = encodeStringList(description.missingProfiles),
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

    private fun profileListRequestEntity(
        pairing: PairingEntity,
        contents: ProfileListRequestMessage,
        profileMetadata: List<CredentialProfileMetadata>,
        now: Long,
    ) = ProfileListRequestEntity(
        requestId = 0,
        pairingRequestId = pairing.requestId,
        clientId = pairing.clientId,
        vaultAddress = pairing.vaultAddress,
        hostname = pairing.hostname,
        platform = pairing.platform,
        architecture = pairing.architecture,
        machineId = pairing.machineId,
        osVersion = pairing.osVersion,
        state = ProfileListRequestState.WAITING_FOR_COMPLETION.storedName,
        cliVersion = contents.cliVersion,
        profilesJson = json.encodeToString(profileMetadata),
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
    ): ProcessedRelayMessage {
        val rejectedEarly = pairing.state == PairingState.REJECTED.storedName
        if (pairing.state != PairingState.RECEIVING.storedName && !rejectedEarly) {
            return ProcessedRelayMessage()
        }
        if (
            rejectedEarly &&
            dao.getPairingSecret(
                pairing.requestId,
                PairingSecretKind.CLIENT_PSK.storedName,
            ) != null
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
        established.fold(
            onSuccess = { result ->
                val choices = if (rejectedEarly) null else pairingProtocol.sasChoices(result.sas)
                val secret = encryptClientPsk(pairing, result.clientPsk, now)
                dao.recordInitialCompletion(
                    request = request.copy(
                        state = if (rejectedEarly) {
                            InboxRequestState.COMPLETED.storedName
                        } else {
                            InboxRequestState.ACTION_REQUIRED.storedName
                        },
                        completionJson = completion.toString(),
                        updatedAt = now,
                    ),
                    pairing = pairing.copy(
                        state = if (rejectedEarly) {
                            PairingState.REJECTED.storedName
                        } else {
                            PairingState.SAS_VERIFICATION_PENDING.storedName
                        },
                        sasOption0 = choices?.values?.get(0),
                        sasOption1 = choices?.values?.get(1),
                        sasOption2 = choices?.values?.get(2),
                        correctSasIndex = choices?.correctIndex,
                        cliVersion = result.clientMetadata.cliVersion,
                        platform = result.clientMetadata.platform,
                        architecture = result.clientMetadata.architecture,
                        hostname = result.clientMetadata.hostname,
                        friendlyName = pairing.friendlyName ?: result.clientMetadata.hostname,
                        machineId = result.clientMetadata.machineId,
                        osVersion = result.clientMetadata.osVersion,
                        updatedAt = now,
                    ),
                    secret = secret,
                )
            },
            onFailure = { failure ->
                dao.updatePairingRequest(
                    request = request.copy(
                        state = if (rejectedEarly) {
                            InboxRequestState.COMPLETED.storedName
                        } else {
                            InboxRequestState.ACTION_REQUIRED.storedName
                        },
                        completionJson = completion.toString(),
                        updatedAt = now,
                    ),
                    pairing = pairing.copy(
                        state = if (rejectedEarly) {
                            PairingState.REJECTED.storedName
                        } else {
                            PairingState.VERIFICATION_FAILED.storedName
                        },
                        error = failure.message ?: "The pairing message could not be verified.",
                        updatedAt = now,
                    ),
                )
            },
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processFinishRequest(
        credentials: RelayDeviceCredentials,
        relayRequestId: String,
        requestPayload: JsonElement,
        clientId: String,
    ): ProcessedRelayMessage? {
        val pairing = dao.getPairingByClientId(clientId) ?: return null
        if (pairing.vaultIdentityId != credentials.vaultIdentityId) return null
        val accepted = when (pairing.state) {
            PairingState.WAITING_FOR_FINISH.storedName -> true
            PairingState.REJECTED.storedName -> false
            else -> return null
        }
        val clientPsk = decryptClientPsk(pairing) ?: return null
        val prepared = runCatching {
            pairingProtocol.prepareFinishResponse(
                deviceId = pairing.deviceId,
                requestId = relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = requestPayload,
                accepted = accepted,
            )
        }.getOrNull() ?: return null
        val now = currentTimeMillis()
        dao.insertRequest(
            InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = pairing.requestId,
                kind = RequestKind.PAIRING_FINISH.storedName,
                state = if (accepted) {
                    InboxRequestState.WAITING.storedName
                } else {
                    InboxRequestState.COMPLETED.storedName
                },
                listed = false,
                requestJson = requestPayload.toString(),
                responseJson = prepared.response.toString(),
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = if (accepted) null else now,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
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
            RequestKind.CREDENTIAL.storedName -> {
                processCredentialCompletion(activeCredentials, request, completion)
            }
            RequestKind.PROFILE_LIST.storedName -> {
                processProfileListCompletion(activeCredentials, request, completion)
            }
            RequestKind.PROFILE_UPLOAD.storedName -> {
                processProfileUploadCompletion(activeCredentials, request, completion)
            }
            RequestKind.PAIRING_REMOVE.storedName -> {
                processPairingRemoveCompletion(activeCredentials, request, completion)
            }
            else -> null
        }
    }

    private suspend fun processProfileListCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val profileListRequest = dao.getProfileListRequest(request.id) ?: return null
        if (
            profileListRequest.state == ProfileListRequestState.COMPLETED.storedName ||
            profileListRequest.state == ProfileListRequestState.VERIFICATION_FAILED.storedName
        ) {
            return ProcessedRelayMessage()
        }
        val pairingRequestId = profileListRequest.pairingRequestId ?: return null
        val pairing = dao.getPairing(pairingRequestId) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptClientPsk(pairing) ?: return null
        val decoded = runCatching {
            val plaintext = pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
            profileListProtocol.decodeCompletion(plaintext)
        }
        val valid = decoded.getOrNull() == profileListRequest.cliVersion
        val now = currentTimeMillis()
        dao.updateProfileListRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                updatedAt = now,
                completedAt = now,
            ),
            profileListRequest = profileListRequest.copy(
                state = if (valid) {
                    ProfileListRequestState.COMPLETED.storedName
                } else {
                    ProfileListRequestState.VERIFICATION_FAILED.storedName
                },
                error = if (valid) {
                    null
                } else {
                    decoded.exceptionOrNull()?.message
                        ?: "Profile list completion did not match the request."
                },
                updatedAt = now,
                completedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = if (valid) AuditCategory.PROFILE_LIST else AuditCategory.VERIFICATION,
                title = if (valid) "Profile list delivered" else "Profile list confirmation failed",
                detail = "${json.decodeFromString<List<CredentialProfileMetadata>>(profileListRequest.profilesJson).size} profiles",
                outcome = if (valid) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                clientId = profileListRequest.clientId,
                relayRequestId = request.relayRequestId,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processProfileUploadCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val upload = dao.getProfileUploadRequest(request.id) ?: return null
        if (upload.transportCompletedAt != null) return ProcessedRelayMessage()
        val pairingRequestId = upload.pairingRequestId ?: return null
        val pairing = dao.getPairing(pairingRequestId) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptClientPsk(pairing) ?: return null
        val decoded = runCatching {
            val plaintext = pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
            profileUploadProtocol.decodeCompletion(plaintext)
        }
        val result = decoded.getOrNull()
        val valid = result?.cliVersion == upload.cliVersion &&
            result.result == upload.transportResult &&
            result.message == upload.transportMessage
        val now = currentTimeMillis()
        val proposalStillPending = upload.state == ProfileUploadRequestState.REVIEW_PENDING.storedName
        dao.updateProfileUploadRequest(
            request = request.copy(
                state = when {
                    proposalStillPending -> InboxRequestState.ACTION_REQUIRED.storedName
                    else -> InboxRequestState.COMPLETED.storedName
                },
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                updatedAt = now,
                completedAt = request.completedAt ?: if (proposalStillPending) null else now,
            ),
            profileUpload = upload.copy(
                error = if (valid) upload.error else decoded.exceptionOrNull()?.message
                    ?: "The Client completion did not match the received proposal.",
                updatedAt = now,
                transportCompletedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = if (valid) {
                    AuditCategory.PROFILE_PROPOSAL
                } else {
                    AuditCategory.VERIFICATION
                },
                title = if (valid) {
                    "Profile proposal receipt confirmed"
                } else {
                    "Profile proposal confirmation failed"
                },
                detail = upload.proposedName,
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
        if (request.completedAt != null) return ProcessedRelayMessage()
        val pairing = pairingForRequest(request) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptClientPsk(pairing) ?: return null
        val verified = runCatching {
            val plaintext = pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
            pairingRemoveProtocol.decodeCompletion(plaintext)
        }.isSuccess
        if (verified) {
            completePairingRemoval(request, pairing, completion)
        } else {
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
        if (request.completedAt != null) return
        val now = currentTimeMillis()
        dao.updateRequest(
            request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion?.toString() ?: request.completionJson,
                updatedAt = now,
                completedAt = now,
            ),
        )
        dao.updatePairing(
            pairing.copy(
                desiredRelayClientState = RelayClientState.REVOKED.wireName,
                updatedAt = now,
            ),
        )
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
        requestSync()
    }

    private suspend fun processCredentialCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val credentialRequest = dao.getCredentialRequest(request.id) ?: return null
        if (
            credentialRequest.state == CredentialRequestState.COMPLETED.storedName ||
            credentialRequest.state == CredentialRequestState.VERIFICATION_FAILED.storedName
        ) {
            return ProcessedRelayMessage()
        }
        val pairingRequestId = credentialRequest.pairingRequestId ?: return null
        val pairing = dao.getPairing(pairingRequestId) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptClientPsk(pairing) ?: return null
        val decoded = runCatching {
            val plaintext = pairingProtocol.openPairedCompletion(
                deviceId = pairing.deviceId,
                requestId = request.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
            credentialProtocol.decodeCompletion(plaintext)
        }
        val now = currentTimeMillis()
        val completionResult = decoded.getOrNull()
        val valid = when (completionResult) {
            is CredentialCompletion.Approved -> {
                credentialRequest.decision == CredentialDecision.APPROVED.storedName
            }
            is CredentialCompletion.Denied -> {
                credentialRequest.decision == CredentialDecision.DENIED.storedName &&
                    completionResult.reason == CredentialDenialReason.USER_DENIED.wireName &&
                    completionResult.message == CREDENTIAL_DENIAL_MESSAGE
            }
            is CredentialCompletion.Aborted -> true
            null -> false
        }
        dao.updateCredentialRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                updatedAt = now,
                completedAt = now,
            ),
            credentialRequest = credentialRequest.copy(
                state = if (valid) {
                    CredentialRequestState.COMPLETED.storedName
                } else {
                    CredentialRequestState.VERIFICATION_FAILED.storedName
                },
                completionResult = when (completionResult) {
                    is CredentialCompletion.Approved -> {
                        CredentialCompletionResult.APPROVED.storedName
                    }
                    is CredentialCompletion.Denied -> {
                        CredentialCompletionResult.DENIED.storedName
                    }
                    is CredentialCompletion.Aborted -> {
                        CredentialCompletionResult.ABORTED.storedName
                    }
                    null -> null
                },
                completionReason = when (completionResult) {
                    is CredentialCompletion.Denied -> completionResult.reason
                    is CredentialCompletion.Aborted -> completionResult.reason
                    else -> null
                },
                completionMessage = when (completionResult) {
                    is CredentialCompletion.Denied -> completionResult.message
                    is CredentialCompletion.Aborted -> completionResult.message
                    else -> null
                },
                error = if (valid) {
                    null
                } else {
                    decoded.exceptionOrNull()?.message
                        ?: "Credential completion did not match the phone decision."
                },
                updatedAt = now,
                completedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                category = if (valid) AuditCategory.PROFILE_ACCESS else AuditCategory.VERIFICATION,
                title = when {
                    !valid -> "Profile access confirmation failed"
                    completionResult is CredentialCompletion.Approved -> "Profile access delivered"
                    completionResult is CredentialCompletion.Denied -> "Profile access denial confirmed"
                    else -> "Profile access aborted"
                },
                detail = decodeStringList(credentialRequest.profilesJson).joinToString(),
                outcome = when {
                    !valid -> AuditOutcome.FAILED
                    completionResult is CredentialCompletion.Denied -> AuditOutcome.DENIED
                    else -> AuditOutcome.COMPLETED
                },
                clientId = credentialRequest.clientId,
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
        val rootRequest = dao.getRequestById(rootRequestId) ?: return null
        val pairing = dao.getPairing(rootRequestId) ?: return null
        if (pairing.state == PairingState.ACTIVE.storedName) {
            return ProcessedRelayMessage()
        }
        if (pairing.state != PairingState.WAITING_FOR_FINISH.storedName) {
            return ProcessedRelayMessage()
        }
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val clientPsk = decryptClientPsk(pairing) ?: return null
        val verified = runCatching {
            pairingProtocol.verifyFinishCompletion(
                deviceId = pairing.deviceId,
                requestId = finishRequest.relayRequestId,
                clientId = pairing.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(finishRequest.requestJson),
                completion = completion,
            )
        }
        val now = currentTimeMillis()
        if (verified.isSuccess) {
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
                finishRequest = finishRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    completionJson = completion.toString(),
                    updatedAt = now,
                    completedAt = now,
                ),
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
        } else {
            dao.updatePairingRequest(
                request = rootRequest.copy(
                    state = InboxRequestState.ACTION_REQUIRED.storedName,
                    updatedAt = now,
                ),
                pairing = pairing.copy(
                    state = PairingState.VERIFICATION_FAILED.storedName,
                    error = verified.exceptionOrNull()?.message
                        ?: "The pairing confirmation could not be verified.",
                    updatedAt = now,
                ),
            )
            dao.updateRequest(
                finishRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    completionJson = completion.toString(),
                    updatedAt = now,
                    completedAt = now,
                ),
            )
            audit.record(
                AuditRecord(
                    category = AuditCategory.VERIFICATION,
                    title = "Pairing confirmation failed",
                    outcome = AuditOutcome.FAILED,
                    clientId = pairing.clientId,
                    relayRequestId = rootRequest.relayRequestId,
                ),
            )
        }
        return ProcessedRelayMessage()
    }

    private suspend fun credentialsFor(
        pairing: PairingEntity,
        active: RelayDeviceCredentials,
    ): RelayDeviceCredentials? {
        val vaultIdentityId = pairing.vaultIdentityId ?: return null
        if (vaultIdentityId == active.vaultIdentityId) return active
        return when (val result = deviceCredentials.deviceCredentials(vaultIdentityId)) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            else -> null
        }
    }

    private suspend fun credentialsForPairing(
        pairing: PairingEntity,
    ): RelayDeviceCredentials? {
        val vaultIdentityId = pairing.vaultIdentityId ?: return null
        return when (val result = deviceCredentials.deviceCredentials(vaultIdentityId)) {
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
            RequestKind.CREDENTIAL.storedName -> {
                dao.getCredentialRequest(request.id)?.pairingRequestId?.let { dao.getPairing(it) }
            }
            RequestKind.PROFILE_LIST.storedName -> {
                dao.getProfileListRequest(request.id)?.pairingRequestId?.let { dao.getPairing(it) }
            }
            RequestKind.PROFILE_UPLOAD.storedName -> {
                dao.getProfileUploadRequest(request.id)?.pairingRequestId?.let { dao.getPairing(it) }
            }
            RequestKind.PAIRING_REMOVE.storedName -> {
                request.parentRequestId?.let { dao.getPairing(it) }
            }
            else -> null
        }

    private suspend fun encryptProfileUploadVariable(
        relayRequestId: String,
        clientId: String,
        name: String,
        value: String,
        now: Long,
    ): ProfileUploadVariableEntity {
        val id = newId()
        val key = keyManager.activeKey()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = profileUploadVariableLocation(id, relayRequestId, clientId, name),
                plaintext = value.encodeToByteArray(),
            )
        }
        return ProfileUploadVariableEntity(
            id = id,
            requestId = 0,
            name = name,
            encryptionFormat = encrypted.formatVersion,
            encryptionKeyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            createdAt = now,
        )
    }

    private fun profileUploadVariableLocation(
        id: String,
        relayRequestId: String,
        clientId: String,
        name: String,
    ) = EncryptionLocation(
        recordType = "profile_upload_variable",
        recordId = id,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("relay_request_id", relayRequestId),
            EncryptionBinding("client_id", clientId),
            EncryptionBinding("name", name),
        ),
    )

    private suspend fun encryptClientPsk(
        pairing: PairingEntity,
        clientPsk: ByteArray,
        now: Long,
    ): PairingSecretEntity {
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val id = newId()
        val key = keyManager.activeKey()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = pairingSecretLocation(id, pairing),
                plaintext = clientPsk,
            )
        }
        return PairingSecretEntity(
            id = id,
            pairingRequestId = pairing.requestId,
            kind = PairingSecretKind.CLIENT_PSK.storedName,
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
        val key = keyManager.activeKey()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = pairingSecretLocation(existing.id, pairing),
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

    private suspend fun decryptClientPsk(pairing: PairingEntity): ByteArray? {
        val secret = dao.getPairingSecret(
            pairing.requestId,
            PairingSecretKind.CLIENT_PSK.storedName,
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
                location = pairingSecretLocation(secret.id, pairing),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == CLIENT_PSK_BYTES
        }
    }

    private fun pairingSecretLocation(
        secretId: String,
        pairing: PairingEntity,
    ) = EncryptionLocation(
        recordType = "pairing_secret",
        recordId = secretId,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("kind", PairingSecretKind.CLIENT_PSK.storedName),
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

    private fun String.toCredentialRequestState(): CredentialRequestState =
        checkNotNull(CredentialRequestState.entries.find { it.storedName == this })

    private fun String.toCredentialDecision(): CredentialDecision =
        checkNotNull(CredentialDecision.entries.find { it.storedName == this })

    private fun String.toCredentialCompletionResult(): CredentialCompletionResult =
        checkNotNull(CredentialCompletionResult.entries.find { it.storedName == this })

    private fun String.toProfileListRequestState(): ProfileListRequestState =
        checkNotNull(ProfileListRequestState.entries.find { it.storedName == this })

    private fun String.toProfileUploadRequestState(): ProfileUploadRequestState =
        checkNotNull(ProfileUploadRequestState.entries.find { it.storedName == this })

    private fun encodeStringList(values: List<String>): String =
        json.encodeToString(STRING_LIST_SERIALIZER, values)

    private fun decodeStringList(value: String): List<String> =
        json.decodeFromString(STRING_LIST_SERIALIZER, value)

    private companion object {
        const val CLIENT_PSK_BYTES = 32
        const val CREDENTIAL_DENIAL_MESSAGE = "Denied on phone."
        val STRING_LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}

private enum class RequestKind(val storedName: String) {
    PAIRING("pairing"),
    PAIRING_FINISH("pairing_finish"),
    CREDENTIAL("credential"),
    PROFILE_LIST("profile_list"),
    PROFILE_UPLOAD("profile_upload"),
    PAIRING_REMOVE("pairing_remove"),
}

private enum class PairingSecretKind(val storedName: String) {
    CLIENT_PSK("client_psk"),
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
    PairingState.VERIFICATION_FAILED,
)
