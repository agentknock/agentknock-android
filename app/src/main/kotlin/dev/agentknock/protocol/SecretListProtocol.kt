package dev.agentknock.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class SecretListRequestMessage(val clientSoftware: ClientSoftware)

internal data class SecretListSecret(
    val description: String,
    val type: String,
    val environmentVariableNames: List<String> = emptyList(),
    val sshPublicKey: String? = null,
)

internal class SecretListProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): SecretListRequestMessage {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val request = json.decodeFromString<SecretListRequestWire>(plaintext.decodeToString())
        require(request.method == METHOD) { "Unexpected request method" }
        return SecretListRequestMessage(clientSoftware)
    }

    fun response(secrets: Map<String, SecretListSecret>): ByteArray = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put(
                "secrets",
                buildJsonObject {
                    secrets.toSortedMap().forEach { (name, secret) ->
                        put(name, secret.toWire())
                    }
                },
            )
        },
    ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): ClientSoftware =
        json.decodeClientSoftware(plaintext)

    companion object {
        const val METHOD = "SecretList"
        private const val TYPE_ENVIRONMENT = "environment"
        private const val TYPE_SSH = "ssh"
    }

    private fun SecretListSecret.toWire(): JsonObject = buildJsonObject {
        if (description.isNotEmpty()) put("description", description)
        put("type", type)
        when (type) {
            TYPE_ENVIRONMENT -> put(
                "variables",
                buildJsonArray {
                    environmentVariableNames.sorted().forEach { add(JsonPrimitive(it)) }
                },
            )
            TYPE_SSH -> put(
                "public_key",
                requireNotNull(sshPublicKey) { "SSH secret metadata has no public key" },
            )
            else -> error("Unsupported secret type")
        }
    }
}

@Serializable
private data class SecretListRequestWire(
    val method: String,
)
