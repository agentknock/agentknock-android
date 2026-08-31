package dev.agentknock.storage.request

import dev.agentknock.presentation.describeGitSigningContent
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.presentation.renderSingleLineText
import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.GitSignRepository
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.protocol.SshAuthenticationMethod
import dev.agentknock.protocol.SshSignatureAlgorithm
import dev.agentknock.relay.ApprovalReviewEnvironmentDestination
import dev.agentknock.relay.ApprovalReviewEnvironmentSecretFacts
import dev.agentknock.relay.ApprovalReviewStandardInputDestination
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

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

internal enum class ApprovalRequestState(val storedName: String) {
    APPROVAL_PENDING("approval_pending"),
    WAITING_FOR_COMPLETION("waiting_for_completion"),
    COMPLETED("completed"),
    VERIFICATION_FAILED("verification_failed"),
}

internal enum class ApprovalDecision(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
}

internal enum class ApprovalCompletionResult(val storedName: String) {
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

internal enum class RequestKind(val storedName: String) {
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

internal fun approvalRequestState(
    state: InboxRequestState,
    error: String?,
): ApprovalRequestState = when (state) {
    InboxRequestState.REVIEWING,
    InboxRequestState.ACTION_REQUIRED,
    -> ApprovalRequestState.APPROVAL_PENDING
    InboxRequestState.WAITING -> ApprovalRequestState.WAITING_FOR_COMPLETION
    InboxRequestState.COMPLETED -> if (error == null) {
        ApprovalRequestState.COMPLETED
    } else {
        ApprovalRequestState.VERIFICATION_FAILED
    }
}

internal data class InboxRequestSummary(
    val id: String,
    val kind: InboxRequestKind,
    val state: InboxRequestState,
    val status: InboxRequestStatus,
    val title: String,
    val clientName: String,
    val secretNames: List<String>,
    val listSummary: String?,
    val command: String?,
    val arguments: List<String>,
    val receivedAt: Long,
    val completedAt: Long?,
    val userDecisionAvailable: Boolean = true,
)

internal sealed interface InboxRequestStatus {
    data class Pairing(val state: PairingState) : InboxRequestStatus

    data class Approval(
        val state: ApprovalRequestState,
        val decision: ApprovalDecision?,
        val completionResult: ApprovalCompletionResult?,
        val completionReason: String?,
    ) : InboxRequestStatus

    data class SecretUpload(val state: SecretUploadRequestState) : InboxRequestStatus
}

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
    val content: InboxRequestContent,
    val userDecisionAvailable: Boolean = true,
)

internal sealed interface InboxRequestContent {
    data class Pairing(val details: PairingRequestDetails) : InboxRequestContent
    data class SecretUse(val details: SecretUseRequestDetails) : InboxRequestContent
    data class SecretUpload(val details: SecretUploadRequestDetails) : InboxRequestContent
    data class GitSign(val details: GitSignRequestDetails) : InboxRequestContent
    data class SshAuthentication(val details: SshAuthenticationRequestDetails) : InboxRequestContent
}

internal data class SshAuthenticationRequestDetails(
    val state: ApprovalRequestState,
    val decision: ApprovalDecision?,
    val completionResult: ApprovalCompletionResult?,
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
    val state: ApprovalRequestState,
    val decision: ApprovalDecision?,
    val completionResult: ApprovalCompletionResult?,
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
    val state: ApprovalRequestState,
    val decision: ApprovalDecision?,
    val decisionSource: String?,
    val approvalEvaluation: ApprovalEvaluation?,
    val completionResult: ApprovalCompletionResult?,
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

private data class RequestDetailRows(
    val pairing: PairingAttemptEntity?,
    val secretUse: SecretUseRequestEntity?,
    val secretUpload: SecretUploadRequestEntity?,
    val gitSign: GitSignRequestEntity?,
    val sshAuthentication: SshAuthenticationRequestEntity?,
)

internal class RequestInbox(
    private val dao: RequestDao,
    private val pairingProtocol: PairingProtocol = PairingProtocol(),
    private val json: Json = Json,
) {
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
                            status = InboxRequestStatus.Pairing(pairing.state.toPairingState()),
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
                            status = InboxRequestStatus.Approval(
                                state = request.toApprovalRequestState(),
                                decision = secretUse.decision?.toApprovalDecision(),
                                completionResult = secretUse.completionResult
                                    ?.toApprovalCompletionResult(),
                                completionReason = secretUse.completionReason,
                            ),
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
                            status = InboxRequestStatus.Approval(
                                state = request.toApprovalRequestState(),
                                decision = gitSign.decision?.toApprovalDecision(),
                                completionResult = gitSign.completionResult
                                    ?.toApprovalCompletionResult(),
                                completionReason = gitSign.completionReason,
                            ),
                            title = signingContent.requestTitle,
                            clientName = request.clientNameSnapshot,
                            secretNames = listOf(gitSign.secretName),
                            listSummary = signingContent.message,
                            command = invocation.command,
                            arguments = decodeStringList(invocation.argumentsJson),
                            receivedAt = request.receivedAt,
                            completedAt = request.completedAt,
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
                            status = InboxRequestStatus.Approval(
                                state = request.toApprovalRequestState(),
                                decision = authentication.decision?.toApprovalDecision(),
                                completionResult = authentication.completionResult
                                    ?.toApprovalCompletionResult(),
                                completionReason = authentication.completionReason,
                            ),
                            title = "SSH authentication",
                            clientName = request.clientNameSnapshot,
                            secretNames = listOf(authentication.secretName),
                            listSummary = "${authentication.username} · ${authentication.algorithm}",
                            command = invocation.command,
                            arguments = decodeStringList(invocation.argumentsJson),
                            receivedAt = request.receivedAt,
                            completedAt = request.completedAt,
                        )
                    }
                    RequestKind.SECRET_UPLOAD.storedName -> {
                        val upload = secretUploadByRequest[request.id] ?: return@mapNotNull null
                        InboxRequestSummary(
                            id = request.id,
                            kind = InboxRequestKind.SECRET_UPLOAD,
                            state = request.state.toInboxRequestState(),
                            status = InboxRequestStatus.SecretUpload(
                                request.toSecretUploadRequestState(upload.decision),
                            ),
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
        }.map { requests ->
            requests.map { request ->
                request.copy(
                    userDecisionAvailable = request.state == InboxRequestState.ACTION_REQUIRED,
                )
            }
        }
    }

