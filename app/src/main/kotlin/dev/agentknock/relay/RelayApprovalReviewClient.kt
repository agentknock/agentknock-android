package dev.agentknock.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
internal data class ApprovalReviewRequest(
    val instructions: ApprovalReviewInstructions,
    val facts: ApprovalReviewFacts,
    val evidence: ApprovalReviewEvidence,
)

@Serializable
internal data class ApprovalReviewInstructions(
    val general: String,
    val client: String,
    val secrets: Map<String, String>,
)

@Serializable
internal data class ApprovalReviewFacts(
    val client: String,
    val operation: ApprovalReviewOperationFacts,
    val invocation: ApprovalReviewInvocationFacts? = null,
)

@Serializable
internal sealed interface ApprovalReviewOperationFacts

@Serializable
@SerialName("invocation")
internal data class ApprovalReviewInvocationOperationFacts(
    val secrets: Map<String, ApprovalReviewSecretFacts>,
) : ApprovalReviewOperationFacts

@Serializable
@SerialName("git_sign")
internal data class ApprovalReviewGitSignOperationFacts(
    val secret: String,
) : ApprovalReviewOperationFacts

@Serializable
internal data class ApprovalReviewInvocationFacts(
    val secrets: Map<String, ApprovalReviewSecretFacts>,
)

@Serializable
internal sealed interface ApprovalReviewSecretFacts

@Serializable
@SerialName("environment")
internal data class ApprovalReviewEnvironmentSecretFacts(
    @SerialName("environment_variables")
    val environmentVariables: Map<String, String?>,
) : ApprovalReviewSecretFacts

@Serializable
@SerialName("ssh")
internal data class ApprovalReviewSshSecretFacts(
    val provides: String,
) : ApprovalReviewSecretFacts

@Serializable
internal data class ApprovalReviewEvidence(
    val invocation: ApprovalReviewInvocationEvidence,
    val git: ApprovalReviewGitEvidence? = null,
)

@Serializable
internal data class ApprovalReviewInvocationEvidence(
    val reason: String? = null,
    val command: ApprovalReviewCommandEvidence,
)

@Serializable
internal data class ApprovalReviewCommandEvidence(
    val argv: List<String>,
    @SerialName("working_directory") val workingDirectory: String,
    @SerialName("resolved_executable") val resolvedExecutable: String,
    @SerialName("launcher_chain") val launcherChain: List<String>,
)

@Serializable
internal data class ApprovalReviewGitEvidence(
    @SerialName("signed_content") val signedContent: String,
    val repository: ApprovalReviewGitRepositoryEvidence? = null,
)

@Serializable
internal data class ApprovalReviewGitRepositoryEvidence(
    val remote: String? = null,
    val worktree: String? = null,
    val head: ApprovalReviewGitHeadEvidence? = null,
    @SerialName("changed_path_count") val changedPathCount: Long? = null,
    @SerialName("changed_paths") val changedPaths: List<ApprovalReviewGitChangedPathEvidence>? = null,
)

@Serializable
internal data class ApprovalReviewGitHeadEvidence(
    val type: String,
    val name: String? = null,
    val upstream: String? = null,
)

@Serializable
internal data class ApprovalReviewGitChangedPathEvidence(
    val status: String,
    val path: String,
)

internal enum class RelayApprovalReviewDecision {
    APPROVE,
    DENY,
    ASK_USER,
}

internal sealed interface RelayApprovalReviewResult {
    data class Reviewed(
        val decision: RelayApprovalReviewDecision,
        val explanation: String,
    ) : RelayApprovalReviewResult

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : RelayApprovalReviewResult

    data class Unavailable(val cause: IOException) : RelayApprovalReviewResult

    data object InvalidResponse : RelayApprovalReviewResult
}

internal interface RelayApprovalReviewClient {
    suspend fun review(
        deviceId: String,
        deviceToken: String,
        request: ApprovalReviewRequest,
    ): RelayApprovalReviewResult
}

internal class HttpRelayApprovalReviewClient(
    private val client: OkHttpClient,
    private val relayUrl: String = "https://relay.agentknock.dev/",
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelayApprovalReviewClient {
    override suspend fun review(
        deviceId: String,
        deviceToken: String,
        request: ApprovalReviewRequest,
    ): RelayApprovalReviewResult = withContext(dispatcher) {
        val httpRequest = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/v1/device/$deviceId/review")
            .header("Authorization", "Bearer $deviceToken")
            .post(json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful) {
                    runCatching {
                        val value = json.decodeFromString<ApprovalReviewResponse>(responseBody)
                        val decision = when (value.decision) {
                            "approve" -> RelayApprovalReviewDecision.APPROVE
                            "deny" -> RelayApprovalReviewDecision.DENY
                            "ask_user" -> RelayApprovalReviewDecision.ASK_USER
                            else -> error("Unknown approval review decision")
                        }
                        require(value.explanation.isNotBlank()) {
                            "Approval review explanation is empty"
                        }
                        RelayApprovalReviewResult.Reviewed(decision, value.explanation)
                    }.getOrElse { RelayApprovalReviewResult.InvalidResponse }
                } else {
                    val error = runCatching {
                        json.decodeFromString<ApprovalReviewErrorResponse>(responseBody)
                    }.getOrNull()
                    RelayApprovalReviewResult.Rejected(
                        status = response.code,
                        code = error?.code,
                        message = error?.message,
                    )
                }
            }
        } catch (exception: IOException) {
            RelayApprovalReviewResult.Unavailable(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
private data class ApprovalReviewResponse(
    val decision: String,
    val explanation: String,
)

@Serializable
private data class ApprovalReviewErrorResponse(
    @SerialName("error") val code: String,
    val message: String,
)
