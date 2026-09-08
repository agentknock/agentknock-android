package dev.agentknock.ui.settings

import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditEventType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class AuditDetailField(
    val label: String,
    val value: String,
    val monospace: Boolean = false,
)

/** Recorded participants and command, never identities looked up from today's vault. */
internal fun AuditEvent.summaryFields(): List<AuditDetailField> = buildList {
    val presentation = presentation()
    commandField()?.let(::add)
    subject?.let { add(AuditDetailField(presentation.subjectLabel ?: "Subject", it)) }
    clientField(abbreviateId = true)?.let(::add)
    if (context != null && presentation.contextLabel != "Command") {
        add(AuditDetailField(presentation.contextLabel ?: "Context", context))
    }
    if (type in setOf(
            AuditEventType.SECRET_APPROVAL_MODE_CHANGED,
            AuditEventType.CLIENT_APPROVAL_OVERRIDE_CHANGED,
            AuditEventType.TEMPORARY_ACCESS_ALLOWED,
            AuditEventType.TEMPORARY_ACCESS_ENDED,
        )) {
        detail?.let { add(AuditDetailField(presentation.detailLabel ?: "Change", it)) }
    }
}

internal fun AuditEvent.displayDetailFields(formatTimestamp: (Long) -> String): List<AuditDetailField> = buildList {
    val presentation = presentation()
    val relevant = relevantDetailFields().toMutableList()
    clientField(abbreviateId = false)?.let(::add)
    subject?.let {
        add(AuditDetailField(presentation.subjectLabel ?: "Subject", it,
            presentation.subjectLabel in setOf("Pairing address", "Environment variable")))
    }
    commandField()?.let { command ->
        add(command)
        relevant.removeAll { it.label == "Command" }
    }
    if (context != null && presentation.contextLabel != "Command") {
        add(AuditDetailField(presentation.contextLabel ?: "Context", context))
    }
    val (reviewFields, otherFields) = relevant.partition {
        it.label in setOf("AI decision", "AI explanation", "AI review problem", "Applied decision", "Applied explanation")
    }
    addAll(reviewFields)
    detail?.let { add(AuditDetailField(presentation.detailLabel ?: "Details", it)) }
    decisionSource?.takeUnless { it == AuditDecisionSource.AI_REVIEW }?.let {
        add(AuditDetailField("Decision source", it.displayName()))
    }
    expiresAt?.let { add(AuditDetailField("Valid until", formatTimestamp(it))) }
    addAll(otherFields)
}

private fun AuditEvent.commandField(): AuditDetailField? {
    if (presentation().contextLabel != "Command") return null
    val command = data.text("command")?.let { renderShellCommand(it, data.stringArray("arguments")) }
        ?: context ?: return null
    return AuditDetailField("Command", command, monospace = true)
}

private fun AuditEvent.clientField(abbreviateId: Boolean): AuditDetailField? {
    clientName?.takeIf(String::isNotBlank)?.let {
        if (it == subject && presentation().subjectLabel == "Client") return null
        return AuditDetailField("Client", it)
    }
    val id = clientId ?: return null
    return AuditDetailField("Client ID", if (abbreviateId && id.length > 8) "…${id.takeLast(6)}" else id,
        monospace = true)
}

