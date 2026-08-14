package dev.agentknock.storage.request

import dev.agentknock.protocol.CredentialCompletion
import dev.agentknock.protocol.CredentialDenialReason
import dev.agentknock.protocol.CredentialProtocol
import dev.agentknock.protocol.CredentialRequestMessage
import dev.agentknock.protocol.OpenedPairedRequest
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
import dev.agentknock.storage.profile.CredentialEnvironmentResult
import dev.agentknock.storage.profile.CredentialProfileMetadata
import dev.agentknock.storage.profile.CredentialProfileDescription
import dev.agentknock.storage.profile.CredentialProfileSource
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

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

internal enum class InboxRequestKind {
    PAIRING,
    CREDENTIAL,
}

internal enum class CredentialRequestState(val storedName: String) {
    APPROVAL_PENDING("approval_pending"),
    WAITING_FOR_COMPLETION("waiting_for_completion"),
    COMPLETED("completed"),
    VERIFICATION_FAILED("verification_failed"),
}

internal enum class CredentialDecision(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
}

internal enum class CredentialCompletionResult(val storedName: String) {
    APPROVED("approved"),
    DENIED("denied"),
    ABORTED("aborted"),
}

internal fun pairingAdmissionAllowed(existingStates: Iterable<PairingState>): Boolean =
    existingStates.none { it in INCOMPLETE_PAIRING_STATES }

internal data class InboxRequestSummary(
    val id: Long,
    val kind: InboxRequestKind,
    val state: InboxRequestState,
    val pairingState: PairingState?,
    val credentialState: CredentialRequestState?,
    val credentialDecision: CredentialDecision?,
    val credentialResult: CredentialCompletionResult?,
    val title: String,
    val subtitle: String,
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
    val pairing: PairingRequestDetails?,
    val credential: CredentialRequestDetails?,
)

