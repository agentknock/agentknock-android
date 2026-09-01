package dev.agentknock.storage.request

import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.GitSignCompletion
import dev.agentknock.protocol.GitSignProtocol
import dev.agentknock.protocol.GitSignRequestMessage
import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.relay.RelayApprovalReviewClient
import dev.agentknock.relay.RelayClientState
import dev.agentknock.review.approvalReviewGitSignRequest
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.ApprovalPolicyEvaluator
import dev.agentknock.storage.approval.isFullyApproved
import dev.agentknock.storage.approval.requiresAiReview
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.device.RelayDeviceCredentialsResult
import dev.agentknock.storage.secret.GitSignatureResult
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.TemporaryAccessOperation
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

/**
 * Owns Git-signing intake and durable transitions.
 *
 * RequestRepository owns relay transport, paired envelopes, operation serialization, and the
 * process-local lifetime of AI review jobs.
 */
internal class GitSigningRequests(
    private val dao: RequestDao,
    private val secrets: SecretRepository,
    private val deviceCredentials: RelayDeviceCredentialSource,
    private val approvalReviewer: RelayApprovalReviewClient,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val gitSignProtocol: GitSignProtocol = GitSignProtocol(),
    private val json: Json = Json,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun processIncoming(
        client: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        plaintext: ByteArray,
        acceptedPsks: AcceptedRequestPsks,
        credentials: RelayDeviceCredentials,
        sealResponse: suspend (ByteArray) -> JsonElement?,
        launchAiReview: (
            requestId: String,
            requestJson: String,
            review: suspend () -> AiReview,
            complete: suspend (AiReview) -> Unit,
        ) -> Boolean,
    ): ProcessedRelayMessage? {
        val now = currentTimeMillis()
        val contents = runCatching { gitSignProtocol.decodeRequest(plaintext) }
            .getOrNull() ?: return null
        val invocationRequest = dao.getRequestById(contents.invocationId) ?: return null
        if (invocationRequest.kind != RequestKind.SECRET_USE.storedName) return null
        val invocation = dao.getSecretUseRequest(invocationRequest.id) ?: return null
        val expectedTokenHash = invocation.invocationTokenHash
        if (
            invocationRequest.clientId != client.clientId ||
            invocationRequest.deviceIdentityId != client.deviceIdentityId ||
            invocationRequest.state != InboxRequestState.WAITING.storedName ||
            invocationRequest.exchangeEndedAt != null ||
            invocation.decision != ApprovalDecision.APPROVED.storedName ||
            invocationRequest.clientSoftwareJson?.let(::decodeStoredClientSoftware) !=
                contents.clientSoftware ||
            !MessageDigest.isEqual(
                expectedTokenHash,
                invocationTokenHash(contents.invocationToken),
            )
        ) {
            return null
        }

        val storedSecretMetadata = runCatching {
            storedJson.decodeFromString<List<SecretMetadata>>(invocation.secretDetailsJson)
        }.getOrNull() ?: return null
        val sshMetadata = storedSecretMetadata.singleOrNull { secret ->
            secret.name == contents.secret &&
                secret.type == SSH_SECRET_TYPE &&
                secret.sshPublicKey != null
        }
        val description = secrets.describeRequestedSecrets(listOf(contents.secret))
        val approvalPolicies = secrets.approvalPoliciesForNames(
            listOf(contents.secret),
            client.clientId,
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
            deviceIdentityId = client.deviceIdentityId,
            clientId = client.clientId,
            clientNameSnapshot = client.name,
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
            error = null,
            receivedAt = now,
            completedAt = null,
            exchangeEndedAt = null,
            responseOutboxFinished = false,
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
            val currentClient = if (needsAiReview) dao.getClient(client.clientId) else client
            val clientUnavailable = currentClient == null ||
                currentClient.deviceIdentityId != client.deviceIdentityId ||
                currentClient.relayClientState == RelayClientState.REVOKED.wireName ||
                currentClient.desiredRelayClientState == RelayClientState.REVOKED.wireName
            val invocationUnavailable = !parentInvocationIsActive(initialRequest)
            val currentDescription = if (needsAiReview) {
                secrets.describeRequestedSecrets(listOf(contents.secret))
            } else {
                description
            }
            val currentPolicies = if (needsAiReview) {
                secrets.approvalPoliciesForNames(
                    listOf(contents.secret),
                    client.clientId,
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
                    invocationUnavailable ||
                    currentEvaluation == null ||
                    !checkNotNull(initialEvaluation).hasSameSecretPolicies(currentEvaluation) ||
                    currentCredentials?.instructions != credentials.instructions ||
                    currentClient.name != client.name ||
                    currentClient.instructions != client.instructions
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
            if (invocationUnavailable && denial == null) {
                denial = InvocationDenialReason.OTHER to
                    "The parent invocation is no longer active."
            }
            val approvalSettingsDenied =
                denial == null &&
                evaluation?.secrets?.any { it.action == ApprovalAction.DENY } == true
            if (approvalSettingsDenied) {
                denial = InvocationDenialReason.POLICY_DENIED to
                    GIT_SIGN_POLICY_DENIAL_MESSAGE
            }
            val aiDenied = denial == null && aiReview?.decision == AiReviewDecision.DENY
            if (aiDenied) {
                denial = InvocationDenialReason.POLICY_DENIED to
                    "AI review denied use of the SSH key."
            }
            val shouldApprove = !aiInputsChanged && denial == null &&
                evaluation?.isFullyApproved(aiReview?.decision) == true
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
            val response = responsePlaintext?.let { responsePlaintext ->
                sealResponse(responsePlaintext) ?: return null
            }
            val decidedAt = currentTimeMillis()
            val automaticApproval = planAutomaticApproval(
                outcome = when {
                    signature != null -> AutomaticApprovalOutcome.APPROVED
                    denial != null -> AutomaticApprovalOutcome.DENIED
                    else -> AutomaticApprovalOutcome.ACTION_REQUIRED
                },
                evaluation = evaluation,
                aiDecision = aiReview?.decision,
                denialSource = when {
                    aiDenied -> AutomaticApprovalDenialSource.AI
                    approvalSettingsDenied -> AutomaticApprovalDenialSource.POLICY
                    else -> null
                },
            )
            val automaticDecision = automaticApproval.decision
            val automaticDecisionSource = automaticApproval.decisionSource ?: if (
                denial?.first == InvocationDenialReason.INVALID_REQUEST
            ) {
                DECISION_SOURCE_VALIDATION
            } else {
                null
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
            val automaticDecisionAudit = automaticDecision?.let {
                AuditRecord(
                    type = AuditEventType.GIT_SIGN_DECIDED,
                    outcome = when {
                        signature != null -> AuditOutcome.APPROVED
                        aiDenied || approvalSettingsDenied -> AuditOutcome.DENIED
                        denial?.first == InvocationDenialReason.INVALID_REQUEST ->
                            AuditOutcome.REJECTED
                        else -> AuditOutcome.FAILED
                    },
                    decisionSource = when (automaticDecisionSource) {
                        null -> null
                        else -> automaticDecisionSource.toAuditDecisionSource()
                    },
                    subject = contents.secret,
                    detail = if (
                        aiReview != null && aiReview.decision != AiReviewDecision.ASK_USER
                    ) {
                        aiReview.auditFailureDetail()
                    } else {
                        denial?.second
                    },
                    clientId = client.clientId,
                    clientName = client.name,
                    relayRequestId = relayRequestId,
                )
            }
            val persisted = if (requestAlreadyInserted) {
                finishAiReview(
                    request = finalRequest,
                    gitSign = finalGitSign,
                    authorization = authorization,
                    aiReviewAudit = AuditRecord(
                        type = AuditEventType.GIT_SIGN_AI_REVIEWED,
                        outcome = checkNotNull(aiReview).auditOutcome(),
                        decisionSource = AuditDecisionSource.AI_REVIEW,
                        subject = contents.secret,
                        detail = aiReview.auditFailureDetail(),
                        clientId = client.clientId,
                        clientName = client.name,
                        relayRequestId = relayRequestId,
                    ),
                    automaticDecisionAudit = automaticDecisionAudit,
                )
            } else {
                receive(
                    request = finalRequest,
                    gitSign = finalGitSign,
                    client = client.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                    ),
                    acceptedPsks = acceptedPsks,
                    authorization = authorization.takeIf { automaticDecision != null },
                    automaticDecisionAudit = automaticDecisionAudit,
                )
            }
            return when (persisted) {
                ConditionalRequestUpdate.APPLIED -> ProcessedRelayMessage
                ConditionalRequestUpdate.ACTION_REQUIRED -> ProcessedRelayMessage
                ConditionalRequestUpdate.UNAVAILABLE -> null
            }
        }

        if (!needsAiReview) return finishReview(null, requestAlreadyInserted = false)

        withContext(NonCancellable) {
            check(
                receive(
                    request = initialRequest,
                    gitSign = initialGitSign,
                    client = client.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                    ),
                    acceptedPsks = acceptedPsks,
                    authorization = null,
                    automaticDecisionAudit = null,
                ) == ConditionalRequestUpdate.APPLIED,
            )
            check(
                launchAiReview(
                    relayRequestId,
                    initialRequest.requestJson,
                    {
                        requestAiReview(
                            client = client,
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
                    { finishReview(it, requestAlreadyInserted = true) },
                ),
            )
        }
        return ProcessedRelayMessage
    }

    suspend fun approve(
        requestId: String,
        allowTemporaryAccess: Boolean,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): RequestDecisionResult {
        val request = dao.getRequestById(requestId) ?: return RequestDecisionResult.NotFound
        val gitSign = dao.getGitSignRequest(requestId) ?: return RequestDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            gitSign.decision != null
        ) {
            return RequestDecisionResult.NotPending
        }
        val invocation = activeParentInvocation(request)
            ?: return RequestDecisionResult.ParentUnavailable
        val description = secrets.describeRequestedSecrets(listOf(gitSign.secretName))
        val policy = secrets.approvalPoliciesForNames(
            listOf(gitSign.secretName),
            request.clientId,
            TemporaryAccessOperation.GIT_SIGN,
        ).singleOrNull() ?: return RequestDecisionResult.ApprovalChanged
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
            ) ?: return RequestDecisionResult.ClientUnavailable
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
        val invocationSecrets = runCatching {
            storedJson.decodeFromString<List<SecretMetadata>>(invocation.secretDetailsJson)
        }.getOrNull() ?: return RequestDecisionResult.ParentUnavailable
        val expectedPublicKey = invocationSecrets.singleOrNull { secret ->
            secret.name == gitSign.secretName && secret.type == SSH_SECRET_TYPE
        }?.sshPublicKey ?: return RequestDecisionResult.ParentUnavailable
        if (!parentInvocationIsActive(request)) {
            return RequestDecisionResult.ParentUnavailable
        }
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
            -> return RequestDecisionResult.SecretChangedSinceInvocation
            GitSignatureResult.SecretUnavailable -> return RequestDecisionResult.SecretUnavailable
            GitSignatureResult.SecretCorrupted -> return RequestDecisionResult.SecretCorrupted
            GitSignatureResult.UnsupportedEncryption -> {
                return RequestDecisionResult.UnsupportedEncryption
            }
        }
        val grant = if (allowTemporaryAccess) {
            planTemporaryAccess(
                policies = listOf(policy),
                storedEvaluation = checkNotNull(storedEvaluation),
                now = currentTimeMillis(),
            )
                ?: return RequestDecisionResult.TemporaryAccessUnavailable
        } else {
            null
        }
        val response = sealResponse(
            request,
            gitSignProtocol.approvedResponse(signature),
        ) ?: return RequestDecisionResult.ClientUnavailable
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
    ): RequestDecisionResult {
        val request = dao.getRequestById(requestId) ?: return RequestDecisionResult.NotFound
        val gitSign = dao.getGitSignRequest(requestId) ?: return RequestDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            gitSign.decision != null
        ) {
            return RequestDecisionResult.NotPending
        }
        if (request.parentRequestId?.let { dao.getSecretUseRequest(it) } == null) {
            return RequestDecisionResult.ParentUnavailable
        }
        val response = sealResponse(
            request,
            gitSignProtocol.deniedResponse(
                InvocationDenialReason.USER_DENIED,
                GIT_SIGN_DENIAL_MESSAGE,
            ),
        ) ?: return RequestDecisionResult.ClientUnavailable
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
            if (!parentInvocationIsActive(request)) {
                return@execute ConditionalRequestUpdate.UNAVAILABLE
            }
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
            if (
                gitSign.decision == ApprovalDecision.APPROVED.storedName &&
                !parentInvocationIsActive(currentRequest)
            ) {
                dao.updateGitSignRequest(
                    request = currentRequest.copy(
                        state = InboxRequestState.ACTION_REQUIRED.storedName,
                        responseJson = null,
                    ),
                    gitSignRequest = currentGitSign.copy(
                        decision = null,
                        completionResult = null,
                        completionReason = null,
                        completionMessage = null,
                        decidedAt = null,
                    ),
                )
                audit.append(
                    listOf(aiReviewAudit.copy(outcome = AuditOutcome.DEFERRED)),
                    occurredAt,
                )
                return@execute ConditionalRequestUpdate.ACTION_REQUIRED
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
        openCompletion: suspend () -> CompletionOpenResult,
    ): Boolean {
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
            val priorError = currentRequest.error
            val softwareMatches = completionResult?.clientSoftware ==
                currentRequest.clientSoftwareJson?.let(::decodeStoredClientSoftware)
            val valid = priorError == null && softwareMatches && when (completionResult) {
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
            val error = priorError ?: if (valid) {
                null
            } else {
                GIT_SIGN_COMPLETION_VERIFICATION_ERROR
            }
            val now = currentTimeMillis()
            dao.updateGitSignRequest(
                request = currentRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
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
    ): RequestDecisionResult {
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
                ?: return@execute RequestDecisionResult.NotFound
            val currentGitSign = dao.getGitSignRequest(request.id)
                ?: return@execute RequestDecisionResult.NotFound
            if (
                currentRequest.state != InboxRequestState.ACTION_REQUIRED.storedName ||
                currentGitSign.decision != null
            ) {
                return@execute RequestDecisionResult.NotPending
            }
            if (
                decision == ApprovalDecision.APPROVED &&
                !parentInvocationIsActive(currentRequest)
            ) {
                return@execute RequestDecisionResult.ParentUnavailable
            }
            val updatedRequest = currentRequest.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = response.toString(),
                responseOutboxFinished = false,
            )
            val updatedGitSign = currentGitSign.copy(
                decision = decision.storedName,
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
                ConditionalRequestUpdate.APPLIED -> RequestDecisionResult.Decided
                ConditionalRequestUpdate.ACTION_REQUIRED,
                ConditionalRequestUpdate.UNAVAILABLE,
                -> RequestDecisionResult.ApprovalChanged
            }
        }
    }

    private suspend fun persistTemporaryDecision(
        request: InboxRequestEntity,
        gitSign: GitSignRequestEntity,
        response: JsonElement,
        authorization: AuthorizationCommitment,
        grant: TemporaryAccessPlan,
    ): RequestDecisionResult = writeTransaction.execute {
        val now = currentTimeMillis()
        val currentRequest = dao.getRequestById(request.id)
            ?: return@execute RequestDecisionResult.NotFound
        val currentGitSign = dao.getGitSignRequest(request.id)
            ?: return@execute RequestDecisionResult.NotFound
        if (
            currentRequest.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            currentGitSign.decision != null
        ) {
            return@execute RequestDecisionResult.NotPending
        }
        if (currentGitSign.approvalEvaluationJson != gitSign.approvalEvaluationJson) {
            return@execute RequestDecisionResult.ApprovalChanged
        }
        if (!parentInvocationIsActive(currentRequest)) {
            return@execute RequestDecisionResult.ParentUnavailable
        }
        if (
            !dao.authorizationMatches(
                authorization,
                currentRequest.clientId,
                TemporaryAccessOperation.GIT_SIGN.storedName,
                now,
            )
        ) {
            return@execute RequestDecisionResult.ApprovalChanged
        }
        val temporaryAccessStarted = secrets.allowTemporaryAccess(
            policies = grant.policies,
            clientId = currentRequest.clientId,
            operation = TemporaryAccessOperation.GIT_SIGN,
            expiresAt = grant.expiresAt,
        )
        val decisionSource = if (temporaryAccessStarted) {
            grant.decisionSource
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
            RequestDecisionResult.Decided
        } else {
            RequestDecisionResult.TemporaryAccessNotStarted
        }
    }

    private suspend fun refreshApprovalEvaluation(
        requestId: String,
        approvalEvaluationJson: String,
    ): RequestDecisionResult = writeTransaction.execute {
        val currentRequest = dao.getRequestById(requestId)
            ?: return@execute RequestDecisionResult.NotFound
        val currentGitSign = dao.getGitSignRequest(requestId)
            ?: return@execute RequestDecisionResult.NotFound
        if (
            currentRequest.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            currentGitSign.decision != null
        ) {
            return@execute RequestDecisionResult.NotPending
        }
        dao.updateGitSignRequest(
            currentRequest,
            currentGitSign.copy(approvalEvaluationJson = approvalEvaluationJson),
        )
        RequestDecisionResult.ApprovalChanged
    }

    private suspend fun requestAiReview(
        client: ClientEntity,
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
            client = client,
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

    private suspend fun credentialsForClient(client: ClientEntity): RelayDeviceCredentials? {
        return when (
            val result = deviceCredentials.deviceCredentials(client.deviceIdentityId)
        ) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            else -> null
        }
    }

    private suspend fun activeParentInvocation(
        request: InboxRequestEntity,
    ): SecretUseRequestEntity? {
        val parent = request.parentRequestId?.let { dao.getRequestById(it) } ?: return null
        if (
            parent.kind != RequestKind.SECRET_USE.storedName ||
            parent.state != InboxRequestState.WAITING.storedName ||
            parent.exchangeEndedAt != null ||
            parent.clientId != request.clientId ||
            parent.deviceIdentityId != request.deviceIdentityId
        ) {
            return null
        }
        return dao.getSecretUseRequest(parent.id)?.takeIf {
            it.decision == ApprovalDecision.APPROVED.storedName
        }
    }

    private suspend fun parentInvocationIsActive(request: InboxRequestEntity): Boolean =
        activeParentInvocation(request) != null

    private fun encodeClientSoftware(value: ClientSoftware): String = json.encodeToString(value)

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

    private companion object {
        const val MAX_AI_REVIEW_GIT_CONTENT_BYTES = 128 * 1024
    }

}
