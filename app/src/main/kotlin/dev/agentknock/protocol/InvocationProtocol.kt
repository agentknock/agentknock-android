package dev.agentknock.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

internal data class InvocationRequestMessage(
    val clientSoftware: ClientSoftware,
    val invocationToken: ByteArray,
    val secrets: List<String>,
    val secretDelivery: Map<String, InvocationSecretDelivery> = emptyMap(),
    val reason: String?,
    val operation: InvocationExecOperation,
    val launcherChain: List<String>,
)

internal data class InvocationSecretDelivery(
    val environment: InvocationEnvironmentDelivery? = null,
)

internal data class InvocationEnvironmentDelivery(
    val only: Set<String>? = null,
    val omit: Set<String> = emptySet(),
    val rename: Map<String, String> = emptyMap(),
    val stdin: String? = null,
)

internal data class InvocationExecOperation(
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val executablePath: String,
    val executableHash: String?,
    val executableMode: InvocationExecutableMode,
    val stdin: InvocationStreamKind,
    val stdout: InvocationStreamKind,
    val stderr: InvocationStreamKind,
)

internal enum class InvocationExecutableMode(val wireName: String) {
    BINARY("BINARY"),
    SCRIPT("SCRIPT"),
    ;

    companion object {
        fun fromWireName(value: String): InvocationExecutableMode = entries.singleOrNull {
            it.wireName == value
        } ?: throw IllegalArgumentException("Unsupported executable mode")
    }
}

internal enum class InvocationStreamKind(val wireName: String) {
    TERMINAL("TERMINAL"),
    NULL_DEVICE("NULL_DEVICE"),
    PIPE("PIPE"),
    SOCKET("SOCKET"),
    REGULAR_FILE("REGULAR_FILE"),
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        fun fromWireName(value: String): InvocationStreamKind = entries.singleOrNull {
            it.wireName == value
        } ?: throw IllegalArgumentException("Unsupported standard-stream kind")
    }
}

internal sealed interface InvocationResponseSecret {
    val description: String

    data class Environment(
        override val description: String,
        val environment: Map<String, String>,
    ) : InvocationResponseSecret

    data class Ssh(
        override val description: String,
        val publicKey: String,
    ) : InvocationResponseSecret
}

internal enum class InvocationDenialReason(val wireName: String) {
    USER_DENIED("USER_DENIED"),
    POLICY_DENIED("POLICY_DENIED"),
    INVALID_REQUEST("INVALID_REQUEST"),
    OTHER("OTHER"),
}

internal sealed interface InvocationCompletion {
    val clientSoftware: ClientSoftware

    data class Approved(override val clientSoftware: ClientSoftware) : InvocationCompletion

    data class Denied(
        override val clientSoftware: ClientSoftware,
        val reason: String,
        val message: String,
    ) : InvocationCompletion

    data class Aborted(
        override val clientSoftware: ClientSoftware,
        val reason: String,
        val message: String,
    ) : InvocationCompletion
}

