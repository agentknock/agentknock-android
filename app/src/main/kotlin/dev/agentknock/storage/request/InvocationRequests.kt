package dev.agentknock.storage.request

import dev.agentknock.protocol.InvocationCompletion
import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.protocol.InvocationProtocol
import dev.agentknock.protocol.InvocationRequestMessage
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
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.RequestedSecretsResult
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.TemporaryAccessOperation
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

private data class InvocationTemporaryGrant(
    val policies: List<SecretApprovalPolicy>,
    val expiresAt: Long,
    val evaluation: ApprovalEvaluation,
    val decisionSource: String,
)

/** Owns durable Invocation transitions without owning relay transport, envelope crypto, or AI. */
internal class InvocationRequests(
    private val dao: RequestDao,
    private val secrets: SecretRepository,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val invocationProtocol: InvocationProtocol = InvocationProtocol(),
    private val json: Json = Json,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun approve(
        requestId: String,
        allowTemporaryAccess: Boolean,
        openRequest: suspend (InboxRequestEntity) -> InvocationRequestMessage?,
        sealResponse: suspend (InboxRequestEntity, ByteArray) -> JsonElement?,
    ): InvocationDecisionResult {
        val request = dao.getRequestById(requestId) ?: return InvocationDecisionResult.NotFound
        val secretUseRequest = dao.getSecretUseRequest(requestId)
            ?: return InvocationDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            secretUseRequest.decision != null
        ) {
            return InvocationDecisionResult.NotPending
        }
        val contents = openRequest(request) ?: return InvocationDecisionResult.PairingUnavailable
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
        val authorization = latestDescription.authorizationCommitment(currentPolicies)
        if (currentEvaluation?.secrets?.any { it.action == ApprovalAction.DENY } == true) {
            val response = sealResponse(
                request,
                invocationProtocol.deniedResponse(
                    InvocationDenialReason.POLICY_DENIED,
                    SECRET_USE_POLICY_DENIAL_MESSAGE,
                ),
            ) ?: return InvocationDecisionResult.PairingUnavailable
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
            )
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
            return InvocationDecisionResult.SecretsChanged
        }
        val availableSecrets = when (val result = latestResolution.values) {
            is RequestedSecretsResult.Available -> result.secrets
            is RequestedSecretsResult.MissingSecrets -> {
                return InvocationDecisionResult.MissingSecrets(result.names)
            }
            is RequestedSecretsResult.ConflictingVariable -> {
                return InvocationDecisionResult.ConflictingVariable(result.name)
            }
            is RequestedSecretsResult.MissingEnvironmentVariables -> {
                return InvocationDecisionResult.Invalid(
                    "Secret ${result.secretName} has no ${result.names.joinToString()} variable.",
                )
            }
            is RequestedSecretsResult.EnvironmentOptionsForSshSecret -> {
                return InvocationDecisionResult.Invalid(
                    "Secret ${result.secretName} is not an environment-variable secret.",
                )
            }
            RequestedSecretsResult.MultipleSshKeys -> {
                return InvocationDecisionResult.Invalid("A request can use at most one SSH key.")
            }
            RequestedSecretsResult.UnsupportedSecretType -> {
                return InvocationDecisionResult.Invalid("A requested secret type is unsupported.")
            }
            RequestedSecretsResult.SecretUnavailable -> {
                return InvocationDecisionResult.SecretUnavailable
            }
            RequestedSecretsResult.SecretCorrupted -> {
                return InvocationDecisionResult.SecretCorrupted
            }
            RequestedSecretsResult.UnsupportedEncryption -> {
                return InvocationDecisionResult.UnsupportedEncryption
            }
        }
        val temporaryGrant = if (allowTemporaryAccess) {
            prepareTemporaryGrant(
                request = request,
                description = latestDescription,
                storedEvaluation = storedEvaluation,
            ) ?: return InvocationDecisionResult.TemporaryAccessUnavailable
        } else {
            null
        }
        val response = sealResponse(
            request,
            invocationProtocol.approvedResponse(
                availableSecrets.mapValues { (_, secret) -> secret.toResponseSecret() },
            ),
        ) ?: return InvocationDecisionResult.PairingUnavailable
        val providedSecretsJson = json.encodeToString(
            dev.agentknock.review.approvalReviewSecretFacts(
                latestDescription,
                availableSecrets,
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
    ): InvocationDecisionResult {
        val request = dao.getRequestById(requestId) ?: return InvocationDecisionResult.NotFound
        val secretUseRequest = dao.getSecretUseRequest(requestId)
            ?: return InvocationDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            secretUseRequest.decision != null
        ) {
            return InvocationDecisionResult.NotPending
        }
        val response = sealResponse(
            request,
            invocationProtocol.deniedResponse(
                InvocationDenialReason.USER_DENIED,
                SECRET_USE_DENIAL_MESSAGE,
            ),
        ) ?: return InvocationDecisionResult.PairingUnavailable
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
        completion: JsonElement,
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
            val softwareMatches = completionResult?.clientSoftware ==
                currentRequest.clientSoftwareJson?.let(::decodeClientSoftware)
            val valid = softwareMatches && when (completionResult) {
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
            val error = if (valid) null else SECRET_USE_COMPLETION_VERIFICATION_ERROR
            val auditDetail = when {
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
                    completionJson = completion.toString().takeIf {
                        opened is CompletionOpenResult.Opened
                    },
                    responseOutboxFinished = true,
                    error = error,
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
    ): InvocationDecisionResult {
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
                        ),
                    ),
                    now,
                )
            }
            when (result) {
                ConditionalRequestUpdate.APPLIED -> InvocationDecisionResult.Decided
                ConditionalRequestUpdate.ACTION_REQUIRED,
                ConditionalRequestUpdate.UNAVAILABLE,
                -> InvocationDecisionResult.SecretsChanged
            }
        }
    }

    private suspend fun prepareTemporaryGrant(
        request: InboxRequestEntity,
        description: RequestedSecretDescription,
        storedEvaluation: ApprovalEvaluation?,
    ): InvocationTemporaryGrant? {
        storedEvaluation ?: return null
        val evaluationsById = storedEvaluation.secrets.associateBy { it.secretId }
        val aiCanEscalateToTemporaryAccess =
            storedEvaluation.aiReview?.decision == AiReviewDecision.ASK_USER ||
                storedEvaluation.aiReview?.failure != null ||
                storedEvaluation.aiReview == null
        val protectedNames = description.reviewMetadata
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
        if (!storedEvaluation.hasSameSecretPolicies(currentEvaluation)) return null
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
        if (grantablePolicies.isEmpty()) return null
        val expiresAt = currentTimeMillis() + TEMPORARY_ACCESS_DURATION_MILLIS
        val grantedIds = grantablePolicies.map(SecretApprovalPolicy::secretId).toSet()
        val alsoApprovedByAi = storedEvaluation.aiReview?.decision == AiReviewDecision.APPROVE &&
            storedEvaluation.secrets.any {
                it.action == ApprovalAction.ASK_AI && it.secretId !in grantedIds
            }
        val alsoApprovedOnce = storedEvaluation.secrets.any {
            it.secretId !in grantedIds && it.action == ApprovalAction.ASK_ME
        }
        return InvocationTemporaryGrant(
            policies = grantablePolicies,
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
    }

    private suspend fun persistTemporaryDecision(
        request: InboxRequestEntity,
        secretUseRequest: SecretUseRequestEntity,
        response: JsonElement,
        providedSecretsJson: String,
        authorization: AuthorizationCommitment,
        grant: InvocationTemporaryGrant,
    ): InvocationDecisionResult {
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
                return@execute InvocationDecisionResult.SecretsChanged
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
                    ),
                ),
                now,
            )
            if (temporaryAccessStarted) {
                InvocationDecisionResult.Decided
            } else {
                InvocationDecisionResult.TemporaryAccessNotStarted
            }
        }
    }

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
    )

    private fun encodeStringList(values: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), values)

    private fun decodeStringList(value: String): List<String> =
        storedJson.decodeFromString(ListSerializer(String.serializer()), value)

    private fun decodeClientSoftware(value: String) =
        runCatching {
            storedJson.decodeFromString<dev.agentknock.protocol.ClientSoftware>(value)
        }.getOrNull()

}
