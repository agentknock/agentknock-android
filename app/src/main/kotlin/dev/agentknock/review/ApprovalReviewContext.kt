package dev.agentknock.review

import dev.agentknock.protocol.GitSignHead
import dev.agentknock.protocol.GitSignRequestMessage
import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.relay.ApprovalReviewCommandEvidence
import dev.agentknock.relay.ApprovalReviewEnvironmentSecretFacts
import dev.agentknock.relay.ApprovalReviewEnvironmentVariableFacts
import dev.agentknock.relay.ApprovalReviewEnvironmentDestination
import dev.agentknock.relay.ApprovalReviewOmittedDestination
import dev.agentknock.relay.ApprovalReviewEvidence
import dev.agentknock.relay.ApprovalReviewFacts
import dev.agentknock.relay.ApprovalReviewGitChangedPathEvidence
import dev.agentknock.relay.ApprovalReviewGitHeadEvidence
import dev.agentknock.relay.ApprovalReviewGitRepositoryEvidence
import dev.agentknock.relay.ApprovalReviewInstructions
import dev.agentknock.relay.ApprovalReviewOperation
import dev.agentknock.relay.ApprovalReviewParentFacts
import dev.agentknock.relay.ApprovalReviewRequest
import dev.agentknock.relay.ApprovalReviewSecretFacts
import dev.agentknock.relay.ApprovalReviewSshSecretFacts
import dev.agentknock.storage.request.PairingEntity
import dev.agentknock.storage.request.SecretUseRequestEntity
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.EnvironmentVariableReviewDestination
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal fun approvalReviewRequest(
    pairing: PairingEntity,
    contents: InvocationRequestMessage,
    description: RequestedSecretDescription,
    values: Map<String, SecretValues>,
    evaluation: ApprovalEvaluation,
    policies: List<SecretApprovalPolicy>,
    deviceInstructions: String,
): ApprovalReviewRequest {
    val secrets = approvalReviewSecretFacts(description, values)
    return ApprovalReviewRequest(
        instructions = approvalReviewInstructions(
            pairing = pairing,
            deviceInstructions = deviceInstructions,
            decisionSecretNames = secrets.keys,
            evaluation = evaluation,
            policies = policies,
        ),
        facts = ApprovalReviewFacts(
            client = pairing.approvalReviewClientName(),
            operation = ApprovalReviewOperation.INVOCATION,
            secrets = secrets,
        ),
        evidence = ApprovalReviewEvidence(
            reason = contents.reason,
            command = ApprovalReviewCommandEvidence(
                argv = listOf(contents.operation.command) + contents.operation.arguments,
                workingDirectory = contents.operation.workingDirectory,
                resolvedExecutable = contents.operation.executablePath,
                launcherChain = contents.launcherChain,
            ),
        ),
    )
}

internal fun approvalReviewGitSignRequest(
    pairing: PairingEntity,
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
        instructions = approvalReviewInstructions(
            pairing = pairing,
            deviceInstructions = deviceInstructions,
            decisionSecretNames = setOf(contents.secret),
            evaluation = evaluation,
            policies = policies,
        ),
        facts = ApprovalReviewFacts(
            client = pairing.approvalReviewClientName(),
            operation = ApprovalReviewOperation.GIT_SIGN,
            secret = contents.secret,
        ),
        parentFacts = ApprovalReviewParentFacts(
            operation = ApprovalReviewOperation.INVOCATION,
            elapsedSeconds = parentElapsedSeconds,
            secrets = invocationSecrets,
        ),
        evidence = ApprovalReviewEvidence(
            signedContent = signedContent,
            repository = contents.repository?.let { repository ->
                ApprovalReviewGitRepositoryEvidence(
                    remote = repository.remote,
                    worktree = repository.worktree,
                    head = repository.head?.let { head ->
                        when (head) {
                            is GitSignHead.Branch -> ApprovalReviewGitHeadEvidence(
                                type = "BRANCH",
                                name = head.name,
                                upstream = head.upstream,
                            )
                            GitSignHead.Detached ->
                                ApprovalReviewGitHeadEvidence(type = "DETACHED")
                        }
                    },
                    changedPathCount = repository.changedPathCount,
                    changedPaths = repository.changedPaths?.map { path ->
                        ApprovalReviewGitChangedPathEvidence(
                            status = path.status.name,
                            path = path.path,
                        )
                    },
                )
            },
        ),
        parentEvidence = ApprovalReviewEvidence(
            reason = invocation.reason,
            command = ApprovalReviewCommandEvidence(
                argv = listOf(invocation.command) + decodeStringList(invocation.argumentsJson),
                workingDirectory = invocation.workingDirectory,
                resolvedExecutable = invocation.executablePath,
                launcherChain = decodeStringList(invocation.launcherChainJson),
            ),
        ),
    )
}

