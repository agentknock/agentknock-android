package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class ProfileListRequestMessage(val cliVersion: String)

internal data class ProfileListProfile(
    val description: String,
    val environmentVariableNames: List<String>,
)

internal class ProfileListProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): ProfileListRequestMessage {
        val request = json.decodeFromString<ProfileListRequestWire>(plaintext.decodeToString())
        require(request.method == LIST_METHOD) { "Unexpected request method" }
        return ProfileListRequestMessage(request.cliVersion)
    }

    fun response(profiles: Map<String, ProfileListProfile>): ByteArray = json.encodeToString(
        ProfileListResponseWire.serializer(),
        ProfileListResponseWire(
            profiles = profiles.mapValues { (_, profile) ->
                ProfileListProfileWire(
                    description = profile.description,
                    environment = profile.environmentVariableNames.associateWith {
                        ProfileValueSource.STORED
                    },
                )
            },
        ),
    ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): String =
        json.decodeFromString<ProfileListCompletionWire>(plaintext.decodeToString()).cliVersion

    companion object {
        const val LIST_METHOD = "List"
    }
}

@Serializable
private data class ProfileListRequestWire(
    @SerialName("cli_version") val cliVersion: String,
    val method: String,
)

@Serializable
private data class ProfileListResponseWire(
    val profiles: Map<String, ProfileListProfileWire>,
)

@Serializable
private data class ProfileListProfileWire(
    val description: String,
    val environment: Map<String, ProfileValueSource>,
)

@Serializable
private enum class ProfileValueSource {
    STORED,
    ISSUED,
}

@Serializable
private data class ProfileListCompletionWire(
    @SerialName("cli_version") val cliVersion: String,
)