internal fun AuditEvent.relevantDetailFields(): List<AuditDetailField> = buildList {
    when (type) {
        AuditEventType.SECRET_USE_RECEIVED,
        AuditEventType.SECRET_USE_AI_REVIEWED,
        AuditEventType.SECRET_USE_DECIDED,
        AuditEventType.SECRET_USE_COMPLETED,
        -> addInvocationFields(data)

        AuditEventType.GIT_SIGN_RECEIVED,
        AuditEventType.GIT_SIGN_AI_REVIEWED,
        AuditEventType.GIT_SIGN_DECIDED,
        AuditEventType.GIT_SIGN_COMPLETED,
        -> addGitSigningFields(data)

        AuditEventType.SSH_AUTHENTICATION_RECEIVED,
        AuditEventType.SSH_AUTHENTICATION_AI_REVIEWED,
        AuditEventType.SSH_AUTHENTICATION_DECIDED,
        AuditEventType.SSH_AUTHENTICATION_COMPLETED,
        -> addSshAuthenticationFields(data)

        AuditEventType.SECRET_UPLOAD_RECEIVED,
        AuditEventType.SECRET_UPLOAD_DECIDED,
        AuditEventType.SECRET_UPLOAD_COMPLETED,
        -> addSecretUploadFields(data)

        AuditEventType.PAIRING_REQUESTED,
        AuditEventType.PAIRING_DECIDED,
        AuditEventType.PAIRING_COMPLETED,
        AuditEventType.PAIRING_CONFIRMATION_RECEIVED,
        -> addPairingFields(data)

        AuditEventType.CLIENT_RESUMED,
        AuditEventType.CLIENT_SUSPENDED,
        AuditEventType.CLIENT_REVOKED,
        AuditEventType.CLIENT_PENDING,
        AuditEventType.CLIENT_REMOVAL_UNCONFIRMED,
        AuditEventType.CLIENT_RENAMED,
        AuditEventType.CLIENT_INSTRUCTIONS_CHANGED,
        AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED,
        AuditEventType.CLIENT_UNPAIRED_ITSELF,
        -> addClientFields(data)

        AuditEventType.SECRET_APPROVAL_MODE_CHANGED,
        AuditEventType.SECRET_INSTRUCTIONS_CHANGED,
        AuditEventType.CLIENT_APPROVAL_OVERRIDE_CHANGED,
        AuditEventType.TEMPORARY_ACCESS_ALLOWED,
        AuditEventType.TEMPORARY_ACCESS_ENDED,
        AuditEventType.SECRET_CREATED,
        AuditEventType.SSH_KEY_CREATED,
        AuditEventType.SSH_KEY_REPLACED,
        AuditEventType.SSH_PUBLIC_KEY_COMMENT_UPDATED,
        AuditEventType.SECRET_UPDATED,
        AuditEventType.SECRET_DELETED,
        AuditEventType.ENVIRONMENT_VARIABLE_ADDED,
        AuditEventType.ENVIRONMENT_VARIABLE_UPDATED,
        AuditEventType.ENVIRONMENT_VARIABLE_DELETED,
        -> addSecretFields(data)

        AuditEventType.NEW_PAIRINGS_RESUMED,
        AuditEventType.NEW_PAIRINGS_PAUSED,
        AuditEventType.PAIRING_ADDRESS_CLAIMED,
        AuditEventType.PAIRING_ADDRESS_CHANGED,
        AuditEventType.GENERAL_AI_REVIEW_INSTRUCTIONS_CHANGED,
        -> addDeviceFields(type, data)

        AuditEventType.SECRET_LIST_RECEIVED,
        AuditEventType.SECRET_LIST_COMPLETED,
        -> Unit

        AuditEventType.REQUEST_REJECTED -> {
            addText(data, "rejection_code", "Error code", transform = ::displayStoredValue)
            addText(data, "request_kind", "Request type", transform = ::displayStoredValue)
        }
    }
    addCompletionFields(data, detail)
}.filterNot { it.value == subject || it.value == context || it.value == detail }

internal fun AuditEvent.technicalJson(): String = auditJson.encodeToString(
    JsonElement.serializer(),
    buildJsonObject {
        put("sequence", id)
        put("occurred_at", occurredAt)
        put("event_type", type.code)
        put("outcome", outcome.code)
        decisionSource?.let { put("decision_source", it.code) }
        subject?.let { put("subject", it) }
        context?.let { put("context", it) }
        detail?.let { put("detail", it) }
        expiresAt?.let { put("expires_at", it) }
        clientId?.let { put("client_id", it) }
        clientName?.let { put("client_name", it) }
        relayRequestId?.let { put("request_id", it) }
        put("data", data)
    },
)

private fun MutableList<AuditDetailField>.addInvocationFields(data: JsonObject) {
    val command = data.text("command")
    if (command != null) {
        add(
            AuditDetailField(
                label = "Command",
                value = renderShellCommand(command, data.stringArray("arguments")),
                monospace = true,
            ),
        )
    }
    addText(data, "working_directory", "Working directory", monospace = true)
    addText(data, "reason", "Reason")
    data["provided_secrets"]?.let(::formatSecretDelivery)?.let {
        add(AuditDetailField("Secret delivery", it, monospace = true))
    }
    data.stringArray("missing_secrets").takeIf(List<String>::isNotEmpty)?.let {
        add(AuditDetailField("Missing secrets", it.joinToString()))
    }
    addClientSystem(data)
    addAiReviewFields(data)
}

