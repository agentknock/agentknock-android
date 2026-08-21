package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class SoftwareInfo(
    val name: String,
    val version: String,
)

@Serializable
internal data class ClientSoftware(
    @SerialName("app_info") val application: SoftwareInfo,
    @SerialName("lib_info") val library: SoftwareInfo,
)

internal fun Json.decodeClientSoftware(plaintext: ByteArray): ClientSoftware =
    decodeFromString<ClientSoftware>(plaintext.decodeToString())
