package dev.agentknock.storage.request

import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.auditDataOf
import dev.agentknock.storage.secret.TemporaryAccessGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement

internal data class ClientSummary(
    val clientId: String,
    val name: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    val state: RelayClientState,
    val desiredState: RelayClientState?,
    val pairedAt: Long?,
    val lastRequestAt: Long? = null,
    val temporaryAccessCount: Int = 0,
)

internal data class ClientDetails(
    val clientId: String,
    val name: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    val osVersion: String?,
    val machineId: String?,
    val clientSoftware: ClientSoftware?,
    val instructions: String,
    val state: RelayClientState,
    val desiredState: RelayClientState?,
    val pairedAt: Long?,
    val lastRequestAt: Long? = null,
)

internal enum class ClientChangeResult {
    CHANGED,
    NOT_FOUND,
    INVALID_STATE,
}

internal class ClientRepository(
    private val dao: RequestDao,
    private val temporaryAccessGrants: Flow<List<TemporaryAccessGrant>>,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
) {
    fun observeClients(): Flow<List<ClientSummary>> = combine(
        dao.observeClients(),
        temporaryAccessGrants,
    ) { clients, grants ->
        val grantCounts = grants.distinctBy { it.clientId to it.secretId }
            .groupingBy { it.clientId }.eachCount()
        clients
            .mapNotNull { client ->
                val state = client.relayClientState.toRelayClientState()
                val desiredState = client.desiredRelayClientState?.toRelayClientState()
                if (state == RelayClientState.REVOKED || desiredState == RelayClientState.REVOKED) {
                    return@mapNotNull null
                }
                ClientSummary(
                    clientId = client.clientId,
                    name = client.name,
                    hostname = client.hostname,
                    platform = client.platform,
                    architecture = client.architecture,
                    state = state,
                    desiredState = desiredState,
                    pairedAt = client.pairedAt,
                    lastRequestAt = client.lastSeenAt,
                    temporaryAccessCount = grantCounts[client.clientId] ?: 0,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun observeClient(clientId: String): Flow<ClientDetails?> =
        dao.observeClient(clientId).map { client ->
            client?.takeIf {
                it.relayClientState != RelayClientState.REVOKED.wireName &&
                    it.desiredRelayClientState != RelayClientState.REVOKED.wireName
            }?.let {
                ClientDetails(
                    clientId = it.clientId,
                    name = it.name,
                    hostname = it.hostname,
                    platform = it.platform,
                    architecture = it.architecture,
                    osVersion = it.osVersion,
                    machineId = it.machineId,
                    clientSoftware = it.clientSoftwareJson?.let(::decodeStoredClientSoftware),
                    instructions = it.instructions,
                    state = it.relayClientState.toRelayClientState(),
                    desiredState = it.desiredRelayClientState?.toRelayClientState(),
                    pairedAt = it.pairedAt,
                    lastRequestAt = it.lastSeenAt,
                )
            }
        }

    suspend fun rename(clientId: String, name: String): ClientChangeResult {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A client name cannot be empty" }
        return writeTransaction.execute {
            val client = dao.getClient(clientId)
                ?: return@execute ClientChangeResult.NOT_FOUND
            if (client.name == trimmed) return@execute ClientChangeResult.CHANGED
            check(dao.updateClient(client.copy(name = trimmed)) == 1)
            audit.record(
                AuditRecord(
                    type = AuditEventType.CLIENT_RENAMED,
                    outcome = AuditOutcome.CHANGED,
                    subject = trimmed,
                    detail = client.name.takeUnless { it == trimmed },
                    clientId = clientId,
                    clientName = trimmed,
                    data = client.copy(name = trimmed).auditData() + auditDataOf(
                        "previous_name" to client.name,
                    ),
                ),
            )
            ClientChangeResult.CHANGED
        }
    }

    suspend fun saveInstructions(
        clientId: String,
        instructions: String,
    ): ClientChangeResult = writeTransaction.execute {
        val client = dao.getClient(clientId)
            ?: return@execute ClientChangeResult.NOT_FOUND
        val normalized = instructions.trim()
        if (client.instructions == normalized) return@execute ClientChangeResult.CHANGED
        check(dao.updateClient(client.copy(instructions = normalized)) == 1)
        audit.record(
            AuditRecord(
                type = AuditEventType.CLIENT_INSTRUCTIONS_CHANGED,
                outcome = AuditOutcome.CHANGED,
                subject = client.name,
                clientId = clientId,
                clientName = client.name,
                data = client.copy(instructions = normalized).auditData() + auditDataOf(
                    "previous_instructions" to client.instructions,
                ),
            ),
        )
        ClientChangeResult.CHANGED
    }

    suspend fun setDesiredRelayState(
        clientId: String,
        state: RelayClientState,
    ): ClientChangeResult = writeTransaction.execute {
        if (state == RelayClientState.PENDING) return@execute ClientChangeResult.INVALID_STATE
        val client = dao.getClient(clientId)
            ?: return@execute ClientChangeResult.NOT_FOUND
        if (client.desiredRelayClientState == RelayClientState.REVOKED.wireName) {
            return@execute if (state == RelayClientState.REVOKED) {
                ClientChangeResult.CHANGED
            } else {
                ClientChangeResult.INVALID_STATE
            }
        }
        val current = client.relayClientState.toRelayClientState()
        val allowed = when (current) {
            RelayClientState.ACTIVE -> state == RelayClientState.SUSPENDED ||
                state == RelayClientState.REVOKED
            RelayClientState.SUSPENDED -> state == RelayClientState.ACTIVE ||
                state == RelayClientState.REVOKED
            RelayClientState.REVOKED -> state == RelayClientState.REVOKED
            RelayClientState.PENDING -> false
        }
        if (!allowed) return@execute ClientChangeResult.INVALID_STATE
        val updated = client.copy(desiredRelayClientState = state.wireName)
        if (state == RelayClientState.REVOKED) {
            dao.revokeClient(updated)
        } else {
            check(dao.updateClient(updated) == 1)
        }
        ClientChangeResult.CHANGED
    }

    suspend fun applyRelayState(clientId: String, state: RelayClientState) {
        writeTransaction.execute {
            val client = dao.getClient(clientId) ?: return@execute
            if (state == RelayClientState.REVOKED) {
                check(dao.deleteClient(client.clientId) == 1)
            } else {
                check(
                    dao.updateClient(
                        client.copy(
                            relayClientState = state.wireName,
                            desiredRelayClientState = client.desiredRelayClientState
                                ?.takeUnless { it == state.wireName },
                        ),
                    ) == 1,
                )
            }
            if (client.relayClientState != state.wireName) {
                val applied = client.copy(
                    relayClientState = state.wireName,
                    desiredRelayClientState = client.desiredRelayClientState
                        ?.takeUnless { it == state.wireName },
                )
                audit.record(
                    AuditRecord(
                        type = when (state) {
                            RelayClientState.ACTIVE -> AuditEventType.CLIENT_RESUMED
                            RelayClientState.SUSPENDED -> AuditEventType.CLIENT_SUSPENDED
                            RelayClientState.REVOKED -> AuditEventType.CLIENT_REVOKED
                            RelayClientState.PENDING -> AuditEventType.CLIENT_PENDING
                        },
                        outcome = AuditOutcome.CHANGED,
                        subject = client.name,
                        clientId = client.clientId,
                        clientName = client.name,
                        data = applied.auditData() + auditDataOf(
                            "previous_relay_state" to client.relayClientState,
                            "previous_desired_relay_state" to client.desiredRelayClientState,
                        ),
                    ),
                )
            }
        }
    }

    private fun String.toRelayClientState(): RelayClientState =
        checkNotNull(RelayClientState.entries.find { it.wireName == this })

    private fun ClientEntity.auditData(): Map<String, JsonElement> = auditDataOf(
        "device_identity_id" to deviceIdentityId,
        "name" to name,
        "instructions" to instructions,
        "hostname" to hostname,
        "platform" to platform,
        "architecture" to architecture,
        "os_version" to osVersion,
        "machine_id" to machineId,
        "client_software" to clientSoftwareJson?.let { encoded ->
            runCatching { storedJson.parseToJsonElement(encoded) }.getOrNull()
        },
        "relay_state" to relayClientState,
        "desired_relay_state" to desiredRelayClientState,
        "paired_at" to pairedAt,
        "last_seen_at" to lastSeenAt,
    )

}
