package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class SecretListRequestMessage(val cliVersion: String)

internal data class SecretListSecret(
    val description: String,
    val environmentVariableNames: List<String>,
)

internal class SecretListProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): SecretListRequestMessage {
        val request = json.decodeFromString<SecretListRequestWire>(plaintext.decodeToString())
        require(request.method == METHOD) { "Unexpected request method" }
        return SecretListRequestMessage(request.cliVersion)
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

    fun decodeCompletion(plaintext: ByteArray): String =
        json.decodeFromString<SecretListCompletionWire>(plaintext.decodeToString()).cliVersion

    companion object {
        const val METHOD = "SecretList"
        private const val TYPE_ENVIRONMENT = "environment"
    }
}

@Serializable
private data class SecretListRequestWire(
    @SerialName("cli_version") val cliVersion: String,
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

@Serializable
private data class SecretListCompletionWire(
    @SerialName("cli_version") val cliVersion: String,
)
