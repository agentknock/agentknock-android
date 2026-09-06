package dev.agentknock.protocol

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class GitSignRequestMessage(
    val clientSoftware: ClientSoftware,
    val invocationId: String,
    val invocationToken: ByteArray,
    val secret: String,
    val message: ByteArray,
    val repository: GitSignRepository?,
)

@Serializable
internal data class GitSignRepository(
    val remote: String? = null,
    val worktree: String? = null,
    val head: GitSignHead? = null,
    @SerialName("changed_path_count") val changedPathCount: Long? = null,
    @SerialName("changed_paths") val changedPaths: List<GitSignChangedPath>? = null,
)

@Serializable
internal sealed interface GitSignHead {
    @Serializable
    @SerialName("BRANCH")
    data class Branch(
        val name: String,
        val upstream: String? = null,
    ) : GitSignHead

    @Serializable
    @SerialName("DETACHED")
    data object Detached : GitSignHead
}

@Serializable
internal data class GitSignChangedPath(
    val status: GitSignChangeStatus,
    val path: String,
)

@Serializable
internal enum class GitSignChangeStatus {
    @SerialName("ADDED")
    ADDED,

    @SerialName("DELETED")
    DELETED,

    @SerialName("MODIFIED")
    MODIFIED,

    @SerialName("TYPE_CHANGED")
    TYPE_CHANGED,
}

internal class GitSignProtocol(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decodeRequest(plaintext: ByteArray): GitSignRequestMessage {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val request = json.decodeFromString<GitSignRequestWire>(plaintext.decodeToString())
        require(request.method == METHOD) { "Unexpected request method" }
        require(request.invocationId.isNotEmpty()) { "Git signing invocation ID is empty" }
        require(request.secret.isNotEmpty()) { "Git signing secret name is empty" }
        return GitSignRequestMessage(
            clientSoftware = clientSoftware,
            invocationId = request.invocationId,
            invocationToken = decodeBase64(request.invocationToken, "invocation token").also {
                require(it.size == INVOCATION_TOKEN_BYTES) {
                    "Invocation token must be $INVOCATION_TOKEN_BYTES bytes"
                }
            },
            secret = request.secret,
            message = decodeBase64(request.message, "Git signing message"),
            repository = request.repository,
        )
    }

    fun approvedResponse(signature: String): ByteArray = json.encodeToString(
        GitSignResultWire.serializer(),
        GitSignResultWire(result = RESULT_APPROVED, signature = signature),
    ).encodeToByteArray()

    fun deniedResponse(reason: InvocationDenialReason, message: String): ByteArray =
        json.encodeToString(
            GitSignResultWire.serializer(),
            GitSignResultWire(
                result = RESULT_DENIED,
                reason = reason.wireName,
                message = message,
            ),
        ).encodeToByteArray()

    fun decodeCompletion(plaintext: ByteArray): ApprovalCompletion {
        val clientSoftware = json.decodeClientSoftware(plaintext)
        val completion = json.decodeFromString<GitSignResultWire>(plaintext.decodeToString())
        return when (completion.result) {
            RESULT_APPROVED -> {
                if (completion.signature != null) {
                    throw SerializationException(
                        "Approved Git signing completion contains a signature",
                    )
                }
                ApprovalCompletion.Approved(clientSoftware)
            }
            RESULT_DENIED -> ApprovalCompletion.Denied(
                clientSoftware = clientSoftware,
                reason = completion.reason
                    ?: throw SerializationException("Denied completion has no reason"),
                message = completion.message
                    ?: throw SerializationException("Denied completion has no message"),
            )
            RESULT_ABORTED -> ApprovalCompletion.Aborted(
                clientSoftware = clientSoftware,
                reason = completion.reason
                    ?: throw SerializationException("Aborted completion has no reason"),
                message = completion.message
                    ?: throw SerializationException("Aborted completion has no message"),
            )
            else -> throw SerializationException("Unsupported Git signing completion result")
        }
    }

    private fun decodeBase64(value: String, field: String): ByteArray = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrElse { throw IllegalArgumentException("Invalid $field", it) }

    companion object {
        const val METHOD = "GitSign"
        private const val INVOCATION_TOKEN_BYTES = 32
        private const val RESULT_APPROVED = "APPROVED"
        private const val RESULT_DENIED = "DENIED"
        private const val RESULT_ABORTED = "ABORTED"
    }
}

@Serializable
private data class GitSignRequestWire(
    val method: String,
    @SerialName("invocation_id") val invocationId: String,
    @SerialName("invocation_token") val invocationToken: String,
    val secret: String,
    val message: String,
    val repository: GitSignRepository? = null,
)

@Serializable
private data class GitSignResultWire(
    val result: String,
    val signature: String? = null,
    val reason: String? = null,
    val message: String? = null,
)