private fun MutableList<AuditDetailField>.addGitSigningFields(data: JsonObject) {
    addText(data, "signed_object", "Signed content", monospace = true)
    (data["repository"] as? JsonObject)?.let(::formatRepository)?.let {
        add(AuditDetailField("Repository", it, monospace = true))
    }
    addAiReviewFields(data)
}

private fun MutableList<AuditDetailField>.addSshAuthenticationFields(data: JsonObject) {
    addText(data, "method", "Authentication method", transform = ::displayStoredValue)
    addText(data, "signature_algorithm", "Signature algorithm", monospace = true)
    addText(data, "host_key_fingerprint", "Host key fingerprint", monospace = true)
    addText(data, "host_key_algorithm", "Host key algorithm", monospace = true)
    addAiReviewFields(data)
}

private fun MutableList<AuditDetailField>.addSecretUploadFields(data: JsonObject) {
    addText(data, "upload_mode", "Upload action", transform = ::displayStoredValue)
    data.text("approved_name")
        ?.takeUnless { it == data.text("uploaded_name") }
        ?.let { add(AuditDetailField("Saved as", it)) }
    addText(data, "secret_type", "Secret type", transform = ::displaySecretType)
    if (data.boolean("description_provided") == true) {
        add(AuditDetailField("Description", data.text("description").orEmpty()))
    }
    formatEnvironmentVariables(data["environment_variables"])?.let {
        add(AuditDetailField("Environment variables", it, monospace = true))
    }
    (data["ssh_key"] as? JsonObject)?.let { addSshKeyFields(it) }
    addText(data, "intake_error", "Upload problem")
}

private fun MutableList<AuditDetailField>.addPairingFields(data: JsonObject) {
    addText(data, "pairing_address", "Pairing address", monospace = true)
    addText(data, "pairing_state", "Pairing state", transform = ::displayStoredValue)
    addText(data, "action", "Action", transform = ::displayStoredValue)
    data.boolean("sas_matched")?.let {
        add(AuditDetailField("Verification code", if (it) "Matched" else "Did not match"))
    }
    addClientSystem(data)
}

private fun MutableList<AuditDetailField>.addClientFields(data: JsonObject) {
    val oldInstructions = data.text("previous_instructions")
    val newInstructions = data.text("instructions")
    if (oldInstructions != null || newInstructions != null) {
        oldInstructions?.let { add(AuditDetailField("Previous instructions", it.ifEmpty { "None" })) }
        newInstructions?.let { add(AuditDetailField("Instructions", it.ifEmpty { "None" })) }
    }
    addStateChange(data, "relay_state", "previous_relay_state", "Relay state")
    addStateChange(
        data,
        "desired_relay_state",
        "previous_desired_relay_state",
        "Requested state",
    )
    addClientSystem(data)
}

private fun MutableList<AuditDetailField>.addSecretFields(data: JsonObject) {
    addText(data, "secret_type", "Secret type", transform = ::displaySecretType)
    val previousDescription = data.text("previous_description")
    val description = data.text("description")
    if (previousDescription != null && previousDescription != description) {
        add(AuditDetailField("Previous description", previousDescription.ifEmpty { "None" }))
    }
    description?.takeIf(String::isNotEmpty)?.let {
        add(AuditDetailField("Description", it))
    }
    addStateChange(data, "approval_mode", "previous_approval_mode", "Approval")
    addStateChange(
        data,
        "client_approval_mode",
        "previous_client_approval_mode",
        "Client override",
        nullDisplay = "Use default",
    )
    addText(data, "operation", "Use", transform = ::displayOperation)
    val oldInstructions = data.text("previous_instructions")
    val newInstructions = data.text("instructions")
    if (oldInstructions != null || newInstructions != null) {
        oldInstructions?.let { add(AuditDetailField("Previous instructions", it.ifEmpty { "None" })) }
        newInstructions?.let { add(AuditDetailField("Instructions", it.ifEmpty { "None" })) }
    }
    addText(data, "variable_name", "Environment variable", monospace = true)
    addStateChange(data, "sensitive", "previous_sensitive", "Sensitivity") { value ->
        when (value) {
            "true" -> "Sensitive"
            "false" -> "Not sensitive"
            else -> value
        }
    }
    data.boolean("value_replaced")?.takeIf { it }?.let {
        add(AuditDetailField("Value", "Replaced"))
    }
    formatEnvironmentVariables(data["environment_variables"])?.let {
        add(AuditDetailField("Environment variables", it, monospace = true))
    }
    addText(data, "previous_fingerprint", "Previous SHA-256 fingerprint", monospace = true)
    addText(
        data,
        "previous_fingerprint_hex",
        "Previous SHA-256 fingerprint (hex)",
        monospace = true,
    )
    addSshKeyFields(data)
}

