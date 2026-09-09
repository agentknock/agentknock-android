package dev.agentknock.storage.request

import dev.agentknock.protocol.SecretListProtocol
import dev.agentknock.protocol.SecretListSecret
import dev.agentknock.protocol.SecretUploadContents
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.protocol.SecretUploadProtocol
import dev.agentknock.protocol.SecretUploadRequestMessage
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.auditDataOf
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.runCatchingNonCancellation
import dev.agentknock.storage.secret.ApplySecretUploadResult
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.EnvironmentSecretUpload
import dev.agentknock.storage.secret.EnvironmentSecretUploadPreparation
import dev.agentknock.storage.secret.EnvironmentSecretUploadResult
import dev.agentknock.storage.secret.PreparedEnvironmentSecretUpload
import dev.agentknock.storage.secret.PreparedSshSecretUpload
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SecretUploadTarget
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshKeyCodec
import dev.agentknock.storage.secret.SshPublicKey
import dev.agentknock.storage.secret.SshSecretUpload
import dev.agentknock.storage.secret.SshSecretUploadPreparation
import dev.agentknock.storage.secret.SshSecretUploadResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private const val SECRET_LIST_COMPLETION_VERIFICATION_ERROR =
    "Secret list completion could not be verified."
private const val SECRET_UPLOAD_COMPLETION_VERIFICATION_ERROR =
    "Secret upload completion could not be verified."

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
    val target: SecretUploadTarget? = null,
    val environmentVariables: List<SecretUploadEnvironmentVariableEntity> = emptyList(),
    val sshKey: SecretUploadSshKeyEntity? = null,
    val error: String? = null,
)

private sealed interface PreparedSecretUploadApproval {
    data class Environment(val upload: PreparedEnvironmentSecretUpload) :
        PreparedSecretUploadApproval

    data class Ssh(val upload: PreparedSshSecretUpload) : PreparedSecretUploadApproval
}

private sealed interface SecretUploadApprovalPreparation {
    data class Ready(val upload: PreparedSecretUploadApproval) : SecretUploadApprovalPreparation

    data class Failed(val result: SecretUploadDecisionResult) : SecretUploadApprovalPreparation
}

internal data class SecretUploadLifecycle(
    val state: InboxRequestState,
    val completed: Boolean,
)

internal fun secretUploadLifecycle(
    decision: String?,
    transportFinished: Boolean,
): SecretUploadLifecycle =
    when {
        decision == null -> SecretUploadLifecycle(InboxRequestState.ACTION_REQUIRED, false)
        !transportFinished -> SecretUploadLifecycle(InboxRequestState.WAITING, false)
        else -> SecretUploadLifecycle(InboxRequestState.COMPLETED, true)
    }

internal sealed interface SecretUploadVariableValue {
    data class Available(val value: String) : SecretUploadVariableValue

    data class AuthenticationRequired(val name: String) : SecretUploadVariableValue

    data object NotFound : SecretUploadVariableValue

    data object Unavailable : SecretUploadVariableValue

    data object Corrupted : SecretUploadVariableValue

    data object UnsupportedEncryption : SecretUploadVariableValue
}

internal sealed interface SecretUploadSensitivityResult {
    data object Changed : SecretUploadSensitivityResult

    data object NotFound : SecretUploadSensitivityResult

    data class AuthenticationRequired(val name: String) : SecretUploadSensitivityResult
}

internal sealed interface SecretUploadDecisionResult {
    data class Approved(val secretId: String) : SecretUploadDecisionResult

    data object Rejected : SecretUploadDecisionResult

    data object NotPending : SecretUploadDecisionResult

    data object NotFound : SecretUploadDecisionResult

    data class Invalid(val message: String) : SecretUploadDecisionResult

    data class Invalidated(val message: String) : SecretUploadDecisionResult

    data object SecretUnavailable : SecretUploadDecisionResult

    data object SecretCorrupted : SecretUploadDecisionResult

    data object UnsupportedEncryption : SecretUploadDecisionResult
}