    fun observeRequest(id: String): Flow<InboxRequestDetails?> {
        val rows = combine(
            dao.observePairingAttempt(id),
            dao.observeSecretUseRequest(id),
            dao.observeSecretUploadRequest(id),
            dao.observeGitSignRequest(id),
            dao.observeSshAuthenticationRequest(id),
        ) { pairing, secretUse, secretUpload, gitSign, sshAuthentication ->
            RequestDetailRows(pairing, secretUse, secretUpload, gitSign, sshAuthentication)
        }
        return combine(
            dao.observeRequest(id),
            rows,
            dao.observeSecretUploadEnvironmentVariables(id),
        ) { request, detailRows, uploadVariables ->
            request ?: return@combine null
            val state = request.state.toInboxRequestState()
            val clientSoftware = request.clientSoftwareJson?.let(::decodeClientSoftware)
            val content = when (request.kind) {
                RequestKind.PAIRING.storedName -> {
                    val pairing = detailRows.pairing ?: return@combine null
                    InboxRequestContent.Pairing(
                        PairingRequestDetails(
                            pairingState = pairing.state.toPairingState(),
                            clientName = pairing.friendlyName ?: pairing.hostname
                                ?: pairing.platform ?: "Unknown client",
                            pairingAddress = pairing.pairingAddress,
                            clientId = pairing.clientId,
                            sasOptions = listOfNotNull(
                                pairing.sasOption0,
                                pairing.sasOption1,
                                pairing.sasOption2,
                            ).map(pairingProtocol::formatSas),
                            clientSoftware = clientSoftware,
                            platform = pairing.platform,
                            architecture = pairing.architecture,
                            hostname = pairing.hostname,
                            machineId = pairing.machineId,
                            osVersion = pairing.osVersion,
                            error = request.error,
                            decidedAt = pairing.decidedAt,
                        ),
                    )
                }
                RequestKind.SECRET_USE.storedName -> {
                    val secretUse = detailRows.secretUse ?: return@combine null
                    InboxRequestContent.SecretUse(
                        SecretUseRequestDetails(
                            state = request.toApprovalRequestState(),
                            decision = secretUse.decision?.toApprovalDecision(),
                            decisionSource = secretUse.decisionSource,
                            approvalEvaluation = secretUse.approvalEvaluationJson
                                ?.let(::decodeApprovalEvaluation),
                            completionResult = secretUse.completionResult
                                ?.toApprovalCompletionResult(),
                            completionReason = secretUse.completionReason,
                            completionMessage = secretUse.completionMessage,
                            secrets = decodeStringList(secretUse.secretsJson),
                            secretDetails = json.decodeFromString(secretUse.secretDetailsJson),
                            environmentVariables = decodeEnvironmentReviewFacts(
                                secretUse.providedSecretsJson,
                            ),
                            missingSecrets = decodeStringList(secretUse.missingSecretsJson),
                            reason = secretUse.reason,
                            command = secretUse.command,
                            arguments = decodeStringList(secretUse.argumentsJson),
                            workingDirectory = secretUse.workingDirectory,
                            executablePath = secretUse.executablePath,
                            executableHash = secretUse.executableHash,
                            executableMode = secretUse.executableMode,
                            stdinKind = secretUse.stdinKind,
                            stdoutKind = secretUse.stdoutKind,
                            stderrKind = secretUse.stderrKind,
                            launcherChain = decodeStringList(secretUse.launcherChainJson),
                            clientId = request.clientId,
                            clientName = request.clientNameSnapshot,
                            hostname = secretUse.hostname,
                            platform = secretUse.platform,
                            architecture = secretUse.architecture,
                            machineId = secretUse.machineId,
                            osVersion = secretUse.osVersion,
                            clientSoftware = clientSoftware,
                            error = request.error,
                            decidedAt = secretUse.decidedAt,
                        ),
                    )
                }
                RequestKind.SECRET_UPLOAD.storedName -> {
                    val upload = detailRows.secretUpload ?: return@combine null
                    val summary = decodeUploadSummary(upload.summaryJson)
                    InboxRequestContent.SecretUpload(
                        SecretUploadRequestDetails(
                            state = request.toSecretUploadRequestState(upload.decision),
                            mode = SecretUploadMode.entries.single {
                                it.wireName == upload.mode
                            },
                            uploadedName = upload.uploadedName,
                            approvedName = upload.approvedName,
                            descriptionProvided = upload.descriptionProvided,
                            description = upload.description,
                            secretType = upload.secretType,
                            variableNames = summary.variableNames,
                            variables = uploadVariables.map {
                                SecretUploadVariableDetails(it.id, it.name, it.sensitive)
                            },
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
                            clientSoftware = clientSoftware,
                            error = request.error ?: upload.intakeError,
                            decidedAt = upload.decidedAt,
                        ),
                    )
                }
                RequestKind.GIT_SIGN.storedName -> {
                    val gitSign = detailRows.gitSign ?: return@combine null
                    val parentId = request.parentRequestId ?: return@combine null
                    val invocation = dao.getSecretUseRequest(parentId) ?: return@combine null
                    val invocationRequest = dao.getRequestById(parentId) ?: return@combine null
                    InboxRequestContent.GitSign(
                        GitSignRequestDetails(
                            state = request.toApprovalRequestState(),
                            decision = gitSign.decision?.toApprovalDecision(),
                            completionResult = gitSign.completionResult
                                ?.toApprovalCompletionResult(),
                            completionReason = gitSign.completionReason,
                            completionMessage = gitSign.completionMessage,
                            secretName = gitSign.secretName,
                            message = gitSign.message,
                            repository = gitSign.repositoryJson?.let {
                                runCatching {
                                    json.decodeFromString<GitSignRepository>(it)
                                }.getOrNull()
                            },
                            approvalEvaluation = gitSign.approvalEvaluationJson
                                ?.let(::decodeApprovalEvaluation),
                            invocationRequestId = invocationRequest.id,
                            command = invocation.command,
                            arguments = decodeStringList(invocation.argumentsJson),
                            reason = invocation.reason,
                            clientId = invocationRequest.clientId,
                            clientName = invocationRequest.clientNameSnapshot,
                            clientSoftware = clientSoftware,
                            error = request.error,
                            decidedAt = gitSign.decidedAt,
                        ),
                    )
                }
                RequestKind.SSH_AUTHENTICATE.storedName -> {
                    val authentication = detailRows.sshAuthentication ?: return@combine null
                    val parentId = request.parentRequestId ?: return@combine null
                    val invocation = dao.getSecretUseRequest(parentId) ?: return@combine null
                    val invocationRequest = dao.getRequestById(parentId) ?: return@combine null
                    InboxRequestContent.SshAuthentication(
                        SshAuthenticationRequestDetails(
                            state = request.toApprovalRequestState(),
                            decision = authentication.decision?.toApprovalDecision(),
                            completionResult = authentication.completionResult
                                ?.toApprovalCompletionResult(),
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
                            clientSoftware = clientSoftware,
                            error = request.error,
                            decidedAt = authentication.decidedAt,
                        ),
                    )
                }
                else -> return@combine null
            }
            InboxRequestDetails(
                id = request.id,
                parentRequestId = request.parentRequestId,
                state = state,
                clientSoftware = clientSoftware,
                error = request.error,
                receivedAt = request.receivedAt,
                completedAt = request.completedAt,
                content = content,
                userDecisionAvailable = content is InboxRequestContent.Pairing ||
                    state == InboxRequestState.ACTION_REQUIRED,
            )
        }
    }

