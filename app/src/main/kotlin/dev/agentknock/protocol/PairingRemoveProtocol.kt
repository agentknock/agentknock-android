package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class PairingRemoveProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): String {
        val request = json.decodeFromString<PairingRemoveRequestWire>(plaintext.decodeToString())
        require(request.method == METHOD) { "Unexpected request method" }
        return request.cliVersion
    }

    fun response(): ByteArray = "{}".encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): String =
        json.decodeFromString<PairingRemoveCompletionWire>(plaintext.decodeToString()).cliVersion

    companion object {
        const val METHOD = "PairingRemove"
    }
}

@Serializable
private data class PairingRemoveRequestWire(
    @SerialName("cli_version") val cliVersion: String,
    val method: String,
)

@Serializable
private data class PairingRemoveCompletionWire(
    @SerialName("cli_version") val cliVersion: String,
)
