package dev.agentknock.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class PairedRequestProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun method(plaintext: ByteArray): String =
        json.decodeFromString<RequestMethodWire>(plaintext.decodeToString()).method

    companion object {
        const val FINISH_PAIRING_METHOD = "PairingFinish"
    }
}

@Serializable
private data class RequestMethodWire(val method: String)
