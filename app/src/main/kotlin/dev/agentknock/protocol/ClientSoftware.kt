package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class SoftwareInfo(
    val name: String? = null,
    val version: String? = null,
)

@Serializable
internal data class ClientSoftware(
    @SerialName("app_info") val application: SoftwareInfo? = null,
    @SerialName("lib_info") val library: SoftwareInfo? = null,
)

internal fun Json.decodeClientSoftware(plaintext: ByteArray): ClientSoftware =
    decodeFromString<ClientSoftware>(plaintext.decodeToString())