internal class InvocationProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): InvocationRequestMessage {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val request = json.decodeFromString<InvocationRequestWire>(plaintext.decodeToString())
        require(request.method == METHOD) { "Unexpected request method" }
        require(request.secrets.isNotEmpty()) { "Secret use request has no secrets" }
        require(request.secrets.keys.none(String::isEmpty)) {
            "Secret use request has an empty secret"
        }
        val secretDelivery = request.secrets.mapValues { (secret, options) ->
            decodeSecretDelivery(secret, options)
        }
        require(secretDelivery.values.count { it.environment?.stdin != null } <= 1) {
            "An invocation can send only one environment variable to standard input"
        }
        require(request.operation.type == EXEC_OPERATION_TYPE) { "Unsupported operation type" }
        require(request.launcherChain.size <= MAX_LAUNCHER_CHAIN_LENGTH) {
            "Launcher chain contains more than $MAX_LAUNCHER_CHAIN_LENGTH entries"
        }
        val invocationToken = runCatching {
            Base64.getDecoder().decode(request.invocationToken)
        }.getOrElse { throw IllegalArgumentException("Invalid invocation token", it) }
        require(invocationToken.size == INVOCATION_TOKEN_BYTES) {
            "Invocation token must be $INVOCATION_TOKEN_BYTES bytes"
        }
        return InvocationRequestMessage(
            clientSoftware = clientSoftware,
            invocationToken = invocationToken,
            secrets = request.secrets.keys.toList(),
            secretDelivery = secretDelivery,
            reason = request.reason,
            operation = InvocationExecOperation(
                command = request.operation.command,
                arguments = request.operation.arguments,
                workingDirectory = request.operation.workingDirectory,
                executablePath = request.operation.executablePath,
                executableHash = request.operation.executableHash?.let(::decodeExecutableHash),
                executableMode = InvocationExecutableMode.fromWireName(
                    request.operation.executableMode,
                ),
                stdin = InvocationStreamKind.fromWireName(request.operation.stdin),
                stdout = InvocationStreamKind.fromWireName(request.operation.stdout),
                stderr = InvocationStreamKind.fromWireName(request.operation.stderr),
            ),
            launcherChain = request.launcherChain,
        )
    }

    fun approvedResponse(secrets: Map<String, InvocationResponseSecret>): ByteArray =
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

    fun deniedResponse(reason: InvocationDenialReason, message: String): ByteArray =
        json.encodeToString(
            InvocationResponseWire.serializer(),
            InvocationResponseWire(
                result = RESULT_DENIED,
                reason = reason.wireName,
                message = message,
            ),
        ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): InvocationCompletion {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val completion = json.decodeFromString<InvocationCompletionWire>(
            plaintext.decodeToString(),
        )
        return when (completion.result) {
            RESULT_APPROVED -> InvocationCompletion.Approved(clientSoftware)
            RESULT_DENIED -> InvocationCompletion.Denied(
                clientSoftware = clientSoftware,
                reason = requireNotNull(completion.reason) { "Denied completion has no reason" },
                message = requireNotNull(completion.message) { "Denied completion has no message" },
            )
            RESULT_ABORTED -> InvocationCompletion.Aborted(
                clientSoftware = clientSoftware,
                reason = requireNotNull(completion.reason) { "Aborted completion has no reason" },
                message = requireNotNull(completion.message) { "Aborted completion has no message" },
            )
            else -> error("Unsupported invocation completion result")
        }
    }

    companion object {
        const val METHOD = "Invocation"
        private const val TYPE_ENVIRONMENT = "environment"
        private const val TYPE_SSH = "ssh"
        private const val EXEC_OPERATION_TYPE = "exec"
        private const val RESULT_APPROVED = "APPROVED"
        private const val RESULT_DENIED = "DENIED"
        private const val RESULT_ABORTED = "ABORTED"
        private const val INVOCATION_TOKEN_BYTES = 32
        private const val EXECUTABLE_HASH_BYTES = 32
        private const val MAX_LAUNCHER_CHAIN_LENGTH = 4
    }

    private fun InvocationResponseSecret.toWire(): JsonObject = buildJsonObject {
        if (description.isNotEmpty()) put("description", description)
        when (this@toWire) {
            is InvocationResponseSecret.Environment -> {
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
            is InvocationResponseSecret.Ssh -> {
                put("type", TYPE_SSH)
                put("public_key", publicKey)
            }
        }
    }

    private fun decodeSecretDelivery(
        secret: String,
        value: JsonElement,
    ): InvocationSecretDelivery {
        val options = value as? JsonObject
            ?: throw IllegalArgumentException("Secret delivery options must be an object")
        val environment = options["environment"]?.let { decodeEnvironmentDelivery(secret, it) }
        return InvocationSecretDelivery(environment)
    }

    private fun decodeEnvironmentDelivery(
        secret: String,
        value: JsonElement,
    ): InvocationEnvironmentDelivery {
        val options = value as? JsonObject
            ?: throw IllegalArgumentException("Environment delivery options must be an object")
        val only = options["only"]?.let { decodeEnvironmentNames("only", it) }
        val omitted = options["omit"]?.let { decodeEnvironmentNames("omit", it) }
        val omit = omitted.orEmpty()
        val rename = options["rename"]?.let(::decodeEnvironmentRename).orEmpty()
        val stdin = options["stdin"]?.let { value ->
            require(value is JsonPrimitive && value.isString) {
                "Environment stdin must be a string"
            }
            value.content.also(::requireValidEnvironmentName)
        }
        require(only == null || only.isNotEmpty()) { "Secret $secret has an empty only set" }
        require(omitted == null || omitted.isNotEmpty()) { "Secret $secret has an empty omit set" }
        require(only == null || omit.isEmpty()) { "Secret $secret uses both only and omit" }
        require(only == null || rename.keys.all(only::contains)) {
            "Secret $secret renames a variable not selected by only"
        }
        require(rename.keys.none(omit::contains)) {
            "Secret $secret renames an omitted variable"
        }
        require(stdin == null || only == null || stdin in only) {
            "Secret $secret sends a variable not selected by only to standard input"
        }
        require(stdin == null || stdin !in omit) {
            "Secret $secret sends an omitted variable to standard input"
        }
        require(stdin == null || stdin !in rename) {
            "Secret $secret both renames a variable and sends it to standard input"
        }
        return InvocationEnvironmentDelivery(
            only = only,
            omit = omit,
            rename = rename,
            stdin = stdin,
        )
    }

    private fun decodeEnvironmentNames(option: String, value: JsonElement): Set<String> {
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("Environment $option must be an array")
        val names = array.map { element ->
            require(element is JsonPrimitive && element.isString) {
                "Environment $option must contain strings"
            }
            element.jsonPrimitive.content
                .also(::requireValidEnvironmentName)
        }
        require(names.size == names.distinct().size) { "Environment $option contains duplicates" }
        return names.toSet()
    }

    private fun requireValidEnvironmentName(name: String) {
        require(name.isNotEmpty() && '=' !in name && '\u0000' !in name) {
            "Invalid environment variable name"
        }
    }

    private fun decodeEnvironmentRename(value: JsonElement): Map<String, String> {
        val mapping = value as? JsonObject
            ?: throw IllegalArgumentException("Environment rename must be an object")
        return mapping.mapValues { (source, destination) ->
            requireValidEnvironmentName(source)
            require(destination is JsonPrimitive && destination.isString) {
                "Environment rename destinations must be strings"
            }
            destination.content.also(::requireValidEnvironmentName)
        }
    }

    private fun decodeExecutableHash(value: String): String {
        val decoded = runCatching { Base64.getDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("Invalid executable hash", it) }
        require(decoded.size == EXECUTABLE_HASH_BYTES) {
            "Executable hash must be $EXECUTABLE_HASH_BYTES bytes"
        }
        return Base64.getEncoder().encodeToString(decoded)
    }
}

@Serializable
private data class InvocationRequestWire(
    val method: String,
    val secrets: JsonObject,
    val reason: String? = null,
    val operation: InvocationOperationWire,
    @SerialName("launcher_chain") val launcherChain: List<String>,
    @SerialName("invocation_token") val invocationToken: String,
)

@Serializable
private data class InvocationOperationWire(
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
private data class InvocationResponseWire(
    val result: String,
    val reason: String? = null,
    val message: String? = null,
)

@Serializable
private data class InvocationCompletionWire(
    val result: String,
    val reason: String? = null,
    val message: String? = null,
)
