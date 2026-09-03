package dev.agentknock.storage.request

import dev.agentknock.review.ApprovalReviewSecretFacts
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun decodeStoredApprovalReviewSecretFacts(
    encoded: String,
): Map<String, ApprovalReviewSecretFacts>? = runCatching {
    storedJson.decodeFromString<Map<String, ApprovalReviewSecretFacts>>(encoded)
}.getOrNull() ?: runCatching {
    val stored = storedJson.parseToJsonElement(encoded).jsonObject
    val normalized = JsonObject(
        stored.mapValues { (_, facts) -> normalizeLegacyFacts(facts.jsonObject) },
    )
    storedJson.decodeFromJsonElement<Map<String, ApprovalReviewSecretFacts>>(normalized)
}.getOrNull()

private fun normalizeLegacyFacts(facts: JsonObject): JsonObject {
    val legacyVariables = facts["environment_variables"]?.jsonObject ?: return facts
    return buildJsonObject {
        put("type", checkNotNull(facts["type"]))
        put(
            "variables",
            JsonObject(
                legacyVariables.mapValues { (_, variable) ->
                    normalizeLegacyVariable(variable.jsonObject)
                },
            ),
        )
    }
}

private fun normalizeLegacyVariable(variable: JsonObject): JsonObject {
    val destination = checkNotNull(variable["destination"]).jsonObject
    val delivery = checkNotNull(destination["type"]).jsonPrimitive.content
    return buildJsonObject {
        put("delivery", JsonPrimitive(delivery))
        if (delivery == "environment") {
            put("target", checkNotNull(destination["name"]))
        }
        variable["value"]?.takeUnless { it == JsonNull }?.let { put("value", it) }
    }
}
