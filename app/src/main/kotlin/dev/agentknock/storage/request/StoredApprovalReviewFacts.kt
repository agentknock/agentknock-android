package dev.agentknock.storage.request

import dev.agentknock.relay.ApprovalReviewSecretFacts
import kotlinx.serialization.decodeFromString

internal fun decodeStoredApprovalReviewSecretFacts(
    encoded: String,
): Map<String, ApprovalReviewSecretFacts>? =
    runCatching {
        storedJson.decodeFromString<Map<String, ApprovalReviewSecretFacts>>(encoded)
    }.getOrNull()
