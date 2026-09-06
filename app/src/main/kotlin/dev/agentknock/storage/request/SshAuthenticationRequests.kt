package dev.agentknock.storage.request

import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.protocol.SshAuthenticationCompletion
import dev.agentknock.protocol.SshAuthenticationMessageDetails
import dev.agentknock.protocol.SshAuthenticationProtocol
import dev.agentknock.protocol.SshAuthenticationRequestMessage
import dev.agentknock.subscription.SubscriptionRepository
import dev.agentknock.relay.RelayApprovalReviewClient
import dev.agentknock.relay.RelayClientState
import dev.agentknock.review.approvalReviewSshAuthenticationRequest
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.ApprovalPolicyEvaluator
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.isFullyApproved
import dev.agentknock.storage.approval.requiresAiReview
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.auditDataOf
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.device.RelayDeviceCredentialsResult
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SshAuthenticationSignatureResult
import dev.agentknock.storage.secret.SshKeyCodec
import dev.agentknock.storage.secret.TemporaryAccessOperation
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal const val SSH_AUTHENTICATION_DENIAL_MESSAGE =
    "SSH authentication denied on device."
internal const val SSH_AUTHENTICATION_POLICY_DENIAL_MESSAGE =
    "Approval settings denied SSH authentication."
internal const val SSH_AUTHENTICATION_INVALID_MESSAGE =
    "The SSH authentication data changed or is invalid; start the command again"
private const val SSH_AUTHENTICATION_COMPLETION_ABORTED_DETAIL =
    "SSH authentication was aborted by the client."
private const val SSH_AUTHENTICATION_COMPLETION_VERIFICATION_ERROR =
    "SSH authentication completion could not be verified."
private const val SSH_AUTHENTICATION_SECRET_CHANGED_MESSAGE =
    "The SSH key changed after the command began; start the command again."

private data class SshInvocationSnapshot(
    val requestId: String,
    val secretDetailsJson: String,
)

/**
 * Owns SSH-authentication intake and durable transitions.
 *
 * RequestRepository owns relay transport, paired envelopes, operation serialization, and the
 * process-local lifetime of AI review jobs.
 */
