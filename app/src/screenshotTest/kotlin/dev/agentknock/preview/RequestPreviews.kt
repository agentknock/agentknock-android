package dev.agentknock.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
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
private fun RequestsOfflinePreview() = PreviewScreen { RequestsPage(previewRequestSummaries, "Could not connect to the relay. Pull down to retry.") }

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
    InvocationPage(previewInvocation.copy(approvalEvaluation = previewEvaluation.copy(
        secrets = previewEvaluation.secrets.map { it.copy(action = ApprovalAction.ASK_AI) },
        aiReview = AiReview(AiReviewDecision.ASK_USER, "This command connects to production. Please confirm that these diagnostics are expected."),
    )))
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
private fun GitSignPreview() = PreviewScreen {
    GitSignRequestDetail(previewRequest(InboxRequestContent.GitSign(previewGitSign)), {}, true, {}, {}, {}, Modifier.fillMaxSize())
}

@PreviewTest
@Preview(name = "Light", group = "ssh-authentication", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SshAuthenticationLightPreview() = SshAuthenticationPreview()

@PreviewTest
@Preview(name = "Dark", group = "ssh-authentication", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SshAuthenticationDarkPreview() = SshAuthenticationPreview()

@Composable
private fun SshAuthenticationPreview() = PreviewScreen {
    SshAuthenticationRequestDetail(previewRequest(InboxRequestContent.SshAuthentication(previewAuthentication)), {}, true, {}, {}, {}, Modifier.fillMaxSize())
}

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
    UploadPage(previewUpload.copy(secretType = "ssh", uploadedName = "cf-key", description = previewSshSecret.description,
        variables = emptyList(), variableNames = emptyList(), addedVariables = emptyList(), publicKey = previewSshKey.publicKey, fingerprint = previewSshKey.fingerprint))
}

@Composable private fun UploadPage(details: SecretUploadRequestDetails) {
    SecretUploadRequestDetail(previewRequest(InboxRequestContent.SecretUpload(details)), {}, true, {}, {},
        mapOf("preview-host" to "db.example.test"), {}, { _, _ -> }, Modifier.fillMaxSize())
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

private const val longClientName = "cf-wrk-production-eu-west-1"
private val longSecretNames = listOf("cf-test-production-db-password", "cf-test-deployment-api-token")

@PreviewTest
@Preview(name = "Dark", group = "requests-long-names", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun RequestsLongNamesDarkPreview() = PreviewScreen {
    RequestsPage(previewRequestSummaries.map {
        it.copy(
            clientName = longClientName,
            secretNames = if (it.kind == InboxRequestKind.SECRET_USE) longSecretNames
                else listOf("cf-key-production-deployment"),
        )
    })
}

@PreviewTest
@Preview(name = "Dark", group = "invocation-long-names", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InvocationLongNamesDarkPreview() = PreviewScreen {
    InvocationPage(previewInvocation.copy(
        clientName = longClientName,
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
    "-keystore", "/home/naked/.local/share/agentknock-android/upload-keystore.p12",
    "-storetype", "PKCS12",
    "-storepass:env", "KEYSTORE_PASSWORD",
    "-keypass:env", "KEYSTORE_PASSWORD",
    "-signedjar", "/home/naked/mine/agentknock-android/app/build/publish-internal.aB3xY9/app-release.aab",
    "/home/naked/mine/agentknock-android/app/build/outputs/bundle/release/app-release.aab",
    "agentknock-upload",
)
private const val longCommandSecretName = "agentknock-android-upload-passphrase"

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
        reason = "Sign the Agentknock Android app bundle with its Google Play upload key.",
        secrets = listOf(longCommandSecretName),
        secretDetails = listOf(previewInvocation.secretDetails.single().copy(
            name = longCommandSecretName, description = "Google Play upload key passphrase.",
            environmentVariableNames = listOf("KEYSTORE_PASSWORD"),
        )),
        approvalEvaluation = previewEvaluation.copy(secrets = listOf(
            previewEvaluation.secrets.single().copy(secretId = "long-command-secret", secretName = longCommandSecretName),
        )),
        workingDirectory = "/home/naked/mine/agentknock-android", executablePath = longCommandExecutable,
    ))
}

private val multiSecretDetails = listOf(
    previewInvocation.secretDetails.single().copy(
        name = "cf-db", description = "Staging database.",
        environmentVariableNames = listOf("PGPASSWORD"),
    ),
    previewInvocation.secretDetails.single().copy(
        name = "cf-api", description = "Deployment API.",
        environmentVariableNames = listOf("DEPLOY_TOKEN"),
    ),
)
private val multiSecretInvocation = previewInvocation.copy(
    command = "./deploy", arguments = listOf("--environment", "staging"),
    executablePath = "/home/developer/service/deploy",
    reason = "Deploy the service to staging.",
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
