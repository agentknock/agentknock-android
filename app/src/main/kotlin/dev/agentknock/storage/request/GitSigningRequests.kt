package dev.agentknock.storage.request

import dev.agentknock.protocol.GitSignCompletion
import dev.agentknock.protocol.GitSignProtocol
import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.ApprovalPolicyEvaluator
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.secret.GitSignatureResult
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.TemporaryAccessOperation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal const val GIT_SIGN_DENIAL_MESSAGE = "Git signature denied on device."
internal const val GIT_SIGN_POLICY_DENIAL_MESSAGE =
    "Approval settings denied use of the SSH key."
private const val GIT_SIGN_COMPLETION_ABORTED_DETAIL =
    "Git signing was aborted by the client."
private const val GIT_SIGN_COMPLETION_VERIFICATION_ERROR =
    "Git signing completion could not be verified."

private data class GitSignTemporaryGrant(
    val policy: SecretApprovalPolicy,
    val expiresAt: Long,
    val evaluation: ApprovalEvaluation,
)

/** Owns durable Git-signing transitions without owning transport, envelopes, or AI calls. */
internal class GitSigningRequests(
    private val dao: RequestDao,
    private val secrets: SecretRepository,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val gitSignProtocol: GitSignProtocol = GitSignProtocol(),
    private val json: Json = Json,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun approve(
        requestId: String,
        allowTemporaryAccess: Boolean,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): GitSignDecisionResult {
        val request = dao.getRequestById(requestId) ?: return GitSignDecisionResult.NotFound
        val gitSign = dao.getGitSignRequest(requestId) ?: return GitSignDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            gitSign.decision != null
        ) {
            return GitSignDecisionResult.NotPending
        }
        val invocation = request.parentRequestId?.let { dao.getSecretUseRequest(it) }
            ?: return GitSignDecisionResult.InvocationUnavailable
        val description = secrets.describeRequestedSecrets(listOf(gitSign.secretName))
        val policy = secrets.approvalPoliciesForNames(
            listOf(gitSign.secretName),
            request.clientId,
            TemporaryAccessOperation.GIT_SIGN,
        ).singleOrNull() ?: return GitSignDecisionResult.ApprovalChanged
        val currentEvaluation = ApprovalPolicyEvaluator.evaluate(
            listOf(policy.toRequestedSecretApproval()),
        )
        val authorization = description.authorizationCommitment(listOf(policy))
        if (currentEvaluation.secrets.single().action == ApprovalAction.DENY) {
            val response = sealResponse(
                request,
                gitSignProtocol.deniedResponse(
                    InvocationDenialReason.POLICY_DENIED,
                    GIT_SIGN_POLICY_DENIAL_MESSAGE,
                ),
            ) ?: return GitSignDecisionResult.PairingUnavailable
            return persistDecision(
                request = request,
                decision = ApprovalDecision.DENIED,
                response = response,
                decisionSource = DECISION_SOURCE_POLICY,
                denialReason = InvocationDenialReason.POLICY_DENIED,
                denialMessage = GIT_SIGN_POLICY_DENIAL_MESSAGE,
                authorization = authorization,
            )
        }
        val storedEvaluation = gitSign.approvalEvaluationJson?.let(::decodeApprovalEvaluation)
        if (
            storedEvaluation == null ||
            !storedEvaluation.hasSameSecretPolicies(currentEvaluation)
        ) {
            return refreshApprovalEvaluation(
                requestId,
                json.encodeToString(currentEvaluation),
            )
        }
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
            GitSignatureResult.SecretUnavailable -> return GitSignDecisionResult.SecretUnavailable
            GitSignatureResult.SecretCorrupted -> return GitSignDecisionResult.SecretCorrupted
            GitSignatureResult.UnsupportedEncryption -> {
                return GitSignDecisionResult.UnsupportedEncryption
            }
        }
        val grant = if (allowTemporaryAccess) {
            prepareTemporaryGrant(policy, checkNotNull(storedEvaluation))
                ?: return GitSignDecisionResult.TemporaryAccessUnavailable
        } else {
            null
        }
        val response = sealResponse(
            request,
            gitSignProtocol.approvedResponse(signature),
        ) ?: return GitSignDecisionResult.PairingUnavailable
        return if (grant == null) {
            persistDecision(
                request = request,
                decision = ApprovalDecision.APPROVED,
                response = response,
                decisionSource = DECISION_SOURCE_USER,
                authorization = authorization,
            )
        } else {
            persistTemporaryDecision(request, gitSign, response, authorization, grant)
        }
    }

    suspend fun deny(
        requestId: String,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): GitSignDecisionResult {
        val request = dao.getRequestById(requestId) ?: return GitSignDecisionResult.NotFound
        val gitSign = dao.getGitSignRequest(requestId) ?: return GitSignDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            gitSign.decision != null
        ) {
            return GitSignDecisionResult.NotPending
        }
        if (request.parentRequestId?.let { dao.getSecretUseRequest(it) } == null) {
            return GitSignDecisionResult.InvocationUnavailable
        }
        val response = sealResponse(
            request,
            gitSignProtocol.deniedResponse(
                InvocationDenialReason.USER_DENIED,
                GIT_SIGN_DENIAL_MESSAGE,
            ),
        ) ?: return GitSignDecisionResult.PairingUnavailable
        return persistDecision(
            request = request,
            decision = ApprovalDecision.DENIED,
            response = response,
            decisionSource = DECISION_SOURCE_USER,
            denialReason = InvocationDenialReason.USER_DENIED,
            denialMessage = GIT_SIGN_DENIAL_MESSAGE,
        )
    }

    suspend fun receive(
        request: InboxRequestEntity,
        gitSign: GitSignRequestEntity,
        client: ClientEntity,
        acceptedPsks: AcceptedRequestPsks,
        authorization: AuthorizationCommitment?,
        automaticDecisionAudit: AuditRecord?,
    ): ConditionalRequestUpdate {
        val conditional = gitSign.decision != null
        require(conditional == (automaticDecisionAudit != null)) {
            "An automatic Git-signing decision and its audit must be persisted together"
        }
        require(
            automaticDecisionAudit == null ||
                automaticDecisionAudit.type == AuditEventType.GIT_SIGN_DECIDED,
        ) { "Unexpected automatic Git-signing audit type" }
        require(!conditional || authorization != null) {
            "An automatic Git-signing decision must bind its authorization state"
        }
        return writeTransaction.execute {
            val authorized = !conditional || dao.authorizationMatches(
                checkNotNull(authorization),
                request.clientId,
                TemporaryAccessOperation.GIT_SIGN.storedName,
                currentTimeMillis(),
            )
            val storedRequest = if (authorized) request else request.copy(
                state = InboxRequestState.ACTION_REQUIRED.storedName,
                responseJson = null,
            )
            val storedGitSign = if (authorized) gitSign else gitSign.copy(
                decision = null,
                decisionSource = null,
                completionResult = null,
                completionReason = null,
                completionMessage = null,
                decidedAt = null,
            )
            val storedClient = if (authorized) {
                client
            } else {
                val current = dao.getClient(client.clientId)
                    ?: return@execute ConditionalRequestUpdate.UNAVAILABLE
                if (
                    current.relayClientState != RelayClientState.ACTIVE.wireName ||
                    current.desiredRelayClientState?.let {
                        it != RelayClientState.ACTIVE.wireName
                    } == true
                ) {
                    return@execute ConditionalRequestUpdate.UNAVAILABLE
                }
                current.copy(
                    clientSoftwareJson = client.clientSoftwareJson,
                    lastSeenAt = client.lastSeenAt,
                )
            }
            dao.insertGitSignRequest(
                request = storedRequest,
                gitSignRequest = storedGitSign,
                client = storedClient,
                requestPsk = acceptedPsks.requestPsk,
                currentClientPsk = acceptedPsks.currentClientPsk,
                previousClientPsk = acceptedPsks.previousClientPsk,
            )
            audit.append(
                buildList {
                    add(receivedAudit(storedRequest, storedGitSign))
                    if (authorized) automaticDecisionAudit?.let(::add)
                },
                request.receivedAt,
            )
            if (authorized) {
                ConditionalRequestUpdate.APPLIED
            } else {
                ConditionalRequestUpdate.ACTION_REQUIRED
            }
        }
    }

    suspend fun finishAiReview(
        request: InboxRequestEntity,
        gitSign: GitSignRequestEntity,
        authorization: AuthorizationCommitment,
        aiReviewAudit: AuditRecord,
        automaticDecisionAudit: AuditRecord?,
    ): ConditionalRequestUpdate {
        val conditional = gitSign.decision != null
        require(aiReviewAudit.type == AuditEventType.GIT_SIGN_AI_REVIEWED) {
            "Unexpected Git-signing AI-review audit type"
        }
        require(conditional == (automaticDecisionAudit != null)) {
            "An automatic Git-signing decision and its audit must be persisted together"
        }
        require(
            automaticDecisionAudit == null ||
                automaticDecisionAudit.type == AuditEventType.GIT_SIGN_DECIDED,
        ) { "Unexpected automatic Git-signing audit type" }
        return writeTransaction.execute {
            val occurredAt = currentTimeMillis()
            val currentRequest = dao.getRequestById(request.id)
                ?: return@execute ConditionalRequestUpdate.UNAVAILABLE
            val currentGitSign = dao.getGitSignRequest(request.id)
                ?: return@execute ConditionalRequestUpdate.UNAVAILABLE
            if (
                currentRequest.state != InboxRequestState.REVIEWING.storedName ||
                currentGitSign.decision != null
            ) {
                return@execute ConditionalRequestUpdate.UNAVAILABLE
            }
            val result = dao.updateGitSignRequestIfAuthorized(
                request = currentRequest.copy(
                    state = request.state,
                    responseJson = request.responseJson,
                    responseOutboxFinished = if (request.responseJson != null) {
                        false
                    } else {
                        currentRequest.responseOutboxFinished
                    },
                ),
                gitSignRequest = currentGitSign.copy(
                    approvalEvaluationJson = gitSign.approvalEvaluationJson,
                    decision = gitSign.decision,
                    decisionSource = gitSign.decisionSource,
                    completionReason = gitSign.completionReason,
                    completionMessage = gitSign.completionMessage,
                    decidedAt = gitSign.decidedAt,
                ),
                authorization = authorization,
                clientId = currentRequest.clientId,
                operation = TemporaryAccessOperation.GIT_SIGN.storedName,
                expectedState = InboxRequestState.REVIEWING.storedName,
                now = occurredAt,
            )
            if (result != ConditionalRequestUpdate.UNAVAILABLE) {
                audit.append(
                    buildList {
                        add(
                            if (result == ConditionalRequestUpdate.ACTION_REQUIRED) {
                                aiReviewAudit.copy(outcome = AuditOutcome.DEFERRED)
                            } else {
                                aiReviewAudit
                            },
                        )
                        if (result == ConditionalRequestUpdate.APPLIED) {
                            automaticDecisionAudit?.let(::add)
                        }
                    },
                    occurredAt,
                )
            }
            result
        }
    }

    suspend fun complete(
        request: InboxRequestEntity,
        completion: JsonElement,
        openCompletion: suspend () -> CompletionOpenResult,
    ): Boolean {
        val completionJson = completion.toString()
        terminalCompletionHandled(request.id)?.let { return it }
        val opened = openCompletion()
        val completionResult = (opened as? CompletionOpenResult.Opened)?.plaintext?.let {
            decodeWireCompletionOrNull { gitSignProtocol.decodeCompletion(it) }
        }
        return writeTransaction.execute {
            val currentRequest = dao.getRequestById(request.id) ?: return@execute false
            val gitSign = dao.getGitSignRequest(request.id) ?: return@execute false
            if (currentRequest.exchangeEndedAt != null) {
                return@execute true
            }
            if (opened == CompletionOpenResult.RetryLater) return@execute false
            val softwareMatches = completionResult?.clientSoftware ==
                currentRequest.clientSoftwareJson?.let(::decodeClientSoftware)
            val valid = softwareMatches && when (completionResult) {
                is GitSignCompletion.Approved -> {
                    gitSign.decision == ApprovalDecision.APPROVED.storedName
                }
                is GitSignCompletion.Denied -> {
                    gitSign.decision == ApprovalDecision.DENIED.storedName &&
                        completionResult.reason == (
                            gitSign.completionReason ?:
                            InvocationDenialReason.USER_DENIED.wireName
                        ) && completionResult.message == (
                            gitSign.completionMessage ?: GIT_SIGN_DENIAL_MESSAGE
                        )
                }
                is GitSignCompletion.Aborted -> true
                null -> false
            }
            val error = if (valid) null else GIT_SIGN_COMPLETION_VERIFICATION_ERROR
            val now = currentTimeMillis()
            dao.updateGitSignRequest(
                request = currentRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    completionJson = completionJson.takeIf {
                        opened is CompletionOpenResult.Opened
                    },
                    responseOutboxFinished = true,
                    error = error,
                    completedAt = now,
                    exchangeEndedAt = now,
                ),
                gitSignRequest = gitSign.copy(
                    completionResult = when {
                        !valid -> null
                        completionResult is GitSignCompletion.Approved -> {
                            ApprovalCompletionResult.APPROVED.storedName
                        }
                        completionResult is GitSignCompletion.Denied -> {
                            ApprovalCompletionResult.DENIED.storedName
                        }
                        completionResult is GitSignCompletion.Aborted -> {
                            ApprovalCompletionResult.ABORTED.storedName
                        }
                        else -> null
                    },
                    completionReason = if (
                        valid && completionResult is GitSignCompletion.Denied
                    ) {
                        gitSign.completionReason ?: InvocationDenialReason.USER_DENIED.wireName
                    } else {
                        null
                    },
                    completionMessage = if (
                        valid && completionResult is GitSignCompletion.Denied
                    ) {
                        gitSign.completionMessage ?: GIT_SIGN_DENIAL_MESSAGE
                    } else {
                        null
                    },
                ),
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.GIT_SIGN_COMPLETED,
                        outcome = when {
                            !valid -> AuditOutcome.FAILED
                            completionResult is GitSignCompletion.Approved -> {
                                AuditOutcome.COMPLETED
                            }
                            completionResult is GitSignCompletion.Denied -> AuditOutcome.DENIED
                            else -> AuditOutcome.ABORTED
                        },
                        subject = gitSign.secretName,
                        detail = when {
                            !valid -> GIT_SIGN_COMPLETION_VERIFICATION_ERROR
                            completionResult is GitSignCompletion.Denied -> {
                                gitSign.completionMessage ?: GIT_SIGN_DENIAL_MESSAGE
                            }
                            completionResult is GitSignCompletion.Aborted -> {
                                GIT_SIGN_COMPLETION_ABORTED_DETAIL
                            }
                            else -> null
                        },
                        clientId = currentRequest.clientId,
                        clientName = currentRequest.clientNameSnapshot,
                        relayRequestId = currentRequest.id,
                    ),
                ),
                now,
            )
            dao.deleteEndedRequestPsk(currentRequest.id)
            true
        }
    }

    private suspend fun terminalCompletionHandled(requestId: String): Boolean? =
        writeTransaction.execute {
            val terminal = dao.getRequestById(requestId) ?: return@execute null
            if (dao.getGitSignRequest(requestId) == null || terminal.exchangeEndedAt == null) {
                return@execute null
            }
            true
        }

    suspend fun expire(request: InboxRequestEntity, message: String, now: Long) {
        writeTransaction.execute {
            val currentRequest = dao.getRequestById(request.id) ?: return@execute
            if (currentRequest.exchangeEndedAt != null) return@execute
            if (currentRequest.completedAt != null) {
                dao.updateRequest(
                    currentRequest.copy(
                        exchangeEndedAt = now,
                        responseOutboxFinished = true,
                    ),
                )
                dao.deleteEndedRequestPsk(request.id)
                return@execute
            }
            val gitSign = dao.getGitSignRequest(request.id) ?: return@execute
            dao.updateGitSignRequest(
                currentRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    error = message,
                    completedAt = now,
                    exchangeEndedAt = now,
                    responseOutboxFinished = true,
                ),
                gitSign,
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.GIT_SIGN_COMPLETED,
                        outcome = AuditOutcome.FAILED,
                        subject = gitSign.secretName,
                        detail = message,
                        clientId = currentRequest.clientId,
                        clientName = currentRequest.clientNameSnapshot,
                        relayRequestId = currentRequest.id,
                    ),
                ),
                now,
            )
            dao.deleteEndedRequestPsk(currentRequest.id)
        }
    }

    private suspend fun persistDecision(
        request: InboxRequestEntity,
        decision: ApprovalDecision,
        response: JsonElement,
        decisionSource: String,
        denialReason: InvocationDenialReason? = null,
        denialMessage: String? = null,
        authorization: AuthorizationCommitment? = null,
    ): GitSignDecisionResult {
        require(decision != ApprovalDecision.APPROVED || authorization != null) {
            "An approved Git signature must bind its authorization state"
        }
        require(decision != ApprovalDecision.DENIED || denialReason != null) {
            "A denied Git signature must record its reason"
        }
        require(decision != ApprovalDecision.DENIED || denialMessage != null) {
            "A denied Git signature must record its message"
        }
        return writeTransaction.execute {
            val now = currentTimeMillis()
            val currentRequest = dao.getRequestById(request.id)
                ?: return@execute GitSignDecisionResult.NotFound
            val currentGitSign = dao.getGitSignRequest(request.id)
                ?: return@execute GitSignDecisionResult.NotFound
            if (
                currentRequest.state != InboxRequestState.ACTION_REQUIRED.storedName ||
                currentGitSign.decision != null
            ) {
                return@execute GitSignDecisionResult.NotPending
            }
            val updatedRequest = currentRequest.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = response.toString(),
                responseOutboxFinished = false,
            )
            val updatedGitSign = currentGitSign.copy(
                decision = decision.storedName,
                decisionSource = decisionSource,
                completionReason = denialReason?.wireName,
                completionMessage = denialMessage,
                decidedAt = now,
            )
            val result = if (authorization == null) {
                dao.updateGitSignRequest(updatedRequest, updatedGitSign)
                ConditionalRequestUpdate.APPLIED
            } else {
                dao.updateGitSignRequestIfAuthorized(
                    request = updatedRequest,
                    gitSignRequest = updatedGitSign,
                    authorization = authorization,
                    clientId = currentRequest.clientId,
                    operation = TemporaryAccessOperation.GIT_SIGN.storedName,
                    expectedState = InboxRequestState.ACTION_REQUIRED.storedName,
                    now = now,
                )
            }
            if (result == ConditionalRequestUpdate.APPLIED) {
                audit.append(
                    listOf(
                        decisionAudit(
                            currentRequest,
                            currentGitSign,
                            decision,
                            decisionSource,
                        ),
                    ),
                    now,
                )
            }
            when (result) {
                ConditionalRequestUpdate.APPLIED -> GitSignDecisionResult.Decided
                ConditionalRequestUpdate.ACTION_REQUIRED,
                ConditionalRequestUpdate.UNAVAILABLE,
                -> GitSignDecisionResult.ApprovalChanged
            }
        }
    }

    private fun prepareTemporaryGrant(
        policy: SecretApprovalPolicy,
        storedEvaluation: ApprovalEvaluation,
    ): GitSignTemporaryGrant? {
        val secretEvaluation = storedEvaluation.secrets.singleOrNull() ?: return null
        val aiCanEscalateToTemporaryAccess =
            storedEvaluation.aiReview?.decision == AiReviewDecision.ASK_USER ||
                storedEvaluation.aiReview?.failure != null ||
                storedEvaluation.aiReview == null
        val eligible = secretEvaluation.temporaryAccessExpiresAt == null && when (policy.mode) {
            SecretApprovalMode.TEMPORARY -> secretEvaluation.action == ApprovalAction.ASK_ME
            SecretApprovalMode.ASK_AI -> {
                secretEvaluation.action == ApprovalAction.ASK_AI && aiCanEscalateToTemporaryAccess
            }
            else -> false
        }
        if (!eligible) return null
        val expiresAt = currentTimeMillis() + TEMPORARY_ACCESS_DURATION_MILLIS
        return GitSignTemporaryGrant(
            policy = policy,
            expiresAt = expiresAt,
            evaluation = storedEvaluation.copy(
                secrets = listOf(secretEvaluation.copy(temporaryAccessExpiresAt = expiresAt)),
            ),
        )
    }

    private suspend fun persistTemporaryDecision(
        request: InboxRequestEntity,
        gitSign: GitSignRequestEntity,
        response: JsonElement,
        authorization: AuthorizationCommitment,
        grant: GitSignTemporaryGrant,
    ): GitSignDecisionResult = writeTransaction.execute {
        val now = currentTimeMillis()
        val currentRequest = dao.getRequestById(request.id)
            ?: return@execute GitSignDecisionResult.NotFound
        val currentGitSign = dao.getGitSignRequest(request.id)
            ?: return@execute GitSignDecisionResult.NotFound
        if (
            currentRequest.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            currentGitSign.decision != null
        ) {
            return@execute GitSignDecisionResult.NotPending
        }
        if (currentGitSign.approvalEvaluationJson != gitSign.approvalEvaluationJson) {
            return@execute GitSignDecisionResult.ApprovalChanged
        }
        if (
            !dao.authorizationMatches(
                authorization,
                currentRequest.clientId,
                TemporaryAccessOperation.GIT_SIGN.storedName,
                now,
            )
        ) {
            return@execute GitSignDecisionResult.ApprovalChanged
        }
        val temporaryAccessStarted = secrets.allowTemporaryAccess(
            policies = listOf(grant.policy),
            clientId = currentRequest.clientId,
            operation = TemporaryAccessOperation.GIT_SIGN,
            expiresAt = grant.expiresAt,
        )
        val decisionSource = if (temporaryAccessStarted) {
            DECISION_SOURCE_TEMPORARY_ACCESS
        } else {
            DECISION_SOURCE_USER
        }
        dao.updateGitSignRequest(
            currentRequest.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = response.toString(),
                responseOutboxFinished = false,
            ),
            currentGitSign.copy(
                approvalEvaluationJson = if (temporaryAccessStarted) {
                    json.encodeToString(grant.evaluation)
                } else {
                    currentGitSign.approvalEvaluationJson
                },
                decision = ApprovalDecision.APPROVED.storedName,
                decisionSource = decisionSource,
                completionReason = null,
                completionMessage = null,
                decidedAt = now,
            ),
        )
        audit.append(
            listOf(
                decisionAudit(
                    currentRequest,
                    currentGitSign,
                    ApprovalDecision.APPROVED,
                    decisionSource,
                ),
            ),
            now,
        )
        if (temporaryAccessStarted) {
            GitSignDecisionResult.Decided
        } else {
            GitSignDecisionResult.TemporaryAccessNotStarted
        }
    }

    private suspend fun refreshApprovalEvaluation(
        requestId: String,
        approvalEvaluationJson: String,
    ): GitSignDecisionResult = writeTransaction.execute {
        val currentRequest = dao.getRequestById(requestId)
            ?: return@execute GitSignDecisionResult.NotFound
        val currentGitSign = dao.getGitSignRequest(requestId)
            ?: return@execute GitSignDecisionResult.NotFound
        if (
            currentRequest.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            currentGitSign.decision != null
        ) {
            return@execute GitSignDecisionResult.NotPending
        }
        dao.updateGitSignRequest(
            currentRequest,
            currentGitSign.copy(approvalEvaluationJson = approvalEvaluationJson),
        )
        GitSignDecisionResult.ApprovalChanged
    }

    private fun receivedAudit(request: InboxRequestEntity, gitSign: GitSignRequestEntity) =
        AuditRecord(
            type = AuditEventType.GIT_SIGN_RECEIVED,
            outcome = AuditOutcome.RECEIVED,
            subject = gitSign.secretName,
            clientId = request.clientId,
            clientName = request.clientNameSnapshot,
            relayRequestId = request.id,
        )

    private fun decisionAudit(
        request: InboxRequestEntity,
        gitSign: GitSignRequestEntity,
        decision: ApprovalDecision,
        decisionSource: String,
    ) = AuditRecord(
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
    )

    private fun decodeClientSoftware(value: String) = runCatching {
        storedJson.decodeFromString<dev.agentknock.protocol.ClientSoftware>(value)
    }.getOrNull()
}
