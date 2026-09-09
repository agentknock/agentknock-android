package dev.agentknock.storage.request

import dev.agentknock.protocol.ClientSoftware
import kotlinx.serialization.json.Json

/** JSON read from Room snapshots, which must remain readable across compatible model changes. */
internal val storedJson = Json {
    ignoreUnknownKeys = true
}

internal fun decodeStoredClientSoftware(value: String): ClientSoftware? = runCatching {
    storedJson.decodeFromString<ClientSoftware>(value)
}
    .getOrNull()
