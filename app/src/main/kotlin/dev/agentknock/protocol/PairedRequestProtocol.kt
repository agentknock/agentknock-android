package dev.agentknock.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class PairedRequestProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun method(plaintext: ByteArray): String =
        json.decodeFromString<RequestMethodWire>(plaintext.decodeToString()).method

    fun errorResponse(code: PairedRequestErrorCode): ByteArray =
        json.encodeToString(
            PairedRequestErrorResponseWire.serializer(),
            PairedRequestErrorResponseWire(
                error = code.wireName,
                message = code.message,
            ),
        ).encodeToByteArray()

    companion object {
        const val FINISH_PAIRING_METHOD = "PairingFinish"
    }
}

internal enum class PairedRequestErrorCode(
    val wireName: String,
    val message: String,
) {
    INVALID_REQUEST(
        wireName = "INVALID_REQUEST",
        message = "The request could not be understood.",
    ),
    UNSUPPORTED_METHOD(
        wireName = "UNSUPPORTED_METHOD",
        message = "The requested operation is not supported.",
    ),
    INVALID_STATE(
        wireName = "INVALID_STATE",
        message = "The requested operation is not available in the current state.",
    ),
}

@Serializable
private data class RequestMethodWire(val method: String)

@Serializable
private data class PairedRequestErrorResponseWire(
    val error: String,
    val message: String,
)