private fun MutableList<AuditDetailField>.addDeviceFields(
    type: AuditEventType,
    data: JsonObject,
) {
    when (type) {
        AuditEventType.PAIRING_ADDRESS_CHANGED -> {
            data.text("previous_pairing_address")?.let {
                add(AuditDetailField("Previous pairing address", it, monospace = true))
            }
        }
        AuditEventType.NEW_PAIRINGS_RESUMED,
        AuditEventType.NEW_PAIRINGS_PAUSED,
        -> addStateChange(
            data,
            "pairing_enabled",
            "previous_pairing_enabled",
            "New pairings",
        ) { value -> if (value == "true") "Allowed" else "Paused" }
        AuditEventType.GENERAL_AI_REVIEW_INSTRUCTIONS_CHANGED -> {
            data.text("previous_instructions")?.let {
                add(AuditDetailField("Previous instructions", it.ifEmpty { "None" }))
            }
            data.text("instructions")?.let {
                add(AuditDetailField("Instructions", it.ifEmpty { "None" }))
            }
        }
        AuditEventType.PAIRING_ADDRESS_CLAIMED -> Unit
        else -> error("Unexpected device event $type")
    }
}

private fun MutableList<AuditDetailField>.addCompletionFields(
    data: JsonObject,
    displayedDetail: String?,
) {
    addText(data, "completion_result", "Client result", transform = ::displayStoredValue)
    data.text("completion_reason")
        ?.takeUnless { it == displayedDetail }
        ?.let { add(AuditDetailField("Client reason", displayStoredValue(it))) }
    data.text("completion_message")
        ?.takeUnless { it == displayedDetail }
        ?.let { add(AuditDetailField("Client message", it)) }
    data.text("transport_error")
        ?.takeUnless { it == displayedDetail }
        ?.let { add(AuditDetailField("Transport error", it)) }
}

private fun MutableList<AuditDetailField>.addAiReviewFields(data: JsonObject) {
    addText(data, "ai_decision", "AI decision", transform = ::displayStoredValue)
    addText(data, "ai_explanation", "AI explanation")
    addText(data, "ai_failure", "AI review problem", transform = ::displayStoredValue)
    val reviewed = data.text("ai_decision")
    val explanation = data.text("ai_explanation")
    data.text("resulting_review_decision")
        ?.takeUnless { it == reviewed }
        ?.let { add(AuditDetailField("Applied decision", displayStoredValue(it))) }
    data.text("resulting_review_explanation")
        ?.takeUnless { it == explanation }
        ?.let { add(AuditDetailField("Applied explanation", it)) }
}

private fun MutableList<AuditDetailField>.addClientSystem(data: JsonObject) {
    val hostname = data.text("hostname")
    val system = listOfNotNull(
        data.text("platform")?.let(::formatPlatformName),
        data.text("os_version"),
        data.text("architecture"),
    ).joinToString(" · ").ifBlank { null }
    when {
        hostname != null && system != null -> add(AuditDetailField("Client system", "$hostname · $system"))
        hostname != null -> add(AuditDetailField("Client system", hostname))
        system != null -> add(AuditDetailField("Client system", system))
    }
}

private fun MutableList<AuditDetailField>.addSshKeyFields(data: JsonObject) {
    val algorithm = data.text("algorithm")
    val bits = data.text("bits")
    if (algorithm != null) {
        add(
            AuditDetailField(
                "Key type",
                listOfNotNull(algorithm.uppercase(), bits?.let { "$it bit" }).joinToString(" · "),
            ),
        )
    }
    addText(data, "fingerprint", "SHA-256 fingerprint", monospace = true)
    addText(data, "fingerprint_hex", "SHA-256 fingerprint (hex)", monospace = true)
    addText(data, "public_key", "Public key", monospace = true)
    addText(data, "comment", "Public key comment")
}

