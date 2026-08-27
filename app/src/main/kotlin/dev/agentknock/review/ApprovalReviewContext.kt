package dev.agentknock.review

import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.protocol.GitSignRequestMessage
import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.relay.ApprovalReviewAction
import dev.agentknock.relay.ApprovalReviewClient
import dev.agentknock.relay.ApprovalReviewEnvironmentVariable
import dev.agentknock.relay.ApprovalReviewGitSigning
import dev.agentknock.relay.ApprovalReviewOperation
import dev.agentknock.relay.ApprovalReviewPolicy
import dev.agentknock.relay.ApprovalReviewRequest
import dev.agentknock.relay.ApprovalReviewRule
import dev.agentknock.relay.ApprovalReviewSecret
import dev.agentknock.relay.ApprovalReviewSecretDecision
import dev.agentknock.relay.ApprovalReviewSoftware
import dev.agentknock.relay.ApprovalReviewSoftwareComponent
import dev.agentknock.relay.ApprovalReviewSshKey
import dev.agentknock.storage.request.PairingEntity
import dev.agentknock.storage.request.SecretUseRequestEntity
import dev.agentknock.storage.rule.ApprovalRule
import dev.agentknock.storage.rule.ApprovalRuleEvaluation
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.SecretApprovalPolicy

internal fun approvalReviewRequest(
    pairing: PairingEntity,
    relayRequestId: String,
    contents: InvocationRequestMessage,
    description: RequestedSecretDescription,
    evaluation: ApprovalRuleEvaluation,
    policies: List<SecretApprovalPolicy>,
    rules: List<ApprovalRule>,
    requestedAt: Long,
    deviceInstructions: String,
): ApprovalReviewRequest = approvalReviewRequestBase(
    pairing = pairing,
    relayRequestId = relayRequestId,
    clientSoftware = contents.clientSoftware,
    reason = contents.reason,
    containsSensitiveMaterial = description.containsSensitiveMaterial,
    description = description,
    evaluation = evaluation,
    policies = policies,
    rules = rules,
    requestedAt = requestedAt,
    operation = ApprovalReviewOperation(
        command = contents.operation.command,
        arguments = contents.operation.arguments,
        workingDirectory = contents.operation.workingDirectory,
        executablePath = contents.operation.executablePath,
        executableSha256 = contents.operation.executableHash,
        executableMode = contents.operation.executableMode,
        stdin = contents.operation.stdin,
        stdout = contents.operation.stdout,
        stderr = contents.operation.stderr,
    ),
    launcherChain = contents.launcherChain,
    gitSigning = null,
    deviceInstructions = deviceInstructions,
)

