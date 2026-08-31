package dev.agentknock.storage.request

import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.SecretListProtocol
import dev.agentknock.protocol.SecretListSecret
import dev.agentknock.protocol.SecretUploadContents
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.protocol.SecretUploadProtocol
import dev.agentknock.protocol.SecretUploadRequestMessage
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.runCatchingNonCancellation
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.secret.ApplyEnvironmentSecretUploadResult
import dev.agentknock.storage.secret.ApplySshSecretUploadResult
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.EnvironmentSecretUpload
import dev.agentknock.storage.secret.EnvironmentSecretUploadPreparation
import dev.agentknock.storage.secret.EnvironmentSecretUploadResult
import dev.agentknock.storage.secret.PreparedEnvironmentSecretUpload
import dev.agentknock.storage.secret.PreparedSshSecretUpload
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SecretUploadTarget
import dev.agentknock.storage.secret.SshKeyCodec
import dev.agentknock.storage.secret.SshSecretUpload
import dev.agentknock.storage.secret.SshSecretUploadPreparation
import dev.agentknock.storage.secret.SshSecretUploadResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
    data class Ready(val upload: PreparedSecretUploadApproval) :
        SecretUploadApprovalPreparation

    data class Failed(val result: SecretUploadDecisionResult) :
        SecretUploadApprovalPreparation
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

