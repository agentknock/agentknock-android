package dev.agentknock.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// Native 1080 × 1920 renders for the Play listing, using the existing screen fixtures.
// Keep the standard 360 × 800 dp phone variants separate from these store assets.

@PreviewTest
@Preview(
    name = "Play Store", group = "invocation",
    device = "spec:width=1080px,height=1920px,dpi=400",
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun InvocationPlayStorePreview() = InvocationDarkPreview()

@PreviewTest
@Preview(
    name = "Play Store", group = "secrets",
    device = "spec:width=1080px,height=1920px,dpi=400",
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun SecretsPlayStorePreview() = SecretsDarkPreview()

@PreviewTest
@Preview(
    name = "Play Store", group = "git-sign",
    device = "spec:width=1080px,height=1920px,dpi=400",
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun GitSignPlayStorePreview() = GitSignDarkPreview()

@PreviewTest
@Preview(
    name = "Play Store", group = "ssh-authentication",
    device = "spec:width=1080px,height=1920px,dpi=400",
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun SshAuthenticationPlayStorePreview() = SshAuthenticationDarkPreview()

@PreviewTest
@Preview(
    name = "Play Store", group = "secret-detail",
    device = "spec:width=1080px,height=1920px,dpi=400",
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun SecretDetailPlayStorePreview() = SecretDetailDarkPreview()

@PreviewTest
@Preview(
    name = "Play Store", group = "client-detail",
    device = "spec:width=1080px,height=1920px,dpi=400",
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ClientDetailPlayStorePreview() = ClientDetailDarkPreview()

@PreviewTest
@Preview(
    name = "Play Store", group = "pairing",
    device = "spec:width=1080px,height=1920px,dpi=400",
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PairingPlayStorePreview() = PairingDarkPreview()

@PreviewTest
@Preview(
    name = "Play Store", group = "audit",
    device = "spec:width=1080px,height=1920px,dpi=400",
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun AuditPlayStorePreview() = AuditDarkPreview()
