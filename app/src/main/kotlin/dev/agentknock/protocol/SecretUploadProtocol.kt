package dev.agentknock.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal enum class SecretUploadMode(val wireName: String) {
    CREATE("CREATE"),
    REPLACE("REPLACE"),
    UPDATE("UPDATE"),
}

internal data class SecretUploadRequestMessage(
    val clientSoftware: ClientSoftware,
    val mode: SecretUploadMode,
    val name: String,
    val descriptionProvided: Boolean,
    val description: String?,
    val contents: SecretUploadContents,
)

internal sealed interface SecretUploadContents {
    data class Environment(val variables: Map<String, String>) : SecretUploadContents

    data class Ssh(val privateKey: String) : SecretUploadContents
}

internal data class SecretUploadCompletion(
    val clientSoftware: ClientSoftware,
    val result: String,
    val message: String?,
)

internal class SecretUploadProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): SecretUploadRequestMessage {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val root = json.parseToJsonElement(plaintext.decodeToString()).jsonObject
        require(root.requiredString("method") == METHOD) { "Unexpected request method" }
        val secret = root.getValue("secret").jsonObject
        val contents = when (secret.requiredString("type")) {
            TYPE_ENVIRONMENT -> SecretUploadContents.Environment(
                secret.getValue("variables").jsonObject.mapValues { (_, value) ->
                    value.jsonObject.requiredString("value")
                }.also { require(it.isNotEmpty()) { "Environment upload has no variables" } },
            )
            TYPE_SSH -> SecretUploadContents.Ssh(secret.requiredString("private_key"))
            else -> error("Unsupported secret type")
        }
        return SecretUploadRequestMessage(
            clientSoftware = clientSoftware,
            mode = SecretUploadMode.entries.singleOrNull {
                it.wireName == root.requiredString("mode")
            } ?: error("Unsupported secret upload mode"),
            name = secret.requiredString("name"),
            descriptionProvided = "description" in secret,
            description = secret.optionalString("description"),
            contents = contents,
        )
    }

    fun receivedResponse(): ByteArray = json.encodeToString(
        SecretUploadResultWire(result = RESULT_RECEIVED),
    ).encodeToByteArray()

    fun rejectedResponse(message: String): ByteArray = json.encodeToString(
        SecretUploadResultWire(result = RESULT_REJECTED, message = message),
    ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): SecretUploadCompletion {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val completion = json.decodeFromString<SecretUploadCompletionWire>(
            plaintext.decodeToString(),
        )
        require(completion.result == RESULT_RECEIVED || completion.result == RESULT_REJECTED) {
            "Unsupported secret upload completion result"
        }
        if (completion.result == RESULT_REJECTED) {
            require(!completion.message.isNullOrBlank()) {
                "Rejected secret upload completion has no message"
            }
        }
        return SecretUploadCompletion(
            clientSoftware = clientSoftware,
            result = completion.result,
            message = completion.message,
        )
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = getValue(name)
        require(value is JsonPrimitive && value.isString) { "$name must be a string" }
        return value.content
    }

    private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            require(value.isString) { "$name must be a string or null" }
            value.content
        }
        else -> throw IllegalArgumentException("$name must be a string or null")
    }

    companion object {
        const val METHOD = "SecretUpload"
        const val RESULT_RECEIVED = "RECEIVED"
        const val RESULT_REJECTED = "REJECTED"
        private const val TYPE_ENVIRONMENT = "environment"
        private const val TYPE_SSH = "ssh"
    }
}

@Serializable
private data class SecretUploadResultWire(
    val result: String,
    val message: String? = null,
)

@Serializable
private data class SecretUploadCompletionWire(
    val result: String,
    val message: String? = null,
)
