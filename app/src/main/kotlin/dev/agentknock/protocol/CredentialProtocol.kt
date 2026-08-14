package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class CredentialRequestMessage(
    val cliVersion: String,
    val profiles: List<String>,
    val reason: String?,
    val operation: CredentialExecOperation,
    val launcherChain: List<String>,
)

internal data class CredentialExecOperation(
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val resolvedPath: String?,
    val stdin: String,
    val stdout: String,
    val stderr: String,
)

internal enum class CredentialDenialReason(val wireName: String) {
    USER_DENIED("USER_DENIED"),
    POLICY_DENIED("POLICY_DENIED"),
    INVALID_REQUEST("INVALID_REQUEST"),
    OTHER("OTHER"),
}

internal sealed interface CredentialCompletion {
    val cliVersion: String

    data class Approved(override val cliVersion: String) : CredentialCompletion

    data class Denied(
        override val cliVersion: String,
        val reason: String,
        val message: String,
    ) : CredentialCompletion

    data class Aborted(
        override val cliVersion: String,
        val reason: String,
        val message: String,
    ) : CredentialCompletion
}

internal class CredentialProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): CredentialRequestMessage {
        val request = json.decodeFromString<CredentialRequestWire>(plaintext.decodeToString())
        require(request.method == CREDENTIAL_REQUEST_METHOD) { "Unexpected request method" }
        require(request.profiles.isNotEmpty()) { "Credential request has no profiles" }
        require(request.profiles.none(String::isEmpty)) { "Credential request has an empty profile" }
        require(request.operation.type == EXEC_OPERATION_TYPE) { "Unsupported operation type" }
        return CredentialRequestMessage(
            cliVersion = request.cliVersion,
            profiles = request.profiles,
            reason = request.reason,
            operation = CredentialExecOperation(
                command = request.operation.command,
                arguments = request.operation.arguments,
                workingDirectory = request.operation.workingDirectory,
                resolvedPath = request.operation.resolvedPath,
                stdin = request.operation.stdin,
                stdout = request.operation.stdout,
                stderr = request.operation.stderr,
            ),
            launcherChain = request.launcherChain,
        )
    }

    fun method(plaintext: ByteArray): String =
        json.decodeFromString<RequestMethodWire>(plaintext.decodeToString()).method

    fun approvedResponse(environment: Map<String, String>): ByteArray = json.encodeToString(
        CredentialResponseWire.serializer(),
        CredentialResponseWire(result = RESULT_APPROVED, environment = environment),
    ).encodeToByteArray()

    fun deniedResponse(reason: CredentialDenialReason, message: String): ByteArray =
        json.encodeToString(
            CredentialResponseWire.serializer(),
            CredentialResponseWire(
                result = RESULT_DENIED,
                reason = reason.wireName,
                message = message,
            ),
        ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): CredentialCompletion {
        val completion = json.decodeFromString<CredentialCompletionWire>(
            plaintext.decodeToString(),
        )
        return when (completion.result) {
            RESULT_APPROVED -> CredentialCompletion.Approved(completion.cliVersion)
            RESULT_DENIED -> CredentialCompletion.Denied(
                cliVersion = completion.cliVersion,
                reason = requireNotNull(completion.reason) { "Denied completion has no reason" },
                message = requireNotNull(completion.message) { "Denied completion has no message" },
            )
            RESULT_ABORTED -> CredentialCompletion.Aborted(
                cliVersion = completion.cliVersion,
                reason = requireNotNull(completion.reason) { "Aborted completion has no reason" },
                message = requireNotNull(completion.message) { "Aborted completion has no message" },
            )
            else -> error("Unsupported credential completion result")
        }
    }

    companion object {
        const val CREDENTIAL_REQUEST_METHOD = "CredentialRequest"
        const val FINISH_PAIRING_METHOD = "FinishPairing"
        private const val EXEC_OPERATION_TYPE = "exec"
        private const val RESULT_APPROVED = "APPROVED"
        private const val RESULT_DENIED = "DENIED"
        private const val RESULT_ABORTED = "ABORTED"
    }
}

@Serializable
private data class RequestMethodWire(val method: String)

@Serializable
private data class CredentialRequestWire(
    @SerialName("cli_version") val cliVersion: String,
    val method: String,
    val profiles: List<String>,
    val reason: String? = null,
    val operation: CredentialOperationWire,
    @SerialName("launcher_chain") val launcherChain: List<String>,
)

@Serializable
private data class CredentialOperationWire(
    val type: String,
    val command: String,
    val arguments: List<String>,
    @SerialName("working_directory") val workingDirectory: String,
    @SerialName("resolved_path") val resolvedPath: String? = null,
    val stdin: String,
    val stdout: String,
    val stderr: String,
)

@Serializable
private data class CredentialResponseWire(
    val result: String,
    val environment: Map<String, String>? = null,
    val reason: String? = null,
    val message: String? = null,
)

@Serializable
private data class CredentialCompletionWire(
    @SerialName("cli_version") val cliVersion: String,
    val result: String,
    val reason: String? = null,
    val message: String? = null,
)
