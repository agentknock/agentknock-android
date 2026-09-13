package dev.agentknock.storage.request

import dev.agentknock.protocol.PairingRemoveProtocol
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.auditDataOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Owns durable client-removal transitions without owning relay transport or envelope crypto. */
internal class ClientRemovalRequests(
    private val dao: RequestDao,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val protocol: PairingRemoveProtocol = PairingRemoveProtocol(),
    private val json: Json = Json,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun receive(
        client: ClientEntity,
        relayRequestId: String,
        requestPayload: JsonElement,
        plaintext: ByteArray,
        requestPsk: RequestPskEntity,
        sealResponse: (ByteArray) -> JsonElement?,
    ): JsonElement? {
        val clientSoftware =
            runCatching { protocol.decodeRequest(plaintext) }.getOrNull() ?: return null
        val response = sealResponse(protocol.response()) ?: return null
        val now = currentTimeMillis()
        dao.insertPairingRemoval(
            request =
                InboxRequestEntity(
                    id = relayRequestId,
                    parentRequestId = null,
                    deviceIdentityId = client.deviceIdentityId,
                    clientId = client.clientId,
                    clientNameSnapshot = client.name,
                    clientSoftwareJson = json.encodeToString(clientSoftware),
                    kind = RequestKind.PAIRING_REMOVE.storedName,
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
            requestPsk = requestPsk,
            client =
                client.copy(
                    desiredRelayClientState = RelayClientState.REVOKED.wireName,
                    lastSeenAt = now,
                ),
        )
        return response
    }

    /** Returns true when the completion is terminal and can be acknowledged. */
    suspend fun complete(
        request: InboxRequestEntity,
        openCompletion: suspend () -> CompletionOpenResult,
    ): Boolean {
        val existing = dao.getRequestById(request.id) ?: return false
        if (existing.kind != RequestKind.PAIRING_REMOVE.storedName) return false
        if (existing.exchangeEndedAt != null) return true

        val opened = openCompletion()
        val decoded =
            (opened as? CompletionOpenResult.Opened)?.plaintext?.let {
                decodeWireCompletionOrNull { protocol.decodeCompletion(it) }
            }
        return writeTransaction.execute {
            val current = dao.getRequestById(request.id) ?: return@execute false
            if (current.kind != RequestKind.PAIRING_REMOVE.storedName) return@execute false
            if (current.exchangeEndedAt != null) return@execute true
            if (opened == CompletionOpenResult.RetryLater) return@execute false

            val priorError = current.error
            val valid = priorError == null && decoded != null
            val error =
                priorError ?: CLIENT_REMOVAL_COMPLETION_VERIFICATION_ERROR.takeUnless { valid }
            val now = currentTimeMillis()
            dao.updateEndedRequest(
                current.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    responseOutboxFinished = true,
                    error = error,
                    completedAt = current.completedAt ?: now,
                    exchangeEndedAt = now,
                )
            )
            audit.append(
                records =
                    listOf(
                        AuditRecord(
                            type =
                                if (valid) {
                                    AuditEventType.CLIENT_UNPAIRED_ITSELF
                                } else {
                                    AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED
                                },
                            outcome = if (valid) AuditOutcome.COMPLETED else AuditOutcome.FAILED,
                            subject = current.clientNameSnapshot,
                            detail = error,
                            clientId = current.clientId,
                            clientName = current.clientNameSnapshot,
                            relayRequestId = current.id,
                            data =
                                current.requestAuditData() +
                                    auditDataOf(
                                        "completion_valid" to valid,
                                        "returned_client_software" to
                                            decoded?.let {
                                                storedJson.encodeToJsonElement(it)
                                            },
                                    ),
                        )
                    ),
                occurredAt = now,
            )
            true
        }
    }

    suspend fun expire(request: InboxRequestEntity, message: String, now: Long) {
        writeTransaction.execute {
            val current = dao.getRequestById(request.id) ?: return@execute
            if (current.kind != RequestKind.PAIRING_REMOVE.storedName) return@execute
            if (current.exchangeEndedAt != null) return@execute

            val unconfirmed = current.completedAt == null
            dao.updateEndedRequest(
                current.copy(
                    state = InboxRequestState.COMPLETED.storedName,
                    responseOutboxFinished = true,
                    error = current.error ?: message.takeIf { unconfirmed },
                    completedAt = current.completedAt ?: now,
                    exchangeEndedAt = now,
                )
            )
            if (unconfirmed) {
                audit.append(
                    records =
                        listOf(
                            AuditRecord(
                                type = AuditEventType.CLIENT_REMOVAL_UNCONFIRMED,
                                outcome = AuditOutcome.FAILED,
                                subject = current.clientNameSnapshot,
                                detail = message,
                                clientId = current.clientId,
                                clientName = current.clientNameSnapshot,
                                relayRequestId = current.id,
                                data =
                                    current.requestAuditData() +
                                        auditDataOf(
                                            "transport_error" to message,
                                            "completion_confirmed" to false,
                                        ),
                            )
                        ),
                    occurredAt = now,
                )
            }
        }
    }

    private companion object {
        const val CLIENT_REMOVAL_COMPLETION_VERIFICATION_ERROR =
            "Client removal completion could not be verified."
    }
}