    fun observePendingNotifications(): Flow<List<RequestNotification>> =
        dao.observeActionRequiredRequests().map { requests ->
            requests.mapNotNull { notificationFor(it) }
        }

    private suspend fun notificationFor(
        request: InboxRequestEntity,
    ): RequestNotification? = when (request.kind) {
                RequestKind.SECRET_USE.storedName -> {
                    val secretUse = dao.getSecretUseRequest(request.id) ?: return null
                    if (secretUse.decision != null) return null
                    val secrets = decodeStringList(secretUse.secretsJson)
                    val command = renderShellCommand(
                        secretUse.command,
                        decodeStringList(secretUse.argumentsJson),
                    )
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
                        decisionAvailable = true,
                    )
                }
                RequestKind.GIT_SIGN.storedName -> {
                    val gitSign = dao.getGitSignRequest(request.id) ?: return null
                    if (gitSign.decision != null) return null
                    val parentId = request.parentRequestId ?: return null
                    val invocation = dao.getSecretUseRequest(parentId) ?: return null
                    val parentRequest = dao.getRequestById(parentId) ?: return null
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
                        decisionAvailable = true,
                    )
                }
                RequestKind.SSH_AUTHENTICATE.storedName -> {
                    val authentication = dao.getSshAuthenticationRequest(request.id)
                        ?: return null
                    if (authentication.decision != null) return null
                    val parentId = request.parentRequestId ?: return null
                    val invocation = dao.getSecretUseRequest(parentId) ?: return null
                    val parentRequest = dao.getRequestById(parentId) ?: return null
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
                        decisionAvailable = true,
                    )
                }
                RequestKind.PAIRING.storedName -> {
                    val pairing = dao.getPairingAttempt(request.id) ?: return null
                    RequestNotification(
                        requestId = request.id,
                        title = "Pairing requested",
                        summary = renderSingleLineText(
                            pairing.friendlyName ?: pairing.hostname
                                ?: pairing.platform ?: "Unknown client",
                        ),
                        details = listOf(
                            RequestNotificationDetail(
                                null,
                                when (pairing.state.toPairingState()) {
                                    PairingState.RECEIVING -> request.error
                                        ?: "Waiting for the client to complete the secure exchange."
                                    PairingState.SAS_VERIFICATION_PENDING ->
                                        "Open Agentknock and compare the security code."
                                    PairingState.RELAY_ACTIVATION_PENDING,
                                    PairingState.WAITING_FOR_FINISH,
                                    -> "The pairing is still waiting for the client and can be rejected."
                                    else -> "Open Agentknock to review this pairing."
                                },
                            ),
                        ),
                        decisionAvailable = false,
                    )
                }
                RequestKind.SECRET_UPLOAD.storedName -> {
                    val upload = dao.getSecretUploadRequest(request.id) ?: return null
                    if (upload.decision != null) return null
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

    private fun SecretUploadRequestEntity.listSummary(): String {
        val summary = decodeUploadSummary(summaryJson)
        if (secretType == SSH_SECRET_TYPE) return summary.fingerprint ?: "SSH key"
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

    private fun String.toInboxRequestState(): InboxRequestState =
        checkNotNull(InboxRequestState.entries.find { it.storedName == this })

    private fun String.toPairingState(): PairingState =
        checkNotNull(PairingState.entries.find { it.storedName == this })

    private fun InboxRequestEntity.toApprovalRequestState(): ApprovalRequestState =
        approvalRequestState(state.toInboxRequestState(), error)

    private fun String.toApprovalDecision(): ApprovalDecision =
        checkNotNull(ApprovalDecision.entries.find { it.storedName == this })

    private fun String.toApprovalCompletionResult(): ApprovalCompletionResult =
        checkNotNull(ApprovalCompletionResult.entries.find { it.storedName == this })

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

    private fun decodeStringList(value: String): List<String> =
        json.decodeFromString(STRING_LIST_SERIALIZER, value)

    private fun decodeUploadSummary(value: String): SecretUploadSummarySnapshot =
        json.decodeFromString(value)

    private fun decodeApprovalEvaluation(value: String): ApprovalEvaluation? =
        runCatching { json.decodeFromString<ApprovalEvaluation>(value) }.getOrNull()

    private fun decodeEnvironmentReviewFacts(
        value: String?,
    ): Map<String, Map<String, String?>> = value?.let { encoded ->
        json.decodeStoredApprovalReviewSecretFacts(encoded)
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

    private fun decodeClientSoftware(value: String): ClientSoftware? =
        runCatching { json.decodeFromString<ClientSoftware>(value) }.getOrNull()

    private companion object {
        val STRING_LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
