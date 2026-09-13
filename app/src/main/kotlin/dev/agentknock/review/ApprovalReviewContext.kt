package dev.agentknock.review

import dev.agentknock.protocol.GitSignRequestMessage
import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.protocol.SshAuthenticationMessageDetails
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.request.ClientEntity
import dev.agentknock.storage.request.SecretUseRequestEntity
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.EnvironmentVariableReviewDestination
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretValues
import kotlinx.serialization.json.Json

internal fun approvalReviewRequest(
    client: ClientEntity,
    contents: InvocationRequestMessage,
    description: RequestedSecretDescription,
    values: Map<String, SecretValues>,
    nonSensitiveEnvironmentValues: Map<String, Map<String, String>>,
    evaluation: ApprovalEvaluation,
    policies: List<SecretApprovalPolicy>,
    deviceInstructions: String,
): ApprovalReviewRequest {
    val secrets =
        approvalReviewSecretFacts(
            description,
            values,
            nonSensitiveEnvironmentValues,
        )
    return ApprovalReviewRequest(
        instructions =
            approvalReviewInstructions(
                client = client,
                deviceInstructions = deviceInstructions,
                decisionSecretNames = secrets.keys,
                evaluation = evaluation,
                policies = policies,
            ),
        facts =
            ApprovalReviewFacts(
                client = client.name,
                operation = ApprovalReviewOperation.INVOCATION,
                secrets = secrets,
            ),
        evidence =
            ApprovalReviewEvidence(
                reason = contents.reason,
                command =
                    ApprovalReviewCommandEvidence(
                        argv = listOf(contents.operation.command) + contents.operation.arguments,
                        workingDirectory = contents.operation.workingDirectory,
                        resolvedExecutable = contents.operation.executablePath,
                        launcherChain = contents.launcherChain,
                        scriptContents = contents.operation.scriptContents,
                    ),
            ),
    )
}

internal fun approvalReviewGitSignRequest(
    client: ClientEntity,
    contents: GitSignRequestMessage,
    signedContent: String,
    invocation: SecretUseRequestEntity,
    invocationSecrets: Map<String, ApprovalReviewSecretFacts>,
    parentElapsedSeconds: Long,
    evaluation: ApprovalEvaluation,
    policies: List<SecretApprovalPolicy>,
    deviceInstructions: String,
): ApprovalReviewRequest {
    require(invocationSecrets[contents.secret] is ApprovalReviewSshSecretFacts) {
        "Git signing review requires an SSH secret from the parent invocation"
    }
    require(parentElapsedSeconds >= 0) { "Parent elapsed time is negative" }
    return ApprovalReviewRequest(
        instructions =
            approvalReviewInstructions(
                client = client,
                deviceInstructions = deviceInstructions,
                decisionSecretNames = setOf(contents.secret),
                evaluation = evaluation,
                policies = policies,
            ),
        facts =
            ApprovalReviewFacts(
                client = client.name,
                operation = ApprovalReviewOperation.GIT_SIGN,
                secret = contents.secret,
            ),
        parentFacts =
            ApprovalReviewParentFacts(
                operation = ApprovalReviewOperation.INVOCATION,
                elapsedSeconds = parentElapsedSeconds,
                secrets = invocationSecrets,
            ),
        evidence =
            ApprovalReviewEvidence(
                signedContent = signedContent,
                repository =
                    contents.repository?.let { repository ->
                        ApprovalReviewGitRepositoryEvidence(
                            remote = repository.remote,
                            worktree = repository.worktree,
                            head =
                                repository.head?.let { head ->
                                    ApprovalReviewGitHeadEvidence(
                                        type = head.type,
                                        name = head.name,
                                        upstream = head.upstream,
                                    )
                                },
                            changedPathCount = repository.changedPathCount,
                            changedPaths =
                                repository.changedPaths?.map { path ->
                                    ApprovalReviewGitChangedPathEvidence(
                                        status = path.status,
                                        path = path.path,
                                    )
                                },
                        )
                    },
            ),
        parentEvidence =
            ApprovalReviewEvidence(
                reason = invocation.reason,
                command =
                    ApprovalReviewCommandEvidence(
                        argv =
                            listOf(invocation.command) + decodeStringList(invocation.argumentsJson),
                        workingDirectory = invocation.workingDirectory,
                        resolvedExecutable = invocation.executablePath,
                        launcherChain = decodeStringList(invocation.launcherChainJson),
                        scriptContents = invocation.scriptContents,
                    ),
            ),
    )
}