private fun approvalReviewRequestBase(
    pairing: PairingEntity,
    relayRequestId: String,
    clientSoftware: ClientSoftware,
    reason: String?,
    containsSensitiveMaterial: Boolean,
    description: RequestedSecretDescription,
    evaluation: ApprovalRuleEvaluation,
    policies: List<SecretApprovalPolicy>,
    rules: List<ApprovalRule>,
    requestedAt: Long,
    operation: ApprovalReviewOperation,
    launcherChain: List<String>,
    gitSigning: ApprovalReviewGitSigning?,
    deviceInstructions: String,
): ApprovalReviewRequest {
    val secretNamesById = description.reviewMetadata.associate { secret ->
        secret.id to secret.name
    }
    val policiesById = policies.associateBy(SecretApprovalPolicy::secretId)
    return ApprovalReviewRequest(
        contextVersion = 1,
        policy = ApprovalReviewPolicy(
            decision = evaluation.action.storedName,
            deviceInstructions = deviceInstructions,
            clientInstructions = pairing.instructions,
            secretDecisions = evaluation.secrets.map { secret ->
                val policy = checkNotNull(policiesById[secret.secretId])
                ApprovalReviewSecretDecision(
                    secretId = secret.secretId,
                    secretName = secret.secretName,
                    decision = secret.action.storedName,
                    defaultDecision = policy.defaultMode.storedName,
                    clientOverride = policy.overridden,
                    instructions = policy.instructions,
                    matchingRuleIds = secret.matchedRuleIds.sorted(),
                    decisiveRuleIds = secret.decisiveRuleIds.sorted(),
                )
            },
            matchingRules = rules.sortedBy(ApprovalRule::id).map { rule ->
                ApprovalReviewRule(
                    id = rule.id,
                    name = rule.name,
                    action = rule.action.storedName,
                    secretIds = rule.secretIds.sorted(),
                    secretNames = rule.secretIds.mapNotNull(secretNamesById::get).sorted(),
                    command = rule.command,
                    commandMatch = rule.commandMatch.storedName,
                    executablePath = rule.executablePath,
                    executableSha256 = rule.executableHash,
                    workingDirectory = rule.workingDirectory,
                    createdAtUnixMs = rule.createdAt,
                    updatedAtUnixMs = rule.updatedAt,
                    expiresAtUnixMs = rule.expiresAt,
                    lastMatchedAtUnixMs = rule.lastMatchedAt,
                    previousMatchCount = rule.matchCount,
                )
            },
        ),
        action = ApprovalReviewAction(
            requestId = relayRequestId,
            requestedAtUnixMs = requestedAt,
            reason = reason,
            containsSensitiveMaterial = containsSensitiveMaterial,
            client = ApprovalReviewClient(
                id = pairing.clientId,
                name = pairing.friendlyName ?: pairing.hostname ?: "Unknown client",
                hostname = pairing.hostname,
                platform = pairing.platform,
                architecture = pairing.architecture,
                machineId = pairing.machineId,
                osVersion = pairing.osVersion,
                software = ApprovalReviewSoftware(
                    application = ApprovalReviewSoftwareComponent(
                        name = clientSoftware.application.name,
                        version = clientSoftware.application.version,
                    ),
                    library = ApprovalReviewSoftwareComponent(
                        name = clientSoftware.library.name,
                        version = clientSoftware.library.version,
                    ),
                ),
            ),
            secrets = description.reviewMetadata.map { secret ->
                ApprovalReviewSecret(
                    id = secret.id,
                    name = secret.name,
                    description = secret.description,
                    type = secret.type,
                    createdAtUnixMs = secret.createdAt,
                    updatedAtUnixMs = secret.updatedAt,
                    environmentVariables = secret.environmentVariables.map { variable ->
                        ApprovalReviewEnvironmentVariable(
                            name = variable.name,
                            sensitive = variable.sensitive,
                            notes = variable.notes,
                            createdAtUnixMs = variable.createdAt,
                            updatedAtUnixMs = variable.updatedAt,
                            valueUpdatedAtUnixMs = variable.valueUpdatedAt,
                        )
                    },
                    sshKey = secret.sshKey?.let { key ->
                        ApprovalReviewSshKey(
                            algorithm = key.algorithm,
                            publicKey = key.publicKey,
                            fingerprint = key.fingerprint,
                            comment = key.comment,
                            materialUpdatedAtUnixMs = key.materialUpdatedAt,
                        )
                    },
                )
            },
            operation = operation,
            launcherChain = launcherChain,
            gitSigning = gitSigning,
        ),
    )
}

internal fun approvalReviewGitSignRequest(
    pairing: PairingEntity,
    relayRequestId: String,
    contents: GitSignRequestMessage,
    invocation: SecretUseRequestEntity,
    description: RequestedSecretDescription,
    evaluation: ApprovalRuleEvaluation,
    policies: List<SecretApprovalPolicy>,
    rules: List<ApprovalRule>,
    requestedAt: Long,
    deviceInstructions: String,
): ApprovalReviewRequest {
    val invocationSoftware = kotlinx.serialization.json.Json.decodeFromString<
        dev.agentknock.protocol.ClientSoftware
    >(invocation.clientSoftwareJson)
    val arguments = kotlinx.serialization.json.Json.decodeFromString<List<String>>(
        invocation.argumentsJson,
    )
    val launcherChain = kotlinx.serialization.json.Json.decodeFromString<List<String>>(
        invocation.launcherChainJson,
    )
    return approvalReviewRequestBase(
        pairing = pairing,
        relayRequestId = relayRequestId,
        clientSoftware = invocationSoftware,
        reason = invocation.reason,
        containsSensitiveMaterial = true,
        description = description,
        evaluation = evaluation,
        policies = policies,
        rules = rules,
        requestedAt = requestedAt,
        operation = ApprovalReviewOperation(
            command = invocation.command,
            arguments = arguments,
            workingDirectory = invocation.workingDirectory,
            executablePath = invocation.executablePath,
            executableSha256 = invocation.executableHash,
            executableMode = invocation.executableMode,
            stdin = invocation.stdinKind,
            stdout = invocation.stdoutKind,
            stderr = invocation.stderrKind,
        ),
        launcherChain = launcherChain,
        gitSigning = ApprovalReviewGitSigning(
            secretName = contents.secret,
            namespace = "git",
            message = contents.message.decodeToString(throwOnInvalidSequence = false),
            messageSizeBytes = contents.message.size,
        ),
        deviceInstructions = deviceInstructions,
    )
}