/** Owns secret-list and secret-upload request state, without owning relay transport or dispatch. */
internal class SecretManagementRequests(
    private val dao: RequestDao,
    private val material: RequestMaterialStore,
    private val secrets: SecretRepository,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val sshKeys: SshKeyCodec = SshKeyCodec(),
    private val secretListProtocol: SecretListProtocol = SecretListProtocol(),
    private val secretUploadProtocol: SecretUploadProtocol = SecretUploadProtocol(),
    private val json: Json = Json,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun receiveSecretList(
        client: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        plaintext: ByteArray,
        acceptedPsks: AcceptedRequestPsks,
        sealResponse: (ByteArray) -> JsonElement?,
    ): JsonElement? {
        val contents =
            runCatching { secretListProtocol.decodeRequest(plaintext) }.getOrNull() ?: return null
        val secretMetadata = secrets.listSecretsForClient()
        val responsePlaintext =
            secretListProtocol.response(
                secretMetadata.associateTo(sortedMapOf()) { secret ->
                    secret.name to
                        SecretListSecret(
                            description = secret.description,
                            type = secret.type,
                            environmentVariableNames = secret.environmentVariableNames,
                            sshPublicKey = secret.sshPublicKey,
                        )
                }
            )
        val response = sealResponse(responsePlaintext) ?: return null
        val now = currentTimeMillis()
        val software = json.encodeToString(contents.clientSoftware)
        writeTransaction.execute {
            dao.insertSecretListRequest(
                request =
                    InboxRequestEntity(
                        id = relayRequestId,
                        parentRequestId = null,
                        deviceIdentityId = client.deviceIdentityId,
                        clientId = client.clientId,
                        clientNameSnapshot = client.name,
                        clientSoftwareJson = software,
                        kind = RequestKind.SECRET_LIST.storedName,
                        state = InboxRequestState.WAITING.storedName,
                        listed = false,
                        requestJson = requestPayload.toString(),
                        responseJson = response.toString(),
                        error = null,
                        receivedAt = now,
                        completedAt = null,
                        exchangeEndedAt = null,
                        responseOutboxFinished = false,
                    ),
                client = client.copy(clientSoftwareJson = software, lastSeenAt = now),
                requestPsk = acceptedPsks.requestPsk,
                currentClientPsk = acceptedPsks.currentClientPsk,
                previousClientPsk = acceptedPsks.previousClientPsk,
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_LIST_RECEIVED,
                        outcome = AuditOutcome.RECEIVED,
                        subject = "${secretMetadata.size} secrets",
                        detail = secretMetadata.joinToString { "${it.name} (${it.type})" },
                        clientId = client.clientId,
                        clientName = client.name,
                        relayRequestId = relayRequestId,
                        data =
                            auditDataOf(
                                "device_identity_id" to client.deviceIdentityId,
                                "request_kind" to RequestKind.SECRET_LIST.storedName,
                                "client_software" to json.parseToJsonElement(software),
                                "received_at" to now,
                                "secret_count" to secretMetadata.size,
                                "secrets" to json.encodeToJsonElement(secretMetadata),
                            ),
                    )
                ),
                now,
            )
        }
        return response
    }

    suspend fun receiveSecretUpload(
        client: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        plaintext: ByteArray,
        acceptedPsks: AcceptedRequestPsks,
        sealResponse: (ByteArray) -> JsonElement?,
    ): JsonElement? {
        val contents =
            runCatching { secretUploadProtocol.decodeRequest(plaintext) }.getOrNull() ?: return null
        val now = currentTimeMillis()
        val requestedTarget = secrets.targetForSecretUpload(contents.mode, contents.name)
        val prepared =
            when (val uploadContents = contents.contents) {
                is SecretUploadContents.Environment -> {
                    val upload =
                        EnvironmentSecretUpload(
                            mode = contents.mode,
                            name = contents.name,
                            descriptionProvided = contents.descriptionProvided,
                            description = contents.description,
                            variables = uploadContents.variables,
                        )
                    when (val validation = secrets.describeEnvironmentSecretUpload(upload)) {
                        is EnvironmentSecretUploadResult.Valid ->
                            PreparedSecretUpload(
                                type = ENVIRONMENT_SECRET_TYPE,
                                target = validation.summary.target,
                                summary =
                                    SecretUploadSummarySnapshot(
                                        variableNames = uploadContents.variables.keys.sorted(),
                                        addedVariables = validation.summary.addedVariables,
                                        changedVariables = validation.summary.changedVariables,
                                        unchangedVariables = validation.summary.unchangedVariables,
                                        removedVariables = validation.summary.removedVariables,
                                    ),
                                environmentVariables =
                                    uploadContents.variables.map { (name, value) ->
                                        material.encryptSecretUploadVariable(
                                            relayRequestId = relayRequestId,
                                            clientId = client.clientId,
                                            name = name,
                                            value = value,
                                            sensitive =
                                                validation.summary.variableSensitivity.getValue(
                                                    name
                                                ),
                                        )
                                    },
                            )
                        is EnvironmentSecretUploadResult.Invalid ->
                            PreparedSecretUpload(
                                type = ENVIRONMENT_SECRET_TYPE,
                                target = validation.target,
                                summary =
                                    SecretUploadSummarySnapshot(
                                        variableNames = uploadContents.variables.keys.sorted()
                                    ),
                                error = validation.message,
                            )
                    }
                }
                is SecretUploadContents.Ssh ->
                    prepareSshUpload(
                        client = client,
                        relayRequestId = relayRequestId,
                        contents = contents,
                        uploadContents = uploadContents,
                        requestedTarget = requestedTarget,
                    )
            }
        val responsePlaintext =
            prepared.error?.let(secretUploadProtocol::rejectedResponse)
                ?: secretUploadProtocol.receivedResponse()
        val response = sealResponse(responsePlaintext) ?: return null
        val initialDecision = prepared.error?.let { SecretUploadRequestState.REJECTED.storedName }
        val lifecycle = secretUploadLifecycle(initialDecision, transportFinished = false)
        val software = json.encodeToString(contents.clientSoftware)
        writeTransaction.execute {
            dao.insertSecretUploadRequest(
                request =
                    InboxRequestEntity(
                        id = relayRequestId,
                        parentRequestId = null,
                        deviceIdentityId = client.deviceIdentityId,
                        clientId = client.clientId,
                        clientNameSnapshot = client.name,
                        clientSoftwareJson = software,
                        kind = RequestKind.SECRET_UPLOAD.storedName,
                        state = lifecycle.state.storedName,
                        listed = prepared.error == null,
                        requestJson = requestPayload.toString(),
                        responseJson = response.toString(),
                        error = null,
                        receivedAt = now,
                        completedAt = null,
                        exchangeEndedAt = null,
                        responseOutboxFinished = false,
                    ),
                secretUpload =
                    SecretUploadRequestEntity(
                        requestId = relayRequestId,
                        decision = initialDecision,
                        mode = contents.mode.wireName,
                        uploadedName = contents.name,
                        approvedName = null,
                        descriptionProvided = contents.descriptionProvided,
                        description = contents.description,
                        secretType = prepared.type,
                        targetSecretId = prepared.target?.secretId,
                        targetSecretRevision = prepared.target?.revision,
                        summaryJson = json.encodeToString(prepared.summary),
                        intakeError = prepared.error,
                        decidedAt = if (prepared.error != null) now else null,
                    ),
                client = client.copy(clientSoftwareJson = software, lastSeenAt = now),
                environmentVariables = prepared.environmentVariables,
                sshKey = prepared.sshKey,
                requestPsk = acceptedPsks.requestPsk,
                currentClientPsk = acceptedPsks.currentClientPsk,
                previousClientPsk = acceptedPsks.previousClientPsk,
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_UPLOAD_RECEIVED,
                        outcome =
                            if (prepared.error == null) {
                                AuditOutcome.RECEIVED
                            } else {
                                AuditOutcome.REJECTED
                            },
                        decisionSource = prepared.error?.let { AuditDecisionSource.VALIDATION },
                        subject = contents.name,
                        detail = prepared.error,
                        clientId = client.clientId,
                        clientName = client.name,
                        relayRequestId = relayRequestId,
                        data =
                            auditDataOf(
                                "device_identity_id" to client.deviceIdentityId,
                                "request_kind" to RequestKind.SECRET_UPLOAD.storedName,
                                "client_software" to json.parseToJsonElement(software),
                                "received_at" to now,
                            ) +
                                SecretUploadRequestEntity(
                                        requestId = relayRequestId,
                                        decision = initialDecision,
                                        mode = contents.mode.wireName,
                                        uploadedName = contents.name,
                                        approvedName = null,
                                        descriptionProvided = contents.descriptionProvided,
                                        description = contents.description,
                                        secretType = prepared.type,
                                        targetSecretId = prepared.target?.secretId,
                                        targetSecretRevision = prepared.target?.revision,
                                        summaryJson = json.encodeToString(prepared.summary),
                                        intakeError = prepared.error,
                                        decidedAt = if (prepared.error != null) now else null,
                                    )
                                    .auditData() +
                                prepared.auditMaterialData(),
                    )
                ),
                now,
            )
        }
        return response
    }

    suspend fun approveSecretUpload(
        requestId: String,
        approvedName: String,
    ): SecretUploadDecisionResult {
        val request = dao.getRequestById(requestId) ?: return SecretUploadDecisionResult.NotFound
        val uploadRequest =
            dao.getSecretUploadRequest(requestId) ?: return SecretUploadDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
                uploadRequest.decision != null
        ) {
            return SecretUploadDecisionResult.NotPending
        }
        val mode = SecretUploadMode.entries.single { it.wireName == uploadRequest.mode }
        if (mode != SecretUploadMode.CREATE) {
            val currentTarget = secrets.targetForSecretUpload(mode, uploadRequest.uploadedName)
            if (currentTarget != uploadRequest.target()) {
                val message =
                    if (currentTarget == null) {
                        "The target secret no longer exists."
                    } else {
                        "The target secret changed before the upload was approved."
                    }
                rejectSecretUpload(
                    request = request,
                    upload = uploadRequest,
                    decisionSource = AuditDecisionSource.VALIDATION,
                    detail = message,
                )
                return SecretUploadDecisionResult.Invalidated(message)
            }
        }
        val finalName = approvedName.trim()
        val preparation =
            when (uploadRequest.secretType) {
                ENVIRONMENT_SECRET_TYPE ->
                    prepareEnvironmentApproval(request, uploadRequest, finalName)
                SSH_SECRET_TYPE -> prepareSshApproval(request, uploadRequest, finalName)
                else ->
                    SecretUploadApprovalPreparation.Failed(
                        SecretUploadDecisionResult.Invalid("Unsupported secret type.")
                    )
            }
        val preparedUpload =
            when (preparation) {
                is SecretUploadApprovalPreparation.Failed -> return preparation.result
                is SecretUploadApprovalPreparation.Ready -> preparation.upload
            }

        val now = currentTimeMillis()
        val lifecycle =
            secretUploadLifecycle(
                SecretUploadRequestState.APPROVED.storedName,
                transportFinished = request.exchangeEndedAt != null,
            )
        return writeTransaction.execute {
            val materialAudit = pendingUploadMaterialAudit(request.id)
            val applied =
                when (preparedUpload) {
                    is PreparedSecretUploadApproval.Environment ->
                        secrets.applyPreparedEnvironmentSecretUpload(preparedUpload.upload)
                    is PreparedSecretUploadApproval.Ssh ->
                        secrets.applyPreparedSshSecretUpload(preparedUpload.upload)
                }
            val secretId =
                when (applied) {
                    is ApplySecretUploadResult.Applied -> applied.secretId
                    is ApplySecretUploadResult.Invalid -> {
                        return@execute SecretUploadDecisionResult.Invalid(applied.message)
                    }
                }
            dao.updateSecretUploadRequest(
                request =
                    request.copy(
                        state = lifecycle.state.storedName,
                        listed = false,
                        completedAt = if (lifecycle.completed) request.completedAt ?: now else null,
                    ),
                secretUpload =
                    uploadRequest.copy(
                        decision = SecretUploadRequestState.APPROVED.storedName,
                        approvedName = finalName,
                        decidedAt = now,
                    ),
                discardUploadedValues = true,
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_UPLOAD_DECIDED,
                        outcome = AuditOutcome.APPROVED,
                        decisionSource = AuditDecisionSource.USER,
                        subject = finalName,
                        detail = uploadRequest.uploadedName.takeUnless { it == finalName },
                        clientId = request.clientId,
                        clientName = request.clientNameSnapshot,
                        relayRequestId = request.id,
                        data =
                            request.requestAuditData() +
                                uploadRequest
                                    .copy(
                                        decision = SecretUploadRequestState.APPROVED.storedName,
                                        approvedName = finalName,
                                        decidedAt = now,
                                    )
                                    .auditData() +
                                materialAudit +
                                auditDataOf("applied_secret_id" to secretId),
                    )
                ),
                now,
            )
            SecretUploadDecisionResult.Approved(secretId)
        }
    }

    suspend fun readSecretUploadVariable(
        requestId: String,
        variableId: String,
        sensitiveAccessAuthorized: Boolean,
    ): SecretUploadVariableValue {
        val request = dao.getRequestById(requestId) ?: return SecretUploadVariableValue.NotFound
        val upload =
            dao.getSecretUploadRequest(requestId) ?: return SecretUploadVariableValue.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName || upload.decision != null
        ) {
            return SecretUploadVariableValue.NotFound
        }
        val variable =
            dao.getSecretUploadEnvironmentVariables(requestId).find {
                it.id == variableId
            } ?: return SecretUploadVariableValue.NotFound
        if (variable.sensitive && !sensitiveAccessAuthorized) {
            return SecretUploadVariableValue.AuthenticationRequired(variable.name)
        }
        return when (
            val result = material.decryptSecretUploadEnvironmentVariable(request, variable)
        ) {
            is DecryptionResult.Plaintext ->
                runCatching {
                        SecretUploadVariableValue.Available(
                            result.value.decodeToString(throwOnInvalidSequence = true)
                        )
                    }
                    .getOrDefault(SecretUploadVariableValue.Corrupted)
            DecryptionResult.KeyUnavailable -> SecretUploadVariableValue.Unavailable
            DecryptionResult.AuthenticationFailed -> SecretUploadVariableValue.Corrupted
            DecryptionResult.UnsupportedFormat -> SecretUploadVariableValue.UnsupportedEncryption
        }
    }

    suspend fun setSecretUploadVariableSensitivity(
        requestId: String,
        variableId: String,
        sensitive: Boolean,
        sensitivityReductionAuthorized: Boolean,
    ): SecretUploadSensitivityResult = writeTransaction.execute {
        val request =
            dao.getRequestById(requestId) ?: return@execute SecretUploadSensitivityResult.NotFound
        val upload =
            dao.getSecretUploadRequest(requestId)
                ?: return@execute SecretUploadSensitivityResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName || upload.decision != null
        ) {
            return@execute SecretUploadSensitivityResult.NotFound
        }
        val variable =
            dao.getSecretUploadEnvironmentVariables(requestId).find {
                it.id == variableId
            } ?: return@execute SecretUploadSensitivityResult.NotFound
        if (variable.sensitive && !sensitive && !sensitivityReductionAuthorized) {
            return@execute SecretUploadSensitivityResult.AuthenticationRequired(variable.name)
        }
        if (variable.sensitive == sensitive) {
            return@execute SecretUploadSensitivityResult.Changed
        }
        if (dao.updateSecretUploadEnvironmentVariable(variable.copy(sensitive = sensitive)) != 1) {
            return@execute SecretUploadSensitivityResult.NotFound
        }
        SecretUploadSensitivityResult.Changed
    }

    suspend fun rejectSecretUpload(requestId: String): SecretUploadDecisionResult {
        val request = dao.getRequestById(requestId) ?: return SecretUploadDecisionResult.NotFound
        val upload =
            dao.getSecretUploadRequest(requestId) ?: return SecretUploadDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName || upload.decision != null
        ) {
            return SecretUploadDecisionResult.NotPending
        }
        rejectSecretUpload(
            request = request,
            upload = upload,
            decisionSource = AuditDecisionSource.USER,
        )
        return SecretUploadDecisionResult.Rejected
    }

    private suspend fun rejectSecretUpload(
        request: InboxRequestEntity,
        upload: SecretUploadRequestEntity,
        decisionSource: AuditDecisionSource,
        detail: String? = null,
    ) {
        writeTransaction.execute {
            val now = currentTimeMillis()
            val materialAudit = pendingUploadMaterialAudit(request.id)
            val lifecycle =
                secretUploadLifecycle(
                    SecretUploadRequestState.REJECTED.storedName,
                    transportFinished = request.exchangeEndedAt != null,
                )
            dao.updateSecretUploadRequest(
                request =
                    request.copy(
                        state = lifecycle.state.storedName,
                        listed = false,
                        completedAt = if (lifecycle.completed) request.completedAt ?: now else null,
                    ),
                secretUpload =
                    upload.copy(
                        decision = SecretUploadRequestState.REJECTED.storedName,
                        decidedAt = now,
                    ),
                discardUploadedValues = true,
            )
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_UPLOAD_DECIDED,
                        outcome = AuditOutcome.REJECTED,
                        decisionSource = decisionSource,
                        subject = upload.uploadedName,
                        detail = detail,
                        clientId = request.clientId,
                        clientName = request.clientNameSnapshot,
                        relayRequestId = request.id,
                        data =
                            request.requestAuditData() +
                                upload
                                    .copy(
                                        decision = SecretUploadRequestState.REJECTED.storedName,
                                        decidedAt = now,
                                    )
                                    .auditData() +
                                materialAudit,
                    )
                ),
                now,
            )
        }
    }

    suspend fun expireSecretList(
        request: InboxRequestEntity,
        message: String,
        now: Long,
    ) {
        writeTransaction.execute {
            val current = dao.getRequestById(request.id) ?: return@execute
            if (current.exchangeEndedAt != null) return@execute
            dao.updateSecretListRequest(
                current.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    responseOutboxFinished = true,
                    error = message,
                    failureKind = current.failureKind ?: RequestFailureKind.RELAY.storedName,
                    completedAt = now,
                    exchangeEndedAt = now,
                )
            )
            dao.deleteEndedRequestPsk(request.id)
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_LIST_COMPLETED,
                        outcome = AuditOutcome.FAILED,
                        subject = "Secret list",
                        detail = message,
                        clientId = current.clientId,
                        clientName = current.clientNameSnapshot,
                        relayRequestId = current.id,
                        data =
                            current.requestAuditData() + auditDataOf("transport_error" to message),
                    )
                ),
                now,
            )
        }
    }

    suspend fun expireSecretUpload(
        request: InboxRequestEntity,
        message: String,
        now: Long,
    ) {
        writeTransaction.execute {
            val current = dao.getRequestById(request.id) ?: return@execute
            if (current.exchangeEndedAt != null) return@execute
            val upload = dao.getSecretUploadRequest(request.id) ?: return@execute
            val lifecycle = secretUploadLifecycle(upload.decision, transportFinished = true)
            dao.updateSecretUploadRequest(
                current.copy(
                    state = lifecycle.state.storedName,
                    responseOutboxFinished = true,
                    error = message,
                    failureKind = current.failureKind ?: RequestFailureKind.RELAY.storedName,
                    completedAt = current.completedAt ?: if (lifecycle.completed) now else null,
                    exchangeEndedAt = now,
                ),
                upload,
            )
            dao.deleteEndedRequestPsk(request.id)
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_UPLOAD_COMPLETED,
                        outcome = AuditOutcome.FAILED,
                        subject = upload.uploadedName,
                        detail = message,
                        clientId = current.clientId,
                        clientName = current.clientNameSnapshot,
                        relayRequestId = current.id,
                        data =
                            current.requestAuditData() +
                                upload.auditData() +
                                auditDataOf("transport_error" to message),
                    )
                ),
                now,
            )
        }
    }

    suspend fun completeSecretList(
        request: InboxRequestEntity,
        openCompletion: suspend () -> CompletionOpenResult,
    ): Boolean {
        terminalCompletionHandled(request.id)?.let {
            return it
        }
        val opened = openCompletion()
        val decoded =
            (opened as? CompletionOpenResult.Opened)?.plaintext?.let {
                decodeWireCompletionOrNull { secretListProtocol.decodeCompletion(it) }
            }
        val now = currentTimeMillis()
        return writeTransaction.execute {
            val current = dao.getRequestById(request.id) ?: return@execute false
            if (current.exchangeEndedAt != null) return@execute true
            if (opened == CompletionOpenResult.RetryLater) return@execute false
            val priorError = current.error
            val valid =
                priorError == null &&
                    decoded == current.clientSoftwareJson?.let(::decodeStoredClientSoftware)
            val error =
                priorError
                    ?: if (valid) {
                        null
                    } else {
                        SECRET_LIST_COMPLETION_VERIFICATION_ERROR
                    }
            dao.updateSecretListRequest(
                current.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    responseOutboxFinished = true,
                    error = error,
                    failureKind =
                        current.failureKind
                            ?: if (!valid) {
                                RequestFailureKind.VERIFICATION.storedName
                            } else {
                                null
                            },
                    completedAt = now,
                    exchangeEndedAt = now,
                )
            )
            dao.deleteEndedRequestPsk(request.id)
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_LIST_COMPLETED,
                        outcome = if (valid) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                        subject = "Secret list",
                        detail = error,
                        clientId = request.clientId,
                        clientName = request.clientNameSnapshot,
                        relayRequestId = request.id,
                        data =
                            current.requestAuditData() +
                                auditDataOf(
                                    "completion_valid" to valid,
                                    "returned_client_software" to
                                        decoded?.let {
                                            json.encodeToJsonElement(it)
                                        },
                                ),
                    )
                ),
                now,
            )
            true
        }
    }

    suspend fun completeSecretUpload(
        request: InboxRequestEntity,
        openCompletion: suspend () -> CompletionOpenResult,
    ): Boolean {
        terminalCompletionHandled(request.id)?.let {
            return it
        }
        val opened = openCompletion()
        val result =
            (opened as? CompletionOpenResult.Opened)?.plaintext?.let {
                decodeWireCompletionOrNull { secretUploadProtocol.decodeCompletion(it) }
            }
        val now = currentTimeMillis()
        return writeTransaction.execute {
            val current = dao.getRequestById(request.id) ?: return@execute false
            val upload = dao.getSecretUploadRequest(request.id) ?: return@execute false
            if (current.exchangeEndedAt != null) return@execute true
            if (opened == CompletionOpenResult.RetryLater) return@execute false
            val expectedResult =
                if (upload.intakeError == null) {
                    SecretUploadProtocol.RESULT_RECEIVED
                } else {
                    SecretUploadProtocol.RESULT_REJECTED
                }
            val priorError = current.error
            val valid =
                priorError == null &&
                    result != null &&
                    result.clientSoftware ==
                        current.clientSoftwareJson?.let(::decodeStoredClientSoftware) &&
                    result.result == expectedResult &&
                    result.message == upload.intakeError
            val error =
                priorError
                    ?: if (valid) {
                        null
                    } else {
                        SECRET_UPLOAD_COMPLETION_VERIFICATION_ERROR
                    }
            val lifecycle = secretUploadLifecycle(upload.decision, transportFinished = true)
            dao.updateSecretUploadRequest(
                request =
                    current.copy(
                        state = lifecycle.state.storedName,
                        responseOutboxFinished = true,
                        error = error,
                        failureKind =
                            current.failureKind
                                ?: if (!valid) {
                                    RequestFailureKind.VERIFICATION.storedName
                                } else {
                                    null
                                },
                        completedAt = current.completedAt ?: if (lifecycle.completed) now else null,
                        exchangeEndedAt = now,
                    ),
                secretUpload = upload,
            )
            dao.deleteEndedRequestPsk(request.id)
            audit.append(
                listOf(
                    AuditRecord(
                        type = AuditEventType.SECRET_UPLOAD_COMPLETED,
                        outcome = if (valid) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                        subject = upload.uploadedName,
                        detail = error,
                        clientId = request.clientId,
                        clientName = request.clientNameSnapshot,
                        relayRequestId = request.id,
                        data =
                            current.requestAuditData() +
                                upload.auditData() +
                                auditDataOf(
                                    "completion_valid" to valid,
                                    "completion_result" to result?.result,
                                    "completion_message" to result?.message,
                                ),
                    )
                ),
                now,
            )
            true
        }
    }

    private suspend fun terminalCompletionHandled(requestId: String): Boolean? =
        writeTransaction.execute {
            val terminal = dao.getRequestById(requestId) ?: return@execute null
            if (terminal.exchangeEndedAt == null) return@execute null
            true
        }

    private suspend fun prepareSshUpload(
        client: ClientEntity,
        relayRequestId: String,
        contents: SecretUploadRequestMessage,
        uploadContents: SecretUploadContents.Ssh,
        requestedTarget: SecretUploadTarget?,
    ): PreparedSecretUpload {
        val parsedKey = runCatchingNonCancellation {
            withContext(cryptographyDispatcher) {
                sshKeys.importOpenSshPrivateKey(uploadContents.privateKey)
            }
        }
        val parseError = parsedKey.exceptionOrNull()?.message
        val privateKey = parsedKey.getOrNull()
        if (parseError != null) {
            return PreparedSecretUpload(
                type = SSH_SECRET_TYPE,
                summary = SecretUploadSummarySnapshot(),
                target = requestedTarget,
                error = parseError,
            )
        }
        val upload =
            SshSecretUpload(
                mode = contents.mode,
                name = contents.name,
                descriptionProvided = contents.descriptionProvided,
                description = contents.description,
                privateKey = checkNotNull(privateKey),
            )
        return when (val validation = secrets.describeSshSecretUpload(upload)) {
            is SshSecretUploadResult.Valid ->
                PreparedSecretUpload(
                    type = SSH_SECRET_TYPE,
                    target = validation.summary.target,
                    summary =
                        SecretUploadSummarySnapshot(
                            publicKey = validation.summary.publicKey,
                            fingerprint = validation.summary.fingerprint,
                            previousPublicKey = validation.summary.previousPublicKey,
                            previousFingerprint = validation.summary.previousFingerprint,
                            keyChanged = validation.summary.keyChanged,
                        ),
                    sshKey =
                        material.encryptSecretUploadSshKey(
                            relayRequestId,
                            client.clientId,
                            checkNotNull(privateKey),
                        ),
                )
            is SshSecretUploadResult.Invalid ->
                PreparedSecretUpload(
                    type = SSH_SECRET_TYPE,
                    target = validation.target,
                    summary = SecretUploadSummarySnapshot(),
                    error = validation.message,
                )
        }
    }

    private suspend fun pendingUploadMaterialAudit(requestId: String): Map<String, JsonElement> =
        auditDataOf(
            "environment_variables" to
                dao.getSecretUploadEnvironmentVariables(requestId).auditData(),
            "ssh_key" to dao.getSecretUploadSshKey(requestId)?.auditData(),
        )

    private fun PreparedSecretUpload.auditMaterialData(): Map<String, JsonElement> =
        auditDataOf(
            "environment_variables" to environmentVariables.auditData(),
            "ssh_key" to sshKey?.auditData(),
        )

    private fun List<SecretUploadEnvironmentVariableEntity>.auditData() = buildJsonArray {
        sortedBy(SecretUploadEnvironmentVariableEntity::name).forEach { variable ->
            add(
                buildJsonObject {
                    put("variable_id", variable.id)
                    put("name", variable.name)
                    put("sensitive", variable.sensitive)
                }
            )
        }
    }

    private fun SecretUploadSshKeyEntity.auditData() = buildJsonObject {
        val algorithm = checkNotNull(SshKeyAlgorithm.fromStoredName(algorithm))
        val publicKey = SshPublicKey(algorithm, publicKey, comment)
        put("algorithm", algorithm.storedName)
        put("bits", sshKeys.bitLength(publicKey))
        put("public_key", publicKey.line)
        put("fingerprint", publicKey.fingerprint)
        put("fingerprint_hex", publicKey.fingerprintHex)
        put("comment", comment)
    }

    private fun SecretUploadRequestEntity.auditData() =
        auditDataOf(
            "upload_mode" to mode,
            "uploaded_name" to uploadedName,
            "approved_name" to approvedName,
            "description_provided" to descriptionProvided,
            "description" to description,
            "secret_type" to secretType,
            "target_secret_id" to targetSecretId,
            "target_secret_revision" to targetSecretRevision,
            "summary" to json.parseToJsonElement(summaryJson),
            "intake_error" to intakeError,
            "decision" to decision,
            "decided_at" to decidedAt,
        )

    private suspend fun prepareEnvironmentApproval(
        request: InboxRequestEntity,
        uploadRequest: SecretUploadRequestEntity,
        finalName: String,
    ): SecretUploadApprovalPreparation {
        val values = sortedMapOf<String, String>()
        val variableRows = dao.getSecretUploadEnvironmentVariables(request.id)
        for (variable in variableRows) {
            when (val result = material.decryptSecretUploadEnvironmentVariable(request, variable)) {
                is DecryptionResult.Plaintext -> {
                    values[variable.name] =
                        runCatching {
                            result.value.decodeToString(throwOnInvalidSequence = true)
                        }
                            .getOrElse {
                                return SecretUploadApprovalPreparation.Failed(
                                    SecretUploadDecisionResult.SecretCorrupted
                                )
                            }
                }
                DecryptionResult.KeyUnavailable ->
                    return SecretUploadApprovalPreparation.Failed(
                        SecretUploadDecisionResult.SecretUnavailable
                    )
                DecryptionResult.AuthenticationFailed -> {
                    return SecretUploadApprovalPreparation.Failed(
                        SecretUploadDecisionResult.SecretCorrupted
                    )
                }
                DecryptionResult.UnsupportedFormat -> {
                    return SecretUploadApprovalPreparation.Failed(
                        SecretUploadDecisionResult.UnsupportedEncryption
                    )
                }
            }
        }
        val upload =
            EnvironmentSecretUpload(
                mode = SecretUploadMode.entries.single { it.wireName == uploadRequest.mode },
                name = uploadRequest.uploadedName,
                descriptionProvided = uploadRequest.descriptionProvided,
                description = uploadRequest.description,
                variables = values,
                variableSensitivity = variableRows.associate { it.name to it.sensitive },
            )
        return when (
            val result =
                secrets.prepareEnvironmentSecretUpload(
                    upload,
                    finalName,
                    uploadRequest.target(),
                )
        ) {
            is EnvironmentSecretUploadPreparation.Invalid ->
                SecretUploadApprovalPreparation.Failed(
                    SecretUploadDecisionResult.Invalid(result.message)
                )
            is EnvironmentSecretUploadPreparation.Ready ->
                SecretUploadApprovalPreparation.Ready(
                    PreparedSecretUploadApproval.Environment(result.upload)
                )
        }
    }

    private suspend fun prepareSshApproval(
        request: InboxRequestEntity,
        uploadRequest: SecretUploadRequestEntity,
        finalName: String,
    ): SecretUploadApprovalPreparation {
        val keyRow =
            dao.getSecretUploadSshKey(request.id)
                ?: return SecretUploadApprovalPreparation.Failed(
                    SecretUploadDecisionResult.SecretCorrupted
                )
        val plaintext =
            when (val result = material.decryptSecretUploadSshKey(request, keyRow)) {
                is DecryptionResult.Plaintext -> result.value
                DecryptionResult.KeyUnavailable ->
                    return SecretUploadApprovalPreparation.Failed(
                        SecretUploadDecisionResult.SecretUnavailable
                    )
                DecryptionResult.AuthenticationFailed ->
                    return SecretUploadApprovalPreparation.Failed(
                        SecretUploadDecisionResult.SecretCorrupted
                    )
                DecryptionResult.UnsupportedFormat -> {
                    return SecretUploadApprovalPreparation.Failed(
                        SecretUploadDecisionResult.UnsupportedEncryption
                    )
                }
            }
        val privateKey = runCatching {
            sshKeys.fromStored(keyRow.algorithm, plaintext, keyRow.publicKey, keyRow.comment)
        }
            .getOrElse {
                return SecretUploadApprovalPreparation.Failed(
                    SecretUploadDecisionResult.SecretCorrupted
                )
            }
        val upload =
            SshSecretUpload(
                mode = SecretUploadMode.entries.single { it.wireName == uploadRequest.mode },
                name = uploadRequest.uploadedName,
                descriptionProvided = uploadRequest.descriptionProvided,
                description = uploadRequest.description,
                privateKey = privateKey,
            )
        return when (
            val result = secrets.prepareSshSecretUpload(upload, finalName, uploadRequest.target())
        ) {
            is SshSecretUploadPreparation.Invalid ->
                SecretUploadApprovalPreparation.Failed(
                    SecretUploadDecisionResult.Invalid(result.message)
                )
            is SshSecretUploadPreparation.Ready ->
                SecretUploadApprovalPreparation.Ready(
                    PreparedSecretUploadApproval.Ssh(result.upload)
                )
        }
    }
}

private fun SecretUploadRequestEntity.target(): SecretUploadTarget? =
    when {
        targetSecretId == null && targetSecretRevision == null -> null
        targetSecretId != null && targetSecretRevision != null ->
            SecretUploadTarget(
                targetSecretId,
                targetSecretRevision,
            )
        else -> error("Incomplete secret upload target")
    }
