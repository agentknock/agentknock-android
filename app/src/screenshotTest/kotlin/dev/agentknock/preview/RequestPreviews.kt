package dev.agentknock.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.agentknock.protocol.*
import dev.agentknock.storage.request.*
import dev.agentknock.storage.approval.*
import dev.agentknock.ui.MainSection
import dev.agentknock.ui.requests.*
import dev.agentknock.ui.secrets.SecretUploadRequestDetail

@PreviewTest
@Preview(name = "Light", group = "requests", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun RequestsLightPreview() = RequestsPreview()

@PreviewTest
@Preview(name = "Dark", group = "requests", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun RequestsDarkPreview() = RequestsPreview()

@Composable
private fun RequestsPreview() = PreviewScreen { RequestsPage(previewRequestSummaries) }

@PreviewTest
@Preview(name = "Light", group = "requests-empty", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun RequestsEmptyLightPreview() = RequestsEmptyPreview()

@PreviewTest
@Preview(name = "Dark", group = "requests-empty", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun RequestsEmptyDarkPreview() = RequestsEmptyPreview()

@Composable
private fun RequestsEmptyPreview() = PreviewScreen { RequestsPage(emptyList()) }

@PreviewTest
@Preview(name = "Light", group = "requests-offline", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun RequestsOfflineLightPreview() = RequestsOfflinePreview()

@PreviewTest
@Preview(name = "Dark", group = "requests-offline", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun RequestsOfflineDarkPreview() = RequestsOfflinePreview()

@Composable
private fun RequestsOfflinePreview() = PreviewScreen { RequestsPage(previewRequestSummaries, "Couldn't connect to the relay. Check your connection and try again.") }

@Composable private fun RequestsPage(requests: List<InboxRequestSummary>, problem: String? = null) {
    PreviewNavigation(MainSection.REQUESTS, empty = requests.isEmpty()) { modifier ->
        RequestList(
            onPairClient = ({}).takeIf { requests.isEmpty() }, requests = requests, selectedRequestId = null,
            syncing = false, syncProblem = problem, onRefresh = {}, onShowSyncProblem = {},
            onOpenSettings = {}, notificationsEnabled = problem == null, onOpen = {}, onDecision = { _, _ -> }, modifier = modifier,
        )
    }
}

@PreviewTest
@Preview(name = "Light", group = "invocation", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun InvocationLightPreview() = InvocationPreview()

@PreviewTest
@Preview(name = "Dark", group = "invocation", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationDarkPreview() = InvocationPreview()

@Composable
private fun InvocationPreview() = PreviewScreen { InvocationPage(previewInvocation) }

@PreviewTest
@Preview(name = "Light", group = "invocation-ai-review", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun InvocationAiReviewLightPreview() = InvocationAiReviewPreview()

@PreviewTest
@Preview(name = "Dark", group = "invocation-ai-review", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationAiReviewDarkPreview() = InvocationAiReviewPreview()

@Composable
private fun InvocationAiReviewPreview() = PreviewScreen {
    InvocationPage(previewInvocation.copy(
        arguments = previewInvocation.arguments.dropLast(1) + "ANALYZE orders;",
        reason = "Refresh query planner statistics after importing orders.",
        approvalEvaluation = previewEvaluation.copy(
            secrets = previewEvaluation.secrets.map { it.copy(action = ApprovalAction.ASK_AI) },
            aiReview = AiReview(AiReviewDecision.ASK_USER, "ANALYZE updates production database statistics. Your instructions require confirmation before making changes."),
        ),
    ))
}

@PreviewTest
@Preview(name = "Light", group = "invocation-completed", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun InvocationCompletedLightPreview() = InvocationCompletedPreview()

@PreviewTest
@Preview(name = "Dark", group = "invocation-completed", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationCompletedDarkPreview() = InvocationCompletedPreview()

@Composable
private fun InvocationCompletedPreview() = PreviewScreen {
    InvocationPage(previewInvocation.copy(state = ApprovalRequestState.COMPLETED, decision = ApprovalDecision.APPROVED,
        completionResult = ApprovalCompletionResult.APPROVED), InboxRequestState.COMPLETED)
}

@PreviewTest
@Preview(name = "Light", group = "invocation-failed", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun InvocationFailedLightPreview() = InvocationFailedPreview()

@PreviewTest
@Preview(name = "Dark", group = "invocation-failed", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationFailedDarkPreview() = InvocationFailedPreview()

@Composable
private fun InvocationFailedPreview() = PreviewScreen {
    InvocationPage(previewInvocation.copy(state = ApprovalRequestState.VERIFICATION_FAILED,
        error = "The client confirmation could not be verified."), InboxRequestState.COMPLETED)
}

@Composable private fun InvocationPage(details: SecretUseRequestDetails, state: InboxRequestState = InboxRequestState.ACTION_REQUIRED) {
    InvocationRequestDetail(previewRequest(InboxRequestContent.SecretUse(details), state), {}, true, {}, {}, {}, Modifier.fillMaxSize())
}

@PreviewTest
@Preview(name = "Light", group = "git-sign", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun GitSignLightPreview() = GitSignPreview()

@PreviewTest
@Preview(name = "Dark", group = "git-sign", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GitSignDarkPreview() = GitSignPreview()

@Composable
private fun GitSignPreview() = PreviewScreen { GitSignPage(previewGitSign) }

@Composable private fun GitSignPage(details: GitSignRequestDetails, state: InboxRequestState = InboxRequestState.ACTION_REQUIRED) {
    GitSignRequestDetail(previewRequest(InboxRequestContent.GitSign(details), state), {}, true, {}, {}, {}, Modifier.fillMaxSize())
}

private val previewSignedRepository = GitSignRepository(
    remote = "git@git.example.com:commerce/orders-api.git", worktree = "/home/maya/projects/orders-api",
    head = GitSignHead.Branch("main", upstream = "origin/main"), changedPathCount = 2,
    changedPaths = listOf(
        GitSignChangedPath(GitSignChangeStatus.MODIFIED, "orders/db.py"),
        GitSignChangedPath(GitSignChangeStatus.ADDED, "tests/test_db.py"),
    ),
)

@PreviewTest
@Preview(name = "Dark", group = "git-sign-signed", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GitSignSignedDarkPreview() = PreviewScreen {
    GitSignPage(previewGitSign.copy(state = ApprovalRequestState.COMPLETED, decision = ApprovalDecision.APPROVED,
        completionResult = ApprovalCompletionResult.APPROVED, repository = previewSignedRepository), InboxRequestState.COMPLETED)
}

@PreviewTest
@Preview(name = "Dark", group = "git-sign-tag", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GitSignTagDarkPreview() = PreviewScreen {
    GitSignPage(previewGitSign.copy(
        message = ("object c81d2f0e6b439a5f70814f07b9a2d3815e64c092\ntype commit\ntag v1.4.0\n" +
            "tagger Maya Chen <maya@example.com> ${previewTimestamp / 1000} +0000\n\nRelease 1.4.0\n\nDatabase connections now retry during startup.\n").encodeToByteArray(),
        repository = previewSignedRepository.copy(changedPathCount = null, changedPaths = null),
        command = "git", arguments = listOf("tag", "-s", "v1.4.0", "-m", "Release 1.4.0"),
        reason = "Sign the release tag.",
    ))
}

@PreviewTest
@Preview(name = "Dark", group = "git-sign-large-text", widthDp = 360, heightDp = 800, fontScale = 1.5f, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GitSignLargeTextDarkPreview() = GitSignPreview()

@PreviewTest
@Preview(name = "Light", group = "ssh-authentication", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SshAuthenticationLightPreview() = SshAuthenticationPreview()

@PreviewTest
@Preview(name = "Dark", group = "ssh-authentication", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SshAuthenticationDarkPreview() = SshAuthenticationPreview()

@Composable
private fun SshAuthenticationPreview() = PreviewScreen { SshAuthenticationPage(previewAuthentication) }

@Composable private fun SshAuthenticationPage(details: SshAuthenticationRequestDetails, state: InboxRequestState = InboxRequestState.ACTION_REQUIRED) {
    SshAuthenticationRequestDetail(previewRequest(InboxRequestContent.SshAuthentication(details), state), {}, true, {}, {}, {}, Modifier.fillMaxSize())
}

@PreviewTest
@Preview(name = "Dark", group = "ssh-authentication-publickey", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SshAuthenticationPublicKeyDarkPreview() = PreviewScreen {
    SshAuthenticationPage(previewAuthentication.copy(
        method = SshAuthenticationMethod.PUBLIC_KEY, hostKeyAlgorithm = null, hostKeyFingerprint = null,
        username = "git", command = "git", arguments = listOf("push", "origin", "main"),
        reason = "Push the reviewed branch.",
    ))
}

@PreviewTest
@Preview(name = "Dark", group = "ssh-authentication-signed", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SshAuthenticationSignedDarkPreview() = PreviewScreen {
    SshAuthenticationPage(previewAuthentication.copy(state = ApprovalRequestState.COMPLETED, decision = ApprovalDecision.APPROVED,
        completionResult = ApprovalCompletionResult.APPROVED), InboxRequestState.COMPLETED)
}

@PreviewTest
@Preview(name = "Dark", group = "ssh-authentication-large-text", widthDp = 360, heightDp = 800, fontScale = 1.5f, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SshAuthenticationLargeTextDarkPreview() = SshAuthenticationPreview()

@PreviewTest
@Preview(name = "Light", group = "secret-upload", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SecretUploadLightPreview() = SecretUploadPreview()

@PreviewTest
@Preview(name = "Dark", group = "secret-upload", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SecretUploadDarkPreview() = SecretUploadPreview()

@Composable
private fun SecretUploadPreview() = PreviewScreen { UploadPage(previewUpload) }

@PreviewTest
@Preview(name = "Light", group = "ssh-upload", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SshUploadLightPreview() = SshUploadPreview()

@PreviewTest
@Preview(name = "Dark", group = "ssh-upload", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SshUploadDarkPreview() = SshUploadPreview()

@Composable
private fun SshUploadPreview() = PreviewScreen {
    UploadPage(previewUpload.copy(secretType = "ssh", uploadedName = "deploy-ssh-staging", description = "SSH access to staging application servers.",
        variables = emptyList(), variableNames = emptyList(), addedVariables = emptyList(), publicKey = previewSshKey.publicKey, fingerprint = previewSshKey.fingerprint))
}

@Composable private fun UploadPage(details: SecretUploadRequestDetails) {
    SecretUploadRequestDetail(previewRequest(InboxRequestContent.SecretUpload(details)), {}, true, {}, {},
        mapOf("preview-host" to "db.staging.example.com"), {}, { _, _ -> }, Modifier.fillMaxSize())
}

@PreviewTest
@Preview(name = "Light", group = "invocation-reviewing", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun InvocationReviewingLightPreview() = InvocationReviewingPreview()

@PreviewTest
@Preview(name = "Dark", group = "invocation-reviewing", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationReviewingDarkPreview() = InvocationReviewingPreview()

@Composable
private fun InvocationReviewingPreview() = PreviewScreen {
    InvocationPage(previewInvocation.copy(approvalEvaluation = previewEvaluation.copy(
        secrets = previewEvaluation.secrets.map { it.copy(action = ApprovalAction.ASK_AI) },
    )), InboxRequestState.REVIEWING)
}

private const val longClientName = "orders-deploy-runner-eu-west-1"
private val longSecretNames = listOf("orders-production-postgres-password", "orders-production-deployment-token")

@PreviewTest
@Preview(name = "Dark", group = "requests-long-names", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun RequestsLongNamesDarkPreview() = PreviewScreen {
    RequestsPage(previewRequestSummaries.map {
        it.copy(
            clientName = longClientName,
            command = if (it.kind == InboxRequestKind.SECRET_USE) multiSecretInvocation.command else it.command,
            arguments = if (it.kind == InboxRequestKind.SECRET_USE) listOf("--environment", "production") else it.arguments,
            secretNames = if (it.kind == InboxRequestKind.SECRET_USE) longSecretNames
                else listOf("orders-production-deployment-ssh"),
        )
    })
}

@PreviewTest
@Preview(name = "Dark", group = "invocation-long-names", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationLongNamesDarkPreview() = PreviewScreen {
    InvocationPage(multiSecretInvocation.copy(
        clientName = longClientName,
        arguments = listOf("--environment", "production"),
        reason = "Deploy the orders API to production and run database migrations.",
        secrets = longSecretNames,
        secretDetails = longSecretNames.mapIndexed { index, name ->
            previewInvocation.secretDetails.single().copy(
                name = name,
                description = "",
                environmentVariableNames = listOf(if (index == 0) "PGPASSWORD" else "DEPLOY_TOKEN"),
            )
        },
        approvalEvaluation = previewEvaluation.copy(secrets = longSecretNames.mapIndexed { index, name ->
            previewEvaluation.secrets.single().copy(secretId = "long-secret-$index", secretName = name)
        }),
    ))
}

@PreviewTest
@Preview(name = "Dark", group = "invocation-large-text", widthDp = 360, heightDp = 800, fontScale = 1.5f, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationLargeTextDarkPreview() = InvocationPreview()

private const val longCommandExecutable =
    "/nix/store/7q8v2m4x9n6p1r3s5t0w8y2z4a6b9c1d-openjdk-21.0.7+6/bin/jarsigner"
private val longCommandArguments = listOf(
    "-keystore", "/home/maya/.local/share/parcel-android/upload-keystore.p12",
    "-storetype", "PKCS12",
    "-storepass:env", "KEYSTORE_PASSWORD",
    "-keypass:env", "KEYSTORE_PASSWORD",
    "-signedjar", "/home/maya/projects/parcel-android/app/build/publish-internal.aB3xY9/app-release.aab",
    "/home/maya/projects/parcel-android/app/build/outputs/bundle/release/app-release.aab",
    "parcel-upload",
)
private const val longCommandSecretName = "parcel-android-upload-passphrase"

@PreviewTest
@Preview(name = "Dark", group = "requests-long-command", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun RequestsLongCommandDarkPreview() = PreviewScreen {
    RequestsPage(previewRequestSummaries.map { request ->
        if (request.kind == InboxRequestKind.SECRET_USE) {
            request.copy(
                command = longCommandExecutable,
                arguments = longCommandArguments,
                secretNames = listOf(longCommandSecretName),
            )
        } else {
            request
        }
    })
}

@PreviewTest
@Preview(name = "Dark", group = "invocation-long-command", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationLongCommandDarkPreview() = PreviewScreen {
    InvocationPage(previewInvocation.copy(
        command = longCommandExecutable, arguments = longCommandArguments,
        reason = "Sign the Parcel Android release with its Google Play upload key.",
        secrets = listOf(longCommandSecretName),
        secretDetails = listOf(previewInvocation.secretDetails.single().copy(
            name = longCommandSecretName, description = "Google Play upload key passphrase.",
            environmentVariableNames = listOf("KEYSTORE_PASSWORD"),
        )),
        approvalEvaluation = previewEvaluation.copy(secrets = listOf(
            previewEvaluation.secrets.single().copy(secretId = "long-command-secret", secretName = longCommandSecretName),
        )),
        workingDirectory = "/home/maya/projects/parcel-android", executablePath = longCommandExecutable,
    ))
}

private val multiSecretDetails = listOf(
    previewInvocation.secretDetails.single().copy(
        name = "orders-db-staging", description = "PostgreSQL password for staging migrations.",
        environmentVariableNames = listOf("PGPASSWORD"),
    ),
    previewInvocation.secretDetails.single().copy(
        name = "deploy-token-staging", description = "API token for staging deployments.",
        environmentVariableNames = listOf("DEPLOY_TOKEN"),
    ),
)
private val multiSecretInvocation = previewInvocation.copy(
    command = "./deploy", arguments = listOf("--environment", "staging"),
    executablePath = "/home/maya/projects/orders-api/deploy",
    reason = "Deploy the orders API to staging and run database migrations.",
    secrets = multiSecretDetails.map { it.name },
    secretDetails = multiSecretDetails,
    approvalEvaluation = previewEvaluation.copy(secrets = multiSecretDetails.mapIndexed { index, secret ->
        previewEvaluation.secrets.single().copy(secretId = "multi-secret-$index", secretName = secret.name)
    }),
)

@PreviewTest
@Preview(name = "Dark", group = "requests-multiple-secrets", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun RequestsMultipleSecretsDarkPreview() = PreviewScreen {
    RequestsPage(previewRequestSummaries.map { request ->
        if (request.kind == InboxRequestKind.SECRET_USE) {
            request.copy(
                command = multiSecretInvocation.command,
                arguments = multiSecretInvocation.arguments,
                secretNames = multiSecretInvocation.secrets,
            )
        } else {
            request
        }
    })
}

@PreviewTest
@Preview(name = "Dark", group = "invocation-multiple-secrets", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationMultipleSecretsDarkPreview() = PreviewScreen { InvocationPage(multiSecretInvocation) }