internal data class CredentialRequestDetails(
    val state: CredentialRequestState,
    val decision: CredentialDecision?,
    val completionResult: CredentialCompletionResult?,
    val completionReason: String?,
    val completionMessage: String?,
    val profiles: List<String>,
    val profileDetails: List<CredentialProfileMetadata>,
    val missingProfiles: List<String>,
    val reason: String?,
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val resolvedPath: String?,
    val stdinKind: String,
    val stdoutKind: String,
    val stderrKind: String,
    val launcherChain: List<String>,
    val vaultAddress: String,
    val pairingId: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    val machineId: String?,
    val osVersion: String?,
    val cliVersion: String,
    val error: String?,
    val decidedAt: Long?,
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

internal sealed interface CredentialDecisionResult {
    data object Decided : CredentialDecisionResult

    data object ProfilesChanged : CredentialDecisionResult

    data object NotPending : CredentialDecisionResult

    data object NotFound : CredentialDecisionResult

    data class MissingProfiles(val names: List<String>) : CredentialDecisionResult

    data class ConflictingVariable(val name: String) : CredentialDecisionResult

    data object SecretUnavailable : CredentialDecisionResult

    data object SecretCorrupted : CredentialDecisionResult

    data object UnsupportedEncryption : CredentialDecisionResult

    data object PairingUnavailable : CredentialDecisionResult
}

internal class RequestRepository(
    private val dao: RequestDao,
    private val vault: VaultCredentialSource,
    private val profiles: CredentialProfileSource,
    private val relay: RelayInboxClient,
    private val keyManager: LocalEncryptionKeyManager,
    private val encryption: AesGcmEncryption,
    private val pairingProtocol: PairingProtocol = PairingProtocol(),
    private val credentialProtocol: CredentialProtocol = CredentialProtocol(),
    private val json: Json = Json,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val operationMutex = Mutex()

    fun observeRequests(): Flow<List<InboxRequestSummary>> = combine(
        dao.observeListedRequests(),
        dao.observePairings(),
        dao.observeCredentialRequests(),
    ) { requests, pairings, credentialRequests ->
        val pairingByRequest = pairings.associateBy(PairingEntity::requestId)
        val credentialByRequest = credentialRequests.associateBy(CredentialRequestEntity::requestId)
        requests.mapNotNull { request ->
            when (request.kind) {
                RequestKind.PAIRING.storedName -> {
                    val pairing = pairingByRequest[request.id] ?: return@mapNotNull null
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.PAIRING,
                        state = request.state.toInboxRequestState(),
                        pairingState = pairing.state.toPairingState(),
                        credentialState = null,
                        credentialDecision = null,
                        credentialResult = null,
                        title = pairing.hostname
                            ?: pairing.platform?.let { platform ->
                                pairing.architecture?.let { "$platform · $it" } ?: platform
                            }
                            ?: "Unknown client",
                        subtitle = pairing.vaultAddress,
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                    )
                }
                RequestKind.CREDENTIAL.storedName -> {
                    val credential = credentialByRequest[request.id] ?: return@mapNotNull null
                    val requestedProfiles = decodeStringList(credential.profilesJson)
                    InboxRequestSummary(
                        id = request.id,
                        kind = InboxRequestKind.CREDENTIAL,
                        state = request.state.toInboxRequestState(),
                        pairingState = null,
                        credentialState = credential.state.toCredentialRequestState(),
                        credentialDecision = credential.decision?.toCredentialDecision(),
                        credentialResult = credential.completionResult
                            ?.toCredentialCompletionResult(),
                        title = credential.command,
                        subtitle = requestedProfiles.joinToString(),
                        receivedAt = request.receivedAt,
                        completedAt = request.completedAt,
                    )
                }
                else -> null
            }
        }
    }

    fun observeRequest(id: Long): Flow<InboxRequestDetails?> = combine(
        dao.observeRequest(id),
        dao.observePairing(id),
        dao.observeCredentialRequest(id),
    ) { request, pairing, credential ->
        if (request == null) return@combine null
        InboxRequestDetails(
            id = request.id,
            relayRequestId = request.relayRequestId,
            state = request.state.toInboxRequestState(),
            receivedAt = request.receivedAt,
            completedAt = request.completedAt,
            pairing = pairing?.let {
                PairingRequestDetails(
                    pairingState = it.state.toPairingState(),
                    vaultAddress = it.vaultAddress,
                    pairingId = it.pairingId,
                    sasOptions = listOfNotNull(
                        it.sasOption0,
                        it.sasOption1,
                        it.sasOption2,
                    ).map(pairingProtocol::formatSas),
                    cliVersion = it.cliVersion,
                    platform = it.platform,
                    architecture = it.architecture,
                    hostname = it.hostname,
                    machineId = it.machineId,
                    osVersion = it.osVersion,
                    error = it.error,
                    decidedAt = it.decidedAt,
                )
            },
            credential = credential?.let {
                CredentialRequestDetails(
                    state = it.state.toCredentialRequestState(),
                    decision = it.decision?.toCredentialDecision(),
                    completionResult = it.completionResult?.toCredentialCompletionResult(),
                    completionReason = it.completionReason,
                    completionMessage = it.completionMessage,
                    profiles = decodeStringList(it.profilesJson),
                    profileDetails = json.decodeFromString(it.profileDetailsJson),
                    missingProfiles = decodeStringList(it.missingProfilesJson),
                    reason = it.reason,
                    command = it.command,
                    arguments = decodeStringList(it.argumentsJson),
                    workingDirectory = it.workingDirectory,
                    resolvedPath = it.resolvedPath,
                    stdinKind = it.stdinKind,
                    stdoutKind = it.stdoutKind,
                    stderrKind = it.stderrKind,
                    launcherChain = decodeStringList(it.launcherChainJson),
                    vaultAddress = it.vaultAddress,
                    pairingId = it.pairingId,
                    hostname = it.hostname,
                    platform = it.platform,
                    architecture = it.architecture,
                    machineId = it.machineId,
                    osVersion = it.osVersion,
                    cliVersion = it.cliVersion,
                    error = it.error,
                    decidedAt = it.decidedAt,
                )
            },
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

        deliverResponseOutbox(credentials)?.let { return it }

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
                deliverUpdates(credentials, updates.values.toList())?.let { return it }
            }

            if (!batch.hasMore || updates.isEmpty()) break
        } while (true)

        PollInboxResult.Success
    }

    private suspend fun deliverResponseOutbox(
        credentials: VaultRelayCredentials,
    ): PollInboxResult? {
        val updates = dao.getUnacknowledgedResponses().mapNotNull { request ->
            val pairing = pairingForRequest(request) ?: return@mapNotNull null
            if (
                pairing.routeId != credentials.routeId ||
                pairing.vaultIdentityId != credentials.identityId
            ) {
                return@mapNotNull null
            }
            RelayUpdate(
                requestId = request.relayRequestId,
                requestDelivered = request.requestAcknowledgedAt == null,
                response = json.parseToJsonElement(checkNotNull(request.responseJson)),
            )
        }
        return if (updates.isEmpty()) null else deliverUpdates(credentials, updates)
    }

    private suspend fun deliverUpdates(
        credentials: VaultRelayCredentials,
        updates: List<RelayUpdate>,
    ): PollInboxResult? = when (
        val result = relay.update(
            routeId = credentials.routeId,
            authenticationToken = credentials.authenticationToken,
            updates = updates,
        )
    ) {
        is RelayInboxResult.Success -> {
            val now = currentTimeMillis()
            updates.forEach { update ->
                if (update.requestDelivered) {
                    dao.markRequestAcknowledged(update.requestId, now)
                }
                if (update.response != null) {
                    dao.markResponseAcknowledged(update.requestId, now)
                }
                if (update.completionDelivered) {
                    dao.markCompletionAcknowledged(update.requestId, now)
                }
            }
            null
        }
        is RelayInboxResult.Rejected -> {
            PollInboxResult.RelayRejected(result.status, result.message)
        }
        is RelayInboxResult.Unavailable -> PollInboxResult.RelayUnavailable(result.cause.message)
        RelayInboxResult.InvalidResponse -> PollInboxResult.InvalidRelayResponse
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

    suspend fun approveCredentialRequest(requestId: Long): CredentialDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return CredentialDecisionResult.NotFound
            val credentialRequest = dao.getCredentialRequest(requestId)
                ?: return CredentialDecisionResult.NotFound
            if (credentialRequest.state != CredentialRequestState.APPROVAL_PENDING.storedName) {
                return CredentialDecisionResult.NotPending
            }
            val requestedProfiles = decodeStringList(credentialRequest.profilesJson)
            val latestDescription = profiles.describeCredentialProfiles(requestedProfiles)
            val storedProfiles = json.decodeFromString<List<CredentialProfileMetadata>>(
                credentialRequest.profileDetailsJson,
            )
            val storedMissingProfiles = decodeStringList(credentialRequest.missingProfilesJson)
            if (
                latestDescription.profiles != storedProfiles ||
                latestDescription.missingProfiles != storedMissingProfiles
            ) {
                val now = currentTimeMillis()
                dao.updateCredentialRequest(
                    request = request.copy(updatedAt = now),
                    credentialRequest = credentialRequest.copy(
                        profileDetailsJson = json.encodeToString(latestDescription.profiles),
                        missingProfilesJson = encodeStringList(latestDescription.missingProfiles),
                        updatedAt = now,
                    ),
                )
                return CredentialDecisionResult.ProfilesChanged
            }
            val environment = when (
                val result = profiles.credentialEnvironment(requestedProfiles)
            ) {
                is CredentialEnvironmentResult.Available -> result.environment
                is CredentialEnvironmentResult.MissingProfiles -> {
                    return CredentialDecisionResult.MissingProfiles(result.names)
                }
                is CredentialEnvironmentResult.ConflictingVariable -> {
                    return CredentialDecisionResult.ConflictingVariable(result.name)
                }
                CredentialEnvironmentResult.SecretUnavailable -> {
                    return CredentialDecisionResult.SecretUnavailable
                }
                CredentialEnvironmentResult.SecretCorrupted -> {
                    return CredentialDecisionResult.SecretCorrupted
                }
                CredentialEnvironmentResult.UnsupportedEncryption -> {
                    return CredentialDecisionResult.UnsupportedEncryption
                }
            }
            decideCredentialRequest(
                request = request,
                credentialRequest = credentialRequest,
                decision = CredentialDecision.APPROVED,
                responsePlaintext = credentialProtocol.approvedResponse(environment),
            )
        }

    suspend fun denyCredentialRequest(requestId: Long): CredentialDecisionResult =
        operationMutex.withLock {
            val request = dao.getRequestById(requestId)
                ?: return CredentialDecisionResult.NotFound
            val credentialRequest = dao.getCredentialRequest(requestId)
                ?: return CredentialDecisionResult.NotFound
            if (credentialRequest.state != CredentialRequestState.APPROVAL_PENDING.storedName) {
                return CredentialDecisionResult.NotPending
            }
            decideCredentialRequest(
                request = request,
                credentialRequest = credentialRequest,
                decision = CredentialDecision.DENIED,
                responsePlaintext = credentialProtocol.deniedResponse(
                    CredentialDenialReason.USER_DENIED,
                    CREDENTIAL_DENIAL_MESSAGE,
                ),
            )
        }

    private suspend fun decideCredentialRequest(
        request: InboxRequestEntity,
        credentialRequest: CredentialRequestEntity,
        decision: CredentialDecision,
        responsePlaintext: ByteArray,
    ): CredentialDecisionResult {
        val pairingRequestId = credentialRequest.pairingRequestId
            ?: return CredentialDecisionResult.PairingUnavailable
        val pairing = dao.getPairing(pairingRequestId)
            ?: return CredentialDecisionResult.PairingUnavailable
        if (pairing.state != PairingState.ACTIVE.storedName) {
            return CredentialDecisionResult.PairingUnavailable
        }
        val credentials = credentialsForPairing(pairing)
            ?: return CredentialDecisionResult.PairingUnavailable
        val pairingPsk = decryptPairingPsk(pairing)
            ?: return CredentialDecisionResult.PairingUnavailable
        val response = runCatching {
            pairingProtocol.sealPairedResponse(
                routeId = pairing.routeId,
                requestId = request.relayRequestId,
                pairingId = pairing.pairingId,
                pairingPsk = pairingPsk,
                routePrivateKey = credentials.routePrivateKey,
                routePublicKey = credentials.routePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                plaintext = responsePlaintext,
            )
        }.getOrNull() ?: return CredentialDecisionResult.PairingUnavailable
        val now = currentTimeMillis()
        dao.updateCredentialRequest(
            request = request.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = response.toString(),
                responseAcknowledgedAt = null,
                updatedAt = now,
            ),
            credentialRequest = credentialRequest.copy(
                state = CredentialRequestState.WAITING_FOR_COMPLETION.storedName,
                decision = decision.storedName,
                updatedAt = now,
                decidedAt = now,
            ),
        )
        return CredentialDecisionResult.Decided
    }

    private suspend fun processRequest(
        credentials: VaultRelayCredentials,
        message: RelayMessage,
    ): RelayUpdate? {
        val requestPayload = message.request ?: return null
        val existing = dao.getRequestByRelayId(message.requestId)
        if (existing != null) {
            return RelayUpdate(
                requestId = message.requestId,
                requestDelivered = true,
                response = existing.responseJson?.let(json::parseToJsonElement),
            )
        }

        if (pairingProtocol.isInitialRequest(requestPayload)) {
            return startPairing(credentials, message.requestId, requestPayload)
        }

        val pairingId = pairingProtocol.encryptedPairingId(requestPayload) ?: return null
        val pairing = dao.getPairingByPairingId(pairingId) ?: return null
        if (pairing.vaultIdentityId != credentials.identityId) return null
        val pairingPsk = decryptPairingPsk(pairing) ?: return null
        val opened = runCatching {
            pairingProtocol.openPairedRequest(
                routeId = pairing.routeId,
                requestId = message.requestId,
                pairingId = pairing.pairingId,
                pairingPsk = pairingPsk,
                routePrivateKey = credentials.routePrivateKey,
                routePublicKey = credentials.routePublicKey,
                request = requestPayload,
            )
        }.getOrNull() ?: return null
        val method = runCatching { credentialProtocol.method(opened.plaintext) }.getOrNull()
            ?: return null
        if (method == CredentialProtocol.CREDENTIAL_REQUEST_METHOD) {
            return processCredentialRequest(
                pairing = pairing,
                relayRequestId = message.requestId,
                requestPayload = requestPayload,
                opened = opened,
                currentPairingPsk = pairingPsk,
            )
        }
        if (method != CredentialProtocol.FINISH_PAIRING_METHOD) return null
        return processFinishRequest(
            credentials = credentials,
            relayRequestId = message.requestId,
            requestPayload = requestPayload,
            pairingId = pairingId,
        )
    }

    private suspend fun processCredentialRequest(
        pairing: PairingEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        opened: OpenedPairedRequest,
        currentPairingPsk: ByteArray,
    ): RelayUpdate? {
        if (pairing.state != PairingState.ACTIVE.storedName) return null
        val contents = runCatching {
            credentialProtocol.decodeRequest(opened.plaintext)
        }.getOrNull() ?: return null
        val description = profiles.describeCredentialProfiles(contents.profiles)
        val now = currentTimeMillis()
        val currentSecret = dao.getPairingSecret(
            pairing.requestId,
            PairingSecretKind.PSK.storedName,
        ) ?: return null
        val rotatedSecret = if (opened.pairingPsk.contentEquals(currentPairingPsk)) {
            null
        } else {
            encryptPairingPsk(currentSecret, pairing, opened.pairingPsk, now)
        }
        dao.insertCredentialRequest(
            request = InboxRequestEntity(
                relayRequestId = relayRequestId,
                parentRequestId = null,
                kind = RequestKind.CREDENTIAL.storedName,
                state = InboxRequestState.ACTION_REQUIRED.storedName,
                listed = true,
                requestJson = requestPayload.toString(),
                responseJson = null,
                completionJson = null,
                receivedAt = now,
                updatedAt = now,
                completedAt = null,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
            credentialRequest = credentialRequestEntity(
                pairing = pairing,
                contents = contents,
                description = description,
                now = now,
            ),
            rotatedPairingSecret = rotatedSecret,
        )
        return RelayUpdate(requestId = relayRequestId, requestDelivered = true)
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
            responseAcknowledgedAt = null,
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

    private fun credentialRequestEntity(
        pairing: PairingEntity,
        contents: CredentialRequestMessage,
        description: CredentialProfileDescription,
        now: Long,
    ) = CredentialRequestEntity(
        requestId = 0,
        pairingRequestId = pairing.requestId,
        pairingId = pairing.pairingId,
        vaultAddress = pairing.vaultAddress,
        hostname = pairing.hostname,
        platform = pairing.platform,
        architecture = pairing.architecture,
        machineId = pairing.machineId,
        osVersion = pairing.osVersion,
        state = CredentialRequestState.APPROVAL_PENDING.storedName,
        cliVersion = contents.cliVersion,
        profilesJson = encodeStringList(contents.profiles),
        profileDetailsJson = json.encodeToString(description.profiles),
        missingProfilesJson = encodeStringList(description.missingProfiles),
        reason = contents.reason,
        command = contents.operation.command,
        argumentsJson = encodeStringList(contents.operation.arguments),
        workingDirectory = contents.operation.workingDirectory,
        resolvedPath = contents.operation.resolvedPath,
        stdinKind = contents.operation.stdin,
        stdoutKind = contents.operation.stdout,
        stderrKind = contents.operation.stderr,
        launcherChainJson = encodeStringList(contents.launcherChain),
        decision = null,
        completionResult = null,
        completionReason = null,
        completionMessage = null,
        error = null,
        createdAt = now,
        updatedAt = now,
        decidedAt = null,
        completedAt = null,
    )

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
                responseAcknowledgedAt = null,
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
            RequestKind.CREDENTIAL.storedName -> {
                processCredentialCompletion(activeCredentials, request, completion)
            }
            else -> null
        }
    }

    private suspend fun processCredentialCompletion(
        activeCredentials: VaultRelayCredentials,
        request: InboxRequestEntity,
        completion: JsonElement,
    ): RelayUpdate? {
        val credentialRequest = dao.getCredentialRequest(request.id) ?: return null
        if (
            credentialRequest.state == CredentialRequestState.COMPLETED.storedName ||
            credentialRequest.state == CredentialRequestState.VERIFICATION_FAILED.storedName
        ) {
            return RelayUpdate(request.relayRequestId, completionDelivered = true)
        }
        val pairingRequestId = credentialRequest.pairingRequestId ?: return null
        val pairing = dao.getPairing(pairingRequestId) ?: return null
        val credentials = credentialsFor(pairing, activeCredentials) ?: return null
        val pairingPsk = decryptPairingPsk(pairing) ?: return null
        val decoded = runCatching {
            val plaintext = pairingProtocol.openPairedCompletion(
                routeId = pairing.routeId,
                requestId = request.relayRequestId,
                pairingId = pairing.pairingId,
                pairingPsk = pairingPsk,
                routePrivateKey = credentials.routePrivateKey,
                routePublicKey = credentials.routePublicKey,
                request = json.parseToJsonElement(request.requestJson),
                completion = completion,
            )
            credentialProtocol.decodeCompletion(plaintext)
        }
        val now = currentTimeMillis()
        val completionResult = decoded.getOrNull()
        val valid = when (completionResult) {
            is CredentialCompletion.Approved -> {
                credentialRequest.decision == CredentialDecision.APPROVED.storedName
            }
            is CredentialCompletion.Denied -> {
                credentialRequest.decision == CredentialDecision.DENIED.storedName &&
                    completionResult.reason == CredentialDenialReason.USER_DENIED.wireName &&
                    completionResult.message == CREDENTIAL_DENIAL_MESSAGE
            }
            is CredentialCompletion.Aborted -> true
            null -> false
        }
        dao.updateCredentialRequest(
            request = request.copy(
                state = InboxRequestState.COMPLETED.storedName,
                completionJson = completion.toString(),
                responseAcknowledgedAt = request.responseAcknowledgedAt ?: now,
                updatedAt = now,
                completedAt = now,
            ),
            credentialRequest = credentialRequest.copy(
                state = if (valid) {
                    CredentialRequestState.COMPLETED.storedName
                } else {
                    CredentialRequestState.VERIFICATION_FAILED.storedName
                },
                completionResult = when (completionResult) {
                    is CredentialCompletion.Approved -> {
                        CredentialCompletionResult.APPROVED.storedName
                    }
                    is CredentialCompletion.Denied -> {
                        CredentialCompletionResult.DENIED.storedName
                    }
                    is CredentialCompletion.Aborted -> {
                        CredentialCompletionResult.ABORTED.storedName
                    }
                    null -> null
                },
                completionReason = when (completionResult) {
                    is CredentialCompletion.Denied -> completionResult.reason
                    is CredentialCompletion.Aborted -> completionResult.reason
                    else -> null
                },
                completionMessage = when (completionResult) {
                    is CredentialCompletion.Denied -> completionResult.message
                    is CredentialCompletion.Aborted -> completionResult.message
                    else -> null
                },
                error = if (valid) {
                    null
                } else {
                    decoded.exceptionOrNull()?.message
                        ?: "Credential completion did not match the phone decision."
                },
                updatedAt = now,
                completedAt = now,
            ),
        )
        return RelayUpdate(request.relayRequestId, completionDelivered = true)
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

    private suspend fun credentialsForPairing(
        pairing: PairingEntity,
    ): VaultRelayCredentials? {
        val identityId = pairing.vaultIdentityId ?: return null
        return when (val result = vault.relayCredentials(identityId)) {
            is VaultRelayCredentialsResult.Available -> result.credentials
            else -> null
        }
    }

    private suspend fun pairingForRequest(request: InboxRequestEntity): PairingEntity? =
        when (request.kind) {
            RequestKind.PAIRING.storedName -> dao.getPairing(request.id)
            RequestKind.PAIRING_FINISH.storedName -> {
                request.parentRequestId?.let { dao.getPairing(it) }
            }
            RequestKind.CREDENTIAL.storedName -> {
                dao.getCredentialRequest(request.id)?.pairingRequestId?.let { dao.getPairing(it) }
            }
            else -> null
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

    private suspend fun encryptPairingPsk(
        existing: PairingSecretEntity,
        pairing: PairingEntity,
        pairingPsk: ByteArray,
        now: Long,
    ): PairingSecretEntity {
        require(pairingPsk.size == PAIRING_PSK_BYTES)
        val key = keyManager.activeKey()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = pairingSecretLocation(existing.id, pairing),
                plaintext = pairingPsk,
            )
        }
        return existing.copy(
            encryptionFormat = encrypted.formatVersion,
            encryptionKeyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
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

    private fun String.toCredentialRequestState(): CredentialRequestState =
        checkNotNull(CredentialRequestState.entries.find { it.storedName == this })

    private fun String.toCredentialDecision(): CredentialDecision =
        checkNotNull(CredentialDecision.entries.find { it.storedName == this })

    private fun String.toCredentialCompletionResult(): CredentialCompletionResult =
        checkNotNull(CredentialCompletionResult.entries.find { it.storedName == this })

    private fun encodeStringList(values: List<String>): String =
        json.encodeToString(STRING_LIST_SERIALIZER, values)

    private fun decodeStringList(value: String): List<String> =
        json.decodeFromString(STRING_LIST_SERIALIZER, value)

    private companion object {
        const val PAIRING_PSK_BYTES = 32
        const val CREDENTIAL_DENIAL_MESSAGE = "Denied on phone."
        val STRING_LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}

private enum class RequestKind(val storedName: String) {
    PAIRING("pairing"),
    PAIRING_FINISH("pairing_finish"),
    CREDENTIAL("credential"),
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
