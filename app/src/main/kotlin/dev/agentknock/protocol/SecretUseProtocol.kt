package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class SecretUseRequestMessage(
    val cliVersion: String,
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

internal data class SecretUseResponseSecret(
    val description: String,
    val environment: Map<String, String>,
)

internal enum class SecretUseDenialReason(val wireName: String) {
    USER_DENIED("USER_DENIED"),
    POLICY_DENIED("POLICY_DENIED"),
    INVALID_REQUEST("INVALID_REQUEST"),
    OTHER("OTHER"),
}

internal sealed interface SecretUseCompletion {
    val cliVersion: String

    data class Approved(override val cliVersion: String) : SecretUseCompletion

    data class Denied(
        override val cliVersion: String,
        val reason: String,
        val message: String,
    ) : SecretUseCompletion

    data class Aborted(
        override val cliVersion: String,
        val reason: String,
        val message: String,
    ) : SecretUseCompletion
}

internal class SecretUseProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): SecretUseRequestMessage {
        val request = json.decodeFromString<SecretUseRequestWire>(plaintext.decodeToString())
        require(request.method == METHOD) { "Unexpected request method" }
        require(request.secrets.isNotEmpty()) { "Secret use request has no secrets" }
        require(request.secrets.none(String::isEmpty)) { "Secret use request has an empty secret" }
        require(request.operation.type == EXEC_OPERATION_TYPE) { "Unsupported operation type" }
        return SecretUseRequestMessage(
            cliVersion = request.cliVersion,
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

    fun approvedResponse(secrets: Map<String, SecretUseResponseSecret>): ByteArray = json.encodeToString(
        SecretUseResponseWire.serializer(),
        SecretUseResponseWire(
            result = RESULT_APPROVED,
            secrets = secrets.mapValues { (_, secret) ->
                SecretUseResponseSecretWire(
                    description = secret.description.ifEmpty { null },
                    type = TYPE_ENVIRONMENT,
                    variables = secret.environment.mapValues { (_, value) ->
                        SecretUseResponseEnvironmentVariableWire(value)
                    },
                )
            },
        ),
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
        val completion = json.decodeFromString<SecretUseCompletionWire>(
            plaintext.decodeToString(),
        )
        return when (completion.result) {
            RESULT_APPROVED -> SecretUseCompletion.Approved(completion.cliVersion)
            RESULT_DENIED -> SecretUseCompletion.Denied(
                cliVersion = completion.cliVersion,
                reason = requireNotNull(completion.reason) { "Denied completion has no reason" },
                message = requireNotNull(completion.message) { "Denied completion has no message" },
            )
            RESULT_ABORTED -> SecretUseCompletion.Aborted(
                cliVersion = completion.cliVersion,
                reason = requireNotNull(completion.reason) { "Aborted completion has no reason" },
                message = requireNotNull(completion.message) { "Aborted completion has no message" },
            )
            else -> error("Unsupported secret use completion result")
        }
    }

    companion object {
        const val METHOD = "SecretUse"
        private const val EXEC_OPERATION_TYPE = "exec"
        private const val TYPE_ENVIRONMENT = "environment"
        private const val RESULT_APPROVED = "APPROVED"
        private const val RESULT_DENIED = "DENIED"
        private const val RESULT_ABORTED = "ABORTED"
    }
}

@Serializable
private data class SecretUseRequestWire(
    @SerialName("cli_version") val cliVersion: String,
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
    val secrets: Map<String, SecretUseResponseSecretWire>? = null,
    val reason: String? = null,
    val message: String? = null,
)

@Serializable
private data class SecretUseResponseSecretWire(
    val description: String? = null,
    val type: String,
    val variables: Map<String, SecretUseResponseEnvironmentVariableWire>,
)

@Serializable
private data class SecretUseResponseEnvironmentVariableWire(val value: String)

@Serializable
private data class SecretUseCompletionWire(
    @SerialName("cli_version") val cliVersion: String,
    val result: String,
    val reason: String? = null,
    val message: String? = null,
)
