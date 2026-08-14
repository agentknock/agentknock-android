package dev.agentknock.storage.request

import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.relay.RelayInboxClient
import dev.agentknock.relay.RelayInboxResult
import dev.agentknock.relay.RelayMessage
import dev.agentknock.relay.RelayUpdate
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import dev.agentknock.storage.vault.VaultRelayCredentials
import dev.agentknock.storage.vault.VaultRelayCredentialsResult
import dev.agentknock.storage.vault.VaultCredentialSource
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal enum class InboxRequestState(val storedName: String) {
    RECEIVING("receiving"),
    ACTION_REQUIRED("action_required"),
    WAITING("waiting"),
    COMPLETED("completed"),
}

internal enum class PairingState(val storedName: String) {
    RECEIVING("receiving"),
    SAS_VERIFICATION_PENDING("sas_verification_pending"),
    WAITING_FOR_FINISH("waiting_for_finish"),
    REJECTED("rejected"),
    ACTIVE("active"),
    VERIFICATION_FAILED("verification_failed"),
}

internal fun pairingAdmissionAllowed(existingStates: Iterable<PairingState>): Boolean =
    existingStates.none { it in INCOMPLETE_PAIRING_STATES }

internal data class InboxRequestSummary(
    val id: Long,
    val state: InboxRequestState,
    val pairingState: PairingState,
    val title: String,
    val receivedAt: Long,
    val completedAt: Long?,
)

internal data class PairingRequestDetails(
    val pairingState: PairingState,
    val vaultAddress: String,
    val pairingId: String,
    val sasOptions: List<String>,
    val cliVersion: String?,
    val platform: String?,
    val architecture: String?,
    val hostname: String?,
    val machineId: String?,
    val osVersion: String?,
    val error: String?,
    val decidedAt: Long?,
)

internal data class InboxRequestDetails(
    val id: Long,
    val relayRequestId: String,
    val state: InboxRequestState,
    val receivedAt: Long,
    val completedAt: Long?,
    val pairing: PairingRequestDetails,
)

internal sealed interface PollInboxResult {
    data object Success : PollInboxResult

    data object NoVault : PollInboxResult

    data object VaultSecretsUnavailable : PollInboxResult

    data object VaultSecretsCorrupted : PollInboxResult

    data object UnsupportedVaultEncryption : PollInboxResult

    data class RelayRejected(val status: Int, val message: String?) : PollInboxResult

    data class RelayUnavailable(val message: String?) : PollInboxResult

    data object InvalidRelayResponse : PollInboxResult
}

internal enum class PairingDecisionResult {
    VERIFIED,
    REJECTED,
    NOT_PENDING,
    NOT_FOUND,
}

