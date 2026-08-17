package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class ProfileUploadMode(val wireName: String) {
    CREATE("CREATE"),
    REPLACE("REPLACE"),
    UPDATE("UPDATE"),
}

internal data class ProfileUploadRequestMessage(
    val cliVersion: String,
    val mode: ProfileUploadMode,
    val name: String,
    val descriptionProvided: Boolean,
    val description: String?,
    val variables: Map<String, String>,
)

internal data class ProfileUploadCompletion(
    val cliVersion: String,
    val result: String,
    val message: String?,
)

internal class ProfileUploadProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): ProfileUploadRequestMessage {
        val root = json.parseToJsonElement(plaintext.decodeToString()).jsonObject
        require(root.requiredString("method") == METHOD) { "Unexpected request method" }
        val profile = root.getValue("profile").jsonObject
        require(profile.requiredString("type") == TYPE_ENVIRONMENT) {
            "Unsupported profile type"
        }
        val variables = profile.getValue("variables").jsonObject.mapValues { (_, value) ->
            value.jsonObject.requiredString("value")
        }
        return ProfileUploadRequestMessage(
            cliVersion = root.requiredString("cli_version"),
            mode = ProfileUploadMode.entries.singleOrNull {
                it.wireName == root.requiredString("mode")
            } ?: error("Unsupported profile upload mode"),
            name = profile.requiredString("name"),
            descriptionProvided = "description" in profile,
            description = profile["description"]?.jsonPrimitive?.content,
            variables = variables,
        )
    }

    fun receivedResponse(): ByteArray = json.encodeToString(
        ProfileUploadResultWire(result = RESULT_RECEIVED),
    ).encodeToByteArray()

    fun rejectedResponse(message: String): ByteArray = json.encodeToString(
        ProfileUploadResultWire(result = RESULT_REJECTED, message = message),
    ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): ProfileUploadCompletion {
        val completion = json.decodeFromString<ProfileUploadCompletionWire>(
            plaintext.decodeToString(),
        )
        require(completion.result == RESULT_RECEIVED || completion.result == RESULT_REJECTED) {
            "Unsupported profile upload completion result"
        }
        return ProfileUploadCompletion(
            cliVersion = completion.cliVersion,
            result = completion.result,
            message = completion.message,
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        getValue(name).jsonPrimitive.content

    companion object {
        const val METHOD = "ProfileUpload"
        const val RESULT_RECEIVED = "RECEIVED"
        const val RESULT_REJECTED = "REJECTED"
        private const val TYPE_ENVIRONMENT = "environment"
    }
}

@Serializable
private data class ProfileUploadResultWire(
    val result: String,
    val message: String? = null,
)

@Serializable
private data class ProfileUploadCompletionWire(
    @SerialName("cli_version") val cliVersion: String,
    val result: String,
    val message: String? = null,
)