private fun MutableList<AuditDetailField>.addStateChange(
    data: JsonObject,
    currentKey: String,
    previousKey: String,
    label: String,
    nullDisplay: String? = null,
    transform: (String) -> String = ::displayStoredValue,
) {
    val current = data.text(currentKey)
    val previous = data.text(previousKey)
    if (current == null && previous == null) return
    val displayedCurrent = current?.let(transform) ?: nullDisplay ?: "None"
    val displayedPrevious = previous?.let(transform) ?: nullDisplay
    val value = if (displayedPrevious != null && displayedPrevious != displayedCurrent) {
        "$displayedPrevious → $displayedCurrent"
    } else {
        displayedCurrent
    }
    add(AuditDetailField(label, value))
}

private fun MutableList<AuditDetailField>.addText(
    data: JsonObject,
    key: String,
    label: String,
    monospace: Boolean = false,
    transform: (String) -> String = { it },
) {
    data.text(key)?.let { add(AuditDetailField(label, transform(it), monospace)) }
}

private fun formatSecretDelivery(value: JsonElement): String? {
    val secrets = value as? JsonObject ?: return null
    return secrets.mapNotNull { (secretName, rawFacts) ->
        val facts = rawFacts as? JsonObject ?: return@mapNotNull null
        when (facts.text("type")) {
            "ssh" -> "$secretName\n  SSH public key"
            "environment" -> {
                val variables = facts["variables"] as? JsonObject ?: return@mapNotNull secretName
                val lines = variables.mapNotNull { (source, rawVariable) ->
                    val variable = rawVariable as? JsonObject ?: return@mapNotNull null
                    val valueSuffix = variable.text("value")?.let { " = $it" }.orEmpty()
                    when (variable.text("delivery")) {
                        "environment" -> {
                            val target = variable.text("target") ?: return@mapNotNull null
                            "  $source → $target$valueSuffix"
                        }
                        "standard_input" -> "  $source → standard input$valueSuffix"
                        "omitted" -> "  $source · not delivered$valueSuffix"
                        else -> null
                    }
                }
                (listOf(secretName) + lines).joinToString("\n")
            }
            else -> secretName
        }
    }.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
}

private fun formatEnvironmentVariables(value: JsonElement?): String? {
    val variables = value as? JsonArray ?: return null
    return variables.mapNotNull { rawVariable ->
        val variable = rawVariable as? JsonObject ?: return@mapNotNull null
        val name = variable.text("name") ?: variable.text("variable_name")
            ?: return@mapNotNull null
        when (variable.boolean("sensitive")) {
            true -> "$name · sensitive"
            false -> "$name · not sensitive"
            null -> name
        }
    }.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
}

private fun formatRepository(repository: JsonObject): String? {
    val head = repository["head"] as? JsonObject
    val headText = when (head?.text("type")) {
        "BRANCH" -> listOfNotNull(
            head.text("name"),
            head.text("upstream")?.let { "tracking $it" },
        ).joinToString(" · ").ifBlank { null }
        "DETACHED" -> "Detached HEAD"
        else -> null
    }
    val changed = repository.text("changed_path_count")?.let { "$it changed paths" }
    return listOfNotNull(
        repository.text("remote"),
        repository.text("worktree"),
        headText,
        changed,
    ).takeIf(List<String>::isNotEmpty)?.joinToString("\n")
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? = when (text(key)) {
    "true" -> true
    "false" -> false
    else -> null
}

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun displayStoredValue(value: String): String = value
    .lowercase()
    .split('_')
    .joinToString(" ")
    .replaceFirstChar(Char::uppercaseChar)

private fun displaySecretType(value: String): String = when (value) {
    "environment" -> "Environment variables"
    "ssh" -> "SSH key"
    else -> displayStoredValue(value)
}

private fun displayOperation(value: String): String = when (value) {
    "invocation" -> "Secret delivery"
    "git_sign" -> "Git signing"
    "ssh_authenticate" -> "SSH authentication"
    else -> displayStoredValue(value)
}

private val auditJson = Json { prettyPrint = true }
