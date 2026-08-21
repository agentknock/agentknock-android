package dev.agentknock.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class SecretListRequestMessage(val clientSoftware: ClientSoftware)

internal data class SecretListSecret(
    val description: String,
    val environmentVariableNames: List<String>,
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
        SecretListResponseWire.serializer(),
        SecretListResponseWire(
            secrets = secrets.mapValues { (_, secret) ->
                SecretListSecretWire(
                    description = secret.description.ifEmpty { null },
                    type = TYPE_ENVIRONMENT,
                    variables = secret.environmentVariableNames.sorted(),
                )
            },
        ),
    ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): ClientSoftware =
        json.decodeClientSoftware(plaintext)

    companion object {
        const val METHOD = "SecretList"
        private const val TYPE_ENVIRONMENT = "environment"
    }
}

@Serializable
private data class SecretListRequestWire(
    val method: String,
)

@Serializable
private data class SecretListResponseWire(
    val secrets: Map<String, SecretListSecretWire>,
)

@Serializable
private data class SecretListSecretWire(
    val description: String? = null,
    val type: String,
    val variables: List<String>,
)
