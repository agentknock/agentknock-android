package dev.agentknock.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.agentknock.storage.device.ClaimPairingAddressResult
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.AgentknockLockedScreen
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.device.*
import dev.agentknock.ui.requests.TemporaryAccessConfirmation

@PreviewTest
@Preview(
    name = "Light",
    group = "welcome",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun WelcomeLightPreview() = WelcomePreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "welcome",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun WelcomeDarkPreview() = WelcomePreview()

@Composable private fun WelcomePreview() = PreviewScreen { WelcomeScreen({}) }

@PreviewTest
@Preview(
    name = "Light",
    group = "setup",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun SetupLightPreview() = SetupPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "setup",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun SetupDarkPreview() = SetupPreview()

@Composable private fun SetupPreview() = PreviewScreen { SetupPage(false) }

@PreviewTest
@Preview(
    name = "Light",
    group = "pairing-address",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun PairingAddressLightPreview() = PairingAddressPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "pairing-address",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PairingAddressDarkPreview() = PairingAddressPreview()

@Composable private fun PairingAddressPreview() = PreviewScreen { SetupPage(true) }

@PreviewTest
@Preview(
    name = "Light",
    group = "pairing-address-unavailable",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun PairingAddressUnavailableLightPreview() = PairingAddressUnavailablePreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "pairing-address-unavailable",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PairingAddressUnavailableDarkPreview() = PairingAddressUnavailablePreview()

@Composable
private fun PairingAddressUnavailablePreview() = PreviewScreen {
    SetupPage(true, ClaimPairingAddressResult.AddressUnavailable)
}

@Composable
private fun SetupPage(change: Boolean, result: ClaimPairingAddressResult? = null) {
    DeviceSetupContent(
        active = previewIdentity.takeIf { change },
        candidate = null,
        address = previewNewPairingAddress,
        claiming = false,
        result = result,
        changeAddressInitially = change,
        authenticationMode = DeviceAuthenticationMode.APP_LOCK,
        onAuthenticationModeChange = {},
        onBack = ({}).takeIf { change },
        onOpenSettings = null,
        onAddressChange = {},
        onGenerate = {},
        onSubmit = {},
    )
}

@PreviewTest
@Preview(
    name = "Light",
    group = "locked",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun LockedLightPreview() = LockedPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "locked",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun LockedDarkPreview() = LockedPreview()

@Composable private fun LockedPreview() = PreviewScreen { AgentknockLockedScreen(null, {}) }

@PreviewTest
@Preview(
    name = "Light",
    group = "unlock-failed",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun UnlockFailedLightPreview() = UnlockFailedPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "unlock-failed",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun UnlockFailedDarkPreview() = UnlockFailedPreview()

@Composable
private fun UnlockFailedPreview() = PreviewScreen {
    AgentknockLockedScreen("Authentication was canceled. Unlock to continue.", {})
}

@PreviewTest
@Preview(
    name = "Light",
    group = "temporary-access",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun TemporaryAccessLightPreview() = TemporaryAccessPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "temporary-access",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun TemporaryAccessDarkPreview() = TemporaryAccessPreview()

@Composable
private fun TemporaryAccessPreview() = PreviewScreen {
    TemporaryAccessConfirmation(
        "maya-thinkpad",
        listOf(previewSecret.name, previewSshSecret.name),
        TemporaryAccessOperation.INVOCATION,
        true,
        {},
        {},
        modifier = previewDialogModifier(),
    )
}
