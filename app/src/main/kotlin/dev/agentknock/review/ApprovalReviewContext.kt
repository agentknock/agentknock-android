package dev.agentknock.review

import dev.agentknock.protocol.GitSignHead
import dev.agentknock.protocol.GitSignRequestMessage
import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.relay.ApprovalReviewCommandEvidence
import dev.agentknock.relay.ApprovalReviewEnvironmentSecretFacts
import dev.agentknock.relay.ApprovalReviewEvidence
import dev.agentknock.relay.ApprovalReviewFacts
import dev.agentknock.relay.ApprovalReviewGitChangedPathEvidence
import dev.agentknock.relay.ApprovalReviewGitEvidence
import dev.agentknock.relay.ApprovalReviewGitHeadEvidence
import dev.agentknock.relay.ApprovalReviewGitRepositoryEvidence
import dev.agentknock.relay.ApprovalReviewGitSignOperationFacts
import dev.agentknock.relay.ApprovalReviewInstructions
import dev.agentknock.relay.ApprovalReviewInvocationEvidence
import dev.agentknock.relay.ApprovalReviewInvocationFacts
import dev.agentknock.relay.ApprovalReviewInvocationOperationFacts
import dev.agentknock.relay.ApprovalReviewOperationFacts
import dev.agentknock.relay.ApprovalReviewRequest
import dev.agentknock.relay.ApprovalReviewSecretFacts
import dev.agentknock.relay.ApprovalReviewSshSecretFacts
import dev.agentknock.storage.request.PairingEntity
import dev.agentknock.storage.request.SecretUseRequestEntity
import dev.agentknock.storage.rule.ApprovalRuleAction
import dev.agentknock.storage.rule.ApprovalRuleEvaluation
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretValues
import kotlinx.serialization.json.Json

internal fun approvalReviewRequest(
    pairing: PairingEntity,
    contents: InvocationRequestMessage,
    description: RequestedSecretDescription,
    values: Map<String, SecretValues>,
    evaluation: ApprovalRuleEvaluation,
    policies: List<SecretApprovalPolicy>,
    deviceInstructions: String,
): ApprovalReviewRequest {
    val secrets = approvalReviewSecretFacts(description, values)
    return approvalReviewRequestBase(
        pairing = pairing,
        deviceInstructions = deviceInstructions,
        operation = ApprovalReviewInvocationOperationFacts(secrets),
        parentInvocation = null,
        decisionSecretNames = secrets.keys,
        evaluation = evaluation,
        policies = policies,
        evidence = ApprovalReviewEvidence(
            invocation = ApprovalReviewInvocationEvidence(
                reason = contents.reason,
                command = ApprovalReviewCommandEvidence(
                    argv = listOf(contents.operation.command) + contents.operation.arguments,
                    workingDirectory = contents.operation.workingDirectory,
                    resolvedExecutable = contents.operation.executablePath,
                    launcherChain = contents.launcherChain,
                ),
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
    evaluation: ApprovalRuleEvaluation,
    policies: List<SecretApprovalPolicy>,
    deviceInstructions: String,
): ApprovalReviewRequest {
    require(invocationSecrets[contents.secret] is ApprovalReviewSshSecretFacts) {
        "Git signing review requires an SSH secret from the parent invocation"
    }
    return approvalReviewRequestBase(
        pairing = pairing,
        deviceInstructions = deviceInstructions,
        operation = ApprovalReviewGitSignOperationFacts(secret = contents.secret),
        parentInvocation = ApprovalReviewInvocationFacts(secrets = invocationSecrets),
        decisionSecretNames = setOf(contents.secret),
        evaluation = evaluation,
        policies = policies,
        evidence = ApprovalReviewEvidence(
            invocation = ApprovalReviewInvocationEvidence(
                reason = invocation.reason,
                command = ApprovalReviewCommandEvidence(
                    argv = listOf(invocation.command) + decodeStringList(invocation.argumentsJson),
                    workingDirectory = invocation.workingDirectory,
                    resolvedExecutable = invocation.executablePath,
                    launcherChain = decodeStringList(invocation.launcherChainJson),
                ),
            ),
            git = ApprovalReviewGitEvidence(
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
        ),
    )
}

private fun approvalReviewRequestBase(
    pairing: PairingEntity,
    deviceInstructions: String,
    operation: ApprovalReviewOperationFacts,
    parentInvocation: ApprovalReviewInvocationFacts?,
    decisionSecretNames: Set<String>,
    evaluation: ApprovalRuleEvaluation,
    policies: List<SecretApprovalPolicy>,
    evidence: ApprovalReviewEvidence,
): ApprovalReviewRequest {
    val policiesById = policies.associateBy(SecretApprovalPolicy::secretId)
    val secretInstructions = evaluation.secrets
        .filter { it.action == ApprovalRuleAction.ASK_AI }
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

    return ApprovalReviewRequest(
        instructions = ApprovalReviewInstructions(
            general = deviceInstructions,
            client = pairing.instructions,
            secrets = secretInstructions,
        ),
        facts = ApprovalReviewFacts(
            client = pairing.friendlyName ?: pairing.hostname ?: "Unknown client",
            operation = operation,
            invocation = parentInvocation,
        ),
        evidence = evidence,
    )
}

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
                val variables = secret.environmentVariables.associateTo(linkedMapOf()) { variable ->
                    val value = checkNotNull(environment[variable.name]) {
                        "Missing environment variable ${variable.name}"
                    }
                    variable.name to value.takeUnless { variable.sensitive }
                }
                require(variables.keys == environment.keys) {
                    "Review metadata does not match environment values for ${secret.name}"
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