private fun approvalReviewInstructions(
    pairing: PairingEntity,
    deviceInstructions: String,
    decisionSecretNames: Set<String>,
    evaluation: ApprovalEvaluation,
    policies: List<SecretApprovalPolicy>,
): ApprovalReviewInstructions {
    val policiesById = policies.associateBy(SecretApprovalPolicy::secretId)
    val secretInstructions = evaluation.secrets
        .filter { it.action == ApprovalAction.ASK_AI }
        .associateTo(linkedMapOf()) { secret ->
            val policy = checkNotNull(policiesById[secret.secretId]) {
                "Missing approval policy for ${secret.secretName}"
            }
            require(secret.secretName in decisionSecretNames) {
                "AI review is not deciding ${secret.secretName}"
            }
            secret.secretName to policy.instructions
        }
    require(secretInstructions.isNotEmpty()) { "AI review has no secrets to review" }

    return ApprovalReviewInstructions(
        general = deviceInstructions,
        client = pairing.instructions,
        secrets = secretInstructions,
    )
}

private fun PairingEntity.approvalReviewClientName(): String =
    friendlyName ?: hostname ?: "Unknown client"

internal fun approvalReviewSecretFacts(
    description: RequestedSecretDescription,
    values: Map<String, SecretValues>,
): Map<String, ApprovalReviewSecretFacts> {
    require(description.reviewMetadata.mapTo(linkedSetOf()) { it.name } == values.keys) {
        "Review metadata does not match requested secret values"
    }
    return description.reviewMetadata.associateTo(linkedMapOf()) { secret ->
        secret.name to when (secret.type) {
            ENVIRONMENT_SECRET_TYPE -> {
                val environment = checkNotNull(values[secret.name] as? SecretValues.Environment) {
                    "Missing environment values for ${secret.name}"
                }.environment
                require(secret.environmentVariables.map { it.name }.toSet() == environment.keys) {
                    "Review metadata does not match environment values for ${secret.name}"
                }
                val metadata = secret.environmentVariables.associateBy { it.name }
                val destinations = secret.environmentVariableDestinations.ifEmpty {
                    secret.environmentVariables.associate { variable ->
                        variable.name to EnvironmentVariableReviewDestination.Environment(
                            variable.name,
                        )
                    }
                }
                val variables = destinations.mapValuesTo(
                    linkedMapOf(),
                ) { (source, destination) ->
                    when (destination) {
                        is EnvironmentVariableReviewDestination.Environment -> {
                            val variable = checkNotNull(metadata[source]) {
                                "Missing review metadata for environment variable $source"
                            }
                            val value = checkNotNull(environment[source]) {
                                "Missing environment variable $source"
                            }
                            ApprovalReviewEnvironmentVariableFacts(
                                destination = ApprovalReviewEnvironmentDestination(destination.name),
                                value = if (variable.sensitive) JsonNull else JsonPrimitive(value),
                            )
                        }
                        EnvironmentVariableReviewDestination.Omitted ->
                            ApprovalReviewEnvironmentVariableFacts(
                                destination = ApprovalReviewOmittedDestination,
                            )
                    }
                }
                ApprovalReviewEnvironmentSecretFacts(variables)
            }
            SSH_SECRET_TYPE -> {
                require(values[secret.name] is SecretValues.Ssh) {
                    "Missing SSH values for ${secret.name}"
                }
                ApprovalReviewSshSecretFacts(provides = "public_key")
            }
            else -> error("Unsupported review secret type")
        }
    }
}

private fun decodeStringList(value: String): List<String> = Json.decodeFromString(value)
