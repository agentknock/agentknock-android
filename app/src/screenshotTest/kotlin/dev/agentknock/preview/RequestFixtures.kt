package dev.agentknock.preview

import dev.agentknock.protocol.*
import dev.agentknock.storage.request.*
import dev.agentknock.storage.approval.*
import dev.agentknock.storage.secret.*

internal val previewEvaluation = ApprovalEvaluation(listOf(
    SecretApprovalEvaluation(previewSecret.id, previewSecret.name, ApprovalAction.ASK_ME, temporaryAccessEligible = true),
))
internal val previewInvocation = SecretUseRequestDetails(
    state = ApprovalRequestState.APPROVAL_PENDING, decision = null, decisionSource = null,
    approvalEvaluation = previewEvaluation, completionResult = null, completionReason = null,
    completionMessage = null, secrets = listOf("cf-test"),
    secretDetails = listOf(SecretMetadata("cf-test", "Production PostgreSQL credentials.", "environment", listOf("PGPASSWORD"))),
    environmentVariables = emptyMap(), missingSecrets = emptyList(),
    reason = "Check database health before the deployment.", command = "psql",
    arguments = listOf("-h", "db.example.test", "-c", "SELECT version();"),
    workingDirectory = "/home/developer/service", executablePath = "/usr/bin/psql",
    executableHash = null, executableMode = "regular", stdinKind = "terminal", stdoutKind = "terminal",
    stderrKind = "terminal", launcherChain = listOf("bash", "agentknock"), clientId = "preview-laptop",
    clientName = "cf-wrk", hostname = "workstation", platform = "linux", architecture = "x86_64",
    machineId = null, osVersion = "NixOS", clientSoftware = null, error = null, decidedAt = null,
)
internal fun previewRequest(content: InboxRequestContent, state: InboxRequestState = InboxRequestState.ACTION_REQUIRED) = InboxRequestDetails(
    id = "preview-request", parentRequestId = null, state = state, clientSoftware = null, error = null,
    receivedAt = previewTimestamp, completedAt = null, content = content,
)
internal val previewGitSign = GitSignRequestDetails(
    state = ApprovalRequestState.APPROVAL_PENDING, decision = null, completionResult = null,
    completionReason = null, completionMessage = null, secretName = "cf-key",
    message = ("tree " + "a".repeat(40) + "\nauthor Developer <dev@example.test> 1788696000 +0000\n" +
        "committer Developer <dev@example.test> 1788696000 +0000\n\nImprove database health checks\n").encodeToByteArray(),
    repository = GitSignRepository(remote = "git@example.test:team/service.git", worktree = "/home/developer/service", head = GitSignHead.Branch("main")),
    approvalEvaluation = previewEvaluation.copy(secrets = listOf(SecretApprovalEvaluation(
        previewSshSecret.id, previewSshSecret.name, ApprovalAction.ASK_ME, temporaryAccessEligible = true))), invocationRequestId = "preview-invocation",
    invocationReceivedAt = previewTimestamp, command = "git", arguments = listOf("commit", "-S"),
    reason = "Sign the health check change.", clientId = "preview-laptop", clientName = "cf-wrk",
    clientSoftware = null, error = null, decidedAt = null,
)
internal val previewAuthentication = SshAuthenticationRequestDetails(
    state = ApprovalRequestState.APPROVAL_PENDING, decision = null, completionResult = null,
    completionReason = null, completionMessage = null, secretName = "cf-key", username = "deploy",
    method = SshAuthenticationMethod.HOST_BOUND, algorithm = SshSignatureAlgorithm.ED25519,
    hostKeyAlgorithm = "ssh-ed25519", hostKeyFingerprint = previewSshKey.fingerprint,
    approvalEvaluation = previewEvaluation.copy(secrets = listOf(SecretApprovalEvaluation(
        previewSshSecret.id, previewSshSecret.name, ApprovalAction.ASK_ME, temporaryAccessEligible = true))), invocationRequestId = "preview-invocation",
    invocationReceivedAt = previewTimestamp, command = "ssh", arguments = listOf("deploy@server.example.test"),
    reason = "Inspect the deployment status.", clientId = "preview-laptop", clientName = "cf-wrk",
    clientSoftware = null, error = null, decidedAt = null,
)
internal val previewPairing = PairingRequestDetails(
    pairingState = PairingState.SAS_VERIFICATION_PENDING, clientName = "cf-wrk",
    pairingAddress = previewPairingAddress, clientId = "preview-laptop",
    sasOptions = listOf(1234_5678_9012L, 2748_1905_6382L, 5830_7291_4065L).map(PairingProtocol()::formatSas), clientSoftware = null,
    platform = "linux", architecture = "x86_64", hostname = "workstation", machineId = null,
    osVersion = "NixOS", error = null, decidedAt = null,
)
internal val previewUpload = SecretUploadRequestDetails(
    state = SecretUploadRequestState.REVIEW_PENDING, mode = SecretUploadMode.CREATE,
    uploadedName = "cf-stage", approvedName = null, descriptionProvided = true,
    description = "Staging database credentials.", secretType = "environment",
    variableNames = listOf("PGPASSWORD", "PGHOST"),
    variables = listOf(SecretUploadVariableDetails("preview-password", "PGPASSWORD", true), SecretUploadVariableDetails("preview-host", "PGHOST", false)),
    addedVariables = listOf("PGPASSWORD", "PGHOST"), changedVariables = emptyList(),
    unchangedVariables = emptyList(), removedVariables = emptyList(), publicKey = null,
    fingerprint = null, previousPublicKey = null, previousFingerprint = null, keyChanged = false,
    clientName = "cf-wrk", clientId = "preview-laptop", clientSoftware = null, error = null, decidedAt = null,
)
internal fun previewSummary(kind: InboxRequestKind, title: String, status: InboxRequestStatus, state: InboxRequestState = InboxRequestState.ACTION_REQUIRED) = InboxRequestSummary(
    id = kind.name, kind = kind, state = state, status = status, title = title,
    clientName = "cf-wrk", secretNames = listOf(if (kind == InboxRequestKind.SECRET_USE) "cf-test" else "cf-key"),
    listSummary = when (kind) {
        InboxRequestKind.GIT_SIGN -> "Improve database health checks"
        InboxRequestKind.SSH_AUTHENTICATE -> "Remote account: deploy"
        else -> null
    },
    repository = if (kind == InboxRequestKind.GIT_SIGN) "git@example.test:team/service.git" else null,
    command = if (kind == InboxRequestKind.SECRET_USE) "psql" else "git",
    arguments = if (kind == InboxRequestKind.SECRET_USE) listOf("-c", "SELECT version();") else listOf("commit", "-S"),
    receivedAt = previewTimestamp, completedAt = null,
)
internal val previewRequestSummaries = listOf(
    previewSummary(InboxRequestKind.SECRET_USE, "Secret use", InboxRequestStatus.Approval(ApprovalRequestState.APPROVAL_PENDING, null, null, null)),
    previewSummary(InboxRequestKind.GIT_SIGN, "Sign commit", InboxRequestStatus.Approval(ApprovalRequestState.APPROVAL_PENDING, null, null, null)),
    previewSummary(InboxRequestKind.SSH_AUTHENTICATE, "SSH authentication", InboxRequestStatus.Approval(ApprovalRequestState.COMPLETED, ApprovalDecision.APPROVED, ApprovalCompletionResult.APPROVED, null), InboxRequestState.COMPLETED).copy(command = "ssh", arguments = listOf("deploy@server.example.test")),
)
internal val previewPairingSummary = previewSummary(InboxRequestKind.PAIRING, "Pairing", InboxRequestStatus.Pairing(PairingState.SAS_VERIFICATION_PENDING)).copy(secretNames = emptyList(), command = null, arguments = emptyList())
internal val previewUploadSummary = previewSummary(InboxRequestKind.SECRET_UPLOAD, "Create", InboxRequestStatus.SecretUpload(SecretUploadRequestState.REVIEW_PENDING)).copy(secretNames = listOf("cf-stage"), uploadSecretType = "environment", listSummary = "2 environment variables", command = null, arguments = emptyList())