internal sealed interface SecretUploadVariableValue {
    data class Available(val value: String) : SecretUploadVariableValue
    data object NotFound : SecretUploadVariableValue
    data object Unavailable : SecretUploadVariableValue
    data object Corrupted : SecretUploadVariableValue
    data object UnsupportedEncryption : SecretUploadVariableValue
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
        val contents = runCatching { secretListProtocol.decodeRequest(plaintext) }.getOrNull()
            ?: return null
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
        val response = sealResponse(responsePlaintext) ?: return null
        val now = currentTimeMillis()
        val software = json.encodeToString(contents.clientSoftware)
        writeTransaction.execute {
            dao.insertSecretListRequest(
                request = InboxRequestEntity(
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
                    completionJson = null,
                    error = null,
                    receivedAt = now,
                    completedAt = null,
                    requestAcknowledged = false,
                    responseAcknowledged = false,
                    completionAcknowledged = false,
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
                    ),
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
        val contents = runCatching { secretUploadProtocol.decodeRequest(plaintext) }.getOrNull()
            ?: return null
        val now = currentTimeMillis()
        val requestedTarget = secrets.targetForSecretUpload(contents.mode, contents.name)
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
                        target = validation.summary.target,
                        summary = SecretUploadSummarySnapshot(
                            variableNames = uploadContents.variables.keys.sorted(),
                            addedVariables = validation.summary.addedVariables,
                            changedVariables = validation.summary.changedVariables,
                            unchangedVariables = validation.summary.unchangedVariables,
                            removedVariables = validation.summary.removedVariables,
                        ),
                        environmentVariables = uploadContents.variables.map { (name, value) ->
                            material.encryptSecretUploadVariable(
                                relayRequestId = relayRequestId,
                                clientId = client.clientId,
                                name = name,
                                value = value,
                                sensitive = validation.summary.variableSensitivity.getValue(name),
                            )
                        },
                    )
                    is EnvironmentSecretUploadResult.Invalid -> PreparedSecretUpload(
                        type = ENVIRONMENT_SECRET_TYPE,
                        target = validation.target,
                        summary = SecretUploadSummarySnapshot(
                            variableNames = uploadContents.variables.keys.sorted(),
                        ),
                        error = validation.message,
                    )
                }
            }
            is SecretUploadContents.Ssh -> prepareSshUpload(
                client = client,
                relayRequestId = relayRequestId,
                contents = contents,
                uploadContents = uploadContents,
                requestedTarget = requestedTarget,
            )
        }
        val responsePlaintext = prepared.error?.let(secretUploadProtocol::rejectedResponse)
            ?: secretUploadProtocol.receivedResponse()
        val response = sealResponse(responsePlaintext) ?: return null
        val initialDecision = prepared.error?.let { SecretUploadRequestState.REJECTED.storedName }
        val lifecycle = secretUploadLifecycle(initialDecision, transportFinished = false)
        val software = json.encodeToString(contents.clientSoftware)
        writeTransaction.execute {
            dao.insertSecretUploadRequest(
                request = InboxRequestEntity(
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
                    completionJson = null,
                    error = null,
                    receivedAt = now,
                    completedAt = null,
                    requestAcknowledged = false,
                    responseAcknowledged = false,
                    completionAcknowledged = false,
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
                        outcome = if (prepared.error == null) {
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
                    ),
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
        val request = dao.getRequestById(requestId)
            ?: return SecretUploadDecisionResult.NotFound
        val uploadRequest = dao.getSecretUploadRequest(requestId)
            ?: return SecretUploadDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            uploadRequest.decision != null
        ) {
            return SecretUploadDecisionResult.NotPending
        }
        val finalName = approvedName.trim()
        val preparation = when (uploadRequest.secretType) {
            ENVIRONMENT_SECRET_TYPE -> prepareEnvironmentApproval(request, uploadRequest, finalName)
            SSH_SECRET_TYPE -> prepareSshApproval(request, uploadRequest, finalName)
            else -> SecretUploadApprovalPreparation.Failed(
                SecretUploadDecisionResult.Invalid("Unsupported secret type."),
            )
        }
        val preparedUpload = when (preparation) {
            is SecretUploadApprovalPreparation.Failed -> return preparation.result
            is SecretUploadApprovalPreparation.Ready -> preparation.upload
        }

        val now = currentTimeMillis()
        val lifecycle = secretUploadLifecycle(
            SecretUploadRequestState.APPROVED.storedName,
            transportFinished = request.completionJson != null || request.error != null,
        )
        return writeTransaction.execute {
            val secretId = when (preparedUpload) {
                is PreparedSecretUploadApproval.Environment -> when (
                    val applied = secrets.applyPreparedEnvironmentSecretUpload(preparedUpload.upload)
                ) {
                    is ApplyEnvironmentSecretUploadResult.Applied -> applied.secretId
                    is ApplyEnvironmentSecretUploadResult.Invalid -> {
                        return@execute SecretUploadDecisionResult.Invalid(applied.message)
                    }
                }
                is PreparedSecretUploadApproval.Ssh -> when (
                    val applied = secrets.applyPreparedSshSecretUpload(preparedUpload.upload)
                ) {
                    is ApplySshSecretUploadResult.Applied -> applied.secretId
                    is ApplySshSecretUploadResult.Invalid -> {
                        return@execute SecretUploadDecisionResult.Invalid(applied.message)
                    }
                }
            }
            dao.updateSecretUploadRequest(
                request = request.copy(
                    state = lifecycle.state.storedName,
                    listed = false,
                    completedAt = if (lifecycle.completed) request.completedAt ?: now else null,
                ),
                secretUpload = uploadRequest.copy(
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
                    ),
                ),
                now,
            )
            SecretUploadDecisionResult.Approved(secretId)
        }
    }

    suspend fun readSecretUploadVariable(
        requestId: String,
        variableId: String,
    ): SecretUploadVariableValue {
        val request = dao.getRequestById(requestId) ?: return SecretUploadVariableValue.NotFound
        val upload = dao.getSecretUploadRequest(requestId)
            ?: return SecretUploadVariableValue.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            upload.decision != null
        ) {
            return SecretUploadVariableValue.NotFound
        }
        val variable = dao.getSecretUploadEnvironmentVariables(requestId).find {
            it.id == variableId
        } ?: return SecretUploadVariableValue.NotFound
        return when (
            val result = material.decryptSecretUploadEnvironmentVariable(request, variable)
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
    ): Boolean = writeTransaction.execute {
        val request = dao.getRequestById(requestId) ?: return@execute false
        val upload = dao.getSecretUploadRequest(requestId) ?: return@execute false
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            upload.decision != null
        ) {
            return@execute false
        }
        val variable = dao.getSecretUploadEnvironmentVariables(requestId).find {
            it.id == variableId
        } ?: return@execute false
        dao.updateSecretUploadEnvironmentVariable(variable.copy(sensitive = sensitive)) == 1
    }

    suspend fun rejectSecretUpload(requestId: String): SecretUploadDecisionResult {
        val request = dao.getRequestById(requestId)
            ?: return SecretUploadDecisionResult.NotFound
        val upload = dao.getSecretUploadRequest(requestId)
            ?: return SecretUploadDecisionResult.NotFound
        if (
            request.state != InboxRequestState.ACTION_REQUIRED.storedName ||
            upload.decision != null
        ) {
            return SecretUploadDecisionResult.NotPending
        }
        val now = currentTimeMillis()
        val lifecycle = secretUploadLifecycle(
            SecretUploadRequestState.REJECTED.storedName,
            transportFinished = request.completionJson != null || request.error != null,
        )
        return writeTransaction.execute {
            dao.updateSecretUploadRequest(
                request = request.copy(
                    state = lifecycle.state.storedName,
                    listed = false,
                    completedAt = if (lifecycle.completed) request.completedAt ?: now else null,
                ),
                secretUpload = upload.copy(
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
                        decisionSource = AuditDecisionSource.USER,
                        subject = upload.uploadedName,
                        clientId = request.clientId,
                        clientName = request.clientNameSnapshot,
                        relayRequestId = request.id,
                    ),
                ),
                now,
            )
            SecretUploadDecisionResult.Rejected
        }
    }

    suspend fun expireSecretList(
        request: InboxRequestEntity,
        message: String,
        now: Long,
    ) {
        writeTransaction.execute {
            dao.updateSecretListRequest(
                request.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    error = message,
                    completedAt = now,
                ),
            )
        }
    }

    suspend fun expireSecretUpload(
        request: InboxRequestEntity,
        message: String,
        now: Long,
    ) {
        writeTransaction.execute {
            val upload = dao.getSecretUploadRequest(request.id) ?: return@execute
            val pending = upload.decision == null
            dao.updateSecretUploadRequest(
                request.copy(
                    state = if (pending) {
                        InboxRequestState.ACTION_REQUIRED.storedName
                    } else {
                        InboxRequestState.COMPLETED.storedName
                    },
                    error = message,
                    completedAt = if (pending) null else now,
                ),
                upload,
            )
        }
    }

    suspend fun completeSecretList(
        request: InboxRequestEntity,
        completion: JsonElement,
        openCompletion: suspend () -> ByteArray?,
    ): Boolean {
        if (request.completedAt != null) return true
        val plaintext = openCompletion() ?: return false
        val decoded = runCatching { secretListProtocol.decodeCompletion(plaintext) }
        val valid = decoded.getOrNull() == request.clientSoftwareJson?.let(::decodeClientSoftware)
        val error = if (valid) null else SECRET_LIST_COMPLETION_VERIFICATION_ERROR
        val now = currentTimeMillis()
        writeTransaction.execute {
            dao.updateSecretListRequest(
                request.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    completionJson = completion.toString(),
                    responseAcknowledged = true,
                    completionAcknowledged = false,
                    error = error,
                    completedAt = now,
                ),
            )
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
                    ),
                ),
                now,
            )
        }
        return true
    }

    suspend fun completeSecretUpload(
        request: InboxRequestEntity,
        completion: JsonElement,
        openCompletion: suspend () -> ByteArray?,
    ): Boolean {
        val upload = dao.getSecretUploadRequest(request.id) ?: return false
        if (request.completionJson != null || request.error != null) return true
        val plaintext = openCompletion() ?: return false
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
        val error = if (valid) null else SECRET_UPLOAD_COMPLETION_VERIFICATION_ERROR
        val now = currentTimeMillis()
        val lifecycle = secretUploadLifecycle(upload.decision, transportFinished = true)
        writeTransaction.execute {
            dao.updateSecretUploadRequest(
                request = request.copy(
                    state = lifecycle.state.storedName,
                    completionJson = completion.toString(),
                    responseAcknowledged = true,
                    completionAcknowledged = false,
                    error = error,
                    completedAt = request.completedAt ?: if (lifecycle.completed) now else null,
                ),
                secretUpload = upload,
            )
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
                    ),
                ),
                now,
            )
        }
        return true
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
        val upload = SshSecretUpload(
            mode = contents.mode,
            name = contents.name,
            descriptionProvided = contents.descriptionProvided,
            description = contents.description,
            privateKey = checkNotNull(privateKey),
        )
        return when (val validation = secrets.describeSshSecretUpload(upload)) {
            is SshSecretUploadResult.Valid -> PreparedSecretUpload(
                type = SSH_SECRET_TYPE,
                target = validation.summary.target,
                summary = SecretUploadSummarySnapshot(
                    publicKey = validation.summary.publicKey,
                    fingerprint = validation.summary.fingerprint,
                    previousPublicKey = validation.summary.previousPublicKey,
                    previousFingerprint = validation.summary.previousFingerprint,
                    keyChanged = validation.summary.keyChanged,
                ),
                sshKey = material.encryptSecretUploadSshKey(
                    relayRequestId,
                    client.clientId,
                    checkNotNull(privateKey),
                ),
            )
            is SshSecretUploadResult.Invalid -> PreparedSecretUpload(
                type = SSH_SECRET_TYPE,
                target = validation.target,
                summary = SecretUploadSummarySnapshot(),
                error = validation.message,
            )
        }
    }

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
                    values[variable.name] = runCatching {
                        result.value.decodeToString(throwOnInvalidSequence = true)
                    }.getOrElse {
                        return SecretUploadApprovalPreparation.Failed(
                            SecretUploadDecisionResult.SecretCorrupted,
                        )
                    }
                }
                DecryptionResult.KeyUnavailable -> return SecretUploadApprovalPreparation.Failed(
                    SecretUploadDecisionResult.SecretUnavailable,
                )
                DecryptionResult.AuthenticationFailed -> {
                    return SecretUploadApprovalPreparation.Failed(
                        SecretUploadDecisionResult.SecretCorrupted,
                    )
                }
                DecryptionResult.UnsupportedFormat -> {
                    return SecretUploadApprovalPreparation.Failed(
                        SecretUploadDecisionResult.UnsupportedEncryption,
                    )
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
        return when (
            val result = secrets.prepareEnvironmentSecretUpload(
                upload,
                finalName,
                uploadRequest.target(),
            )
        ) {
            is EnvironmentSecretUploadPreparation.Invalid ->
                SecretUploadApprovalPreparation.Failed(
                    SecretUploadDecisionResult.Invalid(result.message),
                )
            is EnvironmentSecretUploadPreparation.Ready ->
                SecretUploadApprovalPreparation.Ready(
                    PreparedSecretUploadApproval.Environment(result.upload),
                )
        }
    }

    private suspend fun prepareSshApproval(
        request: InboxRequestEntity,
        uploadRequest: SecretUploadRequestEntity,
        finalName: String,
    ): SecretUploadApprovalPreparation {
        val keyRow = dao.getSecretUploadSshKey(request.id)
            ?: return SecretUploadApprovalPreparation.Failed(
                SecretUploadDecisionResult.SecretCorrupted,
            )
        val plaintext = when (val result = material.decryptSecretUploadSshKey(request, keyRow)) {
            is DecryptionResult.Plaintext -> result.value
            DecryptionResult.KeyUnavailable -> return SecretUploadApprovalPreparation.Failed(
                SecretUploadDecisionResult.SecretUnavailable,
            )
            DecryptionResult.AuthenticationFailed -> return SecretUploadApprovalPreparation.Failed(
                SecretUploadDecisionResult.SecretCorrupted,
            )
            DecryptionResult.UnsupportedFormat -> {
                return SecretUploadApprovalPreparation.Failed(
                    SecretUploadDecisionResult.UnsupportedEncryption,
                )
            }
        }
        val privateKey = runCatching {
            sshKeys.fromStored(keyRow.algorithm, plaintext, keyRow.publicKey, keyRow.comment)
        }.getOrElse {
            return SecretUploadApprovalPreparation.Failed(
                SecretUploadDecisionResult.SecretCorrupted,
            )
        }
        val upload = SshSecretUpload(
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
                    SecretUploadDecisionResult.Invalid(result.message),
                )
            is SshSecretUploadPreparation.Ready -> SecretUploadApprovalPreparation.Ready(
                PreparedSecretUploadApproval.Ssh(result.upload),
            )
        }
    }

    private fun decodeClientSoftware(value: String): ClientSoftware? =
        runCatching { storedJson.decodeFromString<ClientSoftware>(value) }.getOrNull()
}

private fun SecretUploadRequestEntity.target(): SecretUploadTarget? = when {
    targetSecretId == null && targetSecretRevision == null -> null
    targetSecretId != null && targetSecretRevision != null -> SecretUploadTarget(
        targetSecretId,
        targetSecretRevision,
    )
    else -> error("Incomplete secret upload target")
}
