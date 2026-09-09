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
    completionMessage = null, secrets = listOf(previewSecret.name),
    secretDetails = listOf(SecretMetadata(previewSecret.name, previewSecret.description, "environment", previewSecret.environmentVariables.map { it.name })),
    environmentVariables = emptyMap(), missingSecrets = emptyList(),
    reason = "Confirm the production database is reachable before deploying.", command = "psql",
    arguments = listOf("-h", "db.prod.example.com", "-U", "orders_app", "-d", "orders", "-c", "SELECT 1;"),
    workingDirectory = "/home/maya/projects/orders-api", executablePath = "/run/current-system/sw/bin/psql",
    executableHash = null, executableMode = "regular", stdinKind = "terminal", stdoutKind = "terminal",
    stderrKind = "terminal", launcherChain = listOf("bash", "agentknock"), clientId = "preview-laptop",
    clientName = "maya-thinkpad", hostname = "maya-thinkpad", platform = "linux", architecture = "x86_64",
    machineId = null, osVersion = "NixOS", clientSoftware = null, error = null, decidedAt = null,
)
internal fun previewRequest(content: InboxRequestContent, state: InboxRequestState = InboxRequestState.ACTION_REQUIRED) = InboxRequestDetails(
    id = "preview-request", parentRequestId = null, state = state, clientSoftware = null, error = null,
    receivedAt = previewTimestamp, completedAt = null, content = content,
)
internal val previewGitSign = GitSignRequestDetails(
    state = ApprovalRequestState.APPROVAL_PENDING, decision = null, completionResult = null,
    completionReason = null, completionMessage = null, secretName = previewSshSecret.name,
    message = ("tree 9e7c64bb81d2b6f430ec8ea59a0761f34705cd92\nauthor Maya Chen <maya@example.com> ${previewTimestamp / 1000} +0000\n" +
        "committer Maya Chen <maya@example.com> ${previewTimestamp / 1000} +0000\n\nRetry database connections during startup\n").encodeToByteArray(),
    repository = GitSignRepository(remote = "git@git.example.com:commerce/orders-api.git", worktree = "/home/maya/projects/orders-api", head = GitSignHead.Branch("main")),
    approvalEvaluation = previewEvaluation.copy(secrets = listOf(SecretApprovalEvaluation(
        previewSshSecret.id, previewSshSecret.name, ApprovalAction.ASK_ME, temporaryAccessEligible = true))), invocationRequestId = "preview-invocation",
    invocationReceivedAt = previewTimestamp, command = "git", arguments = listOf("commit", "-S"),
    reason = "Commit the database connection retry fix.", clientId = "preview-laptop", clientName = "maya-thinkpad",
    clientSoftware = null, error = null, decidedAt = null,
)
internal val previewAuthentication = SshAuthenticationRequestDetails(
    state = ApprovalRequestState.APPROVAL_PENDING, decision = null, completionResult = null,
    completionReason = null, completionMessage = null, secretName = previewSshSecret.name, username = "deploy",
    method = SshAuthenticationMethod.HOST_BOUND, algorithm = SshSignatureAlgorithm.ED25519,
    hostKeyAlgorithm = "ssh-ed25519", hostKeyFingerprint = SshKeyCodec().publicKey(
        SshKeyAlgorithm.ED25519, ByteArray(32) { (it + 65).toByte() }, "app-01.prod.example.com",
    ).fingerprint,
    approvalEvaluation = previewEvaluation.copy(secrets = listOf(SecretApprovalEvaluation(
        previewSshSecret.id, previewSshSecret.name, ApprovalAction.ASK_ME, temporaryAccessEligible = true))), invocationRequestId = "preview-invocation",
    invocationReceivedAt = previewTimestamp, command = "ssh", arguments = listOf("deploy@app-01.prod.example.com", "systemctl status orders-api"),
    reason = "Check that the orders API restarted after the deployment.", clientId = "preview-laptop", clientName = "maya-thinkpad",
    clientSoftware = null, error = null, decidedAt = null,
)
internal val previewPairing = PairingRequestDetails(
    pairingState = PairingState.SAS_VERIFICATION_PENDING, clientName = "maya-desktop",
    pairingAddress = previewPairingAddress, clientId = "preview-desktop",
    sasOptions = listOf(1234_5678_9012L, 2748_1905_6382L, 5830_7291_4065L).map(PairingProtocol()::formatSas), clientSoftware = null,
    platform = "linux", architecture = "x86_64", hostname = "maya-desktop", machineId = null,
    osVersion = "NixOS", error = null, decidedAt = null,
)
internal val previewUpload = SecretUploadRequestDetails(
    state = SecretUploadRequestState.REVIEW_PENDING, mode = SecretUploadMode.CREATE,
    uploadedName = "orders-db-staging", approvedName = null, descriptionProvided = true,
    description = "PostgreSQL connection settings for the staging orders database.", secretType = "environment",
    variableNames = listOf("PGPASSWORD", "PGHOST"),
    variables = listOf(SecretUploadVariableDetails("preview-password", "PGPASSWORD", true), SecretUploadVariableDetails("preview-host", "PGHOST", false)),
    addedVariables = listOf("PGPASSWORD", "PGHOST"), changedVariables = emptyList(),
    unchangedVariables = emptyList(), removedVariables = emptyList(), publicKey = null,
    fingerprint = null, previousPublicKey = null, previousFingerprint = null, keyChanged = false,
    clientName = "maya-thinkpad", clientId = "preview-laptop", clientSoftware = null, error = null, decidedAt = null,
)
internal fun previewSummary(kind: InboxRequestKind, title: String, status: InboxRequestStatus, state: InboxRequestState = InboxRequestState.ACTION_REQUIRED) = InboxRequestSummary(
    id = kind.name, kind = kind, state = state, status = status, title = title,
    clientName = "maya-thinkpad", secretNames = listOf(if (kind == InboxRequestKind.SECRET_USE) previewSecret.name else previewSshSecret.name),
    listSummary = when (kind) {
        InboxRequestKind.GIT_SIGN -> "Retry database connections during startup"
        InboxRequestKind.SSH_AUTHENTICATE -> "Remote account: deploy"
        else -> null
    },
    repository = if (kind == InboxRequestKind.GIT_SIGN) previewGitSign.repository?.remote else null,
    command = when (kind) {
        InboxRequestKind.SECRET_USE -> previewInvocation.command
        InboxRequestKind.GIT_SIGN -> previewGitSign.command
        InboxRequestKind.SSH_AUTHENTICATE -> previewAuthentication.command
        else -> null
    },
    arguments = when (kind) {
        InboxRequestKind.SECRET_USE -> previewInvocation.arguments
        InboxRequestKind.GIT_SIGN -> previewGitSign.arguments
        InboxRequestKind.SSH_AUTHENTICATE -> previewAuthentication.arguments
        else -> emptyList()
    },
    receivedAt = previewTimestamp, completedAt = null,
)
internal val previewRequestSummaries = listOf(
    previewSummary(InboxRequestKind.SECRET_USE, "Secret use", InboxRequestStatus.Approval(ApprovalRequestState.APPROVAL_PENDING, null, null, null)),
    previewSummary(InboxRequestKind.GIT_SIGN, "Sign commit", InboxRequestStatus.Approval(ApprovalRequestState.APPROVAL_PENDING, null, null, null)),
    previewSummary(InboxRequestKind.SSH_AUTHENTICATE, "SSH authentication", InboxRequestStatus.Approval(ApprovalRequestState.COMPLETED, ApprovalDecision.APPROVED, ApprovalCompletionResult.APPROVED, null), InboxRequestState.COMPLETED),
)
internal val previewPairingSummary = previewSummary(InboxRequestKind.PAIRING, "Pairing", InboxRequestStatus.Pairing(PairingState.SAS_VERIFICATION_PENDING)).copy(clientName = previewPairing.clientName, secretNames = emptyList(), command = null, arguments = emptyList())
internal val previewUploadSummary = previewSummary(InboxRequestKind.SECRET_UPLOAD, "Create", InboxRequestStatus.SecretUpload(SecretUploadRequestState.REVIEW_PENDING)).copy(secretNames = listOf(previewUpload.uploadedName), uploadSecretType = "environment", listSummary = "2 environment variables", command = null, arguments = emptyList())