internal class RequestRepository(
    private val dao: RequestDao,
    private val vault: VaultCredentialSource,
    private val relay: RelayInboxClient,
    private val keyManager: LocalEncryptionKeyManager,
    private val encryption: AesGcmEncryption,
    private val pairingProtocol: PairingProtocol = PairingProtocol(),
    private val json: Json = Json,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val operationMutex = Mutex()

    fun observeRequests(): Flow<List<InboxRequestSummary>> = combine(
        dao.observeListedRequests(),
        dao.observePairings(),
    ) { requests, pairings ->
        val pairingByRequest = pairings.associateBy(PairingEntity::requestId)
        requests.mapNotNull { request ->
            val pairing = pairingByRequest[request.id] ?: return@mapNotNull null
            InboxRequestSummary(
                id = request.id,
                state = request.state.toInboxRequestState(),
                pairingState = pairing.state.toPairingState(),
                title = pairing.hostname
                    ?: pairing.platform?.let { platform ->
                        pairing.architecture?.let { "$platform · $it" } ?: platform
                    }
                    ?: "Pairing request",
                receivedAt = request.receivedAt,
                completedAt = request.completedAt,
            )
        }
    }

    fun observeRequest(id: Long): Flow<InboxRequestDetails?> = combine(
        dao.observeRequest(id),
        dao.observePairing(id),
    ) { request, pairing ->
        if (request == null || pairing == null) return@combine null
        InboxRequestDetails(
            id = request.id,
            relayRequestId = request.relayRequestId,
            state = request.state.toInboxRequestState(),
            receivedAt = request.receivedAt,
            completedAt = request.completedAt,
            pairing = PairingRequestDetails(
                pairingState = pairing.state.toPairingState(),
                vaultAddress = pairing.vaultAddress,
                pairingId = pairing.pairingId,
                sasOptions = listOfNotNull(
                    pairing.sasOption0,
                    pairing.sasOption1,
                    pairing.sasOption2,
                ).map(pairingProtocol::formatSas),
                cliVersion = pairing.cliVersion,
                platform = pairing.platform,
                architecture = pairing.architecture,
                hostname = pairing.hostname,
                machineId = pairing.machineId,
                osVersion = pairing.osVersion,
                error = pairing.error,
                decidedAt = pairing.decidedAt,
            ),
        )
    }

    suspend fun poll(): PollInboxResult = operationMutex.withLock {
        val credentials = when (val result = vault.activeRelayCredentials()) {
            is VaultRelayCredentialsResult.Available -> result.credentials
            VaultRelayCredentialsResult.Missing -> return PollInboxResult.NoVault
            VaultRelayCredentialsResult.SecretsUnavailable -> {
                return PollInboxResult.VaultSecretsUnavailable
            }
            VaultRelayCredentialsResult.SecretsCorrupted -> {
                return PollInboxResult.VaultSecretsCorrupted
            }
            VaultRelayCredentialsResult.UnsupportedEncryption -> {
                return PollInboxResult.UnsupportedVaultEncryption
            }
        }

        do {
            val batch = when (
                val result = relay.pending(
                    routeId = credentials.routeId,
                    authenticationToken = credentials.authenticationToken,
                )
            ) {
                is RelayInboxResult.Success -> result.value
                is RelayInboxResult.Rejected -> {
                    return PollInboxResult.RelayRejected(result.status, result.message)
                }
                is RelayInboxResult.Unavailable -> {
                    return PollInboxResult.RelayUnavailable(result.cause.message)
                }
                RelayInboxResult.InvalidResponse -> return PollInboxResult.InvalidRelayResponse
            }

            val updates = linkedMapOf<String, RelayUpdate>()
            for (message in batch.messages) {
                if (message.hasRequest) {
                    processRequest(credentials, message)?.let { update ->
                        updates.merge(update)
                    }
                }
                if (message.hasCompletion) {
                    processCompletion(credentials, message)?.let { update ->
                        updates.merge(update)
                    }
                }
            }

            if (updates.isNotEmpty()) {
                when (
                    val result = relay.update(
                        routeId = credentials.routeId,
                        authenticationToken = credentials.authenticationToken,
                        updates = updates.values.toList(),
                    )
                ) {
                    is RelayInboxResult.Success -> {
                        val now = currentTimeMillis()
                        updates.values.forEach { update ->
                            if (update.requestDelivered || update.response != null) {
                                dao.markRequestAcknowledged(update.requestId, now)
                            }
                            if (update.completionDelivered) {
                                dao.markCompletionAcknowledged(update.requestId, now)
                            }
                        }
                    }
                    is RelayInboxResult.Rejected -> {
                        return PollInboxResult.RelayRejected(result.status, result.message)
                    }
                    is RelayInboxResult.Unavailable -> {
                        return PollInboxResult.RelayUnavailable(result.cause.message)
                    }
                    RelayInboxResult.InvalidResponse -> {
                        return PollInboxResult.InvalidRelayResponse
                    }
                }
            }

            if (!batch.hasMore || updates.isEmpty()) break
        } while (true)

        PollInboxResult.Success
    }

    suspend fun chooseSas(requestId: Long, selectedIndex: Int?): PairingDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId) ?: return PairingDecisionResult.NOT_FOUND
            val pairing = dao.getPairing(requestId) ?: return PairingDecisionResult.NOT_FOUND
            if (pairing.state != PairingState.SAS_VERIFICATION_PENDING.storedName) {
                return PairingDecisionResult.NOT_PENDING
            }
            val verified = selectedIndex != null && selectedIndex == pairing.correctSasIndex
            val now = currentTimeMillis()
            dao.updatePairingRequest(
                request = request.copy(
                    state = if (verified) {
                        InboxRequestState.WAITING.storedName
                    } else {
                        InboxRequestState.COMPLETED.storedName
                    },
                    updatedAt = now,
                    completedAt = if (verified) null else now,
                ),
                pairing = pairing.copy(
                    state = if (verified) {
                        PairingState.WAITING_FOR_FINISH.storedName
                    } else {
                        PairingState.REJECTED.storedName
                    },
                    updatedAt = now,
                    decidedAt = now,
                    completedAt = if (verified) null else now,
                ),
            )
            if (verified) PairingDecisionResult.VERIFIED else PairingDecisionResult.REJECTED
        }

    suspend fun rejectPairing(requestId: Long): PairingDecisionResult = operationMutex.withLock {
        val request = dao.getRequestById(requestId) ?: return PairingDecisionResult.NOT_FOUND
        val pairing = dao.getPairing(requestId) ?: return PairingDecisionResult.NOT_FOUND
        if (pairing.state.toPairingState() !in USER_REJECTABLE_PAIRING_STATES) {
            return PairingDecisionResult.NOT_PENDING
        }
        val now = currentTimeMillis()
        dao.updatePairingRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                updatedAt = now,
                completedAt = now,
            ),
            pairing = pairing.copy(
                state = PairingState.REJECTED.storedName,
                updatedAt = now,
                decidedAt = pairing.decidedAt ?: now,
                completedAt = now,
            ),
        )
        PairingDecisionResult.REJECTED
    }

    private suspend fun processRequest(
        credentials: VaultRelayCredentials,
        message: RelayMessage,
    ): RelayUpdate? {
        val requestPayload = message.request ?: return null
        val existing = dao.getRequestByRelayId(message.requestId)
        if (existing != null) {
            return existing.responseJson?.let { response ->
                RelayUpdate(
                    requestId = message.requestId,
                    requestDelivered = true,
                    response = json.parseToJsonElement(response),
                )
            }
        }

        if (pairingProtocol.isInitialRequest(requestPayload)) {
            return startPairing(credentials, message.requestId, requestPayload)
        }

        val pairingId = pairingProtocol.encryptedPairingId(requestPayload) ?: return null
        return processFinishRequest(
            credentials = credentials,
            relayRequestId = message.requestId,
            requestPayload = requestPayload,
            pairingId = pairingId,
        )
    }

    private suspend fun startPairing(
        credentials: VaultRelayCredentials,
        relayRequestId: String,
        requestPayload: JsonElement,
    ): RelayUpdate? {
        if (!pairingProtocol.validateInitialRequest(requestPayload, credentials.address)) return null
        if (
            !pairingAdmissionAllowed(
                dao.getPairings().map { pairing -> pairing.state.toPairingState() },
            )
        ) {
            return null
        }

        val pairingId = pairingProtocol.generatePairingId()
        val response = pairingProtocol.initialResponse(pairingId, credentials.routePublicKey)
        val now = currentTimeMillis()
        val request = InboxRequestEntity(
            relayRequestId = relayRequestId,
            parentRequestId = null,
            kind = RequestKind.PAIRING.storedName,
            state = InboxRequestState.RECEIVING.storedName,
            listed = true,
            requestJson = requestPayload.toString(),
            responseJson = response.toString(),
            completionJson = null,
            receivedAt = now,
            updatedAt = now,
            completedAt = null,
            requestAcknowledgedAt = null,
            completionAcknowledgedAt = null,
        )
        dao.insertPairingRequest(
            request = request,
            pairing = PairingEntity(
                requestId = 0,
                vaultIdentityId = credentials.identityId,
                vaultAddress = credentials.address,
                routeId = credentials.routeId,
                pairingId = pairingId,
                state = PairingState.RECEIVING.storedName,
                sasOption0 = null,
                sasOption1 = null,
                sasOption2 = null,
                correctSasIndex = null,
                cliVersion = null,
                platform = null,
                architecture = null,
                hostname = null,
                machineId = null,
                osVersion = null,
                error = null,
                createdAt = now,
                updatedAt = now,
                decidedAt = null,
                completedAt = null,
            ),
        )
        return RelayUpdate(
            requestId = relayRequestId,
            requestDelivered = true,
            response = response,
        )
    }

    private suspend fun processInitialCompletion(
        credentials: VaultRelayCredentials,
        request: InboxRequestEntity,
        pairing: PairingEntity,
        completion: JsonElement,
    ): RelayUpdate {
        val rejectedEarly = pairing.state == PairingState.REJECTED.storedName
        if (pairing.state != PairingState.RECEIVING.storedName && !rejectedEarly) {
            return RelayUpdate(request.relayRequestId, completionDelivered = true)
        }
        if (
            rejectedEarly &&
            dao.getPairingSecret(pairing.requestId, PairingSecretKind.PSK.storedName) != null
        ) {
            return RelayUpdate(request.relayRequestId, completionDelivered = true)
        }
        val now = currentTimeMillis()
        val established = runCatching {
            pairingProtocol.establish(
                routeId = pairing.routeId,
                requestId = request.relayRequestId,
                pairingId = pairing.pairingId,
                routePrivateKey = credentials.routePrivateKey,
                routePublicKey = credentials.routePublicKey,
                completion = completion,
            )
        }
        established.fold(
            onSuccess = { result ->
                val choices = if (rejectedEarly) null else pairingProtocol.sasChoices(result.sas)
                val secret = encryptPairingPsk(pairing, result.pairingPsk, now)
                dao.recordInitialCompletion(
                    request = request.copy(
                        state = if (rejectedEarly) {
                            InboxRequestState.COMPLETED.storedName
                        } else {
                            InboxRequestState.ACTION_REQUIRED.storedName
                        },
                        completionJson = completion.toString(),
                        updatedAt = now,
                    ),
                    pairing = pairing.copy(
                        state = if (rejectedEarly) {
                            PairingState.REJECTED.storedName
                        } else {
                            PairingState.SAS_VERIFICATION_PENDING.storedName
                        },
                        sasOption0 = choices?.values?.get(0),
                        sasOption1 = choices?.values?.get(1),
                        sasOption2 = choices?.values?.get(2),
                        correctSasIndex = choices?.correctIndex,
                        cliVersion = result.client.cliVersion,
                        platform = result.client.platform,
                        architecture = result.client.architecture,
                        hostname = result.client.hostname,
                        machineId = result.client.machineId,
                        osVersion = result.client.osVersion,
                        updatedAt = now,
                    ),
                    secret = secret,
                )
            },
            onFailure = { failure ->
                dao.updatePairingRequest(
                    request = request.copy(
                        state = if (rejectedEarly) {
                            InboxRequestState.COMPLETED.storedName
                        } else {
                            InboxRequestState.ACTION_REQUIRED.storedName
                        },
                        completionJson = completion.toString(),
                        updatedAt = now,
                    ),
                    pairing = pairing.copy(
                        state = if (rejectedEarly) {
                            PairingState.REJECTED.storedName
                        } else {
                            PairingState.VERIFICATION_FAILED.storedName
                        },
                        error = failure.message ?: "The pairing message could not be verified.",
                        updatedAt = now,
                    ),
                )
            },
        )
        return RelayUpdate(request.relayRequestId, completionDelivered = true)
    }

    private suspend fun processFinishRequest(
        credentials: VaultRelayCredentials,
        relayRequestId: String,
        requestPayload: JsonElement,
        pairingId: String,
    ): RelayUpdate? {
        val pairing = dao.getPairingByPairingId(pairingId) ?: return null
        if (pairing.vaultIdentityId != credentials.identityId) return null
        val accepted = when (pairing.state) {
            PairingState.WAITING_FOR_FINISH.storedName -> true
            PairingState.REJECTED.storedName -> false
            else -> return null
        }
        val pairingPsk = decryptPairingPsk(pairing) ?: return null
        val prepared = runCatching {
            pairingProtocol.prepareFinishResponse(
                routeId = pairing.routeId,
                requestId = relayRequestId,
                pairingId = pairing.pairingId,
                pairingPsk = pairingPsk,
                routePrivateKey = credentials.routePrivateKey,
                routePublicKey = credentials.routePublicKey,
                request = requestPayload,
                accepted = accepted,
            )
        }.getOrNull() ?: return null
        val now = currentTimeMillis()
        dao.insertRequest(
            InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = pairing.requestId,
                kind = RequestKind.PAIRING_FINISH.storedName,
                state = if (accepted) {
                    InboxRequestState.WAITING.storedName
                } else {
                    InboxRequestState.COMPLETED.storedName
                },
                listed = false,
                requestJson = requestPayload.toString(),
                responseJson = prepared.response.toString(),
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = if (accepted) null else now,
                requestAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
        )
        return RelayUpdate(
            requestId = relayRequestId,
            requestDelivered = true,
            response = prepared.response,
        )
    }

    private suspend fun processCompletion(
        activeCredentials: VaultRelayCredentials,
        message: RelayMessage,
    ): RelayUpdate? {
        val completion = message.completion ?: return null
        val request = dao.getRequestByRelayId(message.requestId) ?: return null
        return when (request.kind) {
            RequestKind.PAIRING.storedName -> {
                val pairing = dao.getPairing(request.id) ?: return null
                val credentials = credentialsFor(pairing, activeCredentials) ?: return null
                processInitialCompletion(credentials, request, pairing, completion)
            }
            RequestKind.PAIRING_FINISH.storedName -> {
                processFinishCompletion(activeCredentials, request, completion)
            }
            else -> null
        }
    }

    private suspend fun processFinishCompletion(
        activeCredentials: VaultRelayCredentials,
        finishRequest: InboxRequestEntity,
        completion: JsonElement,
    ): RelayUpdate? {
        val rootRequestId = finishRequest.parentRequestId ?: return null
        val rootRequest = dao.getRequestById(rootRequestId) ?: return null
        val pairing = dao.getPairing(rootRequestId) ?: return null
        if (pairing.state == PairingState.ACTIVE.storedName) {
            return RelayUpdate(finishRequest.relayRequestId, completionDelivered = true)
        }
        if (pairing.state != PairingState.WAITING_FOR_FINISH.storedName) {
            return RelayUpdate(finishRequest.relayRequestId, completionDelivered = true)
        }
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val pairingPsk = decryptPairingPsk(pairing) ?: return null
        val verified = runCatching {
            pairingProtocol.verifyFinishCompletion(
                routeId = pairing.routeId,
                requestId = finishRequest.relayRequestId,
                pairingId = pairing.pairingId,
                pairingPsk = pairingPsk,
                routePrivateKey = credentials.routePrivateKey,
                routePublicKey = credentials.routePublicKey,
                request = json.parseToJsonElement(finishRequest.requestJson),
                completion = completion,
            )
        }
        val now = currentTimeMillis()
        if (verified.isSuccess) {
            dao.finishPairing(
                rootRequest = rootRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    updatedAt = now,
                    completedAt = now,
                ),
                pairing = pairing.copy(
                    state = PairingState.ACTIVE.storedName,
                    updatedAt = now,
                    completedAt = now,
                ),
                finishRequest = finishRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    completionJson = completion.toString(),
                    updatedAt = now,
                    completedAt = now,
                ),
            )
        } else {
            dao.updatePairingRequest(
                request = rootRequest.copy(
                    state = InboxRequestState.ACTION_REQUIRED.storedName,
                    updatedAt = now,
                ),
                pairing = pairing.copy(
                    state = PairingState.VERIFICATION_FAILED.storedName,
                    error = verified.exceptionOrNull()?.message
                        ?: "The pairing confirmation could not be verified.",
                    updatedAt = now,
                ),
            )
            dao.updateRequest(
                finishRequest.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    completionJson = completion.toString(),
                    updatedAt = now,
                    completedAt = now,
                ),
            )
        }
        return RelayUpdate(finishRequest.relayRequestId, completionDelivered = true)
    }

    private suspend fun credentialsFor(
        pairing: PairingEntity,
        active: VaultRelayCredentials,
    ): VaultRelayCredentials? {
        val identityId = pairing.vaultIdentityId ?: return null
        if (identityId == active.identityId) return active
        return when (val result = vault.relayCredentials(identityId)) {
            is VaultRelayCredentialsResult.Available -> result.credentials
            else -> null
        }
    }

    private suspend fun encryptPairingPsk(
        pairing: PairingEntity,
        pairingPsk: ByteArray,
        now: Long,
    ): PairingSecretEntity {
        require(pairingPsk.size == PAIRING_PSK_BYTES)
        val id = newId()
        val key = keyManager.activeKey()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = pairingSecretLocation(id, pairing),
                plaintext = pairingPsk,
            )
        }
        return PairingSecretEntity(
            id = id,
            pairingRequestId = pairing.requestId,
            kind = PairingSecretKind.PSK.storedName,
            encryptionFormat = encrypted.formatVersion,
            encryptionKeyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun decryptPairingPsk(pairing: PairingEntity): ByteArray? {
        val secret = dao.getPairingSecret(pairing.requestId, PairingSecretKind.PSK.storedName)
            ?: return null
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = EncryptedValue(
                    formatVersion = secret.encryptionFormat,
                    keyId = secret.encryptionKeyId,
                    nonce = secret.nonce,
                    ciphertext = secret.ciphertext,
                ),
                location = pairingSecretLocation(secret.id, pairing),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == PAIRING_PSK_BYTES
        }
    }

    private fun pairingSecretLocation(
        secretId: String,
        pairing: PairingEntity,
    ) = EncryptionLocation(
        recordType = "pairing_secret",
        recordId = secretId,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("kind", PairingSecretKind.PSK.storedName),
            EncryptionBinding("pairing_id", pairing.pairingId),
            EncryptionBinding("pairing_request_id", pairing.requestId.toString()),
            EncryptionBinding("route_id", pairing.routeId),
        ),
    )

    private fun MutableMap<String, RelayUpdate>.merge(update: RelayUpdate) {
        val current = this[update.requestId]
        this[update.requestId] = if (current == null) {
            update
        } else {
            RelayUpdate(
                requestId = update.requestId,
                requestDelivered = current.requestDelivered || update.requestDelivered,
                response = update.response ?: current.response,
                completionDelivered = current.completionDelivered || update.completionDelivered,
            )
        }
    }

    private fun String.toInboxRequestState(): InboxRequestState =
        checkNotNull(InboxRequestState.entries.find { it.storedName == this })

    private fun String.toPairingState(): PairingState =
        checkNotNull(PairingState.entries.find { it.storedName == this })

    private companion object {
        const val PAIRING_PSK_BYTES = 32
    }
}

private enum class RequestKind(val storedName: String) {
    PAIRING("pairing"),
    PAIRING_FINISH("pairing_finish"),
}

private enum class PairingSecretKind(val storedName: String) {
    PSK("pairing_psk"),
}

private val INCOMPLETE_PAIRING_STATES = setOf(
    PairingState.RECEIVING,
    PairingState.SAS_VERIFICATION_PENDING,
    PairingState.WAITING_FOR_FINISH,
    PairingState.VERIFICATION_FAILED,
)

private val USER_REJECTABLE_PAIRING_STATES = setOf(
    PairingState.RECEIVING,
    PairingState.VERIFICATION_FAILED,
)
