package dev.agentknock.preview

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.agentknock.storage.secret.*
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.secrets.*

@PreviewTest
@Preview(name = "Light", group = "secret-detail", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SecretDetailLightPreview() = SecretDetailPreview()

@PreviewTest
@Preview(name = "Dark", group = "secret-detail", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SecretDetailDarkPreview() = SecretDetailPreview()

@Composable
private fun SecretDetailPreview() = PreviewScreen { SecretPage(previewSecret) }

@PreviewTest
@Preview(name = "Light", group = "secret-access", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SecretAccessLightPreview() = SecretAccessPreview()

@PreviewTest
@Preview(name = "Dark", group = "secret-access", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SecretAccessDarkPreview() = SecretAccessPreview()

@Composable
private fun SecretAccessPreview() = PreviewScreen { SecretPage(previewSecret, firstItem = 4) }

@PreviewTest
@Preview(name = "Light", group = "secret-ssh", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SecretSshLightPreview() = SecretSshPreview()

@PreviewTest
@Preview(name = "Dark", group = "secret-ssh", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SecretSshDarkPreview() = SecretSshPreview()

@Composable
private fun SecretSshPreview() = PreviewScreen { SecretPage(previewSshSecret) }

@PreviewTest
@Preview(name = "Light", group = "secret-unavailable", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SecretUnavailableLightPreview() = SecretUnavailablePreview()

@PreviewTest
@Preview(name = "Dark", group = "secret-unavailable", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SecretUnavailableDarkPreview() = SecretUnavailablePreview()

@Composable
private fun SecretUnavailablePreview() = PreviewScreen {
    SecretPage(previewSecret.copy(environmentVariables = previewSecret.environmentVariables.map { it.copy(valueAvailable = false) }))
}

@PreviewTest @Preview(name = "Large text", group = "secret-large-text", widthDp = 360, heightDp = 800, fontScale = 1.5f, locale = "en") @Composable
fun SecretLargeTextPreview() = PreviewScreen { SecretPage(previewSecret) }

@Composable
private fun SecretPage(secret: SecretDetails, firstItem: Int = 0) {
    SecretDetail(
        secret = secret, clients = previewClients, revealedValues = emptyMap(), showBack = true,
        aiReviewAccess = AiReviewAccess.ACTIVE, onOpenPlan = {},
        actions = SecretDetailActions(
            onBack = {}, onEditSecret = {}, onDeleteSecret = {}, onAddVariable = {},
            onReplaceSshKey = {}, onSaveSshComment = {}, onCopyPublicKey = {},
            onEditVariable = {}, onReveal = {}, onReadValue = { "db.example.test" },
            onCopy = {}, onSetApprovalMode = {}, onSetClientApprovalOverride = { _, _ -> },
            onSaveInstructions = {}, onEndTemporaryAccess = {},
        ),
        modifier = Modifier.fillMaxSize(), listState = rememberLazyListState(firstItem),
    )
}

@PreviewTest
@Preview(name = "Light", group = "secret-temporary-access", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SecretTemporaryAccessLightPreview() = SecretTemporaryAccessPreview()

@PreviewTest
@Preview(name = "Dark", group = "secret-temporary-access", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SecretTemporaryAccessDarkPreview() = SecretTemporaryAccessPreview()

@Composable
private fun SecretTemporaryAccessPreview() = PreviewScreen {
    SecretPage(previewSecret.copy(temporaryAccessGrants = previewGrants), firstItem = 4)
}
