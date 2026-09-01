package dev.agentknock.storage.request

import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.protocol.SshAuthenticationCompletion
import dev.agentknock.protocol.SshAuthenticationProtocol
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
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SshAuthenticationSignatureResult
import dev.agentknock.storage.secret.SshKeyCodec
import dev.agentknock.storage.secret.TemporaryAccessOperation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal const val SSH_AUTHENTICATION_DENIAL_MESSAGE =
    "SSH authentication denied on device."
internal const val SSH_AUTHENTICATION_POLICY_DENIAL_MESSAGE =
    "Approval settings denied SSH authentication."
private const val SSH_AUTHENTICATION_COMPLETION_ABORTED_DETAIL =
    "SSH authentication was aborted by the client."
private const val SSH_AUTHENTICATION_COMPLETION_VERIFICATION_ERROR =
    "SSH authentication completion could not be verified."

private data class SshAuthenticationTemporaryGrant(
    val policy: SecretApprovalPolicy,
    val expiresAt: Long,
    val evaluation: ApprovalEvaluation,
)

private data class SshInvocationSnapshot(
    val requestId: String,
    val secretDetailsJson: String,
)

/** Owns durable SSH-authentication transitions without owning transport, envelopes, or AI calls. */
internal class SshAuthenticationRequests(
    private val dao: RequestDao,
    private val secrets: SecretRepository,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val sshKeys: SshKeyCodec = SshKeyCodec(),
    private val protocol: SshAuthenticationProtocol = SshAuthenticationProtocol(),
    private val json: Json = Json,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun approve(
        requestId: String,
        allowTemporaryAccess: Boolean,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): SshAuthenticationDecisionResult {
        val request = dao.getRequestById(requestId)
            ?: return SshAuthenticationDecisionResult.NotFound
        val authentication = dao.getSshAuthenticationRequest(requestId)
            ?: return SshAuthenticationDecisionResult.NotFound
        if (!request.isPending(authentication)) {
            return SshAuthenticationDecisionResult.NotPending
        }
        val message = authentication.message
            ?: return SshAuthenticationDecisionResult.InvalidMessage
        val invocation = invocationSnapshot(request)
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
        val authorization = description.authorizationCommitment(listOf(policy))
        if (currentEvaluation.secrets.single().action == ApprovalAction.DENY) {
            val response = sealResponse(
                request,
                protocol.deniedResponse(
                    InvocationDenialReason.POLICY_DENIED,
                    SSH_AUTHENTICATION_POLICY_DENIAL_MESSAGE,
                ),
            ) ?: return SshAuthenticationDecisionResult.PairingUnavailable
            return persistDecision(
                request = request,
                authentication = authentication,
                invocation = invocation,
                message = message,
                decision = ApprovalDecision.DENIED,
                response = response,
                decisionSource = DECISION_SOURCE_POLICY,
                denialReason = InvocationDenialReason.POLICY_DENIED,
                denialMessage = SSH_AUTHENTICATION_POLICY_DENIAL_MESSAGE,
                authorization = authorization,
            )
        }
        val storedEvaluation = authentication.approvalEvaluationJson
            ?.let(::decodeApprovalEvaluation)
        if (
            storedEvaluation == null ||
            !storedEvaluation.hasSameSecretPolicies(currentEvaluation)
        ) {
            return refreshApprovalEvaluation(requestId, json.encodeToString(currentEvaluation))
        }
        val expectedPublicKey = expectedPublicKey(invocation, authentication.secretName)
            ?: return SshAuthenticationDecisionResult.InvocationUnavailable
        val publicKey = runCatching { sshKeys.importOpenSshPublicKey(expectedPublicKey) }
            .getOrElse { return SshAuthenticationDecisionResult.KeyChanged }
        val parsed = runCatching {
            protocol.validateMessage(
                message = message,
                expectedPublicKeyBlob = publicKey.blob(),
                expectedKeyAlgorithm = publicKey.algorithm.publicName,
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
                message = message,
                algorithm = parsed.algorithm,
            )
        ) {
            is SshAuthenticationSignatureResult.Signed -> result.signature
            SshAuthenticationSignatureResult.NotFound,
            SshAuthenticationSignatureResult.WrongType,
            SshAuthenticationSignatureResult.KeyChanged,
            -> return SshAuthenticationDecisionResult.KeyChanged
            SshAuthenticationSignatureResult.SecretUnavailable -> {
                return SshAuthenticationDecisionResult.SecretUnavailable
            }
            SshAuthenticationSignatureResult.SecretCorrupted -> {
                return SshAuthenticationDecisionResult.SecretCorrupted
            }
            SshAuthenticationSignatureResult.UnsupportedEncryption -> {
                return SshAuthenticationDecisionResult.UnsupportedEncryption
            }
        }
        val grant = if (allowTemporaryAccess) {
            prepareTemporaryGrant(policy, storedEvaluation)
                ?: return SshAuthenticationDecisionResult.TemporaryAccessUnavailable
        } else {
            null
        }
        val response = sealResponse(request, protocol.approvedResponse(signature))
            ?: return SshAuthenticationDecisionResult.PairingUnavailable
        return if (grant == null) {
            persistDecision(
                request = request,
                authentication = authentication,
                invocation = invocation,
                message = message,
                decision = ApprovalDecision.APPROVED,
                response = response,
                decisionSource = DECISION_SOURCE_USER,
                authorization = authorization,
            )
        } else {
            persistTemporaryDecision(
                request = request,
                authentication = authentication,
                invocation = invocation,
                message = message,
                response = response,
                authorization = authorization,
                grant = grant,
            )
        }
    }

    suspend fun deny(
        requestId: String,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): SshAuthenticationDecisionResult {
        val request = dao.getRequestById(requestId)
            ?: return SshAuthenticationDecisionResult.NotFound
        val authentication = dao.getSshAuthenticationRequest(requestId)
            ?: return SshAuthenticationDecisionResult.NotFound
        if (!request.isPending(authentication)) {
            return SshAuthenticationDecisionResult.NotPending
        }
        val message = authentication.message
            ?: return SshAuthenticationDecisionResult.InvalidMessage
        val invocation = invocationSnapshot(request)
            ?: return SshAuthenticationDecisionResult.InvocationUnavailable
        val response = sealResponse(
            request,
            protocol.deniedResponse(
                InvocationDenialReason.USER_DENIED,
                SSH_AUTHENTICATION_DENIAL_MESSAGE,
            ),
        ) ?: return SshAuthenticationDecisionResult.PairingUnavailable
        return persistDecision(
            request = request,
            authentication = authentication,
            invocation = invocation,
            message = message,
            decision = ApprovalDecision.DENIED,
            response = response,
            decisionSource = DECISION_SOURCE_USER,
            denialReason = InvocationDenialReason.USER_DENIED,
            denialMessage = SSH_AUTHENTICATION_DENIAL_MESSAGE,
        )
    }

    suspend fun receive(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
        client: ClientEntity,
        acceptedPsks: AcceptedRequestPsks,
        invocationSecretDetailsJson: String,
        authorization: AuthorizationCommitment?,
        automaticDecisionAudit: AuditRecord?,
    ): ConditionalRequestUpdate {
        val conditional = authentication.decision != null
        require(
            request.kind == RequestKind.SSH_AUTHENTICATE.storedName &&
                authentication.requestId == request.id,
        ) { "Mismatched SSH-authentication request rows" }
        require(authentication.message != null) { "A received SSH request must retain its message" }
        require((request.responseJson != null) == conditional) {
            "An automatic SSH-authentication decision must have exactly one response"
        }
        require(
            if (conditional) {
                request.state == InboxRequestState.WAITING.storedName
            } else {
                request.state == InboxRequestState.ACTION_REQUIRED.storedName ||
                    request.state == InboxRequestState.REVIEWING.storedName
            },
        ) { "SSH-authentication state does not match its decision" }
        require(conditional == (automaticDecisionAudit != null)) {
            "An automatic SSH-authentication decision and its audit must be persisted together"
        }
        require(
            automaticDecisionAudit == null ||
                automaticDecisionAudit.type == AuditEventType.SSH_AUTHENTICATION_DECIDED,
        ) { "Unexpected automatic SSH-authentication audit type" }
        require(!conditional || authorization != null) {
            "An automatic SSH-authentication decision must bind its authorization state"
        }
        return writeTransaction.execute {
            if (
                client.clientId != request.clientId ||
                client.deviceIdentityId != request.deviceIdentityId
            ) {
                return@execute ConditionalRequestUpdate.UNAVAILABLE
            }
            val parentRequestId = request.parentRequestId
                ?: return@execute ConditionalRequestUpdate.UNAVAILABLE
            if (
                invocationSnapshot(request) != SshInvocationSnapshot(
                    parentRequestId,
                    invocationSecretDetailsJson,
                )
            ) {
                return@execute ConditionalRequestUpdate.UNAVAILABLE
            }
            val currentClient = dao.getClient(client.clientId)
                ?: return@execute ConditionalRequestUpdate.UNAVAILABLE
            if (
                currentClient.deviceIdentityId != request.deviceIdentityId ||
                currentClient.relayClientState != RelayClientState.ACTIVE.wireName ||
                currentClient.desiredRelayClientState?.let {
                    it != RelayClientState.ACTIVE.wireName
                } == true
            ) {
                return@execute ConditionalRequestUpdate.UNAVAILABLE
            }
            val authorized = !conditional || dao.authorizationMatches(
                checkNotNull(authorization),
                request.clientId,
                TemporaryAccessOperation.SSH_AUTHENTICATE.storedName,
                currentTimeMillis(),
            )
            val storedRequest = if (authorized) request else request.copy(
                state = InboxRequestState.ACTION_REQUIRED.storedName,
                responseJson = null,
            )
            val storedAuthentication = if (authorized) {
                authentication.copy(message = authentication.message.takeIf { !conditional })
            } else {
                authentication.copy(
                    decision = null,
                    completionResult = null,
                    completionReason = null,
                    completionMessage = null,
                    decidedAt = null,
                )
            }
            val observedAt = client.lastSeenAt
            val storedClient = if (
                observedAt != null &&
                (currentClient.lastSeenAt == null || observedAt >= currentClient.lastSeenAt)
            ) {
                currentClient.copy(
                    clientSoftwareJson = client.clientSoftwareJson,
                    lastSeenAt = observedAt,
                )
            } else {
                currentClient
            }
            dao.insertSshAuthenticationRequest(
                request = storedRequest,
                authentication = storedAuthentication,
                client = storedClient,
                requestPsk = acceptedPsks.requestPsk,
                currentClientPsk = acceptedPsks.currentClientPsk,
                previousClientPsk = acceptedPsks.previousClientPsk,
            )
            audit.append(
                buildList {
                    add(receivedAudit(storedRequest, storedAuthentication))
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
        authentication: SshAuthenticationRequestEntity,
        invocationSecretDetailsJson: String,
        authorization: AuthorizationCommitment,
        aiReviewAudit: AuditRecord,
        automaticDecisionAudit: AuditRecord?,
    ): ConditionalRequestUpdate {
        val conditional = authentication.decision != null
        require(
            request.kind == RequestKind.SSH_AUTHENTICATE.storedName &&
                authentication.requestId == request.id,
        ) { "Mismatched SSH-authentication request rows" }
        require((request.responseJson != null) == conditional) {
            "An automatic SSH-authentication decision must have exactly one response"
        }
        require(
            request.state == (if (conditional) {
                InboxRequestState.WAITING.storedName
            } else {
                InboxRequestState.ACTION_REQUIRED.storedName
            }),
        ) { "Reviewed SSH-authentication state does not match its decision" }
        require(aiReviewAudit.type == AuditEventType.SSH_AUTHENTICATION_AI_REVIEWED) {
            "Unexpected SSH-authentication AI-review audit type"
        }
        require(conditional == (automaticDecisionAudit != null)) {
            "An automatic SSH-authentication decision and its audit must be persisted together"
        }
        require(
            automaticDecisionAudit == null ||
                automaticDecisionAudit.type == AuditEventType.SSH_AUTHENTICATION_DECIDED,
        ) { "Unexpected automatic SSH-authentication audit type" }
        return writeTransaction.execute {
            val occurredAt = currentTimeMillis()
            val currentRequest = dao.getRequestById(request.id)
                ?: return@execute ConditionalRequestUpdate.UNAVAILABLE
            val currentAuthentication = dao.getSshAuthenticationRequest(request.id)
                ?: return@execute ConditionalRequestUpdate.UNAVAILABLE
            val parentRequestId = currentRequest.parentRequestId
                ?: return@execute ConditionalRequestUpdate.UNAVAILABLE
            if (
                currentRequest.state != InboxRequestState.REVIEWING.storedName ||
                currentAuthentication.decision != null ||
                currentAuthentication.message == null ||
                authentication.message == null ||
                !currentAuthentication.message.contentEquals(authentication.message) ||
                !currentAuthentication.hasSameRequestDetails(authentication) ||
                invocationSnapshot(currentRequest) != SshInvocationSnapshot(
                    parentRequestId,
                    invocationSecretDetailsJson,
                )
            ) {
                return@execute ConditionalRequestUpdate.UNAVAILABLE
            }
            val result = dao.updateSshAuthenticationRequestIfAuthorized(
                request = currentRequest.copy(
                    state = request.state,
                    responseJson = request.responseJson,
                    responseOutboxFinished = if (request.responseJson != null) {
                        false
                    } else {
                        currentRequest.responseOutboxFinished
                    },
                ),
                authentication = currentAuthentication.copy(
                    message = currentAuthentication.message.takeIf { !conditional },
                    approvalEvaluationJson = authentication.approvalEvaluationJson,
                    decision = authentication.decision,
                    completionReason = authentication.completionReason,
                    completionMessage = authentication.completionMessage,
                    decidedAt = authentication.decidedAt,
                ),
                authorization = authorization,
                clientId = currentRequest.clientId,
                operation = TemporaryAccessOperation.SSH_AUTHENTICATE.storedName,
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
            decodeWireCompletionOrNull { protocol.decodeCompletion(it) }
        }
        return writeTransaction.execute {
            val currentRequest = dao.getRequestById(request.id) ?: return@execute false
            val authentication = dao.getSshAuthenticationRequest(request.id)
                ?: return@execute false
            if (currentRequest.exchangeEndedAt != null) {
                return@execute true
            }
            if (opened == CompletionOpenResult.RetryLater) return@execute false
            val softwareMatches = completionResult?.clientSoftware ==
                currentRequest.clientSoftwareJson?.let(::decodeClientSoftware)
            val valid = softwareMatches && when (completionResult) {
                is SshAuthenticationCompletion.Approved -> {
                    authentication.decision == ApprovalDecision.APPROVED.storedName
                }
                is SshAuthenticationCompletion.Denied -> {
                    authentication.decision == ApprovalDecision.DENIED.storedName &&
                        completionResult.reason == (
                            authentication.completionReason
                                ?: InvocationDenialReason.USER_DENIED.wireName
                        ) && completionResult.message == (
                            authentication.completionMessage
                                ?: SSH_AUTHENTICATION_DENIAL_MESSAGE
                        )
                }
                is SshAuthenticationCompletion.Aborted -> true
                null -> false
            }
            val error = if (valid) null else SSH_AUTHENTICATION_COMPLETION_VERIFICATION_ERROR
            val now = currentTimeMillis()
            dao.updateSshAuthenticationRequest(
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
                authentication = authentication.copy(
                    message = null,
                    completionResult = when {
                        !valid -> null
                        completionResult is SshAuthenticationCompletion.Approved -> {
                            ApprovalCompletionResult.APPROVED.storedName
                        }
                        completionResult is SshAuthenticationCompletion.Denied -> {
                            ApprovalCompletionResult.DENIED.storedName
                        }
                        completionResult is SshAuthenticationCompletion.Aborted -> {
                            ApprovalCompletionResult.ABORTED.storedName
                        }
                        else -> null
                    },
                    completionReason = if (
                        valid && completionResult is SshAuthenticationCompletion.Denied
                    ) {
                        authentication.completionReason
                            ?: InvocationDenialReason.USER_DENIED.wireName
                    } else {
                        null
                    },
                    completionMessage = if (
                        valid && completionResult is SshAuthenticationCompletion.Denied
                    ) {
                        authentication.completionMessage
                            ?: SSH_AUTHENTICATION_DENIAL_MESSAGE
                    } else {
                        null
                    },
                ),
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SSH_AUTHENTICATION_COMPLETED,
                        outcome = when {
                            !valid -> AuditOutcome.FAILED
                            completionResult is SshAuthenticationCompletion.Approved -> {
                                AuditOutcome.COMPLETED
                            }
                            completionResult is SshAuthenticationCompletion.Denied -> {
                                AuditOutcome.DENIED
                            }
                            else -> AuditOutcome.ABORTED
                        },
                        subject = authentication.secretName,
                        detail = when {
                            !valid -> SSH_AUTHENTICATION_COMPLETION_VERIFICATION_ERROR
                            completionResult is SshAuthenticationCompletion.Denied -> {
                                authentication.completionMessage
                                    ?: SSH_AUTHENTICATION_DENIAL_MESSAGE
                            }
                            completionResult is SshAuthenticationCompletion.Aborted -> {
                                SSH_AUTHENTICATION_COMPLETION_ABORTED_DETAIL
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
            val authentication = dao.getSshAuthenticationRequest(request.id) ?: return@execute
            dao.updateSshAuthenticationRequest(
                currentRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    error = message,
                    completedAt = now,
                    exchangeEndedAt = now,
                    responseOutboxFinished = true,
                ),
                authentication.copy(message = null),
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SSH_AUTHENTICATION_COMPLETED,
                        outcome = AuditOutcome.FAILED,
                        subject = authentication.secretName,
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
        authentication: SshAuthenticationRequestEntity,
        invocation: SshInvocationSnapshot,
        message: ByteArray,
        decision: ApprovalDecision,
        response: JsonElement,
        decisionSource: String,
        denialReason: InvocationDenialReason? = null,
        denialMessage: String? = null,
        authorization: AuthorizationCommitment? = null,
    ): SshAuthenticationDecisionResult {
        require(decision != ApprovalDecision.APPROVED || authorization != null) {
            "Approved SSH authentication must bind its authorization state"
        }
        require(decision != ApprovalDecision.DENIED || denialReason != null) {
            "Denied SSH authentication must record its reason"
        }
        require(decision != ApprovalDecision.DENIED || denialMessage != null) {
            "Denied SSH authentication must record its message"
        }
        return writeTransaction.execute {
            val now = currentTimeMillis()
            val currentRequest = dao.getRequestById(request.id)
                ?: return@execute SshAuthenticationDecisionResult.NotFound
            val currentAuthentication = dao.getSshAuthenticationRequest(request.id)
                ?: return@execute SshAuthenticationDecisionResult.NotFound
            if (!currentRequest.isPending(currentAuthentication)) {
                return@execute SshAuthenticationDecisionResult.NotPending
            }
            if (
                currentAuthentication.message?.contentEquals(message) != true ||
                !currentAuthentication.hasSameRequestDetails(authentication) ||
                !invocationSnapshotMatches(currentRequest, invocation)
            ) {
                return@execute SshAuthenticationDecisionResult.InvocationUnavailable
            }
            if (
                currentAuthentication.approvalEvaluationJson !=
                authentication.approvalEvaluationJson
            ) {
                return@execute SshAuthenticationDecisionResult.ApprovalChanged
            }
            val updatedRequest = currentRequest.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = response.toString(),
                responseOutboxFinished = false,
            )
            val updatedAuthentication = currentAuthentication.copy(
                message = null,
                decision = decision.storedName,
                completionReason = denialReason?.wireName,
                completionMessage = denialMessage,
                decidedAt = now,
            )
            val result = if (authorization == null) {
                dao.updateSshAuthenticationRequest(updatedRequest, updatedAuthentication)
                ConditionalRequestUpdate.APPLIED
            } else {
                dao.updateSshAuthenticationRequestIfAuthorized(
                    request = updatedRequest,
                    authentication = updatedAuthentication,
                    authorization = authorization,
                    clientId = currentRequest.clientId,
                    operation = TemporaryAccessOperation.SSH_AUTHENTICATE.storedName,
                    expectedState = InboxRequestState.ACTION_REQUIRED.storedName,
                    now = now,
                )
            }
            if (result == ConditionalRequestUpdate.APPLIED) {
                audit.append(
                    listOf(
                        decisionAudit(
                            currentRequest,
                            currentAuthentication,
                            decision,
                            decisionSource,
                        ),
                    ),
                    now,
                )
            }
            when (result) {
                ConditionalRequestUpdate.APPLIED -> SshAuthenticationDecisionResult.Decided
                ConditionalRequestUpdate.ACTION_REQUIRED,
                ConditionalRequestUpdate.UNAVAILABLE,
                -> SshAuthenticationDecisionResult.ApprovalChanged
            }
        }
    }

    private fun prepareTemporaryGrant(
        policy: SecretApprovalPolicy,
        storedEvaluation: ApprovalEvaluation,
    ): SshAuthenticationTemporaryGrant? {
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
        return SshAuthenticationTemporaryGrant(
            policy = policy,
            expiresAt = expiresAt,
            evaluation = storedEvaluation.copy(
                secrets = listOf(secretEvaluation.copy(temporaryAccessExpiresAt = expiresAt)),
            ),
        )
    }

    private suspend fun persistTemporaryDecision(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
        invocation: SshInvocationSnapshot,
        message: ByteArray,
        response: JsonElement,
        authorization: AuthorizationCommitment,
        grant: SshAuthenticationTemporaryGrant,
    ): SshAuthenticationDecisionResult = writeTransaction.execute {
        val now = currentTimeMillis()
        val currentRequest = dao.getRequestById(request.id)
            ?: return@execute SshAuthenticationDecisionResult.NotFound
        val currentAuthentication = dao.getSshAuthenticationRequest(request.id)
            ?: return@execute SshAuthenticationDecisionResult.NotFound
        if (!currentRequest.isPending(currentAuthentication)) {
            return@execute SshAuthenticationDecisionResult.NotPending
        }
        if (
            currentAuthentication.message?.contentEquals(message) != true ||
            !currentAuthentication.hasSameRequestDetails(authentication) ||
            currentAuthentication.approvalEvaluationJson != authentication.approvalEvaluationJson ||
            !invocationSnapshotMatches(currentRequest, invocation)
        ) {
            return@execute SshAuthenticationDecisionResult.ApprovalChanged
        }
        if (
            !dao.authorizationMatches(
                authorization,
                currentRequest.clientId,
                TemporaryAccessOperation.SSH_AUTHENTICATE.storedName,
                now,
            )
        ) {
            return@execute SshAuthenticationDecisionResult.ApprovalChanged
        }
        val temporaryAccessStarted = secrets.allowTemporaryAccess(
            policies = listOf(grant.policy),
            clientId = currentRequest.clientId,
            operation = TemporaryAccessOperation.SSH_AUTHENTICATE,
            expiresAt = grant.expiresAt,
        )
        val decisionSource = if (temporaryAccessStarted) {
            DECISION_SOURCE_TEMPORARY_ACCESS
        } else {
            DECISION_SOURCE_USER
        }
        dao.updateSshAuthenticationRequest(
            currentRequest.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = response.toString(),
                responseOutboxFinished = false,
            ),
            currentAuthentication.copy(
                message = null,
                approvalEvaluationJson = if (temporaryAccessStarted) {
                    json.encodeToString(grant.evaluation)
                } else {
                    currentAuthentication.approvalEvaluationJson
                },
                decision = ApprovalDecision.APPROVED.storedName,
                completionReason = null,
                completionMessage = null,
                decidedAt = now,
            ),
        )
        audit.append(
            listOf(
                decisionAudit(
                    currentRequest,
                    currentAuthentication,
                    ApprovalDecision.APPROVED,
                    decisionSource,
                ),
            ),
            now,
        )
        if (temporaryAccessStarted) {
            SshAuthenticationDecisionResult.Decided
        } else {
            SshAuthenticationDecisionResult.TemporaryAccessNotStarted
        }
    }

    private suspend fun refreshApprovalEvaluation(
        requestId: String,
        approvalEvaluationJson: String,
    ): SshAuthenticationDecisionResult = writeTransaction.execute {
        val currentRequest = dao.getRequestById(requestId)
            ?: return@execute SshAuthenticationDecisionResult.NotFound
        val authentication = dao.getSshAuthenticationRequest(requestId)
            ?: return@execute SshAuthenticationDecisionResult.NotFound
        if (!currentRequest.isPending(authentication)) {
            return@execute SshAuthenticationDecisionResult.NotPending
        }
        dao.updateSshAuthenticationRequest(
            currentRequest,
            authentication.copy(approvalEvaluationJson = approvalEvaluationJson),
        )
        SshAuthenticationDecisionResult.ApprovalChanged
    }

    private suspend fun terminalCompletionHandled(requestId: String): Boolean? =
        writeTransaction.execute {
            val terminal = dao.getRequestById(requestId) ?: return@execute null
            if (
                dao.getSshAuthenticationRequest(requestId) == null ||
                terminal.exchangeEndedAt == null
            ) {
                return@execute null
            }
            true
        }

    private suspend fun invocationSnapshot(request: InboxRequestEntity): SshInvocationSnapshot? {
        val parentId = request.parentRequestId ?: return null
        val parent = dao.getRequestById(parentId) ?: return null
        val invocation = dao.getSecretUseRequest(parentId) ?: return null
        if (
            parent.kind != RequestKind.SECRET_USE.storedName ||
            parent.clientId != request.clientId ||
            parent.deviceIdentityId != request.deviceIdentityId ||
            parent.clientSoftwareJson != request.clientSoftwareJson ||
            invocation.decision != ApprovalDecision.APPROVED.storedName
        ) {
            return null
        }
        return SshInvocationSnapshot(parentId, invocation.secretDetailsJson)
    }

    private suspend fun invocationSnapshotMatches(
        request: InboxRequestEntity,
        expected: SshInvocationSnapshot,
    ): Boolean = invocationSnapshot(request) == expected

    private fun expectedPublicKey(
        invocation: SshInvocationSnapshot,
        secretName: String,
    ): String? = storedJson.decodeFromString<List<SecretMetadata>>(
        invocation.secretDetailsJson,
    ).singleOrNull { secret ->
        secret.name == secretName && secret.type == SSH_SECRET_TYPE
    }?.sshPublicKey

    private fun InboxRequestEntity.isPending(
        authentication: SshAuthenticationRequestEntity,
    ): Boolean = state == InboxRequestState.ACTION_REQUIRED.storedName &&
        authentication.decision == null

    private fun SshAuthenticationRequestEntity.hasSameRequestDetails(
        other: SshAuthenticationRequestEntity,
    ): Boolean =
        requestId == other.requestId &&
            secretName == other.secretName &&
            username == other.username &&
            method == other.method &&
            algorithm == other.algorithm &&
            hostKeyAlgorithm == other.hostKeyAlgorithm &&
            hostKeyFingerprint == other.hostKeyFingerprint

    private fun receivedAudit(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
    ) = AuditRecord(
        type = AuditEventType.SSH_AUTHENTICATION_RECEIVED,
        outcome = AuditOutcome.RECEIVED,
        subject = authentication.secretName,
        clientId = request.clientId,
        clientName = request.clientNameSnapshot,
        relayRequestId = request.id,
    )

    private fun decisionAudit(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
        decision: ApprovalDecision,
        decisionSource: String,
    ) = AuditRecord(
        type = AuditEventType.SSH_AUTHENTICATION_DECIDED,
        outcome = if (decision == ApprovalDecision.APPROVED) {
            AuditOutcome.APPROVED
        } else {
            AuditOutcome.DENIED
        },
        decisionSource = decisionSource.toAuditDecisionSource(),
        subject = authentication.secretName,
        clientId = request.clientId,
        clientName = request.clientNameSnapshot,
        relayRequestId = request.id,
    )

    private fun decodeClientSoftware(value: String) = runCatching {
        storedJson.decodeFromString<dev.agentknock.protocol.ClientSoftware>(value)
    }.getOrNull()
}
