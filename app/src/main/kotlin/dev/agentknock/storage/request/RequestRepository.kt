package dev.agentknock.storage.request

import dev.agentknock.protocol.InvocationCompletion
import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.protocol.InvocationProtocol
import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.protocol.GitSignCompletion
import dev.agentknock.protocol.GitSignRepository
import dev.agentknock.protocol.GitSignProtocol
import dev.agentknock.protocol.GitSignRequestMessage
import dev.agentknock.protocol.OpenedPairedRequest
import dev.agentknock.protocol.PairedRequestErrorCode
import dev.agentknock.protocol.PairedRequestKeySource
import dev.agentknock.protocol.PairedRequestProtocol
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.protocol.PairingRemoveProtocol
import dev.agentknock.protocol.SecretListSecret
import dev.agentknock.protocol.SecretListProtocol
import dev.agentknock.protocol.SecretListRequestMessage
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.protocol.SecretUploadProtocol
import dev.agentknock.protocol.SecretUploadContents
import dev.agentknock.protocol.SecretUploadRequestMessage
import dev.agentknock.protocol.SshAuthenticationCompletion
import dev.agentknock.protocol.SshAuthenticationMessageDetails
import dev.agentknock.protocol.SshAuthenticationMethod
import dev.agentknock.protocol.SshAuthenticationProtocol
import dev.agentknock.protocol.SshAuthenticationRequestMessage
import dev.agentknock.protocol.SshSignatureAlgorithm
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.presentation.renderSingleLineText
import dev.agentknock.presentation.describeGitSigningContent
import dev.agentknock.review.approvalReviewRequest
import dev.agentknock.review.approvalReviewGitSignRequest
import dev.agentknock.review.approvalReviewSshAuthenticationRequest
import dev.agentknock.review.approvalReviewSecretFacts
import dev.agentknock.relay.ApprovalReviewRequest
import dev.agentknock.relay.ApprovalReviewEnvironmentSecretFacts
import dev.agentknock.relay.ApprovalReviewEnvironmentDestination
import dev.agentknock.relay.ApprovalReviewEnvironmentVariableFacts
import dev.agentknock.relay.ApprovalReviewStandardInputDestination
import dev.agentknock.relay.ApprovalReviewSecretFacts
import dev.agentknock.relay.ApprovalReviewSshSecretFacts
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
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.protocol.InvocationResponseSecret
import dev.agentknock.storage.secret.RequestedSecretsResult
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SecretValues
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.ApplyEnvironmentSecretUploadResult
import dev.agentknock.storage.secret.ApplySshSecretUploadResult
import dev.agentknock.storage.secret.EnvironmentSecretUpload
import dev.agentknock.storage.secret.EnvironmentSecretUploadResult
import dev.agentknock.storage.secret.SshKeyCodec
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.secret.SshSecretUpload
import dev.agentknock.storage.secret.SshSecretUploadResult
import dev.agentknock.storage.secret.GitSignatureResult
import dev.agentknock.storage.secret.SshAuthenticationSignatureResult
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.privateKeyFormat
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.EnvironmentVariableSelection
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
import dev.agentknock.storage.approval.RequestedSecretApproval
import dev.agentknock.storage.approval.requiresAiReview
import dev.agentknock.storage.approval.isFullyApproved
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.NoOpAuditSink
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.device.RelayDeviceCredentialsResult
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable

@Serializable
internal data class SecretUploadSummarySnapshot(
    val variableNames: List<String> = emptyList(),
    val addedVariables: List<String> = emptyList(),
    val changedVariables: List<String> = emptyList(),
    val unchangedVariables: List<String> = emptyList(),
    val removedVariables: List<String> = emptyList(),
    val publicKey: String? = null,
    val fingerprint: String? = null,
    val previousPublicKey: String? = null,
    val previousFingerprint: String? = null,
    val keyChanged: Boolean = false,
)

private data class PreparedSecretUpload(
    val type: String,
    val summary: SecretUploadSummarySnapshot,
    val environmentVariables: List<SecretUploadEnvironmentVariableEntity> = emptyList(),
    val sshKey: SecretUploadSshKeyEntity? = null,
    val error: String? = null,
)

internal enum class InboxRequestState(val storedName: String) {
    REVIEWING("reviewing"),
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
    COMPLETED("completed"),
}

internal enum class InboxRequestKind {
    PAIRING,
    SECRET_USE,
    GIT_SIGN,
    SSH_AUTHENTICATE,
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

internal enum class InvocationCompletionResult(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
    ABORTED("aborted"),
}

internal enum class GitSignRequestState(val storedName: String) {
    APPROVAL_PENDING("approval_pending"),
    WAITING_FOR_COMPLETION("waiting_for_completion"),
    COMPLETED("completed"),
    VERIFICATION_FAILED("verification_failed"),
}

internal enum class GitSignCompletionResult(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
    ABORTED("aborted"),
}

internal enum class SshAuthenticationRequestState(val storedName: String) {
    APPROVAL_PENDING("approval_pending"),
    WAITING_FOR_COMPLETION("waiting_for_completion"),
    COMPLETED("completed"),
    VERIFICATION_FAILED("verification_failed"),
}

internal enum class SshAuthenticationCompletionResult(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
    ABORTED("aborted"),
}

internal enum class SecretUploadRequestState(val storedName: String) {
    REVIEW_PENDING("review_pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    VERIFICATION_FAILED("verification_failed"),
}

internal data class SecretUploadLifecycle(
    val state: InboxRequestState,
    val completed: Boolean,
)

internal fun secretUploadLifecycle(
    decision: String?,
    transportFinished: Boolean,
): SecretUploadLifecycle = when {
    decision == null -> SecretUploadLifecycle(InboxRequestState.ACTION_REQUIRED, false)
    !transportFinished -> SecretUploadLifecycle(InboxRequestState.WAITING, false)
    else -> SecretUploadLifecycle(InboxRequestState.COMPLETED, true)
}

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

internal fun secretUseRequestState(
    state: InboxRequestState,
    error: String?,
): SecretUseRequestState = when (state) {
    InboxRequestState.REVIEWING,
    InboxRequestState.ACTION_REQUIRED,
    -> SecretUseRequestState.APPROVAL_PENDING
    InboxRequestState.WAITING -> SecretUseRequestState.WAITING_FOR_COMPLETION
    InboxRequestState.COMPLETED -> if (error == null) {
        SecretUseRequestState.COMPLETED
    } else {
        SecretUseRequestState.VERIFICATION_FAILED
    }
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

private data class AcceptedRequestPsks(
    val requestPsk: RequestPskEntity,
    val currentClientPsk: ClientPskEntity?,
    val previousClientPsk: ClientPskEntity?,
)

private fun AcceptedRequestPsks.withoutRotationUnless(allowed: Boolean): AcceptedRequestPsks =
    if (allowed) this else copy(currentClientPsk = null, previousClientPsk = null)

internal fun pairingAdmissionAllowed(existingStates: Iterable<PairingState>): Boolean =
    existingStates.none { it in INCOMPLETE_PAIRING_STATES }

internal fun previousPskEligible(updatedAt: Long, now: Long): Boolean =
    now >= updatedAt && now - updatedAt <= PREVIOUS_PSK_OVERLAP_MILLIS

internal data class InboxRequestSummary(
    val id: String,
    val kind: InboxRequestKind,
    val state: InboxRequestState,
    val pairingState: PairingState?,
    val secretUseState: SecretUseRequestState?,
    val secretUseDecision: SecretUseDecision?,
    val secretUseResult: InvocationCompletionResult?,
    val secretUseCompletionReason: String?,
    val secretUploadState: SecretUploadRequestState?,
    val title: String,
    val clientName: String,
    val secretNames: List<String>,
    val listSummary: String?,
    val command: String?,
    val arguments: List<String>,
    val receivedAt: Long,
    val completedAt: Long?,
    val gitSignState: GitSignRequestState? = null,
    val gitSignDecision: SecretUseDecision? = null,
    val gitSignResult: GitSignCompletionResult? = null,
    val gitSignCompletionReason: String? = null,
    val sshAuthenticationState: SshAuthenticationRequestState? = null,
    val sshAuthenticationDecision: SecretUseDecision? = null,
    val sshAuthenticationResult: SshAuthenticationCompletionResult? = null,
    val sshAuthenticationCompletionReason: String? = null,
    val userDecisionAvailable: Boolean = true,
)

internal data class PairingRequestDetails(
    val pairingState: PairingState,
    val clientName: String,
    val pairingAddress: String,
    val clientId: String,
    val sasOptions: List<String>,
    val clientSoftware: ClientSoftware?,
    val platform: String?,
    val architecture: String?,
    val hostname: String?,
    val machineId: String?,
    val osVersion: String?,
    val error: String?,
    val decidedAt: Long?,
)

internal data class InboxRequestDetails(
    val id: String,
    val parentRequestId: String?,
    val state: InboxRequestState,
    val clientSoftware: ClientSoftware?,
    val error: String?,
    val receivedAt: Long,
    val completedAt: Long?,
    val pairing: PairingRequestDetails?,
    val secretUse: SecretUseRequestDetails?,
    val secretUpload: SecretUploadRequestDetails?,
    val gitSign: GitSignRequestDetails? = null,
    val sshAuthentication: SshAuthenticationRequestDetails? = null,
    val userDecisionAvailable: Boolean = true,
)

internal data class SshAuthenticationRequestDetails(
    val state: SshAuthenticationRequestState,
    val decision: SecretUseDecision?,
    val completionResult: SshAuthenticationCompletionResult?,
    val completionReason: String?,
    val completionMessage: String?,
    val secretName: String,
    val username: String,
    val method: SshAuthenticationMethod,
    val algorithm: SshSignatureAlgorithm,
    val hostKeyAlgorithm: String?,
    val hostKeyFingerprint: String?,
    val approvalEvaluation: ApprovalEvaluation?,
    val invocationRequestId: String,
    val command: String,
    val arguments: List<String>,
    val reason: String?,
    val clientId: String,
    val clientName: String,
    val clientSoftware: ClientSoftware?,
    val error: String?,
    val decidedAt: Long?,
)

internal data class GitSignRequestDetails(
    val state: GitSignRequestState,
    val decision: SecretUseDecision?,
    val completionResult: GitSignCompletionResult?,
    val completionReason: String?,
    val completionMessage: String?,
    val secretName: String,
    val message: ByteArray,
    val repository: GitSignRepository?,
    val approvalEvaluation: ApprovalEvaluation?,
    val invocationRequestId: String,
    val command: String,
    val arguments: List<String>,
    val reason: String?,
    val clientId: String,
    val clientName: String,
    val clientSoftware: ClientSoftware?,
    val error: String?,
    val decidedAt: Long?,
)

internal data class SecretUseRequestDetails(
    val state: SecretUseRequestState,
    val decision: SecretUseDecision?,
    val decisionSource: String?,
    val approvalEvaluation: ApprovalEvaluation?,
    val completionResult: InvocationCompletionResult?,
    val completionReason: String?,
    val completionMessage: String?,
    val secrets: List<String>,
    val secretDetails: List<SecretMetadata>,
    val environmentVariables: Map<String, Map<String, String?>>,
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
    val clientId: String,
    val clientName: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    val machineId: String?,
    val osVersion: String?,
    val clientSoftware: ClientSoftware?,
    val error: String?,
    val decidedAt: Long?,
)

private fun InvocationRequestMessage.environmentSelections(): Map<String, EnvironmentVariableSelection> =
    secretDelivery.mapNotNull { (secret, delivery) ->
        delivery.environment?.let { environment ->
            secret to EnvironmentVariableSelection(
                only = environment.only,
                omit = environment.omit,
                rename = environment.rename,
                stdin = environment.stdin,
            )
        }
    }.toMap()

private fun List<SecretMetadata>.environmentSelections(): Map<String, EnvironmentVariableSelection> =
    filter { it.type == ENVIRONMENT_SECRET_TYPE }.associate { secret ->
        secret.name to EnvironmentVariableSelection(
            only = secret.environmentVariableNames.toSet(),
            rename = secret.environmentVariableRename,
            stdin = secret.environmentVariableStdin,
        )
    }

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
    val publicKey: String?,
    val fingerprint: String?,
    val previousPublicKey: String?,
    val previousFingerprint: String?,
    val keyChanged: Boolean,
    val clientName: String,
    val clientId: String,
    val clientSoftware: ClientSoftware?,
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
    val architecture: String?,
    val state: RelayClientState,
    val desiredState: RelayClientState?,
    val pairedAt: Long?,
    val lastRequestAt: Long? = null,
    val temporaryAccessCount: Int = 0,
)

internal data class ClientDetails(
    val clientId: String,
    val name: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    val osVersion: String?,
    val machineId: String?,
    val clientSoftware: ClientSoftware?,
    val instructions: String,
    val state: RelayClientState,
    val desiredState: RelayClientState?,
    val pairedAt: Long?,
    val lastRequestAt: Long? = null,
)

internal data class RequestNotification(
    val requestId: String,
    val title: String,
    val summary: String,
    val details: List<RequestNotificationDetail>,
    val decisionAvailable: Boolean,
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

    data class Invalid(val message: String) : SecretUseDecisionResult

    data object SecretUnavailable : SecretUseDecisionResult

    data object SecretCorrupted : SecretUseDecisionResult

    data object UnsupportedEncryption : SecretUseDecisionResult

    data object PairingUnavailable : SecretUseDecisionResult

    data object TemporaryAccessUnavailable : SecretUseDecisionResult

    data object TemporaryAccessNotStarted : SecretUseDecisionResult
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

internal enum class ClientChangeResult {
    CHANGED,
    NOT_FOUND,
    INVALID_STATE,
}

internal class RequestRepository(
    private val dao: RequestDao,
    private val deviceCredentials: RelayDeviceCredentialSource,
    private val secrets: SecretRepository,
    private val approvalReviewer: RelayApprovalReviewClient? = null,
    private val relay: RelayDeviceClient,
    private val keyManager: VaultKeyManager,
    private val encryption: AesGcmEncryption,
    private val audit: AuditSink = NoOpAuditSink,
    private val sshKeys: SshKeyCodec = SshKeyCodec(),
    private val requestPushRegistration: () -> Unit = {},
    private val pairingProtocol: PairingProtocol = PairingProtocol(),
    private val pairedRequestProtocol: PairedRequestProtocol = PairedRequestProtocol(),
    private val invocationProtocol: InvocationProtocol = InvocationProtocol(),
    private val gitSignProtocol: GitSignProtocol = GitSignProtocol(),
    private val sshAuthenticationProtocol: SshAuthenticationProtocol = SshAuthenticationProtocol(),
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
    private val aiReviewInFlight = MutableStateFlow<Set<String>>(emptySet())

    val pushRegistrationState: StateFlow<RelayPushRegistrationState?> =
        _pushRegistrationState.asStateFlow()

    fun observeRequests(): Flow<List<InboxRequestSummary>> {
        val visibleRequests = combine(
            dao.observeListedRequests(),
            dao.observePendingPairingRequests(),
            dao.observePendingSecretUploadRequests(),
        ) { history, pairings, uploads ->
            (history + pairings + uploads).sortedWith(
                compareByDescending<InboxRequestEntity> { it.receivedAt }
                    .thenByDescending { it.id },
            )
        }
        val signatureRequests = combine(
            dao.observeGitSignRequests(),
            dao.observeSshAuthenticationRequests(),
        ) { gitSign, sshAuthentication -> gitSign to sshAuthentication }
        return combine(
        visibleRequests,
        dao.observePairingAttempts(),
        dao.observeSecretUseRequests(),
        signatureRequests,
        dao.observeSecretUploadRequests(),
    ) { requests, pairingAttempts, secretUseRequests, signatures, secretUploadRequests ->
        val (gitSignRequests, sshAuthenticationRequests) = signatures
        val pairingByRequest = pairingAttempts.associateBy(PairingAttemptEntity::requestId)
        val secretUseByRequest = secretUseRequests.associateBy(SecretUseRequestEntity::requestId)
        val gitSignByRequest = gitSignRequests.associateBy(GitSignRequestEntity::requestId)
        val sshAuthenticationByRequest = sshAuthenticationRequests.associateBy(
            SshAuthenticationRequestEntity::requestId,
        )
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
                        secretUseState = request.toSecretUseRequestState(),
                        secretUseDecision = secretUse.decision?.toSecretUseDecision(),
                        secretUseResult = secretUse.completionResult
                            ?.toInvocationCompletionResult(),
                        secretUseCompletionReason = secretUse.completionReason,
                        secretUploadState = null,
                        title = "Secret use",
                        clientName = request.clientNameSnapshot,
                        secretNames = requestedSecrets,
                        listSummary = null,
                        command = secretUse.command,
                        arguments = decodeStringList(secretUse.argumentsJson),
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                    )
                }
                RequestKind.GIT_SIGN.storedName -> {
                    val gitSign = gitSignByRequest[request.id] ?: return@mapNotNull null
                    val invocation = request.parentRequestId?.let(secretUseByRequest::get)
                        ?: return@mapNotNull null
                    val signingContent = describeGitSigningContent(gitSign.message)
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.GIT_SIGN,
                        state = request.state.toInboxRequestState(),
                        pairingState = null,
                        secretUseState = null,
                        secretUseDecision = null,
                        secretUseResult = null,
                        secretUseCompletionReason = null,
                        secretUploadState = null,
                        title = signingContent.requestTitle,
                        clientName = request.clientNameSnapshot,
                        secretNames = listOf(gitSign.secretName),
                        listSummary = signingContent.message,
                        command = invocation.command,
                        arguments = decodeStringList(invocation.argumentsJson),
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                        gitSignState = request.toGitSignRequestState(),
                        gitSignDecision = gitSign.decision?.toSecretUseDecision(),
                        gitSignResult = gitSign.completionResult?.toGitSignCompletionResult(),
                        gitSignCompletionReason = gitSign.completionReason,
                    )
                }
                RequestKind.SSH_AUTHENTICATE.storedName -> {
                    val authentication = sshAuthenticationByRequest[request.id]
                        ?: return@mapNotNull null
                    val invocation = request.parentRequestId?.let(secretUseByRequest::get)
                        ?: return@mapNotNull null
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.SSH_AUTHENTICATE,
                        state = request.state.toInboxRequestState(),
                        pairingState = null,
                        secretUseState = null,
                        secretUseDecision = null,
                        secretUseResult = null,
                        secretUseCompletionReason = null,
                        secretUploadState = null,
                        title = "SSH authentication",
                        clientName = request.clientNameSnapshot,
                        secretNames = listOf(authentication.secretName),
                        listSummary = "${authentication.username} · ${authentication.algorithm}",
                        command = invocation.command,
                        arguments = decodeStringList(invocation.argumentsJson),
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                        sshAuthenticationState = request.toSshAuthenticationRequestState(),
                        sshAuthenticationDecision = authentication.decision
                            ?.toSecretUseDecision(),
                        sshAuthenticationResult = authentication.completionResult
                            ?.toSshAuthenticationCompletionResult(),
                        sshAuthenticationCompletionReason = authentication.completionReason,
                    )
                }
                RequestKind.SECRET_UPLOAD.storedName -> {
                    val upload = secretUploadByRequest[request.id] ?: return@mapNotNull null
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.SECRET_UPLOAD,
                        state = request.state.toInboxRequestState(),
                        pairingState = null,
                        secretUseState = null,
                        secretUseDecision = null,
                        secretUseResult = null,
                        secretUseCompletionReason = null,
                        secretUploadState = request.toSecretUploadRequestState(upload.decision),
                        title = when (
                            SecretUploadMode.entries.single { it.wireName == upload.mode }
                        ) {
                            SecretUploadMode.CREATE -> "Create"
                            SecretUploadMode.REPLACE -> "Replace"
                            SecretUploadMode.UPDATE -> "Update"
                        },
                        clientName = request.clientNameSnapshot,
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
    }.combine(aiReviewInFlight) { requests, inFlight ->
        requests.map { request ->
            request.copy(
                userDecisionAvailable = request.state == InboxRequestState.ACTION_REQUIRED &&
                    request.id !in inFlight,
            )
        }
    }
    }

