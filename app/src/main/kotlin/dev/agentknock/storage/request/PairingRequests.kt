package dev.agentknock.storage.request

import dev.agentknock.protocol.PairedRequestErrorCode
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.auditDataOf
import dev.agentknock.storage.device.RelayDeviceCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class PairingDecisionResult {
    VERIFIED,
    REJECTED,
    NOT_PENDING,
    NOT_FOUND,
}

internal data class MatchingPairingSas(val clientLabel: String?)

/** The established pairing material needed to authenticate its one finish exchange. */
internal class PairingFinishContext(
    val clientId: String,
    val clientPsk: ByteArray,
    internal val pairingRequestId: String,
    internal val deviceIdentityId: String,
)

/** Owns the persisted lifecycle of the one concrete pairing protocol. */
internal class PairingRequests(
    private val dao: RequestDao,
    private val material: RequestMaterialStore,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val pairingProtocol: PairingProtocol,
    private val json: Json,
    private val currentTimeMillis: () -> Long,
) {
    suspend fun start(
        credentials: RelayDeviceCredentials,
        requestId: String,
        requestPayload: JsonElement,
    ): Boolean {
        val deviceRandom = pairingProtocol.generateDeviceRandom()
        val now = currentTimeMillis()
        return writeTransaction.execute {
            if (dao.getRequestById(requestId) != null) return@execute false
            val collidesWithClient = dao.getClientById(requestId) != null
            val rejection =
                when {
                    !pairingProtocol.validateInitialRequest(requestPayload) -> {
                        PairedRequestErrorCode.INVALID_REQUEST
                    }
                    collidesWithClient ||
                        dao.getPairingAttempts().any {
                            it.state.toPairingState().blocksAdmission
                        } -> PairedRequestErrorCode.INVALID_STATE
                    else -> null
                }
            val response =
                rejection?.publicResponse()
                    ?: credentials.deviceKey.use { keyPair ->
                        pairingProtocol.initialResponse(
                            deviceId = credentials.deviceId,
                            devicePublicKey = keyPair.publicKey,
                            deviceRandom = deviceRandom,
                        )
                    }
            val accepted = rejection == null

            dao.insertPairingRequest(
                request =
                    InboxRequestEntity(
                        id = requestId,
                        parentRequestId = null,
                        deviceIdentityId = credentials.deviceIdentityId,
                        clientId = requestId,
                        clientNameSnapshot = requestId,
                        clientSoftwareJson = null,
                        kind = RequestKind.PAIRING.storedName,
                        state =
                            if (accepted) {
                                InboxRequestState.WAITING.storedName
                            } else {
                                InboxRequestState.COMPLETED.storedName
                            },
                        listed = accepted,
                        requestJson = requestPayload.toString(),
                        responseJson = response.toString(),
                        error = rejection?.message,
                        receivedAt = now,
                        completedAt = now.takeUnless { accepted },
                        exchangeEndedAt = null,
                        responseOutboxFinished = false,
                    ),
                attempt =
                    PairingAttemptEntity(
                        requestId = requestId,
                        pairingAddress = credentials.address,
                        friendlyName = null,
                        deviceRandom = deviceRandom,
                        desiredRelayClientState = null,
                        relayClientState = RelayClientState.PENDING.wireName,
                        state =
                            if (accepted) {
                                PairingState.EXCHANGE_PENDING.storedName
                            } else {
                                PairingState.REJECTED.storedName
                            },
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
                        decidedAt = now.takeUnless { accepted },
                    ),
            )
            audit.append(
                records =
                    listOf(
                        AuditRecord(
                            type = AuditEventType.PAIRING_REQUESTED,
                            outcome =
                                if (accepted) AuditOutcome.RECEIVED else AuditOutcome.REJECTED,
                            decisionSource = AuditDecisionSource.VALIDATION.takeUnless { accepted },
                            detail = rejection?.message,
                            clientId = requestId,
                            relayRequestId = requestId,
                            data =
                                auditDataOf(
                                    "device_identity_id" to credentials.deviceIdentityId,
                                    "device_id" to credentials.deviceId,
                                    "pairing_address" to credentials.address,
                                    "request_kind" to RequestKind.PAIRING.storedName,
                                    "pairing_state" to
                                        if (accepted) {
                                            PairingState.EXCHANGE_PENDING.storedName
                                        } else {
                                            PairingState.REJECTED.storedName
                                        },
                                    "rejection_code" to rejection?.wireName,
                                    "received_at" to now,
                                ),
                        )
                    ),
                occurredAt = now,
            )
            true
        }
    }

    /** Returns true when the completion is terminal and can be acknowledged. */
    suspend fun completeInitial(
        credentials: RelayDeviceCredentials,
        requestId: String,
        completion: JsonElement,
    ): Boolean {
        val request = dao.getRequestById(requestId) ?: return false
        val attempt = dao.getPairingAttempt(requestId) ?: return false
        if (request.deviceIdentityId != credentials.deviceIdentityId) return false
        if (request.exchangeEndedAt != null) return true
        if (attempt.state.toPairingState() != PairingState.EXCHANGE_PENDING) {
            return endDiscardedInitialCompletion(requestId)
        }

        val established =
            try {
                credentials.deviceKey.use { keyPair ->
                    pairingProtocol.establish(
                        deviceId = credentials.deviceId,
                        clientId = attempt.clientId,
                        devicePrivateKey = keyPair.privateKey,
                        devicePublicKey = keyPair.publicKey,
                        deviceRandom = attempt.deviceRandom,
                        initialRequest = json.parseToJsonElement(request.requestJson),
                        completion = completion,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!failure.isIrrecoverableCompletionFailure()) return false
                return failInitialCompletion(requestId)
            }

        val metadata = runCatching {
            pairingProtocol.decodeClientMetadata(established.applicationPlaintext)
        }
            .getOrNull()
        val preparedAttempt =
            try {
                val choices = pairingProtocol.sasChoices(established.sas)
                material.withEncryptedPendingPsk(
                    attempt =
                        attempt.copy(
                            state = PairingState.SAS_VERIFICATION_PENDING.storedName,
                            sasOption0 = choices.values[0],
                            sasOption1 = choices.values[1],
                            sasOption2 = choices.values[2],
                            correctSasIndex = choices.correctIndex,
                            platform = metadata?.platform,
                            architecture = metadata?.architecture,
                            hostname = metadata?.hostname,
                            friendlyName = attempt.friendlyName ?: metadata?.hostname,
                            machineId = metadata?.machineId,
                            osVersion = metadata?.osVersion,
                        ),
                    request = request,
                    clientPsk = established.clientPsk,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }

        val now = currentTimeMillis()
        return writeTransaction.execute {
            val currentRequest = dao.getRequestById(requestId) ?: return@execute false
            val currentAttempt = dao.getPairingAttempt(requestId) ?: return@execute false
            if (currentRequest.exchangeEndedAt != null) return@execute true
            if (currentAttempt.state.toPairingState() != PairingState.EXCHANGE_PENDING) {
                dao.updateEndedRequest(
                    currentRequest.copy(
                        responseOutboxFinished = true,
                        exchangeEndedAt = now,
                    )
                )
                return@execute true
            }

            dao.recordInitialCompletion(
                request =
                    currentRequest.copy(
                        state = InboxRequestState.ACTION_REQUIRED.storedName,
                        clientSoftwareJson = metadata?.clientSoftware?.let(json::encodeToString),
                        responseOutboxFinished = true,
                        error =
                            currentRequest.error ?: METADATA_WARNING.takeIf { metadata == null },
                        exchangeEndedAt = now,
                    ),
                attempt =
                    currentAttempt.copy(
                        state = PairingState.SAS_VERIFICATION_PENDING.storedName,
                        sasOption0 = preparedAttempt.sasOption0,
                        sasOption1 = preparedAttempt.sasOption1,
                        sasOption2 = preparedAttempt.sasOption2,
                        correctSasIndex = preparedAttempt.correctSasIndex,
                        platform = preparedAttempt.platform,
                        architecture = preparedAttempt.architecture,
                        hostname = preparedAttempt.hostname,
                        friendlyName = preparedAttempt.friendlyName,
                        machineId = preparedAttempt.machineId,
                        osVersion = preparedAttempt.osVersion,
                        pendingPsk = preparedAttempt.pendingPsk,
                    ),
            )
            true
        }
    }

    suspend fun chooseSas(requestId: String, selectedIndex: Int?): PairingDecisionResult =
        writeTransaction.execute {
            val request =
                dao.getRequestById(requestId) ?: return@execute PairingDecisionResult.NOT_FOUND
            val attempt =
                dao.getPairingAttempt(requestId) ?: return@execute PairingDecisionResult.NOT_FOUND
            if (attempt.state.toPairingState() != PairingState.SAS_VERIFICATION_PENDING) {
                return@execute PairingDecisionResult.NOT_PENDING
            }

            val verified = selectedIndex != null && selectedIndex == attempt.correctSasIndex
            val now = currentTimeMillis()
            if (verified) {
                dao.updatePairingRequest(
                    request = request.copy(state = InboxRequestState.WAITING.storedName),
                    attempt =
                        attempt.copy(
                            state = PairingState.WAITING_FOR_FINISH.storedName,
                            desiredRelayClientState = RelayClientState.ACTIVE.wireName,
                            decidedAt = now,
                        ),
                )
            } else {
                dao.rejectPairing(
                    request =
                        request.copy(
                            state = InboxRequestState.COMPLETED.storedName,
                            listed = false,
                            completedAt = now,
                        ),
                    attempt =
                        attempt.copy(
                            state = PairingState.REJECTED.storedName,
                            desiredRelayClientState =
                                RelayClientState.REVOKED.wireName.takeUnless {
                                    attempt.relayClientState == RelayClientState.REVOKED.wireName
                                },
                            pendingPsk = null,
                            decidedAt = null,
                        ),
                )
            }
            audit.append(
                records =
                    listOf(
                        attempt.decisionAudit(
                            request = request,
                            verified = verified,
                            action = "select_sas",
                            selectedIndex = selectedIndex,
                        )
                    ),
                occurredAt = now,
            )
            if (verified) PairingDecisionResult.VERIFIED else PairingDecisionResult.REJECTED
        }

    suspend fun matchingPendingSas(
        requestId: String,
        selectedIndex: Int,
    ): MatchingPairingSas? {
        val attempt = dao.getPairingAttempt(requestId) ?: return null
        if (
            attempt.state.toPairingState() != PairingState.SAS_VERIFICATION_PENDING ||
                selectedIndex != attempt.correctSasIndex
        ) {
            return null
        }
        return MatchingPairingSas(attempt.friendlyName ?: attempt.hostname ?: attempt.platform)
    }

    suspend fun reject(requestId: String): PairingDecisionResult = writeTransaction.execute {
        val request =
            dao.getRequestById(requestId) ?: return@execute PairingDecisionResult.NOT_FOUND
        val attempt =
            dao.getPairingAttempt(requestId) ?: return@execute PairingDecisionResult.NOT_FOUND
        if (!attempt.state.toPairingState().isRejectable) {
            return@execute PairingDecisionResult.NOT_PENDING
        }

        val now = currentTimeMillis()
        dao.rejectPairing(
            request =
                request.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    listed = false,
                    completedAt = now,
                ),
            attempt =
                attempt.copy(
                    state = PairingState.REJECTED.storedName,
                    desiredRelayClientState =
                        RelayClientState.REVOKED.wireName.takeUnless {
                            attempt.relayClientState == RelayClientState.REVOKED.wireName
                        },
                    pendingPsk = null,
                ),
        )
        audit.append(
            records =
                listOf(
                    attempt.decisionAudit(
                        request = request,
                        verified = false,
                        action = "reject",
                    )
                ),
            occurredAt = now,
        )
        PairingDecisionResult.REJECTED
    }

    /** Applies relay state to the still-pending pairing and reports whether a retry is needed. */
    suspend fun applyRelayClientState(clientId: String, state: RelayClientState): Boolean =
        writeTransaction.execute {
            val attempt = dao.getPairingAttempt(clientId) ?: return@execute false
            val updated =
                attempt.copy(
                    relayClientState = state.wireName,
                    desiredRelayClientState =
                        if (state == RelayClientState.REVOKED) {
                            null
                        } else {
                            attempt.desiredRelayClientState?.takeUnless { it == state.wireName }
                        },
                )
            check(dao.updatePairingAttempt(updated) == 1)
            updated.desiredRelayClientState != null &&
                updated.desiredRelayClientState != updated.relayClientState
        }

    suspend fun endInitialExchange(requestId: String, now: Long, message: String) {
        writeTransaction.execute {
            val request = dao.getRequestById(requestId) ?: return@execute
            val attempt = dao.getPairingAttempt(requestId) ?: return@execute
            if (request.exchangeEndedAt != null) return@execute
            if (attempt.state.toPairingState() == PairingState.EXCHANGE_PENDING) {
                dao.updatePairingRequest(
                    request =
                        request.copy(
                            state = InboxRequestState.ACTION_REQUIRED.storedName,
                            responseOutboxFinished = true,
                            error = message,
                            exchangeEndedAt = now,
                        ),
                    attempt =
                        attempt.withoutEstablishedMaterial(state = PairingState.EXCHANGE_FAILED),
                )
            } else {
                dao.updateEndedRequest(
                    request.copy(
                        responseOutboxFinished = true,
                        exchangeEndedAt = now,
                    )
                )
            }
        }
    }

    suspend fun finishContext(
        credentials: RelayDeviceCredentials,
        clientId: String,
    ): PairingFinishContext? {
        val attempt = dao.getPairingAttempt(clientId) ?: return null
        val rootRequest = dao.getRequestById(attempt.requestId) ?: return null
        if (!rootRequest.matchesPendingPairing(credentials, attempt, clientId)) return null
        val clientPsk = material.decryptPendingPsk(attempt, rootRequest) ?: return null
        return PairingFinishContext(
            clientId = clientId,
            clientPsk = clientPsk,
            pairingRequestId = rootRequest.id,
            deviceIdentityId = rootRequest.deviceIdentityId,
        )
    }

    suspend fun receiveFinish(
        pairing: PairingFinishContext,
        relayRequestId: String,
        requestPayload: JsonElement,
        plaintext: ByteArray,
        requestPsk: RequestPskEntity,
        sealResponse: suspend (ByteArray) -> JsonElement?,
    ): ProcessedRelayMessage? {
        val responsePlaintext =
            runCatching {
                pairingProtocol.prepareFinishResponse(plaintext)
            }
                .getOrNull() ?: return null
        val response = sealResponse(responsePlaintext) ?: return null
        val promoted =
            try {
                writeTransaction.execute {
                    if (dao.getRequestById(relayRequestId) != null) return@execute false
                    val rootRequest =
                        dao.getRequestById(pairing.pairingRequestId) ?: return@execute false
                    val attempt =
                        dao.getPairingAttempt(pairing.pairingRequestId) ?: return@execute false
                    if (!rootRequest.matchesPendingPairing(pairing, attempt)) return@execute false

                    val relayState = attempt.relayClientState.toRelayClientState()
                    if (
                        relayState == RelayClientState.PENDING &&
                            attempt.desiredRelayClientState != RelayClientState.ACTIVE.wireName
                    ) {
                        return@execute false
                    }

                    val now = currentTimeMillis()
                    val clientName = attempt.auditClientName()
                    val finishRequest =
                        InboxRequestEntity(
                            id = relayRequestId,
                            parentRequestId = rootRequest.id,
                            deviceIdentityId = rootRequest.deviceIdentityId,
                            clientId = rootRequest.clientId,
                            clientNameSnapshot = clientName,
                            clientSoftwareJson = rootRequest.clientSoftwareJson,
                            kind = RequestKind.PAIRING_FINISH.storedName,
                            state = InboxRequestState.COMPLETED.storedName,
                            listed = false,
                            requestJson = requestPayload.toString(),
                            responseJson = response.toString(),
                            error = null,
                            receivedAt = now,
                            completedAt = now,
                            exchangeEndedAt = null,
                            responseOutboxFinished = false,
                        )
                    val client =
                        if (relayState == RelayClientState.REVOKED) {
                            null
                        } else {
                            ClientEntity(
                                clientId = attempt.clientId,
                                deviceIdentityId = rootRequest.deviceIdentityId,
                                name = clientName,
                                instructions = "",
                                desiredRelayClientState =
                                    attempt.desiredRelayClientState?.takeUnless {
                                        it == relayState.wireName
                                    },
                                relayClientState = relayState.wireName,
                                clientSoftwareJson = rootRequest.clientSoftwareJson,
                                platform = attempt.platform,
                                architecture = attempt.architecture,
                                hostname = attempt.hostname,
                                machineId = attempt.machineId,
                                osVersion = attempt.osVersion,
                                pairedAt = now,
                                lastSeenAt = now,
                            )
                        }
                    val encryptedClientPsk = client?.let {
                        material.encryptClientPsk(it, pairing.clientPsk, now)
                    }
                    dao.finishPairing(
                        rootRequest =
                            rootRequest.copy(
                                state = InboxRequestState.COMPLETED.storedName,
                                listed = false,
                                completedAt = now,
                            ),
                        attempt =
                            attempt.copy(
                                state = PairingState.COMPLETED.storedName,
                                desiredRelayClientState = null,
                                pendingPsk = null,
                            ),
                        client = client,
                        clientPsk = encryptedClientPsk,
                        finishRequest = finishRequest,
                        requestPsk = requestPsk,
                    )
                    if (client != null) {
                        audit.append(
                            records =
                                listOf(
                                    AuditRecord(
                                        type = AuditEventType.PAIRING_COMPLETED,
                                        outcome = AuditOutcome.COMPLETED,
                                        subject = clientName,
                                        clientId = attempt.clientId,
                                        clientName = clientName,
                                        relayRequestId = rootRequest.id,
                                        data =
                                            rootRequest.requestAuditData() +
                                                attempt.auditData() +
                                                auditDataOf(
                                                    "resulting_pairing_state" to
                                                        PairingState.COMPLETED.storedName,
                                                    "paired_client_name" to client.name,
                                                    "paired_at" to client.pairedAt,
                                                    "client_relay_state" to client.relayClientState,
                                                ),
                                    )
                                ),
                            occurredAt = now,
                        )
                    }
                    true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        return ProcessedRelayMessage.takeIf { promoted }
    }

    suspend fun recordRejectedRequest(
        pairing: PairingFinishContext,
        relayRequestId: String,
        requestPayload: JsonElement,
        responsePayload: JsonElement,
        requestPsk: RequestPskEntity,
        code: PairedRequestErrorCode,
    ): Boolean =
        try {
            writeTransaction.execute {
                if (dao.getRequestById(relayRequestId) != null) return@execute false
                val rootRequest =
                    dao.getRequestById(pairing.pairingRequestId) ?: return@execute false
                val attempt =
                    dao.getPairingAttempt(pairing.pairingRequestId) ?: return@execute false
                if (!rootRequest.matchesPendingPairing(pairing, attempt)) return@execute false
                val now = currentTimeMillis()
                val clientName = attempt.auditClientName()
                dao.insertHiddenPairedRequest(
                    request =
                        InboxRequestEntity(
                            id = relayRequestId,
                            parentRequestId = rootRequest.id,
                            deviceIdentityId = rootRequest.deviceIdentityId,
                            clientId = rootRequest.clientId,
                            clientNameSnapshot = clientName,
                            clientSoftwareJson = rootRequest.clientSoftwareJson,
                            kind = RequestKind.UNKNOWN.storedName,
                            state = InboxRequestState.COMPLETED.storedName,
                            listed = false,
                            requestJson = requestPayload.toString(),
                            responseJson = responsePayload.toString(),
                            error = code.message,
                            receivedAt = now,
                            completedAt = now,
                            exchangeEndedAt = null,
                            responseOutboxFinished = false,
                        ),
                    client = null,
                    requestPsk = requestPsk,
                    currentClientPsk = null,
                    previousClientPsk = null,
                )
                audit.append(
                    records =
                        listOf(
                            AuditRecord(
                                type = AuditEventType.REQUEST_REJECTED,
                                outcome = AuditOutcome.REJECTED,
                                decisionSource = AuditDecisionSource.VALIDATION,
                                detail = code.message,
                                clientId = rootRequest.clientId,
                                clientName = clientName,
                                relayRequestId = relayRequestId,
                                data =
                                    rootRequest.requestAuditData() +
                                        attempt.auditData() +
                                        auditDataOf(
                                            "rejection_code" to code.wireName,
                                            "rejected_request_id" to relayRequestId,
                                        ),
                            )
                        ),
                    occurredAt = now,
                )
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    /** Returns true when the completion is terminal and can be acknowledged. */
    suspend fun completeFinish(
        requestId: String,
        opened: CompletionOpenResult,
    ): Boolean {
        if (opened == CompletionOpenResult.RetryLater) return false
        val accepted =
            (opened as? CompletionOpenResult.Opened)?.plaintext?.let {
                decodeWireCompletionOrNull { pairingProtocol.finishCompletionAccepted(it) }
            }
        val now = currentTimeMillis()
        return writeTransaction.execute {
            val request = dao.getRequestById(requestId) ?: return@execute false
            if (request.kind != RequestKind.PAIRING_FINISH.storedName) return@execute false
            if (request.exchangeEndedAt != null) return@execute true
            val attempt = request.parentRequestId?.let { dao.getPairingAttempt(it) }

            val detail =
                request.error
                    ?: when (accepted) {
                        true -> null
                        false -> FINISH_REJECTED_ERROR
                        null -> FINISH_VERIFICATION_ERROR
                    }
            val valid = request.error == null && accepted == true
            dao.updateEndedRequest(
                request.copy(
                    responseOutboxFinished = true,
                    error = detail,
                    exchangeEndedAt = now,
                )
            )
            audit.append(
                records =
                    listOf(
                        AuditRecord(
                            type = AuditEventType.PAIRING_CONFIRMATION_RECEIVED,
                            outcome =
                                if (valid) {
                                    AuditOutcome.COMPLETED
                                } else {
                                    AuditOutcome.FAILED
                                },
                            subject = request.clientNameSnapshot,
                            detail = detail,
                            clientId = request.clientId,
                            clientName = request.clientNameSnapshot,
                            relayRequestId = request.id,
                            data =
                                request.requestAuditData() +
                                    (attempt?.auditData() ?: emptyMap()) +
                                    auditDataOf(
                                        "completion_accepted" to accepted,
                                        "completion_valid" to valid,
                                    ),
                        )
                    ),
                occurredAt = now,
            )
            true
        }
    }

    suspend fun endFinishExchange(requestId: String, now: Long, message: String) {
        writeTransaction.execute {
            val request = dao.getRequestById(requestId) ?: return@execute
            if (request.kind != RequestKind.PAIRING_FINISH.storedName) return@execute
            if (request.exchangeEndedAt != null) return@execute
            dao.updateEndedRequest(
                request.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    responseOutboxFinished = true,
                    error = request.error ?: message,
                    completedAt = request.completedAt ?: now,
                    exchangeEndedAt = now,
                )
            )
        }
    }

    private suspend fun failInitialCompletion(requestId: String): Boolean {
        val now = currentTimeMillis()
        return writeTransaction.execute {
            val request = dao.getRequestById(requestId) ?: return@execute false
            val attempt = dao.getPairingAttempt(requestId) ?: return@execute false
            if (request.exchangeEndedAt != null) return@execute true
            if (attempt.state.toPairingState() != PairingState.EXCHANGE_PENDING) {
                dao.updateEndedRequest(
                    request.copy(
                        responseOutboxFinished = true,
                        exchangeEndedAt = now,
                    )
                )
                return@execute true
            }

            dao.updatePairingRequest(
                request =
                    request.copy(
                        state = InboxRequestState.ACTION_REQUIRED.storedName,
                        responseOutboxFinished = true,
                        error = request.error ?: INITIAL_COMPLETION_VERIFICATION_ERROR,
                        exchangeEndedAt = now,
                    ),
                attempt = attempt.withoutEstablishedMaterial(PairingState.EXCHANGE_FAILED),
            )
            true
        }
    }

    private suspend fun endDiscardedInitialCompletion(requestId: String): Boolean {
        val now = currentTimeMillis()
        return writeTransaction.execute {
            val request = dao.getRequestById(requestId) ?: return@execute false
            if (request.exchangeEndedAt == null) {
                dao.updateEndedRequest(
                    request.copy(
                        responseOutboxFinished = true,
                        exchangeEndedAt = now,
                    )
                )
            }
            true
        }
    }

    private fun PairingAttemptEntity.decisionAudit(
        request: InboxRequestEntity,
        verified: Boolean,
        action: String,
        selectedIndex: Int? = null,
    ) =
        AuditRecord(
            type = AuditEventType.PAIRING_DECIDED,
            outcome = if (verified) AuditOutcome.APPROVED else AuditOutcome.REJECTED,
            decisionSource = AuditDecisionSource.USER,
            subject = auditClientName(),
            clientId = clientId,
            clientName = auditClientName(),
            relayRequestId = request.id,
            data =
                request.requestAuditData() +
                    auditData() +
                    auditDataOf(
                        "action" to action,
                        "selected_sas_option" to selectedIndex,
                        "sas_matched" to verified,
                        "resulting_pairing_state" to
                            if (verified) {
                                PairingState.WAITING_FOR_FINISH.storedName
                            } else {
                                PairingState.REJECTED.storedName
                            },
                    ),
        )

    private fun PairingAttemptEntity.auditData() =
        auditDataOf(
            "pairing_address" to pairingAddress,
            "friendly_name" to friendlyName,
            "pairing_state" to state,
            "relay_client_state" to relayClientState,
            "desired_relay_client_state" to desiredRelayClientState,
            "hostname" to hostname,
            "platform" to platform,
            "architecture" to architecture,
            "machine_id" to machineId,
            "os_version" to osVersion,
            "decided_at" to decidedAt,
        )

    private fun InboxRequestEntity.matchesPendingPairing(
        credentials: RelayDeviceCredentials,
        attempt: PairingAttemptEntity,
        clientId: String,
    ): Boolean =
        id == attempt.requestId &&
            this.clientId == clientId &&
            deviceIdentityId == credentials.deviceIdentityId &&
            kind == RequestKind.PAIRING.storedName &&
            attempt.clientId == clientId &&
            attempt.state.toPairingState().acceptsFinishRequest

    private fun InboxRequestEntity.matchesPendingPairing(
        pairing: PairingFinishContext,
        attempt: PairingAttemptEntity,
    ): Boolean =
        id == pairing.pairingRequestId &&
            id == attempt.requestId &&
            clientId == pairing.clientId &&
            deviceIdentityId == pairing.deviceIdentityId &&
            kind == RequestKind.PAIRING.storedName &&
            attempt.clientId == pairing.clientId &&
            attempt.state.toPairingState().acceptsFinishRequest

    private fun PairingAttemptEntity.withoutEstablishedMaterial(state: PairingState) =
        copy(
            state = state.storedName,
            sasOption0 = null,
            sasOption1 = null,
            sasOption2 = null,
            correctSasIndex = null,
            pendingPsk = null,
            decidedAt = null,
        )

    private fun String.toRelayClientState(): RelayClientState =
        checkNotNull(RelayClientState.entries.find { it.wireName == this })

    private companion object {
        const val INITIAL_COMPLETION_VERIFICATION_ERROR =
            "The pairing message could not be verified."
        const val METADATA_WARNING =
            "The client details could not be read, but the verification code is valid."
        const val FINISH_REJECTED_ERROR = "The client did not accept the pairing."
        const val FINISH_VERIFICATION_ERROR = "The pairing confirmation could not be verified."
    }
}

private fun PairedRequestErrorCode.publicResponse(): JsonElement = buildJsonObject {
    put("error", wireName)
    put("message", message)
}

internal fun PairingAttemptEntity.auditClientName(): String =
    sequenceOf(friendlyName, hostname, clientId).filterNotNull().first(String::isNotBlank)