internal fun approvalReviewSshAuthenticationRequest(
    client: ClientEntity,
    secretName: String,
    details: SshAuthenticationMessageDetails,
    invocation: SecretUseRequestEntity,
    invocationSecrets: Map<String, ApprovalReviewSecretFacts>,
    parentElapsedSeconds: Long,
    evaluation: ApprovalEvaluation,
    policies: List<SecretApprovalPolicy>,
    deviceInstructions: String,
): ApprovalReviewRequest {
    require(invocationSecrets[secretName] is ApprovalReviewSshSecretFacts) {
        "SSH authentication review requires an SSH secret from the parent invocation"
    }
    require(parentElapsedSeconds >= 0) { "Parent elapsed time is negative" }
    return ApprovalReviewRequest(
        instructions =
            approvalReviewInstructions(
                client = client,
                deviceInstructions = deviceInstructions,
                decisionSecretNames = setOf(secretName),
                evaluation = evaluation,
                policies = policies,
            ),
        facts =
            ApprovalReviewFacts(
                client = client.name,
                operation = ApprovalReviewOperation.SSH_AUTHENTICATE,
                secret = secretName,
            ),
        parentFacts =
            ApprovalReviewParentFacts(
                operation = ApprovalReviewOperation.INVOCATION,
                elapsedSeconds = parentElapsedSeconds,
                secrets = invocationSecrets,
            ),
        evidence =
            ApprovalReviewEvidence(
                sshAuthentication =
                    ApprovalReviewSshAuthenticationEvidence(
                        username = details.username,
                        method = details.method.wireName,
                        algorithm = details.algorithm.wireName,
                        hostKeyAlgorithm = details.hostKeyAlgorithm,
                        hostKeyFingerprint = details.hostKeyFingerprint,
                    )
            ),
        parentEvidence =
            ApprovalReviewEvidence(
                reason = invocation.reason,
                command =
                    ApprovalReviewCommandEvidence(
                        argv =
                            listOf(invocation.command) + decodeStringList(invocation.argumentsJson),
                        workingDirectory = invocation.workingDirectory,
                        resolvedExecutable = invocation.executablePath,
                        launcherChain = decodeStringList(invocation.launcherChainJson),
                        scriptContents = invocation.scriptContents,
                    ),
            ),
    )
}

private fun approvalReviewInstructions(
    client: ClientEntity,
    deviceInstructions: String,
    decisionSecretNames: Set<String>,
    evaluation: ApprovalEvaluation,
    policies: List<SecretApprovalPolicy>,
): ApprovalReviewInstructions {
    val policiesById = policies.associateBy(SecretApprovalPolicy::secretId)
    val secretInstructions =
        evaluation.secrets
            .filter {
                it.secretName in decisionSecretNames && it.action == ApprovalAction.ASK_AI
            }
            .associateTo(linkedMapOf()) { secret ->
                val policy =
                    checkNotNull(policiesById[secret.secretId]) {
                        "Missing approval policy for ${secret.secretName}"
                    }
                secret.secretName to policy.instructions
            }
    require(secretInstructions.isNotEmpty()) { "AI review has no secrets to review" }

    return ApprovalReviewInstructions(
        general = deviceInstructions,
        client = client.instructions,
        secrets = secretInstructions,
    )
}

internal fun approvalReviewSecretFacts(
    description: RequestedSecretDescription,
    values: Map<String, SecretValues>,
    nonSensitiveEnvironmentValues: Map<String, Map<String, String>>,
): Map<String, ApprovalReviewSecretFacts> {
    require(description.reviewMetadata.mapTo(linkedSetOf()) { it.name } == values.keys) {
        "Review metadata does not match requested secret values"
    }
    return description.reviewMetadata.associateTo(linkedMapOf()) { secret ->
        secret.name to
            when (secret.type) {
                ENVIRONMENT_SECRET_TYPE -> {
                    val environment =
                        checkNotNull(values[secret.name] as? SecretValues.Environment) {
                                "Missing environment values for ${secret.name}"
                            }
                            .environment
                    val metadata = secret.environmentVariables.associateBy { it.name }
                    val deliveredSources =
                        metadata.mapNotNullTo(linkedSetOf()) { (source, variable) ->
                            source.takeUnless {
                                variable.destination == EnvironmentVariableReviewDestination.Omitted
                            }
                        }
                    require(environment.keys == deliveredSources) {
                        "Review metadata does not match delivered environment values for ${secret.name}"
                    }
                    val safeValues = nonSensitiveEnvironmentValues[secret.name].orEmpty()
                    val variables =
                        metadata.mapValuesTo(linkedMapOf()) { (source, variable) ->
                            val destination = variable.destination
                            val value = if (variable.sensitive) null else safeValues[source]
                            if (
                                !variable.sensitive &&
                                    destination != EnvironmentVariableReviewDestination.Omitted
                            ) {
                                require(value != null) {
                                    "Missing non-sensitive environment value $source"
                                }
                            }
                            when (destination) {
                                is EnvironmentVariableReviewDestination.Environment ->
                                    ApprovalReviewEnvironmentVariableFacts(
                                        delivery = ApprovalReviewEnvironmentDelivery.ENVIRONMENT,
                                        target = destination.name,
                                        value = value,
                                    )
                                EnvironmentVariableReviewDestination.Omitted ->
                                    ApprovalReviewEnvironmentVariableFacts(
                                        delivery = ApprovalReviewEnvironmentDelivery.OMITTED,
                                        value = value,
                                    )
                                EnvironmentVariableReviewDestination.StandardInput ->
                                    ApprovalReviewEnvironmentVariableFacts(
                                        delivery = ApprovalReviewEnvironmentDelivery.STANDARD_INPUT,
                                        value = value,
                                    )
                            }
                        }
                    ApprovalReviewEnvironmentSecretFacts(variables)
                }
                SSH_SECRET_TYPE -> {
                    require(values[secret.name] is SecretValues.Ssh) {
                        "Missing SSH values for ${secret.name}"
                    }
                    ApprovalReviewSshSecretFacts
                }
                else -> error("Unsupported review secret type")
            }
    }
}

private fun decodeStringList(value: String): List<String> = Json.decodeFromString(value)
