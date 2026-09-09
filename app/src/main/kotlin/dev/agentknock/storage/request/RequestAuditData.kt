package dev.agentknock.storage.request

import dev.agentknock.storage.audit.auditDataOf
import kotlinx.serialization.json.JsonElement

/** Stable, non-cryptographic context shared by every event for a persisted relay request. */
internal fun InboxRequestEntity.requestAuditData(): Map<String, JsonElement> =
    auditDataOf(
        "parent_request_id" to parentRequestId,
        "device_identity_id" to deviceIdentityId,
        "request_kind" to kind,
        "client_software" to
            clientSoftwareJson?.let { encoded ->
                runCatching { storedJson.parseToJsonElement(encoded) }.getOrNull()
            },
        "received_at" to receivedAt,
    )
