package dev.agentknock.storage.request

import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.InvocationCompletion
import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.protocol.InvocationProtocol
import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.relay.RelayClientState
import dev.agentknock.relay.RelayApprovalReviewClient
import dev.agentknock.review.approvalReviewRequest
import dev.agentknock.review.approvalReviewSecretFacts
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.AiReview
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.ApprovalPolicyEvaluator
import dev.agentknock.storage.approval.isFullyApproved
import dev.agentknock.storage.approval.requiresInvocationAiReview
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.auditDataOf
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.device.RelayDeviceCredentialsResult
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.RequestedSecretsResult
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SecretValues
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.storage.secret.containsSelectedSensitiveEnvironmentValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal const val SECRET_USE_DENIAL_MESSAGE = "Denied on device."
internal const val SECRET_USE_POLICY_DENIAL_MESSAGE =
    "Approval settings denied access to a requested secret."
private const val SECRET_USE_COMPLETION_VERIFICATION_ERROR =
    "Secret use completion could not be verified."

/**
 * Owns Invocation intake and durable transitions.
 *
 * RequestRepository owns relay transport, paired envelopes, operation serialization, and the
 * process-local lifetime of AI review jobs.
 */
internal class InvocationRequests(
    private val dao: RequestDao,
    private val secrets: SecretRepository,
    private val deviceCredentials: RelayDeviceCredentialSource,
    private val approvalReviewer: RelayApprovalReviewClient,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val invocationProtocol: InvocationProtocol = InvocationProtocol(),
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
        val contents = runCatching {
            invocationProtocol.decodeRequest(plaintext)
        }.getOrNull() ?: return null
        val environmentSelections = contents.environmentSelections()
        val resolution = secrets.resolveRequestedSecrets(contents.secrets, environmentSelections)
        val description = resolution.description
        val requestedSecrets = resolution.values
        val automaticDenial = automaticSecretUseDenial(requestedSecrets)
        val protectedSecretNames = description.reviewMetadata
            .filter { it.containsSelectedSensitiveEnvironmentValue() }
            .map { it.name }
        val approvalPolicies = if (automaticDenial == null) {
            secrets.approvalPoliciesForNames(
                protectedSecretNames,
                client.clientId,
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
        val needsAiReview = initialApprovalEvaluation?.requiresInvocationAiReview() == true
        val initialAvailableSecrets =
            (requestedSecrets as? RequestedSecretsResult.Available)?.secrets
        val initialProvidedSecretsJson = initialAvailableSecrets?.let { values ->
            json.encodeToString(
                approvalReviewSecretFacts(
                    description,
                    values,
                    resolution.nonSensitiveEnvironmentValues,
                ),
            )
        }
        val now = currentTimeMillis()
        val initialRequest = InboxRequestEntity(
            id = relayRequestId,
            parentRequestId = null,
            deviceIdentityId = client.deviceIdentityId,
            clientId = client.clientId,
            clientNameSnapshot = client.name,
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
            error = null,
            receivedAt = now,
            completedAt = null,
            exchangeEndedAt = null,
            responseOutboxFinished = false,
        )
        val initialSecretUse = secretUseRequestEntity(
            requestId = relayRequestId,
            client = client,
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
            val currentClient = if (needsAiReview) dao.getClient(client.clientId) else client
            val clientUnavailable = currentClient == null ||
                currentClient.deviceIdentityId != client.deviceIdentityId ||
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
                json.encodeToString(
                    approvalReviewSecretFacts(
                        currentDescription,
                        values,
                        currentResolution.nonSensitiveEnvironmentValues,
                    ),
                )
            }
            val currentProtectedSecretNames = currentDescription.reviewMetadata
                .filter { it.containsSelectedSensitiveEnvironmentValue() }
                .map { it.name }
            val currentApprovalPolicies = if (needsAiReview) {
                secrets.approvalPoliciesForNames(
                    currentProtectedSecretNames,
                    client.clientId,
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
                    currentClient.name != client.name ||
                    currentClient.instructions != client.instructions
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
            val allProtectedUsesApproved = !aiInputsChanged && approvalEvaluation
                ?.isFullyApproved(aiReview?.decision) == true
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
                sealResponse(responsePlaintext) ?: return null
            } else {
                null
            }
            val automaticApproval = planAutomaticApproval(
                outcome = when {
                    denial != null -> AutomaticApprovalOutcome.DENIED
                    allProtectedUsesApproved || !currentDescription.containsSensitiveMaterial ->
                        AutomaticApprovalOutcome.APPROVED
                    else -> AutomaticApprovalOutcome.ACTION_REQUIRED
                },
                evaluation = approvalEvaluation,
                aiDecision = aiReview?.decision,
                nonSensitive = denial == null &&
                    !currentDescription.containsSensitiveMaterial,
                denialSource = when {
                    aiDenial != null -> AutomaticApprovalDenialSource.AI
                    policyDenial != null -> AutomaticApprovalDenialSource.POLICY
                    else -> null
                },
            )
            val automaticDecision = automaticApproval.decision
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
                    client = client,
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
                    decisionSource = automaticApproval.decisionSource,
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
                    detail = reviewed.auditFailureDetail(),
                    clientId = client.clientId,
                    clientName = client.name,
                    relayRequestId = relayRequestId,
                    data = finalSecretUse.auditData(),
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
                    decisionSource = if (
                        denial?.first == InvocationDenialReason.INVALID_REQUEST
                    ) {
                        AuditDecisionSource.VALIDATION
                    } else {
                        automaticApproval.decisionSource?.toAuditDecisionSource()
                    },
                    subject = contents.secrets.joinToString(),
                    detail = if (
                        aiReview != null &&
                        (automaticApproval.aiApprovalUsed || aiDenial != null)
                    ) {
                        aiReview.auditFailureDetail()
                    } else {
                        denial?.second
                    },
                    clientId = client.clientId,
                    clientName = client.name,
                    relayRequestId = relayRequestId,
                    data = finalSecretUse.auditData(),
                )
            }
            val persistence = if (requestAlreadyInserted) {
                finishAiReview(
                    request = finalRequest,
                    secretUseRequest = finalSecretUse,
                    authorization = authorization,
                    aiReviewAudit = checkNotNull(aiReviewAudit),
                    automaticDecisionAudit = automaticDecisionAudit,
                )
            } else {
                receive(
                    request = finalRequest,
                    secretUseRequest = finalSecretUse,
                    client = client.copy(
                        clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                        lastSeenAt = now,
                    ),
                    acceptedPsks = acceptedPsks,
                    authorization = authorization.takeIf { automaticDecision != null },
                    automaticDecisionAudit = automaticDecisionAudit,
                )
            }
            when (persistence) {
                ConditionalRequestUpdate.APPLIED -> Unit
                ConditionalRequestUpdate.ACTION_REQUIRED -> return ProcessedRelayMessage
                ConditionalRequestUpdate.UNAVAILABLE -> return null
            }
            return ProcessedRelayMessage
        }

        if (!needsAiReview) return finishReview(null, requestAlreadyInserted = false)

        withContext(NonCancellable) {
            val received = receive(
                request = initialRequest,
                secretUseRequest = initialSecretUse,
                client = client.copy(
                    clientSoftwareJson = encodeClientSoftware(contents.clientSoftware),
                    lastSeenAt = now,
                ),
                acceptedPsks = acceptedPsks,
                authorization = null,
                automaticDecisionAudit = null,
            )
            check(received == ConditionalRequestUpdate.APPLIED)
            if (
                !launchAiReview(
                    relayRequestId,
                    initialRequest.requestJson,
                    {
                        requestAiReview(
                            client = client,
                            contents = contents,
                            description = description,
                            values = checkNotNull(initialAvailableSecrets),
                            nonSensitiveEnvironmentValues =
                                resolution.nonSensitiveEnvironmentValues,
                            evaluation = initialApprovalEvaluation,
                            policies = approvalPolicies,
                            credentials = credentials,
                        )
                    },
                    { finishReview(it, requestAlreadyInserted = true) },
                )
            ) {
                dao.recoverInterruptedAiReview(
                    relayRequestId,
                    initialRequest.requestJson,
                )
            }
        }
        return ProcessedRelayMessage
    }


    suspend fun approve(
        requestId: String,
        allowTemporaryAccess: Boolean,
        openRequest: suspend (InboxRequestEntity) -> ByteArray?,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): RequestDecisionResult {
        val request = dao.getRequestById(requestId) ?: return RequestDecisionResult.NotFound
        val secretUseRequest = dao.getSecretUseRequest(requestId)
            ?: return RequestDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            secretUseRequest.decision != null
        ) {
            return RequestDecisionResult.NotPending
        }
        val plaintext = openRequest(request) ?: return RequestDecisionResult.ClientUnavailable
        val contents = runCatching { invocationProtocol.decodeRequest(plaintext) }.getOrNull()
            ?: return RequestDecisionResult.ClientUnavailable
        val requestedSecrets = contents.secrets
        val storedSecrets = storedJson.decodeFromString<List<SecretMetadata>>(
            secretUseRequest.secretDetailsJson,
        )
        val environmentSelections = contents.environmentSelections()
        val latestResolution = secrets.resolveRequestedSecrets(
            requestedSecrets,
            environmentSelections,
        )
        val latestDescription = latestResolution.description
        val storedMissingSecrets = decodeStringList(secretUseRequest.missingSecretsJson)
        val protectedNames = latestDescription.reviewMetadata
            .filter { it.containsSelectedSensitiveEnvironmentValue() }
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
        val authorization = latestDescription.authorizationCommitment(currentPolicies)
        if (currentEvaluation?.secrets?.any { it.action == ApprovalAction.DENY } == true) {
            val response = sealResponse(
                request,
                invocationProtocol.deniedResponse(
                    InvocationDenialReason.POLICY_DENIED,
                    SECRET_USE_POLICY_DENIAL_MESSAGE,
                ),
            ) ?: return RequestDecisionResult.ClientUnavailable
            return persistDecision(
                request = request,
                secretUseRequest = secretUseRequest,
                decision = ApprovalDecision.DENIED,
                response = response,
                providedSecretsJson = secretUseRequest.providedSecretsJson,
                decisionSource = DECISION_SOURCE_POLICY,
                denialReason = InvocationDenialReason.POLICY_DENIED,
                denialMessage = SECRET_USE_POLICY_DENIAL_MESSAGE,
                authorization = authorization,
            ).asCurrentPolicyDenial()
        }
        if (
            latestDescription.secrets != storedSecrets ||
            latestDescription.missingSecrets != storedMissingSecrets ||
            latestDescription.containsSensitiveMaterial !=
            secretUseRequest.containsSensitiveMaterial ||
            approvalContextChanged
        ) {
            dao.updateSecretUseRequest(
                request = request,
                secretUseRequest = secretUseRequest.copy(
                    secretDetailsJson = json.encodeToString(latestDescription.secrets),
                    missingSecretsJson = encodeStringList(latestDescription.missingSecrets),
                    containsSensitiveMaterial = latestDescription.containsSensitiveMaterial,
                    approvalEvaluationJson = currentEvaluation?.let { json.encodeToString(it) },
                ),
            )
            return RequestDecisionResult.SecretChanged
        }
        val availableSecrets = when (val result = latestResolution.values) {
            is RequestedSecretsResult.Available -> result.secrets
            is RequestedSecretsResult.MissingSecrets -> {
                return RequestDecisionResult.MissingSecrets(result.names)
            }
            is RequestedSecretsResult.ConflictingVariable -> {
                return RequestDecisionResult.ConflictingVariable(result.name)
            }
            is RequestedSecretsResult.MissingEnvironmentVariables -> {
                return RequestDecisionResult.Invalid(
                    "Secret ${result.secretName} has no ${result.names.joinToString()} variable.",
                )
            }
            is RequestedSecretsResult.EnvironmentOptionsForSshSecret -> {
                return RequestDecisionResult.Invalid(
                    "Secret ${result.secretName} is not an environment-variable secret.",
                )
            }
            RequestedSecretsResult.MultipleSshKeys -> {
                return RequestDecisionResult.Invalid("A request can use at most one SSH key.")
            }
            RequestedSecretsResult.UnsupportedSecretType -> {
                return RequestDecisionResult.Invalid("A requested secret type is unsupported.")
            }
            RequestedSecretsResult.SecretUnavailable -> {
                return RequestDecisionResult.SecretUnavailable
            }
            RequestedSecretsResult.SecretCorrupted -> {
                return RequestDecisionResult.SecretCorrupted
            }
            RequestedSecretsResult.UnsupportedEncryption -> {
                return RequestDecisionResult.UnsupportedEncryption
            }
        }
        val temporaryGrant = if (allowTemporaryAccess) {
            storedEvaluation ?: return RequestDecisionResult.TemporaryAccessUnavailable
            val latestPolicies = secrets.approvalPoliciesForNames(
                protectedNames,
                request.clientId,
                TemporaryAccessOperation.INVOCATION,
            )
            planTemporaryAccess(
                policies = latestPolicies,
                storedEvaluation = storedEvaluation,
                now = currentTimeMillis(),
            ) ?: return RequestDecisionResult.TemporaryAccessUnavailable
        } else {
            null
        }
        val response = sealResponse(
            request,
            invocationProtocol.approvedResponse(
                availableSecrets.mapValues { (_, secret) -> secret.toResponseSecret() },
            ),
        ) ?: return RequestDecisionResult.ClientUnavailable
        val providedSecretsJson = json.encodeToString(
            dev.agentknock.review.approvalReviewSecretFacts(
                latestDescription,
                availableSecrets,
                latestResolution.nonSensitiveEnvironmentValues,
            ),
        )
        return if (temporaryGrant == null) {
            persistDecision(
                request = request,
                secretUseRequest = secretUseRequest,
                decision = ApprovalDecision.APPROVED,
                response = response,
                providedSecretsJson = providedSecretsJson,
                decisionSource = DECISION_SOURCE_USER,
                authorization = authorization,
            )
        } else {
            persistTemporaryDecision(
                request = request,
                secretUseRequest = secretUseRequest,
                response = response,
                providedSecretsJson = providedSecretsJson,
                authorization = authorization,
                grant = temporaryGrant,
            )
        }
    }

    suspend fun deny(
        requestId: String,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): RequestDecisionResult {
        val request = dao.getRequestById(requestId) ?: return RequestDecisionResult.NotFound
        val secretUseRequest = dao.getSecretUseRequest(requestId)
            ?: return RequestDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            secretUseRequest.decision != null
        ) {
            return RequestDecisionResult.NotPending
        }
        val response = sealResponse(
            request,
            invocationProtocol.deniedResponse(
                InvocationDenialReason.USER_DENIED,
                SECRET_USE_DENIAL_MESSAGE,
            ),
        ) ?: return RequestDecisionResult.ClientUnavailable
        return persistDecision(
            request = request,
            secretUseRequest = secretUseRequest,
            decision = ApprovalDecision.DENIED,
            response = response,
            providedSecretsJson = secretUseRequest.providedSecretsJson,
            decisionSource = DECISION_SOURCE_USER,
            denialReason = InvocationDenialReason.USER_DENIED,
            denialMessage = SECRET_USE_DENIAL_MESSAGE,
        )
    }

    suspend fun receive(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        client: ClientEntity,
        acceptedPsks: AcceptedRequestPsks,
        authorization: AuthorizationCommitment?,
        automaticDecisionAudit: AuditRecord?,
    ): ConditionalRequestUpdate {
        val conditional = secretUseRequest.decision != null
        require(conditional == (automaticDecisionAudit != null)) {
            "An automatic invocation decision and its audit must be persisted together"
        }
        require(
            automaticDecisionAudit == null ||
                automaticDecisionAudit.type == AuditEventType.SECRET_USE_DECIDED,
        ) { "Unexpected automatic invocation audit type" }
        require(!conditional || authorization != null) {
            "An automatic invocation decision must bind its authorization state"
        }
        return writeTransaction.execute {
            val authorized = !conditional || dao.authorizationMatches(
                checkNotNull(authorization),
                request.clientId,
                TemporaryAccessOperation.INVOCATION.storedName,
                currentTimeMillis(),
            )
            val storedRequest = if (authorized) request else request.copy(
                state = InboxRequestState.ACTION_REQUIRED.storedName,
                responseJson = null,
            )
            val storedSecretUse = if (authorized) secretUseRequest else secretUseRequest.copy(
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
            dao.insertSecretUseRequest(
                request = storedRequest,
                secretUseRequest = storedSecretUse,
                client = storedClient,
                requestPsk = acceptedPsks.requestPsk,
                currentClientPsk = acceptedPsks.currentClientPsk,
                previousClientPsk = acceptedPsks.previousClientPsk,
            )
            val records = buildList {
                add(receivedAudit(storedRequest, storedSecretUse))
                if (authorized) automaticDecisionAudit?.let(::add)
            }
            audit.append(records, request.receivedAt)
            if (authorized) {
                ConditionalRequestUpdate.APPLIED
            } else {
                ConditionalRequestUpdate.ACTION_REQUIRED
            }
        }
    }

    suspend fun finishAiReview(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        authorization: AuthorizationCommitment,
        aiReviewAudit: AuditRecord,
        automaticDecisionAudit: AuditRecord?,
    ): ConditionalRequestUpdate {
        val conditional = secretUseRequest.decision != null
        require(aiReviewAudit.type == AuditEventType.SECRET_USE_AI_REVIEWED) {
            "Unexpected invocation AI-review audit type"
        }
        require(conditional == (automaticDecisionAudit != null)) {
            "An automatic invocation decision and its audit must be persisted together"
        }
        require(
            automaticDecisionAudit == null ||
                automaticDecisionAudit.type == AuditEventType.SECRET_USE_DECIDED,
        ) { "Unexpected automatic invocation audit type" }
        return writeTransaction.execute {
            val occurredAt = currentTimeMillis()
            val result = dao.updateSecretUseRequestIfAuthorized(
                request = request,
                secretUseRequest = secretUseRequest,
                authorization = authorization,
                clientId = request.clientId,
                operation = TemporaryAccessOperation.INVOCATION.storedName,
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
        val plaintext = (opened as? CompletionOpenResult.Opened)?.plaintext
        val completionResult = plaintext?.let {
            decodeWireCompletionOrNull { invocationProtocol.decodeCompletion(it) }
        }
        return writeTransaction.execute {
            val currentRequest = dao.getRequestById(request.id) ?: return@execute false
            val secretUseRequest = dao.getSecretUseRequest(request.id) ?: return@execute false
            if (currentRequest.exchangeEndedAt != null) {
                return@execute true
            }
            if (opened == CompletionOpenResult.RetryLater) return@execute false
            val priorError = currentRequest.error
            val softwareMatches = completionResult?.clientSoftware ==
                currentRequest.clientSoftwareJson?.let(::decodeStoredClientSoftware)
            val valid = priorError == null && softwareMatches && when (completionResult) {
                is InvocationCompletion.Approved -> {
                    secretUseRequest.decision == ApprovalDecision.APPROVED.storedName
                }
                is InvocationCompletion.Denied -> {
                    if (secretUseRequest.decision != ApprovalDecision.DENIED.storedName) {
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
            val error = priorError ?: if (valid) {
                null
            } else {
                SECRET_USE_COMPLETION_VERIFICATION_ERROR
            }
            val auditDetail = when {
                priorError != null -> priorError
                !valid -> SECRET_USE_COMPLETION_VERIFICATION_ERROR
                completionResult is InvocationCompletion.Denied ->
                    secretUseRequest.completionMessage ?: SECRET_USE_DENIAL_MESSAGE
                completionResult is InvocationCompletion.Aborted ->
                    "Secret use was aborted by the client."
                else -> null
            }
            val now = currentTimeMillis()
            dao.updateSecretUseRequest(
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
                secretUseRequest = secretUseRequest.copy(
                    completionResult = when {
                        !valid -> null
                        completionResult is InvocationCompletion.Approved -> {
                            ApprovalCompletionResult.APPROVED.storedName
                        }
                        completionResult is InvocationCompletion.Denied -> {
                            ApprovalCompletionResult.DENIED.storedName
                        }
                        completionResult is InvocationCompletion.Aborted -> {
                            ApprovalCompletionResult.ABORTED.storedName
                        }
                        else -> null
                    },
                    completionReason = when {
                        !valid -> null
                        completionResult is InvocationCompletion.Denied -> completionResult.reason
                        completionResult is InvocationCompletion.Aborted -> completionResult.reason
                        else -> null
                    },
                    completionMessage = when {
                        !valid -> null
                        completionResult is InvocationCompletion.Denied -> completionResult.message
                        completionResult is InvocationCompletion.Aborted -> completionResult.message
                        else -> null
                    },
                ),
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_USE_COMPLETED,
                        outcome = when {
                            !valid -> AuditOutcome.FAILED
                            completionResult is InvocationCompletion.Approved ->
                                AuditOutcome.COMPLETED
                            completionResult is InvocationCompletion.Denied -> AuditOutcome.DENIED
                            else -> AuditOutcome.ABORTED
                        },
                        subject = decodeStringList(secretUseRequest.secretsJson).joinToString(),
                        detail = auditDetail,
                        clientId = currentRequest.clientId,
                        clientName = currentRequest.clientNameSnapshot,
                        relayRequestId = currentRequest.id,
                        data = secretUseRequest.auditData(),
                    ),
                ),
                now,
            )
            dao.deleteEndedRequestPsk(request.id)
            true
        }
    }

    private suspend fun terminalCompletionHandled(requestId: String): Boolean? =
        writeTransaction.execute {
            val terminal = dao.getRequestById(requestId) ?: return@execute null
            if (dao.getSecretUseRequest(requestId) == null || terminal.exchangeEndedAt == null) {
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
            val secretUseRequest = dao.getSecretUseRequest(request.id) ?: return@execute
            dao.updateSecretUseRequest(
                currentRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    error = message,
                    failureKind = currentRequest.failureKind
                        ?: RequestFailureKind.RELAY.storedName,
                    completedAt = now,
                    exchangeEndedAt = now,
                    responseOutboxFinished = true,
                ),
                secretUseRequest,
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_USE_COMPLETED,
                        outcome = AuditOutcome.FAILED,
                        subject = decodeStringList(secretUseRequest.secretsJson).joinToString(),
                        detail = message,
                        clientId = currentRequest.clientId,
                        clientName = currentRequest.clientNameSnapshot,
                        relayRequestId = currentRequest.id,
                        data = secretUseRequest.auditData(),
                    ),
                ),
                now,
            )
            dao.deleteEndedRequestPsk(request.id)
        }
    }

    private suspend fun persistDecision(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        decision: ApprovalDecision,
        response: JsonElement,
        providedSecretsJson: String?,
        decisionSource: String,
        denialReason: InvocationDenialReason? = null,
        denialMessage: String? = null,
        authorization: AuthorizationCommitment? = null,
    ): RequestDecisionResult {
        require(decision != ApprovalDecision.APPROVED || providedSecretsJson != null) {
            "An approved invocation must record its provided secrets"
        }
        require(decision != ApprovalDecision.APPROVED || authorization != null) {
            "An approved invocation must bind its authorization state"
        }
        require(decision != ApprovalDecision.DENIED || denialReason != null) {
            "A denied invocation must record its reason"
        }
        require(decision != ApprovalDecision.DENIED || denialMessage != null) {
            "A denied invocation must record its message"
        }
        return writeTransaction.execute {
            val now = currentTimeMillis()
            val updatedRequest = request.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = response.toString(),
                responseOutboxFinished = false,
            )
            val updatedSecretUse = secretUseRequest.copy(
                decision = decision.storedName,
                decisionSource = decisionSource,
                providedSecretsJson = providedSecretsJson,
                completionReason = denialReason?.wireName,
                completionMessage = denialMessage,
                decidedAt = now,
            )
            val result = if (authorization != null) {
                dao.updateSecretUseRequestIfAuthorized(
                    request = updatedRequest,
                    secretUseRequest = updatedSecretUse,
                    authorization = authorization,
                    clientId = request.clientId,
                    operation = TemporaryAccessOperation.INVOCATION.storedName,
                    now = now,
                )
            } else {
                dao.updateSecretUseRequest(updatedRequest, updatedSecretUse)
                ConditionalRequestUpdate.APPLIED
            }
            if (result == ConditionalRequestUpdate.APPLIED) {
                audit.append(
                    listOf(
                        AuditRecord(
                            type = AuditEventType.SECRET_USE_DECIDED,
                            outcome = if (decision == ApprovalDecision.APPROVED) {
                                AuditOutcome.APPROVED
                            } else {
                                AuditOutcome.DENIED
                            },
                            decisionSource = decisionSource.toAuditDecisionSource(),
                            subject = decodeStringList(secretUseRequest.secretsJson).joinToString(),
                            clientId = request.clientId,
                            clientName = request.clientNameSnapshot,
                            relayRequestId = request.id,
                            data = secretUseRequest.auditData(),
                        ),
                    ),
                    now,
                )
            }
            when (result) {
                ConditionalRequestUpdate.APPLIED -> RequestDecisionResult.Decided
                ConditionalRequestUpdate.ACTION_REQUIRED,
                ConditionalRequestUpdate.UNAVAILABLE,
                -> RequestDecisionResult.SecretChanged
            }
        }
    }

    private suspend fun persistTemporaryDecision(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        response: JsonElement,
        providedSecretsJson: String,
        authorization: AuthorizationCommitment,
        grant: TemporaryAccessPlan,
    ): RequestDecisionResult {
        val evaluationJson = json.encodeToString(grant.evaluation)
        return writeTransaction.execute {
            val now = currentTimeMillis()
            if (
                !dao.authorizationMatches(
                    authorization,
                    request.clientId,
                    TemporaryAccessOperation.INVOCATION.storedName,
                    now,
                )
            ) {
                return@execute RequestDecisionResult.SecretChanged
            }
            val temporaryAccessStarted = secrets.allowTemporaryAccess(
                policies = grant.policies,
                clientId = request.clientId,
                operation = TemporaryAccessOperation.INVOCATION,
                expiresAt = grant.expiresAt,
            )
            val decisionSource = if (temporaryAccessStarted) {
                grant.decisionSource
            } else {
                DECISION_SOURCE_USER
            }
            dao.updateSecretUseRequest(
                request.copy(
                    state = InboxRequestState.WAITING.storedName,
                    responseJson = response.toString(),
                    responseOutboxFinished = false,
                ),
                secretUseRequest.copy(
                    decision = ApprovalDecision.APPROVED.storedName,
                    decisionSource = decisionSource,
                    providedSecretsJson = providedSecretsJson,
                    approvalEvaluationJson = if (temporaryAccessStarted) {
                        evaluationJson
                    } else {
                        secretUseRequest.approvalEvaluationJson
                    },
                    completionReason = null,
                    completionMessage = null,
                    decidedAt = now,
                ),
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_USE_DECIDED,
                        outcome = AuditOutcome.APPROVED,
                        decisionSource = decisionSource.toAuditDecisionSource(),
                        subject = decodeStringList(secretUseRequest.secretsJson).joinToString(),
                        clientId = request.clientId,
                        clientName = request.clientNameSnapshot,
                        relayRequestId = request.id,
                        data = secretUseRequest.auditData(),
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
        client: ClientEntity,
        contents: InvocationRequestMessage,
        description: RequestedSecretDescription,
        values: Map<String, SecretValues>,
        nonSensitiveEnvironmentValues: Map<String, Map<String, String>>,
        evaluation: ApprovalEvaluation,
        policies: List<SecretApprovalPolicy>,
        credentials: RelayDeviceCredentials,
    ): AiReview {
        val request = approvalReviewRequest(
            client = client,
            contents = contents,
            description = description,
            values = values,
            nonSensitiveEnvironmentValues = nonSensitiveEnvironmentValues,
            evaluation = evaluation,
            policies = policies,
            deviceInstructions = credentials.instructions,
        )
        return performAiReview(approvalReviewer, credentials, request)
    }

    private fun secretUseRequestEntity(
        requestId: String,
        client: ClientEntity,
        contents: InvocationRequestMessage,
        description: RequestedSecretDescription,
        now: Long,
    ) = SecretUseRequestEntity(
        requestId = requestId,
        hostname = client.hostname,
        platform = client.platform,
        architecture = client.architecture,
        machineId = client.machineId,
        osVersion = client.osVersion,
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

    private suspend fun credentialsForClient(client: ClientEntity): RelayDeviceCredentials? {
        return when (
            val result = deviceCredentials.deviceCredentials(client.deviceIdentityId)
        ) {
            is RelayDeviceCredentialsResult.Available -> result.credentials
            else -> null
        }
    }

    private fun encodeClientSoftware(value: ClientSoftware): String = json.encodeToString(value)

    private fun RequestedSecretDescription.hasSameSecretRevisions(
        other: RequestedSecretDescription,
    ): Boolean = reviewMetadata.associate { it.id to it.revision } ==
        other.reviewMetadata.associate { it.id to it.revision }

    private fun receivedAudit(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
    ) = AuditRecord(
        type = AuditEventType.SECRET_USE_RECEIVED,
        outcome = AuditOutcome.RECEIVED,
        subject = decodeStringList(secretUseRequest.secretsJson).joinToString(),
        clientId = request.clientId,
        clientName = request.clientNameSnapshot,
        relayRequestId = request.id,
        data = secretUseRequest.auditData(),
    )

    private fun SecretUseRequestEntity.auditData() = auditDataOf(
        "secrets" to decodeStringList(secretsJson),
        "command" to command,
        "arguments" to decodeStringList(argumentsJson),
        "working_directory" to workingDirectory,
        "executable_path" to executablePath,
        "executable_hash" to executableHash,
        "executable_mode" to executableMode,
        "stdin" to stdinKind,
        "stdout" to stdoutKind,
        "stderr" to stderrKind,
        "launcher_chain" to decodeStringList(launcherChainJson),
        "reason" to reason,
        "hostname" to hostname,
        "platform" to platform,
        "architecture" to architecture,
    )

    private fun encodeStringList(values: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), values)

    private fun decodeStringList(value: String): List<String> =
        storedJson.decodeFromString(ListSerializer(String.serializer()), value)

}
