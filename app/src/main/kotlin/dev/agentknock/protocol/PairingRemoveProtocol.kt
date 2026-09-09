package dev.agentknock.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class PairingRemoveProtocol(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun decodeRequest(plaintext: ByteArray): ClientSoftware {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val request = json.decodeFromString<PairingRemoveRequestWire>(plaintext.decodeToString())
        require(request.method == METHOD) { "Unexpected request method" }
        return clientSoftware
    }

    fun response(): ByteArray = "{}".encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): ClientSoftware =
        json.decodeClientSoftware(plaintext)

    companion object {
        const val METHOD = "PairingRemove"
    }
}

@Serializable private data class PairingRemoveRequestWire(val method: String)