    private fun SecretUploadRequestEntity.listSummary(): String {
        val summary = decodeUploadSummary(summaryJson)
        if (secretType == SSH_SECRET_TYPE) {
            return summary.fingerprint ?: "SSH key"
        }
        if (mode == SecretUploadMode.UPDATE.wireName) {
            return buildList {
                summary.addedVariables.size.takeIf { it > 0 }?.let { add("$it added") }
                summary.changedVariables.size.takeIf { it > 0 }?.let { add("$it updated") }
                summary.removedVariables.size.takeIf { it > 0 }?.let { add("$it removed") }
            }.ifEmpty {
                listOf("No environment variable changes")
            }.joinToString(" · ")
        }
        val count = summary.variableNames.size
        return "$count ${if (count == 1) "environment variable" else "environment variables"}"
    }

    fun observeRequest(id: String): Flow<InboxRequestDetails?> = combine(
        dao.observeRequest(id),
        dao.observePairingAttempt(id),
        dao.observeSecretUseRequest(id),
        dao.observeSecretUploadRequest(id),
    ) { request, pairing, secretUse, secretUpload ->
        if (request == null) return@combine null
        InboxRequestDetails(
            id = request.id,
            parentRequestId = request.parentRequestId,
            state = request.state.toInboxRequestState(),
            clientSoftware = request.clientSoftwareJson?.let(::decodeClientSoftware),
            error = request.error,
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
                    clientSoftware = request.clientSoftwareJson?.let(::decodeClientSoftware),
                    platform = it.platform,
                    architecture = it.architecture,
                    hostname = it.hostname,
                    machineId = it.machineId,
                    osVersion = it.osVersion,
                    error = request.error,
                    decidedAt = it.decidedAt,
                )
            },
            secretUse = secretUse?.let {
                SecretUseRequestDetails(
                    state = request.toSecretUseRequestState(),
                    decision = it.decision?.toSecretUseDecision(),
                    decisionSource = it.decisionSource,
                    approvalEvaluation = it.approvalEvaluationJson?.let(::decodeApprovalEvaluation),
                    completionResult = it.completionResult?.toInvocationCompletionResult(),
                    completionReason = it.completionReason,
                    completionMessage = it.completionMessage,
                    secrets = decodeStringList(it.secretsJson),
                    secretDetails = json.decodeFromString(it.secretDetailsJson),
                    environmentVariables = decodeEnvironmentReviewFacts(it.providedSecretsJson),
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
                    clientId = request.clientId,
                    clientName = request.clientNameSnapshot,
                    hostname = it.hostname,
                    platform = it.platform,
                    architecture = it.architecture,
                    machineId = it.machineId,
                    osVersion = it.osVersion,
                    clientSoftware = request.clientSoftwareJson?.let(::decodeClientSoftware),
                    error = request.error,
                    decidedAt = it.decidedAt,
                )
            },
            secretUpload = secretUpload?.let {
                val summary = decodeUploadSummary(it.summaryJson)
                SecretUploadRequestDetails(
                    state = request.toSecretUploadRequestState(it.decision),
                    mode = SecretUploadMode.entries.single { mode -> mode.wireName == it.mode },
                    uploadedName = it.uploadedName,
                    approvedName = it.approvedName,
                    descriptionProvided = it.descriptionProvided,
                    description = it.description,
                    secretType = it.secretType,
                    variableNames = summary.variableNames,
                    variables = emptyList(),
                    addedVariables = summary.addedVariables,
                    changedVariables = summary.changedVariables,
                    unchangedVariables = summary.unchangedVariables,
                    removedVariables = summary.removedVariables,
                    publicKey = summary.publicKey,
                    fingerprint = summary.fingerprint,
                    previousPublicKey = summary.previousPublicKey,
                    previousFingerprint = summary.previousFingerprint,
                    keyChanged = summary.keyChanged,
                    clientName = request.clientNameSnapshot,
                    clientId = request.clientId,
                    clientSoftware = request.clientSoftwareJson?.let(::decodeClientSoftware),
                    error = request.error ?: it.intakeError,
                    decidedAt = it.decidedAt,
                )
            },
        )
    }.combine(dao.observeSecretUploadEnvironmentVariables(id)) { details, variables ->
        details?.copy(
            secretUpload = details.secretUpload?.copy(
                variables = variables.map {
                    SecretUploadVariableDetails(it.id, it.name, it.sensitive)
                },
            ),
        )
    }.combine(dao.observeSecretUploadSshKey(id)) { details, _ -> details }
        .combine(dao.observeGitSignRequest(id)) { details, gitSign ->
            if (details == null || gitSign == null) return@combine details
            val parentId = details.parentRequestId ?: return@combine details
            val invocation = dao.getSecretUseRequest(parentId) ?: return@combine details
            val invocationRequest = dao.getRequestById(parentId) ?: return@combine details
            details.copy(
                gitSign = GitSignRequestDetails(
                    state = details.state.toGitSignRequestState(details.error),
                    decision = gitSign.decision?.toSecretUseDecision(),
                    completionResult = gitSign.completionResult?.toGitSignCompletionResult(),
                    completionReason = gitSign.completionReason,
                    completionMessage = gitSign.completionMessage,
                    secretName = gitSign.secretName,
                    message = gitSign.message,
                    repository = gitSign.repositoryJson?.let {
                        runCatching { json.decodeFromString<GitSignRepository>(it) }.getOrNull()
                    },
                    approvalEvaluation = gitSign.approvalEvaluationJson?.let(::decodeApprovalEvaluation),
                    invocationRequestId = invocationRequest.id,
                    command = invocation.command,
                    arguments = decodeStringList(invocation.argumentsJson),
                    reason = invocation.reason,
                    clientId = invocationRequest.clientId,
                    clientName = invocationRequest.clientNameSnapshot,
                    clientSoftware = details.clientSoftware,
                    error = details.error,
                    decidedAt = gitSign.decidedAt,
                ),
            )
        }
        .combine(dao.observeSshAuthenticationRequest(id)) { details, authentication ->
            if (details == null || authentication == null) return@combine details
            val parentId = details.parentRequestId ?: return@combine details
            val invocation = dao.getSecretUseRequest(parentId) ?: return@combine details
            val invocationRequest = dao.getRequestById(parentId) ?: return@combine details
            details.copy(
                sshAuthentication = SshAuthenticationRequestDetails(
                    state = details.state.toSshAuthenticationRequestState(details.error),
                    decision = authentication.decision?.toSecretUseDecision(),
                    completionResult = authentication.completionResult
                        ?.toSshAuthenticationCompletionResult(),
                    completionReason = authentication.completionReason,
                    completionMessage = authentication.completionMessage,
                    secretName = authentication.secretName,
                    username = authentication.username,
                    method = SshAuthenticationMethod.entries.single {
                        it.wireName == authentication.method
                    },
                    algorithm = SshSignatureAlgorithm.entries.single {
                        it.wireName == authentication.algorithm
                    },
                    hostKeyAlgorithm = authentication.hostKeyAlgorithm,
                    hostKeyFingerprint = authentication.hostKeyFingerprint,
                    approvalEvaluation = authentication.approvalEvaluationJson
                        ?.let(::decodeApprovalEvaluation),
                    invocationRequestId = invocationRequest.id,
                    command = invocation.command,
                    arguments = decodeStringList(invocation.argumentsJson),
                    reason = invocation.reason,
                    clientId = invocationRequest.clientId,
                    clientName = invocationRequest.clientNameSnapshot,
                    clientSoftware = details.clientSoftware,
                    error = details.error,
                    decidedAt = authentication.decidedAt,
                ),
            )
        }
        .combine(aiReviewInFlight) { details, inFlight ->
            details?.copy(
                userDecisionAvailable = (
                    details.pairing != null ||
                        details.state == InboxRequestState.ACTION_REQUIRED
                    ) && details.id !in inFlight,
            )
        }

    suspend fun getRequestDetails(id: String): InboxRequestDetails? = observeRequest(id).first()

    fun observeClients(): Flow<List<ClientSummary>> = combine(
        dao.observeClients(),
        secrets.observeTemporaryAccessGrants(),
    ) { clients, grants ->
        val grantCounts = grants.groupingBy { it.clientId }.eachCount()
        clients
            .mapNotNull { client ->
                val state = client.relayClientState.toRelayClientState()
                val desiredState = client.desiredRelayClientState?.toRelayClientState()
                if (state == RelayClientState.REVOKED || desiredState == RelayClientState.REVOKED) {
                    return@mapNotNull null
                }
                ClientSummary(
                    clientId = client.clientId,
                    name = client.name,
                    hostname = client.hostname,
                    platform = client.platform,
                    architecture = client.architecture,
                    state = state,
                    desiredState = desiredState,
                    pairedAt = client.pairedAt,
                    lastRequestAt = client.lastSeenAt,
                    temporaryAccessCount = grantCounts[client.clientId] ?: 0,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun observeClient(clientId: String): Flow<ClientDetails?> =
        dao.observeClient(clientId).map { client ->
            client?.takeIf {
                it.relayClientState != RelayClientState.REVOKED.wireName &&
                    it.desiredRelayClientState != RelayClientState.REVOKED.wireName
            }?.let {
                ClientDetails(
                    clientId = it.clientId,
                    name = it.name,
                    hostname = it.hostname,
                    platform = it.platform,
                    architecture = it.architecture,
                    osVersion = it.osVersion,
                    machineId = it.machineId,
                    clientSoftware = it.clientSoftwareJson?.let(::decodeClientSoftware),
                    instructions = it.instructions,
                    state = it.relayClientState.toRelayClientState(),
                    desiredState = it.desiredRelayClientState?.toRelayClientState(),
                    pairedAt = it.pairedAt,
                    lastRequestAt = it.lastSeenAt,
                )
            }
        }

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
                    if (secretUse.decision != null) {
                        return@mapNotNull null
                    }
                    val secrets = decodeStringList(secretUse.secretsJson)
                    val arguments = decodeStringList(secretUse.argumentsJson)
                    val command = renderShellCommand(secretUse.command, arguments)
                    val clientName = renderSingleLineText(request.clientNameSnapshot)
                    val secretNames = secrets.joinToString(transform = ::renderSingleLineText)
                    RequestNotification(
                        requestId = request.id,
                        title = "Secret use requested",
                        summary = "$clientName requests $secretNames",
                        details = listOfNotNull(
                            RequestNotificationDetail("Client", clientName),
                            secretUse.reason?.takeIf(String::isNotBlank)?.let {
                                RequestNotificationDetail(
                                    "Reason reported by client",
                                    renderSingleLineText(it),
                                )
                            },
                            RequestNotificationDetail("Command", command),
                            RequestNotificationDetail("Secrets", secretNames),
                        ),
                        decisionAvailable = request.id !in aiReviewInFlight.value,
                    )
                }
                RequestKind.GIT_SIGN.storedName -> {
                    val gitSign = dao.getGitSignRequest(request.id) ?: return@mapNotNull null
                    if (gitSign.decision != null) {
                        return@mapNotNull null
                    }
                    val parentId = request.parentRequestId ?: return@mapNotNull null
                    val invocation = dao.getSecretUseRequest(parentId) ?: return@mapNotNull null
                    val parentRequest = dao.getRequestById(parentId) ?: return@mapNotNull null
                    val command = renderShellCommand(
                        invocation.command,
                        decodeStringList(invocation.argumentsJson),
                    )
                    val clientName = renderSingleLineText(parentRequest.clientNameSnapshot)
                    RequestNotification(
                        requestId = request.id,
                        title = "Git signature requested",
                        summary = "$clientName requests a signature",
                        details = listOf(
                            RequestNotificationDetail("Client", clientName),
                            RequestNotificationDetail("Command", command),
                            RequestNotificationDetail(
                                "SSH key",
                                renderSingleLineText(gitSign.secretName),
                            ),
                            RequestNotificationDetail(
                                "Content",
                                renderSingleLineText(
                                    gitSign.message.decodeToString(throwOnInvalidSequence = false),
                                ),
                            ),
                        ),
                        decisionAvailable = request.id !in aiReviewInFlight.value,
                    )
                }
                RequestKind.SSH_AUTHENTICATE.storedName -> {
                    val authentication = dao.getSshAuthenticationRequest(request.id)
                        ?: return@mapNotNull null
                    if (authentication.decision != null) {
                        return@mapNotNull null
                    }
                    val parentId = request.parentRequestId ?: return@mapNotNull null
                    val invocation = dao.getSecretUseRequest(parentId) ?: return@mapNotNull null
                    val parentRequest = dao.getRequestById(parentId) ?: return@mapNotNull null
                    val command = renderShellCommand(
                        invocation.command,
                        decodeStringList(invocation.argumentsJson),
                    )
                    val clientName = renderSingleLineText(parentRequest.clientNameSnapshot)
                    RequestNotification(
                        requestId = request.id,
                        title = "SSH authentication requested",
                        summary = "$clientName requests authentication as " +
                            renderSingleLineText(authentication.username),
                        details = listOf(
                            RequestNotificationDetail("Client", clientName),
                            RequestNotificationDetail("Command", command),
                            RequestNotificationDetail(
                                "SSH key",
                                renderSingleLineText(authentication.secretName),
                            ),
                            RequestNotificationDetail(
                                "Remote user",
                                renderSingleLineText(authentication.username),
                            ),
                        ),
                        decisionAvailable = request.id !in aiReviewInFlight.value,
                    )
                }
                RequestKind.PAIRING.storedName -> {
                    val pairing = dao.getPairingAttempt(request.id) ?: return@mapNotNull null
                    RequestNotification(
                        requestId = request.id,
                        title = "Pairing requested",
                        summary = renderSingleLineText(
                            pairing.friendlyName ?: pairing.hostname ?:
                                pairing.platform ?: "Unknown client",
                        ),
                        details = listOf(RequestNotificationDetail(null, when (pairing.state.toPairingState()) {
                            PairingState.RECEIVING -> request.error ?:
                                "Waiting for the client to complete the secure exchange."
                            PairingState.SAS_VERIFICATION_PENDING -> "Open Agentknock and compare the security code."
                            PairingState.RELAY_ACTIVATION_PENDING,
                            PairingState.WAITING_FOR_FINISH,
                            -> "The pairing is still waiting for the client and can be rejected."
                            else -> "Open Agentknock to review this pairing."
                        })),
                        decisionAvailable = false,
                    )
                }
                RequestKind.SECRET_UPLOAD.storedName -> {
                    val upload = dao.getSecretUploadRequest(request.id) ?: return@mapNotNull null
                    if (upload.decision != null) {
                        return@mapNotNull null
                    }
                    val uploadSummary = decodeUploadSummary(upload.summaryJson)
                    RequestNotification(
                        requestId = request.id,
                        title = "Secret upload received",
                        summary = "${renderSingleLineText(request.clientNameSnapshot)} wants to " +
                            "${upload.mode.lowercase()} ${renderSingleLineText(upload.uploadedName)}",
                        details = listOf(
                            RequestNotificationDetail(
                                "Client",
                                renderSingleLineText(request.clientNameSnapshot),
                            ),
                            RequestNotificationDetail(
                                "Change",
                                "${upload.mode.lowercase().replaceFirstChar(Char::uppercase)} " +
                                    renderSingleLineText(upload.uploadedName),
                            ),
                            RequestNotificationDetail(
                                "Type",
                                if (upload.secretType == SSH_SECRET_TYPE) {
                                    "SSH key"
                                } else {
                                    "Environment variables"
                                },
                            ),
                        ) + if (upload.secretType == SSH_SECRET_TYPE) {
                            listOfNotNull(
                                uploadSummary.fingerprint?.let {
                                    RequestNotificationDetail("Fingerprint", it)
                                },
                            )
                        } else {
                            listOf(
                                RequestNotificationDetail(
                                    "Environment variables",
                                    uploadSummary.variableNames
                                        .joinToString(transform = ::renderSingleLineText),
                                ),
                            )
                        },
                        decisionAvailable = false,
                    )
                }
                else -> null
            }
        }

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
            // Finish an event once it starts changing local state, even if the foreground
            // connection is refreshed or enters its background grace period. An invocation can
            // already be persisted while its AI review is still in flight.
            val failure = withContext(NonCancellable) {
                operationMutex.withLock {
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
                        RelayDeviceFrame.Message(
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
                if (request.requestAcknowledgedAt == null) continue
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
        val now = currentTimeMillis()
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

        val client = dao.getClient(event.clientId) ?: return
        if (event.state == RelayClientState.REVOKED) {
            dao.deleteClient(client.clientId)
        } else {
            dao.updateClient(
                client.copy(
                    relayClientState = event.state.wireName,
                    desiredRelayClientState = if (event.state == RelayClientState.REVOKED) {
                        null
                    } else {
                        client.desiredRelayClientState?.takeUnless { it == event.state.wireName }
                    },
                    updatedAt = now,
                ),
            )
        }
        if (client.relayClientState != event.state.wireName) {
            audit.record(
                AuditRecord(
                    type = when (event.state) {
                        RelayClientState.ACTIVE -> AuditEventType.CLIENT_RESUMED
                        RelayClientState.SUSPENDED -> AuditEventType.CLIENT_SUSPENDED
                        RelayClientState.REVOKED -> AuditEventType.CLIENT_REVOKED
                        RelayClientState.PENDING -> AuditEventType.CLIENT_PENDING
                    },
                    outcome = AuditOutcome.CHANGED,
                    subject = client.name,
                    clientId = client.clientId,
                    clientName = client.name,
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
            val request = dao.getRequestById(event.requestId)
            if (request?.kind == RequestKind.PAIRING_REMOVE.storedName) {
                completePairingRemoval(request, completion = null)
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
        val request = dao.getRequestById(relayRequestId) ?: return
        if (request.completedAt != null) return
        val message = "The relay exchange expired before it completed."
        when (request.kind) {
            RequestKind.PAIRING.storedName -> {
                val pairing = dao.getPairingAttempt(request.id) ?: return
                if (pairing.state == PairingState.RECEIVING.storedName) {
                    dao.updatePairingRequest(
                        request.copy(error = message, updatedAt = now),
                        pairing,
                    )
                }
            }
            RequestKind.SECRET_USE.storedName -> {
                val secretUse = dao.getSecretUseRequest(request.id) ?: return
                dao.updateSecretUseRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        error = message,
                        updatedAt = now,
                        completedAt = now,
                    ),
                    secretUse,
                )
            }
            RequestKind.GIT_SIGN.storedName -> {
                val gitSign = dao.getGitSignRequest(request.id) ?: return
                dao.updateGitSignRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        error = message,
                        updatedAt = now,
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
                        updatedAt = now,
                        completedAt = now,
                    ),
                    authentication,
                )
            }
            RequestKind.SECRET_LIST.storedName -> {
                dao.updateSecretListRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        error = message,
                        updatedAt = now,
                        completedAt = now,
                    ),
                )
            }
            RequestKind.SECRET_UPLOAD.storedName -> {
                val upload = dao.getSecretUploadRequest(request.id) ?: return
                val pending = upload.decision == null
                dao.updateSecretUploadRequest(
                    request.copy(
                        state = if (pending) {
                            InboxRequestState.ACTION_REQUIRED.storedName
                        } else {
                            InboxRequestState.COMPLETED.storedName
                        },
                        error = message,
                        updatedAt = now,
                        completedAt = if (pending) null else now,
                    ),
                    upload,
                )
            }
            RequestKind.PAIRING_REMOVE.storedName -> {
                dao.updateRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        error = message,
                        updatedAt = now,
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
                        updatedAt = now,
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
                updatedAt = now,
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

    suspend fun approveSecretUseRequest(requestId: String): SecretUseDecisionResult =
        approveSecretUseRequest(requestId, allowTemporaryAccess = false)

    suspend fun allowSecretUseTemporarily(requestId: String): SecretUseDecisionResult =
        approveSecretUseRequest(requestId, allowTemporaryAccess = true)

    private suspend fun approveSecretUseRequest(
        requestId: String,
        allowTemporaryAccess: Boolean,
    ): SecretUseDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return SecretUseDecisionResult.NotFound
            val secretUseRequest = dao.getSecretUseRequest(requestId)
                ?: return SecretUseDecisionResult.NotFound
            if (
                request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
                secretUseRequest.decision != null
            ) {
                return SecretUseDecisionResult.NotPending
            }
            val requestedSecrets = decodeStringList(secretUseRequest.secretsJson)
            val storedSecrets = json.decodeFromString<List<SecretMetadata>>(
                secretUseRequest.secretDetailsJson,
            )
            val environmentSelections = storedSecrets.environmentSelections()
            val latestDescription = secrets.describeRequestedSecrets(
                requestedSecrets,
                environmentSelections,
            )
            val storedMissingSecrets = decodeStringList(secretUseRequest.missingSecretsJson)
            val protectedNames = latestDescription.reviewMetadata
                .filter { secret ->
                    secret.type == ENVIRONMENT_SECRET_TYPE &&
                        secret.environmentVariables.any { it.sensitive }
                }
                .map { it.name }
            val currentPolicies = secrets.approvalPoliciesForNames(
                protectedNames,
                request.clientId,
                TemporaryAccessOperation.INVOCATION,
            )
            val currentEvaluation = protectedNames.takeIf { it.isNotEmpty() }?.let {
                ApprovalPolicyEvaluator.evaluate(
                    currentPolicies.map { policy -> policy.toRequestedSecretApproval() },
                )
            }
            val storedEvaluation = secretUseRequest.approvalEvaluationJson
                ?.let(::decodeApprovalEvaluation)
            val approvalContextChanged = when {
                currentEvaluation == null -> storedEvaluation?.secrets?.isNotEmpty() == true
                storedEvaluation == null -> true
                else -> !storedEvaluation.hasSameSecretPolicies(currentEvaluation)
            }
            if (currentEvaluation?.secrets?.any { it.action == ApprovalAction.DENY } == true) {
                return@withLock decideSecretUseRequest(
                    request = request,
                    secretUseRequest = secretUseRequest,
                    decision = SecretUseDecision.DENIED,
                    responsePlaintext = invocationProtocol.deniedResponse(
                        InvocationDenialReason.POLICY_DENIED,
                        "Approval settings denied access to a requested secret.",
                    ),
                    providedSecretsJson = secretUseRequest.providedSecretsJson,
                    decisionSource = DECISION_SOURCE_POLICY,
                )
            }
            if (
                latestDescription.secrets != storedSecrets ||
                latestDescription.missingSecrets != storedMissingSecrets ||
                latestDescription.containsSensitiveMaterial !=
                secretUseRequest.containsSensitiveMaterial ||
                approvalContextChanged
            ) {
                val now = currentTimeMillis()
                dao.updateSecretUseRequest(
                    request = request.copy(updatedAt = now),
                    secretUseRequest = secretUseRequest.copy(
                        secretDetailsJson = json.encodeToString(latestDescription.secrets),
                        missingSecretsJson = encodeStringList(latestDescription.missingSecrets),
                        containsSensitiveMaterial = latestDescription.containsSensitiveMaterial,
                        approvalEvaluationJson = currentEvaluation?.let { json.encodeToString(it) },
                    ),
                )
                return SecretUseDecisionResult.SecretsChanged
            }
            val availableSecrets = when (
                val result = secrets.requestedSecrets(requestedSecrets, environmentSelections)
            ) {
                is RequestedSecretsResult.Available -> result.secrets
                is RequestedSecretsResult.MissingSecrets -> {
                    return SecretUseDecisionResult.MissingSecrets(result.names)
                }
                is RequestedSecretsResult.ConflictingVariable -> {
                    return SecretUseDecisionResult.ConflictingVariable(result.name)
                }
                is RequestedSecretsResult.MissingEnvironmentVariables -> {
                    return SecretUseDecisionResult.Invalid(
                        "Secret ${result.secretName} has no ${result.names.joinToString()} variable.",
                    )
                }
                is RequestedSecretsResult.EnvironmentOptionsForSshSecret -> {
                    return SecretUseDecisionResult.Invalid(
                        "Secret ${result.secretName} is not an environment-variable secret.",
                    )
                }
                RequestedSecretsResult.MultipleSshKeys -> {
                    return SecretUseDecisionResult.Invalid(
                        "A request can use at most one SSH key.",
                    )
                }
                RequestedSecretsResult.UnsupportedSecretType -> {
                    return SecretUseDecisionResult.Invalid("A requested secret type is unsupported.")
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
            val authorization = latestDescription.authorizationCommitment(currentPolicies)
            val temporaryGrant = if (allowTemporaryAccess) {
                val storedEvaluation = secretUseRequest.approvalEvaluationJson
                    ?.let(::decodeApprovalEvaluation)
                    ?: return SecretUseDecisionResult.TemporaryAccessUnavailable
                val evaluationsById = storedEvaluation.secrets.associateBy { it.secretId }
                val aiCanEscalateToTemporaryAccess =
                    storedEvaluation.aiReview?.decision == AiReviewDecision.ASK_USER ||
                        storedEvaluation.aiReview?.failure != null ||
                        (storedEvaluation.aiReview == null &&
                            request.id !in aiReviewInFlight.value)
                val protectedNames = latestDescription.reviewMetadata
                    .filter { secret ->
                        secret.type == ENVIRONMENT_SECRET_TYPE &&
                            secret.environmentVariables.any { it.sensitive }
                    }
                    .map { it.name }
                val currentPolicies = secrets.approvalPoliciesForNames(
                    protectedNames,
                    request.clientId,
                    TemporaryAccessOperation.INVOCATION,
                )
                val currentEvaluation = ApprovalPolicyEvaluator.evaluate(
                    currentPolicies.map { it.toRequestedSecretApproval() },
                )
                if (!storedEvaluation.hasSameSecretPolicies(currentEvaluation)) {
                    return SecretUseDecisionResult.TemporaryAccessUnavailable
                }
                val grantablePolicies = currentPolicies.filter { policy ->
                    val evaluation = evaluationsById[policy.secretId]
                    evaluation?.temporaryAccessExpiresAt == null && when (policy.mode) {
                        SecretApprovalMode.TEMPORARY -> evaluation?.action == ApprovalAction.ASK_ME
                        SecretApprovalMode.ASK_AI ->
                            evaluation?.action == ApprovalAction.ASK_AI &&
                                aiCanEscalateToTemporaryAccess
                        else -> false
                    }
                }
                if (grantablePolicies.isEmpty()) {
                    return SecretUseDecisionResult.TemporaryAccessUnavailable
                }
                val expiresAt = currentTimeMillis() + TEMPORARY_ACCESS_DURATION_MILLIS
                val grantedIds = grantablePolicies.map(SecretApprovalPolicy::secretId).toSet()
                val alsoApprovedByAi = storedEvaluation.aiReview?.decision ==
                    AiReviewDecision.APPROVE && storedEvaluation.secrets.any {
                    it.action == ApprovalAction.ASK_AI && it.secretId !in grantedIds
                }
                val alsoApprovedOnce = storedEvaluation.secrets.any {
                    it.secretId !in grantedIds && it.action == ApprovalAction.ASK_ME
                }
                TemporaryGrant(
                    policies = grantablePolicies,
                    operation = TemporaryAccessOperation.INVOCATION,
                    expiresAt = expiresAt,
                    evaluation = storedEvaluation.copy(
                        secrets = storedEvaluation.secrets.map { evaluation ->
                            if (grantablePolicies.any { it.secretId == evaluation.secretId }) {
                                evaluation.copy(temporaryAccessExpiresAt = expiresAt)
                            } else {
                                evaluation
                            }
                        },
                    ),
                    decisionSource = if (alsoApprovedByAi || alsoApprovedOnce) {
                        DECISION_SOURCE_MIXED
                    } else {
                        DECISION_SOURCE_TEMPORARY_ACCESS
                    },
                )
            } else {
                null
            }
            val decisionResult = decideSecretUseRequest(
                request = request,
                secretUseRequest = secretUseRequest,
                decision = SecretUseDecision.APPROVED,
                responsePlaintext = invocationProtocol.approvedResponse(
                    availableSecrets.mapValues { (_, secret) -> secret.toResponseSecret() },
                ),
                providedSecretsJson = json.encodeToString(
                    approvalReviewSecretFacts(latestDescription, availableSecrets),
                ),
                decisionSource = temporaryGrant?.decisionSource ?: DECISION_SOURCE_USER,
                authorization = authorization,
            )
            if (decisionResult == SecretUseDecisionResult.Decided && temporaryGrant != null) {
                val started = runCatching {
                    secrets.allowTemporaryAccess(
                        policies = temporaryGrant.policies,
                        clientId = request.clientId,
                        operation = temporaryGrant.operation,
                        expiresAt = temporaryGrant.expiresAt,
                    )
                }.getOrDefault(false)
                if (!started) {
                    return@withLock SecretUseDecisionResult.TemporaryAccessNotStarted
                }
                val decidedRequest = dao.getRequestById(requestId)
                    ?: return@withLock SecretUseDecisionResult.TemporaryAccessNotStarted
                val decidedSecretUse = dao.getSecretUseRequest(requestId)
                    ?: return@withLock SecretUseDecisionResult.TemporaryAccessNotStarted
                dao.updateSecretUseRequest(
                    decidedRequest,
                    decidedSecretUse.copy(
                        decisionSource = temporaryGrant.decisionSource,
                        approvalEvaluationJson = json.encodeToString(temporaryGrant.evaluation),
                    ),
                )
            }
            decisionResult
        }.also { result ->
            if (
                result == SecretUseDecisionResult.Decided ||
                result == SecretUseDecisionResult.TemporaryAccessNotStarted
            ) {
                requestSync()
            }
        }

    suspend fun denySecretUseRequest(requestId: String): SecretUseDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return SecretUseDecisionResult.NotFound
            val secretUseRequest = dao.getSecretUseRequest(requestId)
                ?: return SecretUseDecisionResult.NotFound
            if (
                request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
                secretUseRequest.decision != null
            ) {
                return SecretUseDecisionResult.NotPending
            }
            decideSecretUseRequest(
                request = request,
                secretUseRequest = secretUseRequest,
                decision = SecretUseDecision.DENIED,
                responsePlaintext = invocationProtocol.deniedResponse(
                    InvocationDenialReason.USER_DENIED,
                    SECRET_USE_DENIAL_MESSAGE,
                ),
                providedSecretsJson = secretUseRequest.providedSecretsJson,
            )
        }.also { result ->
            if (result == SecretUseDecisionResult.Decided) requestSync()
        }

    private suspend fun decideSecretUseRequest(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        decision: SecretUseDecision,
        responsePlaintext: ByteArray,
        providedSecretsJson: String?,
        decisionSource: String = DECISION_SOURCE_USER,
        authorization: AuthorizationCommitment? = null,
    ): SecretUseDecisionResult {
        require(decision != SecretUseDecision.APPROVED || providedSecretsJson != null) {
            "An approved invocation must record its provided secrets"
        }
        require(decision != SecretUseDecision.APPROVED || authorization != null) {
            "An approved invocation must bind its authorization state"
        }
        val client = dao.getClient(request.clientId)
            ?: return SecretUseDecisionResult.PairingUnavailable
        if (
            client.relayClientState != RelayClientState.ACTIVE.wireName ||
            client.desiredRelayClientState?.let { it != RelayClientState.ACTIVE.wireName } == true
        ) {
            return SecretUseDecisionResult.PairingUnavailable
        }
        val credentials = credentialsForRequest(request)
            ?: return SecretUseDecisionResult.PairingUnavailable
        val clientPsk = decryptRequestPsk(request)
            ?: return SecretUseDecisionResult.PairingUnavailable
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
                deviceId = credentials.deviceId,
                requestId = request.id,
                clientId = request.clientId,
                clientPsk = clientPsk,
                devicePrivateKey = credentials.devicePrivateKey,
                devicePublicKey = credentials.devicePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                plaintext = responsePlaintext,
            )
        }.getOrNull() ?: return SecretUseDecisionResult.PairingUnavailable
        val now = currentTimeMillis()
        val updatedRequest = request.copy(
            state = InboxRequestState.WAITING.storedName,
            responseJson = response.toString(),
            responseAcknowledgedAt = null,
            updatedAt = now,
        )
        val updatedSecretUse = secretUseRequest.copy(
            decision = decision.storedName,
            decisionSource = decisionSource,
            providedSecretsJson = providedSecretsJson,
            decidedAt = now,
        )
        val persisted = if (decision == SecretUseDecision.APPROVED) {
            dao.updateSecretUseRequestIfAuthorized(
                request = updatedRequest,
                secretUseRequest = updatedSecretUse,
                authorization = checkNotNull(authorization),
                clientId = request.clientId,
                operation = TemporaryAccessOperation.INVOCATION.storedName,
                now = now,
            ) == ConditionalRequestUpdate.APPLIED
        } else {
            dao.updateSecretUseRequest(updatedRequest, updatedSecretUse)
            true
        }
        if (!persisted) return SecretUseDecisionResult.SecretsChanged
        audit.record(
            AuditRecord(
                type = AuditEventType.SECRET_USE_DECIDED,
                outcome = if (decision == SecretUseDecision.APPROVED) {
                    AuditOutcome.APPROVED
                } else {
                    AuditOutcome.DENIED
                },
                decisionSource = decisionSource.toAuditDecisionSource(),
                subject = decodeStringList(secretUseRequest.secretsJson).joinToString(),
                clientId = request.clientId,
                clientName = request.clientNameSnapshot,
                relayRequestId = request.id,
            ),
        )
        return SecretUseDecisionResult.Decided
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
                    decision = SecretUseDecision.DENIED,
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
                val changedAt = currentTimeMillis()
                dao.updateGitSignRequest(
                    request.copy(updatedAt = changedAt),
                    gitSign.copy(
                        approvalEvaluationJson = json.encodeToString(currentEvaluation),
                    ),
                )
                return GitSignDecisionResult.ApprovalChanged
            }
            val authorization = latestDescription.authorizationCommitment(listOf(policy))
            val expectedPublicKey = json.decodeFromString<List<SecretMetadata>>(
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
                        (storedEvaluation.aiReview == null &&
                            request.id !in aiReviewInFlight.value)
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
                decision = SecretUseDecision.APPROVED,
                responsePlaintext = gitSignProtocol.approvedResponse(signature),
                decisionSource = temporaryGrant?.decisionSource ?: DECISION_SOURCE_USER,
                authorization = authorization,
            )
            if (decisionResult == GitSignDecisionResult.Decided && temporaryGrant != null) {
                val started = runCatching {
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
                decision = SecretUseDecision.DENIED,
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
        decision: SecretUseDecision,
        responsePlaintext: ByteArray,
        decisionSource: String = DECISION_SOURCE_USER,
        authorization: AuthorizationCommitment? = null,
    ): GitSignDecisionResult {
        require(decision != SecretUseDecision.APPROVED || authorization != null) {
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
        val clientPsk = decryptRequestPsk(request)
            ?: return GitSignDecisionResult.PairingUnavailable
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
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
            responseAcknowledgedAt = null,
            updatedAt = now,
        )
        val updatedGitSign = gitSign.copy(
            decision = decision.storedName,
            completionReason = if (decision == SecretUseDecision.DENIED) {
                if (decisionSource == DECISION_SOURCE_POLICY) {
                    InvocationDenialReason.POLICY_DENIED.wireName
                } else {
                    InvocationDenialReason.USER_DENIED.wireName
                }
            } else {
                null
            },
            completionMessage = if (decision == SecretUseDecision.DENIED) {
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
        val persisted = if (decision == SecretUseDecision.APPROVED) {
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
                outcome = if (decision == SecretUseDecision.APPROVED) {
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
                decision = SecretUseDecision.DENIED,
                responsePlaintext = sshAuthenticationProtocol.deniedResponse(
                    InvocationDenialReason.POLICY_DENIED,
                    "Approval settings denied SSH authentication.",
                ),
                decisionSource = DECISION_SOURCE_POLICY,
            )
        }
        if (storedEvaluation == null || !storedEvaluation.hasSameSecretPolicies(currentEvaluation)) {
            val changedAt = currentTimeMillis()
            dao.updateSshAuthenticationRequest(
                request.copy(updatedAt = changedAt),
                authentication.copy(
                    approvalEvaluationJson = json.encodeToString(currentEvaluation),
                ),
            )
            return SshAuthenticationDecisionResult.ApprovalChanged
        }
        val expectedPublicKey = json.decodeFromString<List<SecretMetadata>>(
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
                    (storedEvaluation.aiReview == null &&
                        request.id !in aiReviewInFlight.value)
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
            decision = SecretUseDecision.APPROVED,
            responsePlaintext = sshAuthenticationProtocol.approvedResponse(signature),
            decisionSource = temporaryGrant?.decisionSource ?: DECISION_SOURCE_USER,
            authorization = description.authorizationCommitment(listOf(policy)),
        )
        if (result == SshAuthenticationDecisionResult.Decided && temporaryGrant != null) {
            val started = runCatching {
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
            decision = SecretUseDecision.DENIED,
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
        decision: SecretUseDecision,
        responsePlaintext: ByteArray,
        decisionSource: String = DECISION_SOURCE_USER,
        authorization: AuthorizationCommitment? = null,
    ): SshAuthenticationDecisionResult {
        require(decision != SecretUseDecision.APPROVED || authorization != null) {
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
        val clientPsk = decryptRequestPsk(request)
            ?: return SshAuthenticationDecisionResult.PairingUnavailable
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
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
            responseAcknowledgedAt = null,
            updatedAt = now,
        )
        val reason = when {
            decision != SecretUseDecision.DENIED -> null
            decisionSource == DECISION_SOURCE_POLICY -> InvocationDenialReason.POLICY_DENIED.wireName
            else -> InvocationDenialReason.USER_DENIED.wireName
        }
        val message = when {
            decision != SecretUseDecision.DENIED -> null
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
        val persisted = if (decision == SecretUseDecision.APPROVED) {
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
                outcome = if (decision == SecretUseDecision.APPROVED) {
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
                existing.requestJson != requestPayload.toString()
            ) {
                return null
            }
            if (existing.state == InboxRequestState.REVIEWING.storedName) {
                existing = existing.copy(
                    state = InboxRequestState.ACTION_REQUIRED.storedName,
                    updatedAt = currentTimeMillis(),
                )
                check(dao.updateRequest(existing) == 1)
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
        val request = dao.getRequestById(requestId)
            ?: return@withLock SecretUploadDecisionResult.NotFound
        val uploadRequest = dao.getSecretUploadRequest(requestId)
            ?: return@withLock SecretUploadDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            uploadRequest.decision != null
        ) {
            return@withLock SecretUploadDecisionResult.NotPending
        }
        val secretId = when (uploadRequest.secretType) {
            ENVIRONMENT_SECRET_TYPE -> {
                val values = sortedMapOf<String, String>()
                val variableRows = dao.getSecretUploadEnvironmentVariables(requestId)
                for (variable in variableRows) {
                    when (
                        val result = decryptSecretUploadEnvironmentVariable(request, variable)
                    ) {
                        is DecryptionResult.Plaintext -> {
                            values[variable.name] = runCatching {
                                result.value.decodeToString(throwOnInvalidSequence = true)
                            }.getOrElse {
                                return@withLock SecretUploadDecisionResult.SecretCorrupted
                            }
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
                when (val result = secrets.applyEnvironmentSecretUpload(upload, approvedName.trim())) {
                    is ApplyEnvironmentSecretUploadResult.Invalid -> {
                        return@withLock SecretUploadDecisionResult.Invalid(result.message)
                    }
                    is ApplyEnvironmentSecretUploadResult.Applied -> result.secretId
                }
            }
            SSH_SECRET_TYPE -> {
                val keyRow = dao.getSecretUploadSshKey(requestId)
                val privateKey = if (keyRow == null) {
                    null
                } else {
                    val plaintext = when (
                        val result = decryptSecretUploadSshKey(request, keyRow)
                    ) {
                        is DecryptionResult.Plaintext -> result.value
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
                    runCatching {
                        sshKeys.fromStored(
                            keyRow.algorithm,
                            plaintext,
                            keyRow.publicKey,
                            keyRow.comment,
                        )
                    }.getOrElse {
                        return@withLock SecretUploadDecisionResult.SecretCorrupted
                    }
                }
                val upload = SshSecretUpload(
                    mode = SecretUploadMode.entries.single { it.wireName == uploadRequest.mode },
                    name = uploadRequest.uploadedName,
                    descriptionProvided = uploadRequest.descriptionProvided,
                    description = uploadRequest.description,
                    privateKey = privateKey,
                )
                when (val result = secrets.applySshSecretUpload(upload, approvedName.trim())) {
                    is ApplySshSecretUploadResult.Invalid -> {
                        return@withLock SecretUploadDecisionResult.Invalid(result.message)
                    }
                    is ApplySshSecretUploadResult.Applied -> result.secretId
                }
            }
            else -> return@withLock SecretUploadDecisionResult.Invalid(
                "Unsupported secret type.",
            )
        }
        val now = currentTimeMillis()
        val transportFinished = request.completionJson != null || request.error != null
        val lifecycle = secretUploadLifecycle(
            SecretUploadRequestState.APPROVED.storedName,
            transportFinished,
        )
        dao.updateSecretUploadRequest(
            request = request.copy(
                state = lifecycle.state.storedName,
                listed = false,
                updatedAt = now,
                completedAt = if (lifecycle.completed) request.completedAt ?: now else null,
            ),
            secretUpload = uploadRequest.copy(
                decision = SecretUploadRequestState.APPROVED.storedName,
                approvedName = approvedName.trim(),
                decidedAt = now,
            ),
            discardUploadedValues = true,
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.SECRET_UPLOAD_DECIDED,
                outcome = AuditOutcome.APPROVED,
                decisionSource = AuditDecisionSource.USER,
                subject = approvedName.trim(),
                detail = uploadRequest.uploadedName.takeUnless { it == approvedName.trim() },
                clientId = request.clientId,
                clientName = request.clientNameSnapshot,
                relayRequestId = request.id,
            ),
        )
        SecretUploadDecisionResult.Approved(secretId)
    }

    suspend fun readSecretUploadVariable(
        requestId: String,
        variableId: String,
    ): SecretUploadVariableValue = operationMutex.withLock {
        val request = dao.getRequestById(requestId)
            ?: return@withLock SecretUploadVariableValue.NotFound
        val upload = dao.getSecretUploadRequest(requestId)
            ?: return@withLock SecretUploadVariableValue.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            upload.decision != null
        ) {
            return@withLock SecretUploadVariableValue.NotFound
        }
        val variable = dao.getSecretUploadEnvironmentVariables(requestId).find {
            it.id == variableId
        }
            ?: return@withLock SecretUploadVariableValue.NotFound
        when (
            val result = decryptSecretUploadEnvironmentVariable(request, variable)
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
        requestId: String,
        variableId: String,
        sensitive: Boolean,
    ): Boolean = operationMutex.withLock {
        val request = dao.getRequestById(requestId) ?: return@withLock false
        val upload = dao.getSecretUploadRequest(requestId) ?: return@withLock false
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            upload.decision != null
        ) {
            return@withLock false
        }
        val variable = dao.getSecretUploadEnvironmentVariables(requestId).find {
            it.id == variableId
        }
            ?: return@withLock false
        dao.updateSecretUploadEnvironmentVariable(variable.copy(sensitive = sensitive)) == 1
    }

    suspend fun rejectSecretUpload(requestId: String): SecretUploadDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return@withLock SecretUploadDecisionResult.NotFound
            val upload = dao.getSecretUploadRequest(requestId)
                ?: return@withLock SecretUploadDecisionResult.NotFound
            if (
                request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
                upload.decision != null
            ) {
                return@withLock SecretUploadDecisionResult.NotPending
            }
            val now = currentTimeMillis()
            val transportFinished = request.completionJson != null || request.error != null
            val lifecycle = secretUploadLifecycle(
                SecretUploadRequestState.REJECTED.storedName,
                transportFinished,
            )
            dao.updateSecretUploadRequest(
                request = request.copy(
                    state = lifecycle.state.storedName,
                    listed = false,
                    updatedAt = now,
                    completedAt = if (lifecycle.completed) request.completedAt ?: now else null,
                ),
                secretUpload = upload.copy(
                    decision = SecretUploadRequestState.REJECTED.storedName,
                    decidedAt = now,
                ),
                discardUploadedValues = true,
            )
            audit.record(
                AuditRecord(
                    type = AuditEventType.SECRET_UPLOAD_DECIDED,
                    outcome = AuditOutcome.REJECTED,
                    decisionSource = AuditDecisionSource.USER,
                    subject = upload.uploadedName,
                    clientId = request.clientId,
                    clientName = request.clientNameSnapshot,
                    relayRequestId = request.id,
                ),
            )
            SecretUploadDecisionResult.Rejected
        }

    suspend fun renameClient(clientId: String, name: String): ClientChangeResult =
        operationMutex.withLock {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "A client name cannot be empty" }
            val client = dao.getClient(clientId)
                ?: return@withLock ClientChangeResult.NOT_FOUND
            dao.updateClient(client.copy(name = trimmed, updatedAt = currentTimeMillis()))
            audit.record(
                AuditRecord(
                    type = AuditEventType.CLIENT_RENAMED,
                    outcome = AuditOutcome.CHANGED,
                    subject = trimmed,
                    detail = client.name.takeUnless { it == trimmed },
                    clientId = clientId,
                    clientName = trimmed,
                ),
            )
            ClientChangeResult.CHANGED
        }

    suspend fun saveClientInstructions(
        clientId: String,
        instructions: String,
    ): ClientChangeResult = operationMutex.withLock {
        val client = dao.getClient(clientId)
            ?: return@withLock ClientChangeResult.NOT_FOUND
        val normalized = instructions.trim()
        if (client.instructions == normalized) return@withLock ClientChangeResult.CHANGED
        dao.updateClient(
            client.copy(instructions = normalized, updatedAt = currentTimeMillis()),
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.CLIENT_INSTRUCTIONS_CHANGED,
                outcome = AuditOutcome.CHANGED,
                subject = client.name,
                clientId = clientId,
                clientName = client.name,
            ),
        )
        ClientChangeResult.CHANGED
    }

    suspend fun setClientState(
        clientId: String,
        state: RelayClientState,
    ): ClientChangeResult = operationMutex.withLock {
        if (state == RelayClientState.PENDING) return@withLock ClientChangeResult.INVALID_STATE
        val client = dao.getClient(clientId)
            ?: return@withLock ClientChangeResult.NOT_FOUND
        val current = client.relayClientState.toRelayClientState()
        val allowed = when (current) {
            RelayClientState.ACTIVE -> state == RelayClientState.SUSPENDED ||
                state == RelayClientState.REVOKED
            RelayClientState.SUSPENDED -> state == RelayClientState.ACTIVE ||
                state == RelayClientState.REVOKED
            RelayClientState.REVOKED -> state == RelayClientState.REVOKED
            RelayClientState.PENDING -> false
        }
        if (!allowed) return@withLock ClientChangeResult.INVALID_STATE
        val updated = client.copy(
            desiredRelayClientState = state.wireName,
            updatedAt = currentTimeMillis(),
        )
        if (state == RelayClientState.REVOKED) {
            dao.revokeClient(updated)
        } else {
            dao.updateClient(updated)
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
        val clientPsk = decryptPendingPsk(attempt, rootRequest) ?: return null
        val opened = runCatching {
            pairingProtocol.openPairedRequest(
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
            requestPsk = encryptRequestPsk(
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
        val clientPsk = decryptClientPsk(client) ?: return null
        val previousClientPsk = decryptPreviousClientPsk(client)
        val opened = runCatching {
            pairingProtocol.openPairedRequest(
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
        val acceptedSecrets = acceptedRequestPsks(
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
        val description = secrets.describeRequestedSecrets(contents.secrets, environmentSelections)
        val requestedSecrets = secrets.requestedSecrets(contents.secrets, environmentSelections)
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
            updatedAt = now,
            completedAt = null,
            requestAcknowledgedAt = null,
            responseAcknowledgedAt = null,
            completionAcknowledgedAt = null,
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
        val pendingRequestId = if (needsAiReview) {
            aiReviewInFlight.update { it + relayRequestId }
            try {
                dao.insertSecretUseRequest(
                    request = initialRequest,
                    secretUseRequest = initialSecretUse,
                    client = pairing.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                        updatedAt = now,
                    ),
                    requestPsk = acceptedSecrets.requestPsk,
                    currentClientPsk = acceptedSecrets.currentClientPsk,
                    previousClientPsk = acceptedSecrets.previousClientPsk,
                )
                recordSecretUseRequested(pairing, relayRequestId, contents)
                relayRequestId
            } catch (failure: Throwable) {
                aiReviewInFlight.update { it - relayRequestId }
                throw failure
            }
        } else {
            null
        }
        try {
        val reviewResult = if (needsAiReview) {
            requestAiReview(
                pairing = pairing,
                contents = contents,
                description = description,
                values = checkNotNull(initialAvailableSecrets),
                evaluation = initialApprovalEvaluation,
                policies = approvalPolicies,
                credentials = credentials,
            )
        } else {
            null
        }
        val currentDescription = if (needsAiReview) {
            secrets.describeRequestedSecrets(contents.secrets, environmentSelections)
        } else {
            description
        }
        val currentRequestedSecrets = if (needsAiReview) {
            secrets.requestedSecrets(contents.secrets, environmentSelections)
        } else {
            requestedSecrets
        }
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
            credentialsForClient(pairing, credentials)
        } else {
            credentials
        }
        val aiInputsChanged = needsAiReview && (
            currentAutomaticDenial != null ||
                !description.hasSameSecretRevisions(currentDescription) ||
                currentApprovalEvaluation == null ||
                !checkNotNull(initialApprovalEvaluation)
                    .hasSameSecretPolicies(currentApprovalEvaluation) ||
                currentCredentials?.instructions != credentials.instructions
            )
        val aiReview = if (aiInputsChanged) {
            AiReview(
                decision = AiReviewDecision.ASK_USER,
                explanation = "The secret or approval settings changed during AI review.",
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
            deviceInstructions = if (needsAiReview) {
                currentCredentials?.let { current ->
                    AuthorizationDeviceInstructionsCommitment(
                        deviceIdentityId = current.deviceIdentityId,
                        instructions = current.instructions,
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
        val denial = currentAutomaticDenial ?: policyDenial ?: aiDenial
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
                pairingProtocol.sealPairedResponse(
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
            denial != null -> SecretUseDecision.DENIED
            allProtectedUsesApproved -> SecretUseDecision.APPROVED
            !currentDescription.containsSensitiveMaterial -> SecretUseDecision.APPROVED
            else -> null
        }
        val decidedAt = currentTimeMillis()
        val finalRequest = initialRequest.copy(
            state = reviewedRequestState(response != null).storedName,
            responseJson = response?.toString(),
            updatedAt = decidedAt,
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
        if (pendingRequestId == null) {
            val inserted = if (automaticDecision == SecretUseDecision.APPROVED) {
                dao.insertSecretUseRequestIfAuthorized(
                    request = finalRequest,
                    secretUseRequest = finalSecretUse,
                    client = pairing.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                        updatedAt = now,
                    ),
                    requestPsk = acceptedSecrets.requestPsk,
                    currentClientPsk = acceptedSecrets.currentClientPsk,
                    previousClientPsk = acceptedSecrets.previousClientPsk,
                    authorization = authorization,
                    clientId = pairing.clientId,
                    operation = TemporaryAccessOperation.INVOCATION.storedName,
                    now = currentTimeMillis(),
                )
            } else {
                dao.insertSecretUseRequest(
                    request = finalRequest,
                    secretUseRequest = finalSecretUse,
                    client = pairing.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                        updatedAt = now,
                    ),
                    requestPsk = acceptedSecrets.requestPsk,
                    currentClientPsk = acceptedSecrets.currentClientPsk,
                    previousClientPsk = acceptedSecrets.previousClientPsk,
                )
                true
            }
            if (automaticDecision == SecretUseDecision.APPROVED && !inserted) return null
            recordSecretUseRequested(pairing, relayRequestId, contents)
        } else if (automaticDecision == SecretUseDecision.APPROVED) {
            val update = dao.updateSecretUseRequestIfAuthorized(
                request = finalRequest,
                secretUseRequest = finalSecretUse,
                authorization = authorization,
                clientId = pairing.clientId,
                operation = TemporaryAccessOperation.INVOCATION.storedName,
                now = currentTimeMillis(),
            )
            when (update) {
                ConditionalRequestUpdate.APPLIED -> Unit
                ConditionalRequestUpdate.ACTION_REQUIRED -> return ProcessedRelayMessage()
                ConditionalRequestUpdate.UNAVAILABLE -> return null
            }
        } else {
            dao.updateSecretUseRequest(finalRequest, finalSecretUse)
        }
        if (aiReview != null) {
            audit.record(
                AuditRecord(
                    type = AuditEventType.SECRET_USE_AI_REVIEWED,
                    outcome = aiReview.auditOutcome(),
                    decisionSource = AuditDecisionSource.AI_REVIEW,
                    subject = contents.secrets.joinToString(),
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
                    type = AuditEventType.SECRET_USE_DECIDED,
                    outcome = when {
                        automaticDecision == SecretUseDecision.APPROVED -> AuditOutcome.APPROVED
                        denial?.first == InvocationDenialReason.INVALID_REQUEST ->
                            AuditOutcome.REJECTED
                        aiDenial != null || policyDenial != null -> AuditOutcome.DENIED
                        else -> AuditOutcome.FAILED
                    },
                    decisionSource = when {
                        automaticDecision == SecretUseDecision.APPROVED &&
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
                ),
            )
        }
        return ProcessedRelayMessage(response)
        } finally {
            pendingRequestId?.let {
                aiReviewInFlight.update { it - relayRequestId }
            }
        }
    }

    private suspend fun recordSecretUseRequested(
        pairing: ClientEntity,
        relayRequestId: String,
        contents: InvocationRequestMessage,
    ) {
        audit.record(
            AuditRecord(
                type = AuditEventType.SECRET_USE_RECEIVED,
                outcome = AuditOutcome.RECEIVED,
                subject = contents.secrets.joinToString(),
                clientId = pairing.clientId,
                clientName = pairing.auditClientName(),
                relayRequestId = relayRequestId,
            ),
        )
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
        val expectedTokenHash = invocation.invocationTokenHash ?: return null
        if (
            invocationRequest.clientId != pairing.clientId ||
            invocationRequest.deviceIdentityId != pairing.deviceIdentityId ||
            invocation.decision != SecretUseDecision.APPROVED.storedName ||
            invocationRequest.clientSoftwareJson?.let(::decodeClientSoftware) !=
                contents.clientSoftware ||
            !MessageDigest.isEqual(
                expectedTokenHash,
                invocationTokenHash(contents.invocationToken),
            )
        ) {
            return null
        }

        val sshMetadata = json.decodeFromString<List<SecretMetadata>>(
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
            updatedAt = now,
            completedAt = null,
            requestAcknowledgedAt = null,
            responseAcknowledgedAt = null,
            completionAcknowledgedAt = null,
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
        val pendingRequestId = if (needsAiReview) {
            aiReviewInFlight.update { it + relayRequestId }
            try {
                dao.insertGitSignRequest(
                    request = initialRequest,
                    gitSignRequest = initialGitSign,
                    client = pairing.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                        updatedAt = now,
                    ),
                    requestPsk = acceptedSecrets.requestPsk,
                    currentClientPsk = acceptedSecrets.currentClientPsk,
                    previousClientPsk = acceptedSecrets.previousClientPsk,
                )
                recordGitSignRequested(pairing, relayRequestId, contents.secret)
                relayRequestId
            } catch (failure: Throwable) {
                aiReviewInFlight.update { it - relayRequestId }
                throw failure
            }
        } else {
            null
        }
        try {
        val reviewResult = if (needsAiReview) {
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
        } else {
            null
        }
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
            credentialsForClient(pairing, credentials)
        } else {
            credentials
        }
        val aiInputsChanged = needsAiReview && (
            currentEvaluation == null ||
                !checkNotNull(initialEvaluation).hasSameSecretPolicies(currentEvaluation) ||
                currentCredentials?.instructions != credentials.instructions
            )
        val aiReview = if (aiInputsChanged) {
            AiReview(
                decision = AiReviewDecision.ASK_USER,
                explanation = "The SSH key or approval settings changed during AI review.",
            )
        } else {
            reviewResult
        }
        val evaluation = (if (aiInputsChanged) currentEvaluation else initialEvaluation)
            ?.copy(aiReview = aiReview)
        val authorization = currentDescription.authorizationCommitment(
            policies = currentPolicies,
            deviceInstructions = if (needsAiReview) {
                currentCredentials?.let { current ->
                    AuthorizationDeviceInstructionsCommitment(
                        deviceIdentityId = current.deviceIdentityId,
                        instructions = current.instructions,
                    )
                }
            } else {
                null
            },
        )
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
                pairingProtocol.sealPairedResponse(
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
            signature != null -> SecretUseDecision.APPROVED
            denial != null -> SecretUseDecision.DENIED
            else -> null
        }
        val finalRequest = initialRequest.copy(
            state = reviewedRequestState(response != null).storedName,
            responseJson = response?.toString(),
            updatedAt = decidedAt,
        )
        val finalGitSign = initialGitSign.copy(
            decision = automaticDecision?.storedName,
            approvalEvaluationJson = evaluation?.let { json.encodeToString(it) },
            completionReason = denial?.first?.wireName,
            completionMessage = denial?.second,
            decidedAt = automaticDecision?.let { decidedAt },
        )
        if (pendingRequestId == null) {
            val inserted = if (automaticDecision == SecretUseDecision.APPROVED) {
                dao.insertGitSignRequestIfAuthorized(
                    request = finalRequest,
                    gitSignRequest = finalGitSign,
                    client = pairing.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                        updatedAt = now,
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
                        updatedAt = now,
                    ),
                    requestPsk = acceptedSecrets.requestPsk,
                    currentClientPsk = acceptedSecrets.currentClientPsk,
                    previousClientPsk = acceptedSecrets.previousClientPsk,
                )
                true
            }
            if (automaticDecision == SecretUseDecision.APPROVED && !inserted) return null
            recordGitSignRequested(pairing, relayRequestId, contents.secret)
        } else if (automaticDecision == SecretUseDecision.APPROVED) {
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
        } finally {
            pendingRequestId?.let {
                aiReviewInFlight.update { it - relayRequestId }
            }
        }
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
        val expectedTokenHash = invocation.invocationTokenHash ?: return null
        if (
            invocationRequest.clientId != pairing.clientId ||
            invocationRequest.deviceIdentityId != pairing.deviceIdentityId ||
            invocation.decision != SecretUseDecision.APPROVED.storedName ||
            invocationRequest.clientSoftwareJson?.let(::decodeClientSoftware) !=
                contents.clientSoftware ||
            !MessageDigest.isEqual(
                expectedTokenHash,
                invocationTokenHash(contents.invocationToken),
            )
        ) {
            return null
        }
        val sshMetadata = json.decodeFromString<List<SecretMetadata>>(
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
            updatedAt = now,
            completedAt = null,
            requestAcknowledgedAt = null,
            responseAcknowledgedAt = null,
            completionAcknowledgedAt = null,
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
        val pendingRequestId = if (needsAiReview) {
            aiReviewInFlight.update { it + relayRequestId }
            try {
                dao.insertSshAuthenticationRequest(
                    request = initialRequest,
                    authentication = initialAuthentication,
                    client = pairing.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                        updatedAt = now,
                    ),
                    requestPsk = acceptedSecrets.requestPsk,
                    currentClientPsk = acceptedSecrets.currentClientPsk,
                    previousClientPsk = acceptedSecrets.previousClientPsk,
                )
                recordSshAuthenticationRequested(
                    pairing = pairing,
                    relayRequestId = relayRequestId,
                    secretName = contents.secret,
                    username = messageDetails.username,
                )
                relayRequestId
            } catch (failure: Throwable) {
                aiReviewInFlight.update { it - relayRequestId }
                throw failure
            }
        } else {
            null
        }
        try {
            val reviewResult = if (needsAiReview) {
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
            } else {
                null
            }
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
                credentialsForClient(pairing, credentials)
            } else {
                credentials
            }
            val aiInputsChanged = needsAiReview && (
                currentEvaluation == null ||
                    !checkNotNull(initialEvaluation).hasSameSecretPolicies(currentEvaluation) ||
                    currentCredentials?.instructions != credentials.instructions
                )
            val aiReview = if (aiInputsChanged) {
                AiReview(
                    decision = AiReviewDecision.ASK_USER,
                    explanation = "The SSH key or approval settings changed during AI review.",
                )
            } else {
                reviewResult
            }
            val evaluation = (if (aiInputsChanged) currentEvaluation else initialEvaluation)
                ?.copy(aiReview = aiReview)
            val authorization = currentDescription.authorizationCommitment(
                policies = currentPolicies,
                deviceInstructions = if (needsAiReview) {
                    currentCredentials?.let { current ->
                        AuthorizationDeviceInstructionsCommitment(
                            deviceIdentityId = current.deviceIdentityId,
                            instructions = current.instructions,
                        )
                    }
                } else {
                    null
                },
            )
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
                    pairingProtocol.sealPairedResponse(
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
                signature != null -> SecretUseDecision.APPROVED
                denial != null -> SecretUseDecision.DENIED
                else -> null
            }
            val finalRequest = initialRequest.copy(
                state = reviewedRequestState(response != null).storedName,
                responseJson = response?.toString(),
                updatedAt = decidedAt,
            )
            val finalAuthentication = initialAuthentication.copy(
                decision = automaticDecision?.storedName,
                approvalEvaluationJson = evaluation?.let { json.encodeToString(it) },
                completionReason = denial?.first?.wireName,
                completionMessage = denial?.second,
                decidedAt = automaticDecision?.let { decidedAt },
            )
            if (pendingRequestId == null) {
                val inserted = if (automaticDecision == SecretUseDecision.APPROVED) {
                    dao.insertSshAuthenticationRequestIfAuthorized(
                        request = finalRequest,
                        authentication = finalAuthentication,
                        client = pairing.copy(
                            clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                            lastSeenAt = now,
                            updatedAt = now,
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
                            updatedAt = now,
                        ),
                        requestPsk = acceptedSecrets.requestPsk,
                        currentClientPsk = acceptedSecrets.currentClientPsk,
                        previousClientPsk = acceptedSecrets.previousClientPsk,
                    )
                    true
                }
                if (automaticDecision == SecretUseDecision.APPROVED && !inserted) {
                    return null
                }
                recordSshAuthenticationRequested(
                    pairing = pairing,
                    relayRequestId = relayRequestId,
                    secretName = contents.secret,
                    username = messageDetails.username,
                )
            } else if (automaticDecision == SecretUseDecision.APPROVED) {
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
        } finally {
            pendingRequestId?.let { aiReviewInFlight.update { it - relayRequestId } }
        }
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
        val reviewer = approvalReviewer
            ?: return AiReview(failure = AiReviewFailure.UNAVAILABLE)
        val request = approvalReviewRequest(
            client = pairing,
            contents = contents,
            description = description,
            values = values,
            evaluation = evaluation,
            policies = policies,
            deviceInstructions = credentials.instructions,
        )
        return performAiReview(reviewer, credentials, request)
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
        val reviewer = approvalReviewer
            ?: return AiReview(failure = AiReviewFailure.UNAVAILABLE)
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
            decodeApprovalReviewSecretFacts(stored)
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
        return performAiReview(reviewer, credentials, request)
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
        val reviewer = approvalReviewer
            ?: return AiReview(failure = AiReviewFailure.UNAVAILABLE)
        val elapsedSeconds = parentElapsedSeconds ?: return AiReview(
            decision = AiReviewDecision.ASK_USER,
            explanation = "The relative timing of the parent invocation is unavailable.",
        )
        val invocationSecrets = invocation.providedSecretsJson?.let {
            decodeApprovalReviewSecretFacts(it)
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
        return performAiReview(reviewer, credentials, request)
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
        val contents = runCatching {
            secretListProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val secretMetadata = secrets.listSecretsForClient()
        val responsePlaintext = secretListProtocol.response(
            secretMetadata.associateTo(sortedMapOf()) { secret ->
                secret.name to SecretListSecret(
                    description = secret.description,
                    type = secret.type,
                    environmentVariableNames = secret.environmentVariableNames,
                    sshPublicKey = secret.sshPublicKey,
                )
            },
        )
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
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
        val now = currentTimeMillis()
        dao.insertSecretListRequest(
            request = InboxRequestEntity(
                id = relayRequestId,
                parentRequestId = null,
                deviceIdentityId = pairing.deviceIdentityId,
                clientId = pairing.clientId,
                clientNameSnapshot = pairing.name,
                clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                kind = RequestKind.SECRET_LIST.storedName,
                state = InboxRequestState.WAITING.storedName,
                listed = false,
                requestJson = requestPayload.toString(),
                responseJson = response.toString(),
                completionJson = null,
                error = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = null,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            client = pairing.copy(
                clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                lastSeenAt = now,
                updatedAt = now,
            ),
            requestPsk = acceptedSecrets.requestPsk,
            currentClientPsk = acceptedSecrets.currentClientPsk,
            previousClientPsk = acceptedSecrets.previousClientPsk,
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.SECRET_LIST_RECEIVED,
                outcome = AuditOutcome.RECEIVED,
                subject = "${secretMetadata.size} secrets",
                detail = secretMetadata.joinToString { "${it.name} (${it.type})" },
                clientId = pairing.clientId,
                clientName = pairing.auditClientName(),
                relayRequestId = relayRequestId,
            ),
        )
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
        val contents = runCatching {
            secretUploadProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val now = currentTimeMillis()
        val prepared = when (val uploadContents = contents.contents) {
            is SecretUploadContents.Environment -> {
                val upload = EnvironmentSecretUpload(
                    mode = contents.mode,
                    name = contents.name,
                    descriptionProvided = contents.descriptionProvided,
                    description = contents.description,
                    variables = uploadContents.variables,
                )
                when (val validation = secrets.describeEnvironmentSecretUpload(upload)) {
                    is EnvironmentSecretUploadResult.Valid -> PreparedSecretUpload(
                        type = ENVIRONMENT_SECRET_TYPE,
                        summary = SecretUploadSummarySnapshot(
                            variableNames = uploadContents.variables.keys.sorted(),
                            addedVariables = validation.summary.addedVariables,
                            changedVariables = validation.summary.changedVariables,
                            unchangedVariables = validation.summary.unchangedVariables,
                            removedVariables = validation.summary.removedVariables,
                        ),
                        environmentVariables = uploadContents.variables.map { (name, value) ->
                            encryptSecretUploadVariable(
                                relayRequestId = relayRequestId,
                                clientId = pairing.clientId,
                                name = name,
                                value = value,
                                sensitive = validation.summary.variableSensitivity.getValue(name),
                            )
                        },
                    )
                    is EnvironmentSecretUploadResult.Invalid -> PreparedSecretUpload(
                        type = ENVIRONMENT_SECRET_TYPE,
                        summary = SecretUploadSummarySnapshot(
                            variableNames = uploadContents.variables.keys.sorted(),
                        ),
                        error = validation.message,
                    )
                }
            }
            is SecretUploadContents.Ssh -> {
                val parsedKey = uploadContents.privateKey?.let { encoded ->
                    runCatching {
                        withContext(cryptographyDispatcher) {
                            sshKeys.importOpenSshPrivateKey(encoded)
                        }
                    }
                }
                val parseError = parsedKey?.exceptionOrNull()?.message
                val privateKey = parsedKey?.getOrNull()
                if (parseError != null) {
                    PreparedSecretUpload(
                        type = SSH_SECRET_TYPE,
                        summary = SecretUploadSummarySnapshot(),
                        error = parseError,
                    )
                } else {
                    val upload = SshSecretUpload(
                        mode = contents.mode,
                        name = contents.name,
                        descriptionProvided = contents.descriptionProvided,
                        description = contents.description,
                        privateKey = privateKey,
                    )
                    when (val validation = secrets.describeSshSecretUpload(upload)) {
                        is SshSecretUploadResult.Valid -> PreparedSecretUpload(
                            type = SSH_SECRET_TYPE,
                            summary = SecretUploadSummarySnapshot(
                                publicKey = validation.summary.publicKey,
                                fingerprint = validation.summary.fingerprint,
                                previousPublicKey = validation.summary.previousPublicKey,
                                previousFingerprint = validation.summary.previousFingerprint,
                                keyChanged = validation.summary.keyChanged,
                            ),
                            sshKey = privateKey?.let {
                                encryptSecretUploadSshKey(
                                    relayRequestId,
                                    pairing.clientId,
                                    it,
                                )
                            },
                        )
                        is SshSecretUploadResult.Invalid -> PreparedSecretUpload(
                            type = SSH_SECRET_TYPE,
                            summary = SecretUploadSummarySnapshot(),
                            error = validation.message,
                        )
                    }
                }
            }
        }
        val responsePlaintext = if (prepared.error == null) {
            secretUploadProtocol.receivedResponse()
        } else {
            secretUploadProtocol.rejectedResponse(prepared.error)
        }
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
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
        val initialDecision = prepared.error?.let {
            SecretUploadRequestState.REJECTED.storedName
        }
        val initialLifecycle = secretUploadLifecycle(
            decision = initialDecision,
            transportFinished = false,
        )
        dao.insertSecretUploadRequest(
            request = InboxRequestEntity(
                id = relayRequestId,
                parentRequestId = null,
                deviceIdentityId = pairing.deviceIdentityId,
                clientId = pairing.clientId,
                clientNameSnapshot = pairing.name,
                clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                kind = RequestKind.SECRET_UPLOAD.storedName,
                state = initialLifecycle.state.storedName,
                listed = prepared.error == null,
                requestJson = requestPayload.toString(),
                responseJson = response.toString(),
                completionJson = null,
                error = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = null,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            secretUpload = SecretUploadRequestEntity(
                requestId = relayRequestId,
                decision = initialDecision,
                mode = contents.mode.wireName,
                uploadedName = contents.name,
                approvedName = null,
                descriptionProvided = contents.descriptionProvided,
                description = contents.description,
                secretType = prepared.type,
                summaryJson = json.encodeToString(prepared.summary),
                intakeError = prepared.error,
                decidedAt = if (prepared.error != null) now else null,
            ),
            client = pairing.copy(
                clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                lastSeenAt = now,
                updatedAt = now,
            ),
            environmentVariables = prepared.environmentVariables,
            sshKey = prepared.sshKey,
            requestPsk = acceptedSecrets.requestPsk,
            currentClientPsk = acceptedSecrets.currentClientPsk,
            previousClientPsk = acceptedSecrets.previousClientPsk,
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.SECRET_UPLOAD_RECEIVED,
                outcome = if (prepared.error == null) {
                    AuditOutcome.RECEIVED
                } else {
                    AuditOutcome.REJECTED
                },
                decisionSource = prepared.error?.let { AuditDecisionSource.VALIDATION },
                subject = contents.name,
                detail = prepared.error,
                clientId = pairing.clientId,
                clientName = pairing.auditClientName(),
                relayRequestId = relayRequestId,
            ),
        )
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
            pairingProtocol.sealPairedResponse(
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
                updatedAt = now,
                completedAt = null,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            requestPsk = acceptedSecrets.requestPsk,
            client = pairing.copy(
                desiredRelayClientState = RelayClientState.REVOKED.wireName,
                lastSeenAt = now,
                updatedAt = now,
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
            pairingProtocol.sealPairedResponse(
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
                    updatedAt = now,
                    completedAt = now,
                    requestAcknowledgedAt = null,
                    responseAcknowledgedAt = null,
                    completionAcknowledgedAt = null,
                ),
                client = pairing.copy(lastSeenAt = now, updatedAt = now),
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
            pairingProtocol.sealPairedResponse(
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
                    updatedAt = now,
                    completedAt = now,
                    requestAcknowledgedAt = null,
                    responseAcknowledgedAt = null,
                    completionAcknowledgedAt = null,
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
            updatedAt = now,
            completedAt = null,
            requestAcknowledgedAt = null,
            responseAcknowledgedAt = null,
            completionAcknowledgedAt = null,
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
        executableMode = contents.operation.executableMode,
        stdinKind = contents.operation.stdin,
        stdoutKind = contents.operation.stdout,
        stderrKind = contents.operation.stderr,
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
                    updatedAt = now,
                ),
                attempt = pairing,
            )
            return null
        }
        val metadata = runCatching {
            pairingProtocol.decodeClientMetadata(result.applicationPlaintext)
        }.getOrNull()
        val choices = pairingProtocol.sasChoices(result.sas)
        val completedAttempt = withEncryptedPendingPsk(
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
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                error = if (metadata == null) {
                    "The client details could not be read, but the security code is valid."
                } else {
                    null
                },
                updatedAt = now,
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
        val relayClientState = pairing.relayClientState?.toRelayClientState() ?: return null
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
            updatedAt = now,
            completedAt = now,
            requestAcknowledgedAt = null,
            responseAcknowledgedAt = null,
            completionAcknowledgedAt = null,
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
                updatedAt = now,
            )
        }
        val clientPsk = client?.let { encryptClientPsk(it, opened.clientPsk, now) }
        dao.finishPairing(
            rootRequest = rootRequest.copy(
                state = InboxRequestState.COMPLETED.storedName,
                listed = false,
                updatedAt = now,
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
        return when (request.kind) {
            RequestKind.PAIRING.storedName -> {
                val pairing = dao.getPairingAttempt(request.id) ?: return null
                val credentials = credentialsForRequest(request, activeCredentials) ?: return null
                processInitialCompletion(credentials, request, pairing, completion)
            }
            RequestKind.PAIRING_FINISH.storedName -> {
                processFinishCompletion(activeCredentials, request, completion)
            }
            RequestKind.SECRET_USE.storedName -> {
                processInvocationCompletion(activeCredentials, request, completion)
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
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                completionAcknowledgedAt = null,
                updatedAt = now,
            ),
        )
        return ProcessedRelayMessage()
    }

    private suspend fun processSecretListCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        if (request.completedAt != null) return ProcessedRelayMessage()
        val plaintext = openStoredCompletion(activeCredentials, request, completion) ?: return null
        val decoded = runCatching { secretListProtocol.decodeCompletion(plaintext) }
        val valid = decoded.getOrNull() == request.clientSoftwareJson?.let(::decodeClientSoftware)
        val error = if (valid) null else decoded.exceptionOrNull()?.message
            ?: "Secret list completion did not match the request."
        val now = currentTimeMillis()
        dao.updateSecretListRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                completionAcknowledgedAt = null,
                error = error,
                updatedAt = now,
                completedAt = now,
            ),
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.SECRET_LIST_COMPLETED,
                outcome = if (valid) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                subject = "Secret list",
                detail = error,
                clientId = request.clientId,
                clientName = request.clientNameSnapshot,
                relayRequestId = request.id,
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
        if (request.completionJson != null || request.error != null) {
            return ProcessedRelayMessage()
        }
        val plaintext = openStoredCompletion(activeCredentials, request, completion) ?: return null
        val decoded = runCatching { secretUploadProtocol.decodeCompletion(plaintext) }
        val result = decoded.getOrNull()
        val expectedResult = if (upload.intakeError == null) {
            SecretUploadProtocol.RESULT_RECEIVED
        } else {
            SecretUploadProtocol.RESULT_REJECTED
        }
        val valid = result != null &&
            result.clientSoftware == request.clientSoftwareJson?.let(::decodeClientSoftware) &&
            result.result == expectedResult &&
            result.message == upload.intakeError
        val error = if (valid) null else decoded.exceptionOrNull()?.message
            ?: "The client completion did not match the received upload."
        val now = currentTimeMillis()
        val lifecycle = secretUploadLifecycle(upload.decision, transportFinished = true)
        dao.updateSecretUploadRequest(
            request = request.copy(
                state = lifecycle.state.storedName,
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                completionAcknowledgedAt = null,
                error = error,
                updatedAt = now,
                completedAt = request.completedAt ?: if (lifecycle.completed) now else null,
            ),
            secretUpload = upload,
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.SECRET_UPLOAD_COMPLETED,
                outcome = if (valid) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                subject = upload.uploadedName,
                detail = error,
                clientId = request.clientId,
                clientName = request.clientNameSnapshot,
                relayRequestId = request.id,
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
                    responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                    completionAcknowledgedAt = null,
                    updatedAt = now,
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
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                completionAcknowledgedAt = if (completion == null) {
                    request.completionAcknowledgedAt
                } else {
                    null
                },
                updatedAt = now,
                completedAt = now,
            ),
        )
        val client = dao.getClient(request.clientId)
        if (client != null && client.desiredRelayClientState != RelayClientState.REVOKED.wireName) {
            dao.revokeClient(
                client.copy(
                    desiredRelayClientState = RelayClientState.REVOKED.wireName,
                    updatedAt = now,
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

    private suspend fun processInvocationCompletion(
        activeCredentials: RelayDeviceCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): ProcessedRelayMessage? {
        val secretUseRequest = dao.getSecretUseRequest(request.id) ?: return null
        if (request.completedAt != null) return ProcessedRelayMessage()
        val plaintext = openStoredCompletion(activeCredentials, request, completion) ?: return null
        val decoded = runCatching { invocationProtocol.decodeCompletion(plaintext) }
        val now = currentTimeMillis()
        val completionResult = decoded.getOrNull()
        val softwareMatches = completionResult?.clientSoftware ==
            request.clientSoftwareJson?.let(::decodeClientSoftware)
        val valid = softwareMatches && when (completionResult) {
            is InvocationCompletion.Approved -> {
                secretUseRequest.decision == SecretUseDecision.APPROVED.storedName
            }
            is InvocationCompletion.Denied -> {
                if (secretUseRequest.decision != SecretUseDecision.DENIED.storedName) {
                    false
                } else {
                    val expectedReason = secretUseRequest.completionReason
                        ?: InvocationDenialReason.USER_DENIED.wireName
                    val expectedMessage = secretUseRequest.completionMessage
                        ?: SECRET_USE_DENIAL_MESSAGE
                    completionResult.reason == expectedReason &&
                        completionResult.message == expectedMessage
                }
            }
            is InvocationCompletion.Aborted -> true
            null -> false
        }
        val error = if (valid) null else decoded.exceptionOrNull()?.message
            ?: "Secret use completion did not match the device decision."
        dao.updateSecretUseRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                completionAcknowledgedAt = null,
                error = error,
                updatedAt = now,
                completedAt = now,
            ),
            secretUseRequest = secretUseRequest.copy(
                completionResult = when (completionResult) {
                    is InvocationCompletion.Approved -> {
                        InvocationCompletionResult.APPROVED.storedName
                    }
                    is InvocationCompletion.Denied -> {
                        InvocationCompletionResult.DENIED.storedName
                    }
                    is InvocationCompletion.Aborted -> {
                        InvocationCompletionResult.ABORTED.storedName
                    }
                    null -> null
                },
                completionReason = when (completionResult) {
                    is InvocationCompletion.Denied -> completionResult.reason
                    is InvocationCompletion.Aborted -> completionResult.reason
                    else -> null
                },
                completionMessage = when (completionResult) {
                    is InvocationCompletion.Denied -> completionResult.message
                    is InvocationCompletion.Aborted -> completionResult.message
                    else -> null
                },
            ),
        )
        audit.record(
            AuditRecord(
                type = AuditEventType.SECRET_USE_COMPLETED,
                outcome = when {
                    !valid -> AuditOutcome.FAILED
                    completionResult is InvocationCompletion.Approved -> AuditOutcome.COMPLETED
                    completionResult is InvocationCompletion.Denied -> AuditOutcome.DENIED
                    else -> AuditOutcome.ABORTED
                },
                subject = decodeStringList(secretUseRequest.secretsJson).joinToString(),
                detail = if (!valid) error else {
                    when (completionResult) {
                        is InvocationCompletion.Denied -> completionResult.message
                        is InvocationCompletion.Aborted -> completionResult.message
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
                gitSign.decision == SecretUseDecision.APPROVED.storedName
            }
            is GitSignCompletion.Denied -> {
                if (gitSign.decision != SecretUseDecision.DENIED.storedName) {
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
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                completionAcknowledgedAt = null,
                error = error,
                updatedAt = now,
                completedAt = now,
            ),
            gitSignRequest = gitSign.copy(
                completionResult = when (completionResult) {
                    is GitSignCompletion.Approved -> GitSignCompletionResult.APPROVED.storedName
                    is GitSignCompletion.Denied -> GitSignCompletionResult.DENIED.storedName
                    is GitSignCompletion.Aborted -> GitSignCompletionResult.ABORTED.storedName
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
                authentication.decision == SecretUseDecision.APPROVED.storedName
            is SshAuthenticationCompletion.Denied -> {
                authentication.decision == SecretUseDecision.DENIED.storedName &&
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
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                completionAcknowledgedAt = null,
                error = error,
                updatedAt = now,
                completedAt = now,
            ),
            authentication = authentication.copy(
                completionResult = when (completionResult) {
                    is SshAuthenticationCompletion.Approved ->
                        SshAuthenticationCompletionResult.APPROVED.storedName
                    is SshAuthenticationCompletion.Denied ->
                        SshAuthenticationCompletionResult.DENIED.storedName
                    is SshAuthenticationCompletion.Aborted ->
                        SshAuthenticationCompletionResult.ABORTED.storedName
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
                responseAcknowledgedAt = finishRequest.responseAcknowledgedAt ?: now,
                completionAcknowledgedAt = null,
                updatedAt = now,
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

    private suspend fun credentialsForClient(
        client: ClientEntity,
        active: RelayDeviceCredentials,
    ): RelayDeviceCredentials? {
        if (client.deviceIdentityId == active.deviceIdentityId) return active
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
        val clientPsk = decryptRequestPsk(request) ?: return null
        return runCatching {
            pairingProtocol.openPairedCompletion(
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

    private suspend fun encryptSecretUploadVariable(
        relayRequestId: String,
        clientId: String,
        name: String,
        value: String,
        sensitive: Boolean,
    ): SecretUploadEnvironmentVariableEntity {
        val id = newId()
        val key = keyManager.activeKey(VaultKeyPurpose.SECRET_VALUES)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = secretUploadVariableLocation(id, relayRequestId, clientId, name),
                plaintext = value.encodeToByteArray(),
            )
        }
        return SecretUploadEnvironmentVariableEntity(
            id = id,
            requestId = relayRequestId,
            name = name,
            sensitive = sensitive,
            encryptedValue = encrypted,
        )
    }

    private fun secretUploadVariableLocation(
        id: String,
        requestId: String,
        clientId: String,
        name: String,
    ) = EncryptionLocation(
        recordType = "secret_upload_variable",
        recordId = id,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("request_id", requestId),
            EncryptionBinding("client_id", clientId),
            EncryptionBinding("name", name),
        ),
    )

    private suspend fun decryptSecretUploadEnvironmentVariable(
        request: InboxRequestEntity,
        variable: SecretUploadEnvironmentVariableEntity,
    ): DecryptionResult = withContext(cryptographyDispatcher) {
        encryption.decrypt(
            encrypted = variable.encryptedValue,
            location = secretUploadVariableLocation(
                variable.id,
                request.id,
                request.clientId,
                variable.name,
            ),
        )
    }

    private suspend fun encryptSecretUploadSshKey(
        relayRequestId: String,
        clientId: String,
        privateKey: SshPrivateKey,
    ): SecretUploadSshKeyEntity {
        val key = keyManager.activeKey(VaultKeyPurpose.SECRET_VALUES)
        val privateKeyFormat = privateKey.algorithm.privateKeyFormat()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = secretUploadSshKeyLocation(
                    relayRequestId,
                    clientId,
                    privateKey.algorithm.storedName,
                    privateKeyFormat,
                    privateKey.publicKey,
                ),
                plaintext = privateKey.privateKey,
            )
        }
        return SecretUploadSshKeyEntity(
            requestId = relayRequestId,
            algorithm = privateKey.algorithm.storedName,
            publicKey = privateKey.publicKey.copyOf(),
            comment = privateKey.comment,
            privateKeyFormat = privateKeyFormat,
            encryptedPrivateKey = encrypted,
        )
    }

    private suspend fun decryptSecretUploadSshKey(
        request: InboxRequestEntity,
        key: SecretUploadSshKeyEntity,
    ): DecryptionResult {
        val algorithm = SshKeyAlgorithm.fromStoredName(key.algorithm)
            ?: return DecryptionResult.UnsupportedFormat
        if (key.privateKeyFormat != algorithm.privateKeyFormat()) {
            return DecryptionResult.UnsupportedFormat
        }
        return withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = key.encryptedPrivateKey,
                location = secretUploadSshKeyLocation(
                    request.id,
                    request.clientId,
                    key.algorithm,
                    key.privateKeyFormat,
                    key.publicKey,
                ),
            )
        }
    }

    private fun secretUploadSshKeyLocation(
        relayRequestId: String,
        clientId: String,
        algorithm: String,
        privateKeyFormat: String,
        publicKey: ByteArray,
    ) = EncryptionLocation(
        recordType = "secret_upload_ssh_key",
        recordId = relayRequestId,
        fieldName = "private_key",
        bindings = listOf(
            EncryptionBinding("client_id", clientId),
            EncryptionBinding("algorithm", algorithm),
            EncryptionBinding("private_key_format", privateKeyFormat),
            EncryptionBinding("public_key", Base64.getEncoder().encodeToString(publicKey)),
        ),
    )

    private suspend fun acceptedRequestPsks(
        client: ClientEntity,
        relayRequestId: String,
        opened: OpenedPairedRequest,
        currentClientPsk: ByteArray,
        now: Long,
    ): AcceptedRequestPsks {
        val requestPsk = encryptRequestPsk(
            deviceIdentityId = client.deviceIdentityId,
            clientId = client.clientId,
            relayRequestId = relayRequestId,
            clientPsk = opened.clientPsk,
        )
        if (opened.keySource != PairedRequestKeySource.ROTATED) {
            return AcceptedRequestPsks(requestPsk, null, null)
        }
        checkNotNull(dao.getClientPsk(client.clientId, ClientPskSlot.CURRENT.storedName)) {
            "The current client key is unavailable"
        }
        return AcceptedRequestPsks(
            requestPsk = requestPsk,
            currentClientPsk = encryptClientPsk(
                client = client,
                clientPsk = opened.clientPsk,
                now = now,
                slot = ClientPskSlot.CURRENT,
            ),
            previousClientPsk = encryptClientPsk(
                client = client,
                clientPsk = currentClientPsk,
                now = now,
                slot = ClientPskSlot.PREVIOUS,
            ),
        )
    }

    private suspend fun encryptRequestPsk(
        deviceIdentityId: String,
        clientId: String,
        relayRequestId: String,
        clientPsk: ByteArray,
    ): RequestPskEntity {
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val key = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = requestPskLocation(relayRequestId, clientId, deviceIdentityId),
                plaintext = clientPsk,
            )
        }
        return RequestPskEntity(
            requestId = relayRequestId,
            encryptedPsk = encrypted,
        )
    }

    private suspend fun decryptRequestPsk(
        request: InboxRequestEntity,
    ): ByteArray? {
        val secret = dao.getRequestPsk(request.id) ?: return null
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = secret.encryptedPsk,
                location = requestPskLocation(
                    request.id,
                    request.clientId,
                    request.deviceIdentityId,
                ),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == CLIENT_PSK_BYTES
        }
    }

    private fun requestPskLocation(
        requestId: String,
        clientId: String,
        deviceIdentityId: String,
    ) = EncryptionLocation(
        recordType = "request_psk",
        recordId = requestId,
        fieldName = "client_psk",
        bindings = listOf(
            EncryptionBinding("client_id", clientId),
            EncryptionBinding("device_identity_id", deviceIdentityId),
        ),
    )

    private suspend fun encryptClientPsk(
        client: ClientEntity,
        clientPsk: ByteArray,
        now: Long,
        slot: ClientPskSlot = ClientPskSlot.CURRENT,
    ): ClientPskEntity {
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val key = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = clientPskLocation(client, slot),
                plaintext = clientPsk,
            )
        }
        return ClientPskEntity(
            clientId = client.clientId,
            slot = slot.storedName,
            encryptedPsk = encrypted,
            storedAt = now,
        )
    }

    private suspend fun decryptClientPsk(
        client: ClientEntity,
        slot: ClientPskSlot = ClientPskSlot.CURRENT,
    ): ByteArray? {
        val secret = dao.getClientPsk(client.clientId, slot.storedName) ?: return null
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = secret.encryptedPsk,
                location = clientPskLocation(client, slot),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == CLIENT_PSK_BYTES
        }
    }

    private suspend fun decryptPreviousClientPsk(client: ClientEntity): ByteArray? {
        val previous = dao.getClientPsk(client.clientId, ClientPskSlot.PREVIOUS.storedName)
            ?: return null
        if (!previousPskEligible(previous.storedAt, currentTimeMillis())) return null
        return decryptClientPsk(client, ClientPskSlot.PREVIOUS)
    }

    private fun clientPskLocation(
        client: ClientEntity,
        slot: ClientPskSlot,
    ) = EncryptionLocation(
        recordType = "client_psk",
        recordId = client.clientId,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("slot", slot.storedName),
            EncryptionBinding("device_identity_id", client.deviceIdentityId),
        ),
    )

    private suspend fun withEncryptedPendingPsk(
        attempt: PairingAttemptEntity,
        request: InboxRequestEntity,
        clientPsk: ByteArray,
    ): PairingAttemptEntity {
        require(attempt.requestId == request.id)
        require(attempt.clientId == request.clientId)
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val key = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = pendingPskLocation(attempt, request),
                plaintext = clientPsk,
            )
        }
        return attempt.copy(pendingPsk = encrypted)
    }

    private suspend fun decryptPendingPsk(
        attempt: PairingAttemptEntity,
        request: InboxRequestEntity,
    ): ByteArray? {
        require(attempt.requestId == request.id)
        require(attempt.clientId == request.clientId)
        val pendingPsk = attempt.pendingPsk ?: return null
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = pendingPsk,
                location = pendingPskLocation(attempt, request),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == CLIENT_PSK_BYTES
        }
    }

    private fun pendingPskLocation(
        attempt: PairingAttemptEntity,
        request: InboxRequestEntity,
    ) = EncryptionLocation(
        recordType = "pairing_attempt",
        recordId = attempt.requestId,
        fieldName = "pending_client_psk",
        bindings = listOf(
            EncryptionBinding("client_id", attempt.clientId),
            EncryptionBinding("device_identity_id", request.deviceIdentityId),
        ),
    )

    private fun String.toInboxRequestState(): InboxRequestState =
        checkNotNull(InboxRequestState.entries.find { it.storedName == this })

    private fun String.toPairingState(): PairingState =
        checkNotNull(PairingState.entries.find { it.storedName == this })

    private fun String.toRelayClientState(): RelayClientState =
        checkNotNull(RelayClientState.entries.find { it.wireName == this })

    private fun InboxRequestEntity.toSecretUseRequestState(): SecretUseRequestState =
        secretUseRequestState(state.toInboxRequestState(), error)

    private fun InboxRequestState.toSecretUseRequestState(error: String?): SecretUseRequestState =
        secretUseRequestState(this, error)

    private fun String.toSecretUseDecision(): SecretUseDecision =
        checkNotNull(SecretUseDecision.entries.find { it.storedName == this })

    private fun String.toInvocationCompletionResult(): InvocationCompletionResult =
        checkNotNull(InvocationCompletionResult.entries.find { it.storedName == this })

    private fun InboxRequestEntity.toGitSignRequestState(): GitSignRequestState =
        state.toInboxRequestState().toGitSignRequestState(error)

    private fun InboxRequestState.toGitSignRequestState(error: String?): GitSignRequestState =
        when (this) {
            InboxRequestState.REVIEWING,
            InboxRequestState.ACTION_REQUIRED,
            -> GitSignRequestState.APPROVAL_PENDING
            InboxRequestState.WAITING -> GitSignRequestState.WAITING_FOR_COMPLETION
            InboxRequestState.COMPLETED -> if (error == null) {
                GitSignRequestState.COMPLETED
            } else {
                GitSignRequestState.VERIFICATION_FAILED
            }
        }

    private fun String.toGitSignCompletionResult(): GitSignCompletionResult =
        checkNotNull(GitSignCompletionResult.entries.find { it.storedName == this })

    private fun InboxRequestEntity.toSshAuthenticationRequestState():
        SshAuthenticationRequestState =
        state.toInboxRequestState().toSshAuthenticationRequestState(error)

    private fun InboxRequestState.toSshAuthenticationRequestState(
        error: String?,
    ): SshAuthenticationRequestState = when (this) {
        InboxRequestState.REVIEWING,
        InboxRequestState.ACTION_REQUIRED,
        -> SshAuthenticationRequestState.APPROVAL_PENDING
        InboxRequestState.WAITING -> SshAuthenticationRequestState.WAITING_FOR_COMPLETION
        InboxRequestState.COMPLETED -> if (error == null) {
            SshAuthenticationRequestState.COMPLETED
        } else {
            SshAuthenticationRequestState.VERIFICATION_FAILED
        }
    }

    private fun String.toSshAuthenticationCompletionResult(): SshAuthenticationCompletionResult =
        checkNotNull(SshAuthenticationCompletionResult.entries.find { it.storedName == this })

    private fun InboxRequestEntity.toSecretUploadRequestState(
        decision: String?,
    ): SecretUploadRequestState = when {
        state.toInboxRequestState() == InboxRequestState.COMPLETED && error != null ->
            SecretUploadRequestState.VERIFICATION_FAILED
        decision == null -> SecretUploadRequestState.REVIEW_PENDING
        decision == SecretUploadRequestState.APPROVED.storedName ->
            SecretUploadRequestState.APPROVED
        decision == SecretUploadRequestState.REJECTED.storedName ->
            SecretUploadRequestState.REJECTED
        else -> error("Unknown secret upload decision: $decision")
    }

    private fun encodeStringList(values: List<String>): String =
        json.encodeToString(STRING_LIST_SERIALIZER, values)

    private fun decodeStringList(value: String): List<String> =
        json.decodeFromString(STRING_LIST_SERIALIZER, value)

    private fun decodeUploadSummary(value: String): SecretUploadSummarySnapshot =
        json.decodeFromString(value)

    private fun invocationTokenHash(token: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token)

    private fun SecretValues.toResponseSecret(): InvocationResponseSecret = when (this) {
        is SecretValues.Environment -> InvocationResponseSecret.Environment(
            description,
            environment,
        )
        is SecretValues.Ssh -> InvocationResponseSecret.Ssh(description, publicKey)
    }

    private fun decodeApprovalEvaluation(value: String): ApprovalEvaluation? =
        runCatching { json.decodeFromString<ApprovalEvaluation>(value) }.getOrNull()

    private fun decodeEnvironmentReviewFacts(
        value: String?,
    ): Map<String, Map<String, String?>> = value?.let { encoded ->
        decodeApprovalReviewSecretFacts(encoded)
            ?.mapNotNull { (secretName, facts) ->
                (facts as? ApprovalReviewEnvironmentSecretFacts)
                    ?.let { environment ->
                        secretName to environment.environmentVariables.mapNotNull {
                                (source, variable) ->
                            val displayName = when (val destination = variable.destination) {
                                is ApprovalReviewEnvironmentDestination -> destination.name
                                ApprovalReviewStandardInputDestination -> source
                                else -> return@mapNotNull null
                            }
                            val factValue = variable.value
                            displayName to when (factValue) {
                                null, JsonNull -> null
                                else -> factValue.jsonPrimitive.content
                            }
                        }.toMap()
                    }
            }
            ?.toMap()
    }.orEmpty()

    private fun decodeApprovalReviewSecretFacts(
        encoded: String,
    ): Map<String, ApprovalReviewSecretFacts>? {
        val current = runCatching {
            json.decodeFromString<Map<String, ApprovalReviewSecretFacts>>(encoded)
        }.getOrNull()
        if (current != null) return current
        return runCatching {
            json.parseToJsonElement(encoded).jsonObject.mapValues { (_, facts) ->
                val objectValue = facts.jsonObject
                when (objectValue.getValue("type").jsonPrimitive.content) {
                    ENVIRONMENT_SECRET_TYPE -> {
                        val variables = objectValue.getValue("environment_variables").jsonObject
                        ApprovalReviewEnvironmentSecretFacts(
                            variables.mapValues { (name, variable) ->
                                ApprovalReviewEnvironmentVariableFacts(
                                    destination = ApprovalReviewEnvironmentDestination(name),
                                    value = variable,
                                )
                            },
                        )
                    }
                    SSH_SECRET_TYPE -> ApprovalReviewSshSecretFacts(
                        provides = objectValue.getValue("provides").jsonPrimitive.content,
                    )
                    else -> error("Unsupported stored review secret type")
                }
            }
        }.getOrNull()
    }

    private fun encodeClientSoftware(value: ClientSoftware): String = json.encodeToString(value)

    private fun decodeClientSoftware(value: String): ClientSoftware? =
        runCatching { json.decodeFromString<ClientSoftware>(value) }.getOrNull()

    private companion object {
        const val CLIENT_PSK_BYTES = 32
        const val IDEMPOTENCY_RETENTION_MILLIS = 25 * 60 * 60 * 1_000L
        const val SECRET_USE_DENIAL_MESSAGE = "Denied on device."
        const val GIT_SIGN_DENIAL_MESSAGE = "Git signature denied on device."
        const val SSH_AUTHENTICATION_DENIAL_MESSAGE = "SSH authentication denied on device."
        const val MAX_AI_REVIEW_GIT_CONTENT_BYTES = 128 * 1024
        const val DECISION_SOURCE_USER = "user"
        const val DECISION_SOURCE_POLICY = "policy"
        const val DECISION_SOURCE_AI = "ai"
        const val DECISION_SOURCE_NON_SENSITIVE = "non_sensitive"
        const val DECISION_SOURCE_TEMPORARY_ACCESS = "temporary_access"
        const val DECISION_SOURCE_MIXED = "mixed"
        const val TEMPORARY_ACCESS_DURATION_MILLIS = 4 * 60 * 60 * 1_000L
        val STRING_LIST_SERIALIZER = ListSerializer(String.serializer())
    }

    private fun SecretApprovalPolicy.toRequestedSecretApproval(): RequestedSecretApproval {
        val activeTemporaryAccess = temporaryAccessExpiresAt?.takeIf {
            mode == SecretApprovalMode.TEMPORARY || mode == SecretApprovalMode.ASK_AI
        }
        return RequestedSecretApproval(
            id = secretId,
            name = secretName,
            defaultAction = when {
                activeTemporaryAccess != null -> ApprovalAction.APPROVE
                mode == SecretApprovalMode.DENY -> ApprovalAction.DENY
                mode == SecretApprovalMode.ASK_ME -> ApprovalAction.ASK_ME
                mode == SecretApprovalMode.TEMPORARY -> ApprovalAction.ASK_ME
                mode == SecretApprovalMode.ASK_AI -> ApprovalAction.ASK_AI
                else -> ApprovalAction.APPROVE
            },
            temporaryAccessEligible = mode == SecretApprovalMode.TEMPORARY ||
                mode == SecretApprovalMode.ASK_AI,
            temporaryAccessExpiresAt = activeTemporaryAccess,
            revision = revision,
        )
    }

    private fun RequestedSecretDescription.authorizationCommitment(
        policies: List<SecretApprovalPolicy>,
        deviceInstructions: AuthorizationDeviceInstructionsCommitment? = null,
    ): AuthorizationCommitment = AuthorizationCommitment(
        secretRevisions = reviewMetadata.associate { secret ->
            secret.id to secret.revision
        },
        policies = policies.associate { policy ->
            policy.secretId to AuthorizationPolicyCommitment(
                mode = policy.mode.storedName,
                temporaryAccessExpiresAt = policy.temporaryAccessExpiresAt,
            )
        },
        deviceInstructions = deviceInstructions,
    )

    private fun RequestedSecretDescription.hasSameSecretRevisions(
        other: RequestedSecretDescription,
    ): Boolean = reviewMetadata.associate { it.id to it.revision } ==
        other.reviewMetadata.associate { it.id to it.revision }

    private fun ApprovalEvaluation.hasSameSecretPolicies(other: ApprovalEvaluation): Boolean =
        secrets.size == other.secrets.size && secrets.zip(other.secrets).all { (stored, current) ->
            stored.secretId == current.secretId &&
                stored.secretName == current.secretName &&
                stored.action == current.action &&
                stored.temporaryAccessEligible == current.temporaryAccessEligible &&
                stored.temporaryAccessExpiresAt == current.temporaryAccessExpiresAt &&
                stored.revision == current.revision
        }
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

private fun String.toAuditDecisionSource(): AuditDecisionSource = when (this) {
    "user" -> AuditDecisionSource.USER
    "policy" -> AuditDecisionSource.APPROVAL_SETTINGS
    "ai" -> AuditDecisionSource.AI_REVIEW
    "non_sensitive" -> AuditDecisionSource.NON_SENSITIVE
    "temporary_access" -> AuditDecisionSource.TEMPORARY_ACCESS
    "mixed" -> AuditDecisionSource.MIXED
    else -> error("Unknown decision source: $this")
}

private enum class RequestKind(val storedName: String) {
    PAIRING("pairing"),
    PAIRING_FINISH("pairing_finish"),
    SECRET_USE("secret_use"),
    GIT_SIGN("git_sign"),
    SSH_AUTHENTICATE("ssh_authenticate"),
    SECRET_LIST("secret_list"),
    SECRET_UPLOAD("secret_upload"),
    PAIRING_REMOVE("pairing_remove"),
    UNKNOWN("unknown"),
}

private enum class ClientPskSlot(val storedName: String) {
    CURRENT("current"),
    PREVIOUS("previous"),
}

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

private const val PREVIOUS_PSK_OVERLAP_MILLIS = 10 * 60 * 1_000L