internal class SshAuthenticationRequests(
    private val dao: RequestDao,
    private val secrets: SecretRepository,
    private val deviceCredentials: RelayDeviceCredentialSource,
    private val approvalReviewer: RelayApprovalReviewClient,
    private val subscription: SubscriptionRepository,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val sshKeys: SshKeyCodec = SshKeyCodec(),
    private val protocol: SshAuthenticationProtocol = SshAuthenticationProtocol(),
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
            review: suspend () -> AiReviewAttempt,
            complete: suspend (AiReviewAttempt) -> Unit,
        ) -> Boolean,
    ): ProcessedRelayMessage? {
        val now = currentTimeMillis()
        val contents = runCatching { protocol.decodeRequest(plaintext) }.getOrNull() ?: return null
        val invocationRequest = dao.getRequestById(contents.invocationId) ?: return null
        if (invocationRequest.kind != RequestKind.SECRET_USE.storedName) return null
        val invocation = dao.getSecretUseRequest(invocationRequest.id) ?: return null
        if (
            invocationRequest.clientId != client.clientId ||
            invocationRequest.deviceIdentityId != client.deviceIdentityId ||
            invocation.decision != ApprovalDecision.APPROVED.storedName ||
            invocationRequest.clientSoftwareJson?.let(::decodeStoredClientSoftware) !=
                contents.clientSoftware ||
            !MessageDigest.isEqual(
                invocation.invocationTokenHash,
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
        } ?: return null
        val expectedPublicKey = runCatching {
            sshKeys.importOpenSshPublicKey(checkNotNull(sshMetadata.sshPublicKey))
        }.getOrNull() ?: return null
        val messageDetails = runCatching {
            protocol.validateMessage(
                message = contents.message,
                expectedPublicKeyBlob = expectedPublicKey.blob(),
                expectedKeyAlgorithm = expectedPublicKey.algorithm.publicName,
            )
        }.getOrNull() ?: return null

        val description = secrets.describeRequestedSecrets(listOf(contents.secret))
        val approvalPolicies = secrets.approvalPoliciesForNames(
            listOf(contents.secret),
            client.clientId,
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
            deviceIdentityId = client.deviceIdentityId,
            clientId = client.clientId,
            clientNameSnapshot = client.name,
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
            error = null,
            receivedAt = now,
            completedAt = null,
            exchangeEndedAt = null,
            responseOutboxFinished = false,
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
            reviewResult: AiReviewAttempt?,
            requestAlreadyInserted: Boolean,
        ): ProcessedRelayMessage? {
            val currentClient = if (needsAiReview) dao.getClient(client.clientId) else client
            val clientUnavailable = currentClient == null ||
                currentClient.deviceIdentityId != client.deviceIdentityId ||
                currentClient.relayClientState == RelayClientState.REVOKED.wireName ||
                currentClient.desiredRelayClientState == RelayClientState.REVOKED.wireName
            val invocationUnavailable = !parentInvocationIsAvailable(initialRequest)
            val currentDescription = if (needsAiReview) {
                secrets.describeRequestedSecrets(listOf(contents.secret))
            } else {
                description
            }
            val currentPolicies = if (needsAiReview) {
                secrets.approvalPoliciesForNames(
                    listOf(contents.secret),
                    client.clientId,
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
                reviewResult?.review
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
                    "The parent invocation is no longer available."
            }
            val approvalSettingsDenied = denial == null &&
                evaluation?.secrets?.any { it.action == ApprovalAction.DENY } == true
            if (approvalSettingsDenied) {
                denial = InvocationDenialReason.POLICY_DENIED to
                    SSH_AUTHENTICATION_POLICY_DENIAL_MESSAGE
            }
            val aiDenied = denial == null && aiReview?.decision == AiReviewDecision.DENY
            if (aiDenied) {
                denial = InvocationDenialReason.POLICY_DENIED to
                    "AI review denied SSH authentication."
            }
            val shouldApprove = !aiInputsChanged && denial == null &&
                evaluation?.isFullyApproved(aiReview?.decision) == true
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
                denial != null -> protocol.deniedResponse(denial.first, denial.second)
                signature != null -> protocol.approvedResponse(signature)
                else -> null
            }
            val response = responsePlaintext?.let { plaintextResponse ->
                sealResponse(plaintextResponse) ?: return null
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
            val finalAuthentication = initialAuthentication.copy(
                decision = automaticDecision?.storedName,
                approvalEvaluationJson = evaluation?.let { json.encodeToString(it) },
                completionReason = denial?.first?.wireName,
                completionMessage = denial?.second,
                decidedAt = automaticDecision?.let { decidedAt },
            )
            val automaticDecisionAudit = automaticDecision?.let {
                AuditRecord(
                    type = AuditEventType.SSH_AUTHENTICATION_DECIDED,
                    outcome = when {
                        signature != null -> AuditOutcome.APPROVED
                        aiDenied || approvalSettingsDenied -> AuditOutcome.DENIED
                        denial?.first == InvocationDenialReason.INVALID_REQUEST ->
                            AuditOutcome.REJECTED
                        else -> AuditOutcome.FAILED
                    },
                    decisionSource = automaticDecisionSource?.toAuditDecisionSource(),
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
                    data = finalRequest.requestAuditData() + finalAuthentication.auditData() +
                        (reviewResult?.auditData(checkNotNull(aiReview))
                            ?: (aiReview?.auditData() ?: emptyMap())),
                )
            }
            val aiReviewAudit = aiReview?.let { reviewed ->
                AuditRecord(
                    type = AuditEventType.SSH_AUTHENTICATION_AI_REVIEWED,
                    outcome = reviewed.auditOutcome(),
                    decisionSource = AuditDecisionSource.AI_REVIEW,
                    subject = contents.secret,
                    detail = reviewed.auditFailureDetail(),
                    clientId = client.clientId,
                    clientName = client.name,
                    relayRequestId = relayRequestId,
                    data = finalRequest.requestAuditData() + finalAuthentication.auditData() +
                        (reviewResult?.auditData(reviewed) ?: reviewed.auditData()),
                )
            }
            val persisted = if (requestAlreadyInserted) {
                finishAiReview(
                    request = finalRequest,
                    authentication = finalAuthentication,
                    authorization = authorization,
                    aiReviewAudit = checkNotNull(aiReviewAudit),
                    automaticDecisionAudit = automaticDecisionAudit,
                )
            } else {
                receive(
                    request = finalRequest,
                    authentication = finalAuthentication,
                    client = client.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                    ),
                    acceptedPsks = acceptedPsks,
                    authorization = authorization.takeIf { automaticDecision != null },
                    automaticDecisionAudit = automaticDecisionAudit,
                    aiReviewAudit = aiReviewAudit,
                )
            }
            return when (persisted) {
                ConditionalRequestUpdate.APPLIED -> ProcessedRelayMessage
                ConditionalRequestUpdate.ACTION_REQUIRED -> ProcessedRelayMessage
                ConditionalRequestUpdate.UNAVAILABLE -> null
            }
        }

        if (!needsAiReview) return finishReview(null, requestAlreadyInserted = false)
        subscription.reviewFallback(credentials.deviceId)?.let { fallback ->
            return finishReview(fallback, requestAlreadyInserted = false)
        }

        withContext(NonCancellable) {
            check(
                receive(
                    request = initialRequest,
                    authentication = initialAuthentication,
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
        val request = dao.getRequestById(requestId)
            ?: return RequestDecisionResult.NotFound
        val authentication = dao.getSshAuthenticationRequest(requestId)
            ?: return RequestDecisionResult.NotFound
        if (!request.isPending(authentication)) {
            return RequestDecisionResult.NotPending
        }
        val message = authentication.message
            ?: return RequestDecisionResult.Invalid(SSH_AUTHENTICATION_INVALID_MESSAGE)
        val invocation = invocationSnapshot(request)
            ?: return RequestDecisionResult.ParentUnavailable
        val description = secrets.describeRequestedSecrets(listOf(authentication.secretName))
        val policy = secrets.approvalPoliciesForNames(
            listOf(authentication.secretName),
            request.clientId,
            TemporaryAccessOperation.SSH_AUTHENTICATE,
        ).singleOrNull() ?: return rejectChangedSecret(
            request,
            authentication,
            invocation,
            message,
            sealResponse,
        )
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
            ) ?: return RequestDecisionResult.ClientUnavailable
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
            ).asCurrentPolicyDenial()
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
            ?: return RequestDecisionResult.ParentUnavailable
        val publicKey = runCatching { sshKeys.importOpenSshPublicKey(expectedPublicKey) }
            .getOrElse { return RequestDecisionResult.SecretChangedSinceInvocation }
        val parsed = runCatching {
            protocol.validateMessage(
                message = message,
                expectedPublicKeyBlob = publicKey.blob(),
                expectedKeyAlgorithm = publicKey.algorithm.publicName,
            )
        }.getOrElse { return RequestDecisionResult.Invalid(SSH_AUTHENTICATION_INVALID_MESSAGE) }
        if (
            parsed.username != authentication.username ||
            parsed.method.wireName != authentication.method ||
            parsed.algorithm.wireName != authentication.algorithm ||
            parsed.hostKeyAlgorithm != authentication.hostKeyAlgorithm ||
            parsed.hostKeyFingerprint != authentication.hostKeyFingerprint
        ) {
            return RequestDecisionResult.Invalid(SSH_AUTHENTICATION_INVALID_MESSAGE)
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
            -> return rejectChangedSecret(
                request,
                authentication,
                invocation,
                message,
                sealResponse,
            )
            SshAuthenticationSignatureResult.SecretUnavailable -> {
                return RequestDecisionResult.SecretUnavailable
            }
            SshAuthenticationSignatureResult.SecretCorrupted -> {
                return RequestDecisionResult.SecretCorrupted
            }
            SshAuthenticationSignatureResult.UnsupportedEncryption -> {
                return RequestDecisionResult.UnsupportedEncryption
            }
        }
        val grant = if (allowTemporaryAccess) {
            planTemporaryAccess(
                policies = listOf(policy),
                storedEvaluation = storedEvaluation,
                now = currentTimeMillis(),
            )
                ?: return RequestDecisionResult.TemporaryAccessUnavailable
        } else {
            null
        }
        val response = sealResponse(request, protocol.approvedResponse(signature))
            ?: return RequestDecisionResult.ClientUnavailable
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

    private suspend fun rejectChangedSecret(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
        invocation: SshInvocationSnapshot,
        message: ByteArray,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): RequestDecisionResult {
        val response = sealResponse(
            request,
            protocol.deniedResponse(
                InvocationDenialReason.INVALID_REQUEST,
                SSH_AUTHENTICATION_SECRET_CHANGED_MESSAGE,
            ),
        ) ?: return RequestDecisionResult.ClientUnavailable
        return persistDecision(
            request = request,
            authentication = authentication,
            invocation = invocation,
            message = message,
            decision = ApprovalDecision.DENIED,
            response = response,
            decisionSource = DECISION_SOURCE_VALIDATION,
            denialReason = InvocationDenialReason.INVALID_REQUEST,
            denialMessage = SSH_AUTHENTICATION_SECRET_CHANGED_MESSAGE,
        ).asSecretChangedSinceInvocation()
    }

    suspend fun deny(
        requestId: String,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): RequestDecisionResult {
        val request = dao.getRequestById(requestId)
            ?: return RequestDecisionResult.NotFound
        val authentication = dao.getSshAuthenticationRequest(requestId)
            ?: return RequestDecisionResult.NotFound
        if (!request.isPending(authentication)) {
            return RequestDecisionResult.NotPending
        }
        val message = authentication.message
            ?: return RequestDecisionResult.Invalid(SSH_AUTHENTICATION_INVALID_MESSAGE)
        if (request.parentRequestId?.let { dao.getSecretUseRequest(it) } == null) {
            return RequestDecisionResult.ParentUnavailable
        }
        val response = sealResponse(
            request,
            protocol.deniedResponse(
                InvocationDenialReason.USER_DENIED,
                SSH_AUTHENTICATION_DENIAL_MESSAGE,
            ),
        ) ?: return RequestDecisionResult.ClientUnavailable
        return persistDecision(
            request = request,
            authentication = authentication,
            invocation = null,
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
        authorization: AuthorizationCommitment?,
        automaticDecisionAudit: AuditRecord?,
        aiReviewAudit: AuditRecord? = null,
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
            if (!parentInvocationIsAvailable(request)) {
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
                    aiReviewAudit?.let(::add)
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
            if (
                currentRequest.state != InboxRequestState.REVIEWING.storedName ||
                currentAuthentication.decision != null ||
                currentAuthentication.message == null ||
                authentication.message == null ||
                !currentAuthentication.message.contentEquals(authentication.message) ||
                !currentAuthentication.hasSameRequestDetails(authentication)
            ) {
                return@execute ConditionalRequestUpdate.UNAVAILABLE
            }
            if (
                authentication.decision == ApprovalDecision.APPROVED.storedName &&
                !parentInvocationIsAvailable(currentRequest)
            ) {
                dao.updateSshAuthenticationRequest(
                    request = currentRequest.copy(
                        state = InboxRequestState.ACTION_REQUIRED.storedName,
                        responseJson = null,
                    ),
                    authentication = currentAuthentication.copy(
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
        openCompletion: suspend () -> CompletionOpenResult,
    ): Boolean {
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
            val priorError = currentRequest.error
            val softwareMatches = completionResult?.clientSoftware ==
                currentRequest.clientSoftwareJson?.let(::decodeStoredClientSoftware)
            val valid = priorError == null && softwareMatches && when (completionResult) {
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
            val error = priorError ?: if (valid) {
                null
            } else {
                SSH_AUTHENTICATION_COMPLETION_VERIFICATION_ERROR
            }
            val now = currentTimeMillis()
            dao.updateSshAuthenticationRequest(
                request = currentRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    responseOutboxFinished = true,
                    error = error,
                    failureKind = currentRequest.failureKind ?: if (!valid) {
                        RequestFailureKind.VERIFICATION.storedName
                    } else {
                        null
                    },
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
                        data = currentRequest.requestAuditData() + authentication.auditData() +
                            auditDataOf(
                                "completion_valid" to valid,
                                "completion_result" to when (completionResult) {
                                    is SshAuthenticationCompletion.Approved ->
                                        ApprovalCompletionResult.APPROVED.storedName
                                    is SshAuthenticationCompletion.Denied ->
                                        ApprovalCompletionResult.DENIED.storedName
                                    is SshAuthenticationCompletion.Aborted ->
                                        ApprovalCompletionResult.ABORTED.storedName
                                    null -> null
                                },
                                "completion_reason" to when (completionResult) {
                                    is SshAuthenticationCompletion.Denied -> completionResult.reason
                                    is SshAuthenticationCompletion.Aborted -> completionResult.reason
                                    else -> null
                                },
                                "completion_message" to when (completionResult) {
                                    is SshAuthenticationCompletion.Denied -> completionResult.message
                                    is SshAuthenticationCompletion.Aborted -> completionResult.message
                                    else -> null
                                },
                                "returned_client_software" to completionResult?.clientSoftware?.let {
                                    json.parseToJsonElement(json.encodeToString(it))
                                },
                            ),
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
                    failureKind = currentRequest.failureKind
                        ?: RequestFailureKind.RELAY.storedName,
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
                        data = currentRequest.requestAuditData() + authentication.auditData() +
                            auditDataOf(
                                "completion_valid" to false,
                                "transport_error" to message,
                            ),
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
        invocation: SshInvocationSnapshot?,
        message: ByteArray,
        decision: ApprovalDecision,
        response: JsonElement,
        decisionSource: String,
        denialReason: InvocationDenialReason? = null,
        denialMessage: String? = null,
        authorization: AuthorizationCommitment? = null,
    ): RequestDecisionResult {
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
                ?: return@execute RequestDecisionResult.NotFound
            val currentAuthentication = dao.getSshAuthenticationRequest(request.id)
                ?: return@execute RequestDecisionResult.NotFound
            if (!currentRequest.isPending(currentAuthentication)) {
                return@execute RequestDecisionResult.NotPending
            }
            if (
                currentAuthentication.message?.contentEquals(message) != true ||
                !currentAuthentication.hasSameRequestDetails(authentication)
            ) {
                return@execute RequestDecisionResult.ParentUnavailable
            }
            if (
                decision == ApprovalDecision.APPROVED &&
                (invocation == null || !invocationSnapshotMatches(currentRequest, invocation))
            ) {
                return@execute RequestDecisionResult.ParentUnavailable
            }
            if (
                currentAuthentication.approvalEvaluationJson !=
                authentication.approvalEvaluationJson
            ) {
                return@execute RequestDecisionResult.ApprovalChanged
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
                ConditionalRequestUpdate.APPLIED -> RequestDecisionResult.Decided
                ConditionalRequestUpdate.ACTION_REQUIRED,
                ConditionalRequestUpdate.UNAVAILABLE,
                -> RequestDecisionResult.ApprovalChanged
            }
        }
    }

    private suspend fun persistTemporaryDecision(
        request: InboxRequestEntity,
        authentication: SshAuthenticationRequestEntity,
        invocation: SshInvocationSnapshot,
        message: ByteArray,
        response: JsonElement,
        authorization: AuthorizationCommitment,
        grant: TemporaryAccessPlan,
    ): RequestDecisionResult = writeTransaction.execute {
        val now = currentTimeMillis()
        val currentRequest = dao.getRequestById(request.id)
            ?: return@execute RequestDecisionResult.NotFound
        val currentAuthentication = dao.getSshAuthenticationRequest(request.id)
            ?: return@execute RequestDecisionResult.NotFound
        if (!currentRequest.isPending(currentAuthentication)) {
            return@execute RequestDecisionResult.NotPending
        }
        if (
            currentAuthentication.message?.contentEquals(message) != true ||
            !currentAuthentication.hasSameRequestDetails(authentication) ||
            currentAuthentication.approvalEvaluationJson != authentication.approvalEvaluationJson ||
            !invocationSnapshotMatches(currentRequest, invocation)
        ) {
            return@execute RequestDecisionResult.ApprovalChanged
        }
        if (
            !dao.authorizationMatches(
                authorization,
                currentRequest.clientId,
                TemporaryAccessOperation.SSH_AUTHENTICATE.storedName,
                now,
            )
        ) {
            return@execute RequestDecisionResult.ApprovalChanged
        }
        val temporaryAccessStarted = secrets.allowTemporaryAccess(
            policies = grant.policies,
            clientId = currentRequest.clientId,
            operation = TemporaryAccessOperation.SSH_AUTHENTICATE,
            expiresAt = grant.expiresAt,
        )
        val decisionSource = if (temporaryAccessStarted) {
            grant.decisionSource
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
        val authentication = dao.getSshAuthenticationRequest(requestId)
            ?: return@execute RequestDecisionResult.NotFound
        if (!currentRequest.isPending(authentication)) {
            return@execute RequestDecisionResult.NotPending
        }
        dao.updateSshAuthenticationRequest(
            currentRequest,
            authentication.copy(approvalEvaluationJson = approvalEvaluationJson),
        )
        RequestDecisionResult.ApprovalChanged
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

    private suspend fun requestAiReview(
        client: ClientEntity,
        contents: SshAuthenticationRequestMessage,
        messageDetails: SshAuthenticationMessageDetails,
        invocation: SecretUseRequestEntity,
        parentElapsedSeconds: Long?,
        evaluation: ApprovalEvaluation,
        policies: List<SecretApprovalPolicy>,
        credentials: RelayDeviceCredentials,
    ): AiReviewAttempt {
        val elapsedSeconds = parentElapsedSeconds ?: return AiReviewAttempt(
            review = AiReview(
                decision = AiReviewDecision.ASK_USER,
                explanation = "The relative timing of the parent invocation is unavailable.",
            ),
            request = null,
        )
        val invocationSecrets = invocation.providedSecretsJson?.let {
            decodeStoredApprovalReviewSecretFacts(it)
        } ?: return AiReviewAttempt(
            review = AiReview(
                decision = AiReviewDecision.ASK_USER,
                explanation = "The parent invocation context is unavailable.",
            ),
            request = null,
        )
        val request = approvalReviewSshAuthenticationRequest(
            client = client,
            secretName = contents.secret,
            details = messageDetails,
            invocation = invocation,
            invocationSecrets = invocationSecrets,
            parentElapsedSeconds = elapsedSeconds,
            evaluation = evaluation,
            policies = policies,
            deviceInstructions = credentials.instructions,
        )
        return AiReviewAttempt(
            review = performAiReview(approvalReviewer, subscription, credentials, request),
            request = request,
        )
    }

    private suspend fun credentialsForClient(client: ClientEntity): RelayDeviceCredentials? =
        when (val result = deviceCredentials.deviceCredentials(client.deviceIdentityId)) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            else -> null
        }

    private suspend fun invocationSnapshot(
        request: InboxRequestEntity,
    ): SshInvocationSnapshot? {
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

    private suspend fun parentInvocationIsAvailable(request: InboxRequestEntity): Boolean =
        invocationSnapshot(request) != null

    private fun encodeClientSoftware(value: ClientSoftware): String = json.encodeToString(value)

    private fun expectedPublicKey(
        invocation: SshInvocationSnapshot,
        secretName: String,
    ): String? = runCatching {
        storedJson.decodeFromString<List<SecretMetadata>>(invocation.secretDetailsJson)
    }.getOrNull()?.singleOrNull { secret ->
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
        data = request.requestAuditData() + authentication.auditData(),
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
        data = request.requestAuditData() + authentication.copy(
            decision = decision.storedName,
        ).auditData(),
    )

    private fun SshAuthenticationRequestEntity.auditData() = auditDataOf(
        "ssh_key" to secretName,
        "username" to username,
        "method" to method,
        "signature_algorithm" to algorithm,
        "host_key_algorithm" to hostKeyAlgorithm,
        "host_key_fingerprint" to hostKeyFingerprint,
        "approval_evaluation" to approvalEvaluationJson?.let(storedJson::parseToJsonElement),
        "decision" to decision,
        "completion_result" to completionResult,
        "completion_reason" to completionReason,
        "completion_message" to completionMessage,
        "decided_at" to decidedAt,
    )

}
