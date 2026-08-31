package dev.agentknock.storage.request

import dev.agentknock.relay.ApprovalReviewSecretFacts
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun Json.decodeStoredApprovalReviewSecretFacts(
    encoded: String,
): Map<String, ApprovalReviewSecretFacts>? =
    runCatching { decodeFromString<Map<String, ApprovalReviewSecretFacts>>(encoded) }.getOrNull()
