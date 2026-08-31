package dev.agentknock.storage.request

import kotlinx.serialization.json.Json

/** JSON read from Room snapshots, which must remain readable across compatible model changes. */
internal val storedJson = Json {
    ignoreUnknownKeys = true
}
