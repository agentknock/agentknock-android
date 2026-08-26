package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class SecretUseRequestMessage(
    val clientSoftware: ClientSoftware,
    val secrets: List<String>,
    val reason: String?,
    val operation: SecretUseExecOperation,
    val launcherChain: List<String>,
)

internal data class SecretUseExecOperation(
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val executablePath: String,
    val executableHash: String?,
    val executableMode: String,
    val stdin: String,
    val stdout: String,
    val stderr: String,
)

internal sealed interface SecretUseResponseSecret {
    val description: String

    data class Environment(
        override val description: String,
        val environment: Map<String, String>,
    ) : SecretUseResponseSecret

    data class Ssh(
        override val description: String,
        val publicKey: String,
    ) : SecretUseResponseSecret
}

internal enum class SecretUseDenialReason(val wireName: String) {
    USER_DENIED("USER_DENIED"),
    POLICY_DENIED("POLICY_DENIED"),
    INVALID_REQUEST("INVALID_REQUEST"),
    OTHER("OTHER"),
}

internal sealed interface SecretUseCompletion {
    val clientSoftware: ClientSoftware

    data class Approved(override val clientSoftware: ClientSoftware) : SecretUseCompletion

    data class Denied(
        override val clientSoftware: ClientSoftware,
        val reason: String,
        val message: String,
    ) : SecretUseCompletion

    data class Aborted(
        override val clientSoftware: ClientSoftware,
        val reason: String,
        val message: String,
    ) : SecretUseCompletion
}

internal class SecretUseProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): SecretUseRequestMessage {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val request = json.decodeFromString<SecretUseRequestWire>(plaintext.decodeToString())
        require(request.method == METHOD) { "Unexpected request method" }
        require(request.secrets.isNotEmpty()) { "Secret use request has no secrets" }
        require(request.secrets.none(String::isEmpty)) { "Secret use request has an empty secret" }
        require(request.operation.type == EXEC_OPERATION_TYPE) { "Unsupported operation type" }
        return SecretUseRequestMessage(
            clientSoftware = clientSoftware,
            secrets = request.secrets,
            reason = request.reason,
            operation = SecretUseExecOperation(
                command = request.operation.command,
                arguments = request.operation.arguments,
                workingDirectory = request.operation.workingDirectory,
                executablePath = request.operation.executablePath,
                executableHash = request.operation.executableHash,
                executableMode = request.operation.executableMode,
                stdin = request.operation.stdin,
                stdout = request.operation.stdout,
                stderr = request.operation.stderr,
            ),
            launcherChain = request.launcherChain,
        )
    }

    fun approvedResponse(secrets: Map<String, SecretUseResponseSecret>): ByteArray =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("result", RESULT_APPROVED)
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

    fun deniedResponse(reason: SecretUseDenialReason, message: String): ByteArray =
        json.encodeToString(
            SecretUseResponseWire.serializer(),
            SecretUseResponseWire(
                result = RESULT_DENIED,
                reason = reason.wireName,
                message = message,
            ),
        ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): SecretUseCompletion {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val completion = json.decodeFromString<SecretUseCompletionWire>(
            plaintext.decodeToString(),
        )
        return when (completion.result) {
            RESULT_APPROVED -> SecretUseCompletion.Approved(clientSoftware)
            RESULT_DENIED -> SecretUseCompletion.Denied(
                clientSoftware = clientSoftware,
                reason = requireNotNull(completion.reason) { "Denied completion has no reason" },
                message = requireNotNull(completion.message) { "Denied completion has no message" },
            )
            RESULT_ABORTED -> SecretUseCompletion.Aborted(
                clientSoftware = clientSoftware,
                reason = requireNotNull(completion.reason) { "Aborted completion has no reason" },
                message = requireNotNull(completion.message) { "Aborted completion has no message" },
            )
            else -> error("Unsupported secret use completion result")
        }
    }

    companion object {
        const val METHOD = "SecretUse"
        private const val TYPE_ENVIRONMENT = "environment"
        private const val TYPE_SSH = "ssh"
        private const val EXEC_OPERATION_TYPE = "exec"
        private const val RESULT_APPROVED = "APPROVED"
        private const val RESULT_DENIED = "DENIED"
        private const val RESULT_ABORTED = "ABORTED"
    }

    private fun SecretUseResponseSecret.toWire(): JsonObject = buildJsonObject {
        if (description.isNotEmpty()) put("description", description)
        when (this@toWire) {
            is SecretUseResponseSecret.Environment -> {
                put("type", TYPE_ENVIRONMENT)
                put(
                    "variables",
                    buildJsonObject {
                        environment.toSortedMap().forEach { (name, value) ->
                            put(name, buildJsonObject { put("value", value) })
                        }
                    },
                )
            }
            is SecretUseResponseSecret.Ssh -> {
                put("type", TYPE_SSH)
                put("public_key", publicKey)
            }
        }
    }
}

@Serializable
private data class SecretUseRequestWire(
    val method: String,
    val secrets: List<String>,
    val reason: String? = null,
    val operation: SecretUseOperationWire,
    @SerialName("launcher_chain") val launcherChain: List<String>,
)

@Serializable
private data class SecretUseOperationWire(
    val type: String,
    val command: String,
    val arguments: List<String>,
    @SerialName("working_directory") val workingDirectory: String,
    @SerialName("executable_path") val executablePath: String,
    @SerialName("executable_hash") val executableHash: String? = null,
    @SerialName("executable_mode") val executableMode: String,
    val stdin: String,
    val stdout: String,
    val stderr: String,
)

@Serializable
private data class SecretUseResponseWire(
    val result: String,
    val reason: String? = null,
    val message: String? = null,
)

@Serializable
private data class SecretUseCompletionWire(
    val result: String,
    val reason: String? = null,
    val message: String? = null,
)
