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
    @SerialName("context_version") val contextVersion: Int,
    val policy: ApprovalReviewPolicy,
    val action: ApprovalReviewAction,
)

@Serializable
internal data class ApprovalReviewPolicy(
    val decision: String,
    @SerialName("device_instructions") val deviceInstructions: String,
    @SerialName("client_instructions") val clientInstructions: String,
    @SerialName("secret_decisions") val secretDecisions: List<ApprovalReviewSecretDecision>,
    @SerialName("matching_rules") val matchingRules: List<ApprovalReviewRule>,
)

@Serializable
internal data class ApprovalReviewSecretDecision(
    @SerialName("secret_id") val secretId: String,
    @SerialName("secret_name") val secretName: String,
    val decision: String,
    @SerialName("default_decision") val defaultDecision: String,
    @SerialName("client_override") val clientOverride: Boolean,
    val instructions: String,
    @SerialName("matching_rule_ids") val matchingRuleIds: List<String>,
    @SerialName("decisive_rule_ids") val decisiveRuleIds: List<String>,
)

@Serializable
internal data class ApprovalReviewRule(
    val id: String,
    val name: String,
    val action: String,
    @SerialName("secret_ids") val secretIds: List<String>,
    @SerialName("secret_names") val secretNames: List<String>,
    val command: List<String>,
    @SerialName("command_match") val commandMatch: String,
    @SerialName("executable_path") val executablePath: String?,
    @SerialName("executable_sha256") val executableSha256: String?,
    @SerialName("working_directory") val workingDirectory: String?,
    @SerialName("created_at_unix_ms") val createdAtUnixMs: Long,
    @SerialName("updated_at_unix_ms") val updatedAtUnixMs: Long,
    @SerialName("expires_at_unix_ms") val expiresAtUnixMs: Long?,
    @SerialName("last_matched_at_unix_ms") val lastMatchedAtUnixMs: Long?,
    @SerialName("previous_match_count") val previousMatchCount: Long,
)

@Serializable
internal data class ApprovalReviewAction(
    @SerialName("request_id") val requestId: String,
    @SerialName("requested_at_unix_ms") val requestedAtUnixMs: Long,
    val reason: String?,
    @SerialName("contains_sensitive_material") val containsSensitiveMaterial: Boolean,
    val client: ApprovalReviewClient,
    val secrets: List<ApprovalReviewSecret>,
    val operation: ApprovalReviewOperation,
    @SerialName("launcher_chain") val launcherChain: List<String>,
    @SerialName("git_signing") val gitSigning: ApprovalReviewGitSigning? = null,
)

@Serializable
internal data class ApprovalReviewGitSigning(
    @SerialName("secret_name") val secretName: String,
    val namespace: String,
    val message: String,
    @SerialName("message_size_bytes") val messageSizeBytes: Int,
)

@Serializable
internal data class ApprovalReviewClient(
    val id: String,
    val name: String,
    val hostname: String?,
    val platform: String?,
    val architecture: String?,
    @SerialName("machine_id") val machineId: String?,
    @SerialName("os_version") val osVersion: String?,
    val software: ApprovalReviewSoftware,
)

@Serializable
internal data class ApprovalReviewSoftware(
    val application: ApprovalReviewSoftwareComponent,
    val library: ApprovalReviewSoftwareComponent,
)

@Serializable
internal data class ApprovalReviewSoftwareComponent(
    val name: String,
    val version: String,
)

@Serializable
internal data class ApprovalReviewSecret(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    @SerialName("created_at_unix_ms") val createdAtUnixMs: Long,
    @SerialName("updated_at_unix_ms") val updatedAtUnixMs: Long,
    @SerialName("environment_variables")
    val environmentVariables: List<ApprovalReviewEnvironmentVariable>,
    @SerialName("ssh_key") val sshKey: ApprovalReviewSshKey?,
)

@Serializable
internal data class ApprovalReviewEnvironmentVariable(
    val name: String,
    val sensitive: Boolean,
    val notes: String,
    @SerialName("created_at_unix_ms") val createdAtUnixMs: Long,
    @SerialName("updated_at_unix_ms") val updatedAtUnixMs: Long,
    @SerialName("value_updated_at_unix_ms") val valueUpdatedAtUnixMs: Long,
)

@Serializable
internal data class ApprovalReviewSshKey(
    val algorithm: String,
    @SerialName("public_key") val publicKey: String,
    val fingerprint: String,
    val comment: String,
    @SerialName("material_updated_at_unix_ms") val materialUpdatedAtUnixMs: Long,
)

@Serializable
internal data class ApprovalReviewOperation(
    val command: String,
    val arguments: List<String>,
    @SerialName("working_directory") val workingDirectory: String,
    @SerialName("executable_path") val executablePath: String,
    @SerialName("executable_sha256") val executableSha256: String?,
    @SerialName("executable_mode") val executableMode: String,
    val stdin: String,
    val stdout: String,
    val stderr: String,
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
    private val json: Json = Json { ignoreUnknownKeys = true },
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
