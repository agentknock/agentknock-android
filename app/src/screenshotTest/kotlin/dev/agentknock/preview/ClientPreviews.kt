package dev.agentknock.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.device.DeviceIdentity
import dev.agentknock.storage.request.*
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.MainSection
import dev.agentknock.ui.clients.*
import dev.agentknock.ui.secrets.SecretList

internal val previewIdentity =
    DeviceIdentity(
        "preview-device",
        previewPairingAddress,
        "preview-device-id",
        true,
        true,
        previewTimestamp,
        "",
    )
internal val previewClient =
    ClientDetails(
        clientId = "preview-laptop",
        name = "maya-thinkpad",
        hostname = "maya-thinkpad",
        platform = "linux",
        architecture = "x86_64",
        osVersion = "NixOS",
        machineId = "e07c2c6d93b64a4a84f6c3b55912d8a7",
        clientSoftware = null,
        instructions =
            "Allow tests, builds, and read-only diagnostics. Ask before deployments or production data changes.",
        state = RelayClientState.ACTIVE,
        desiredState = null,
        pairedAt = previewTimestamp,
        lastRequestAt = previewTimestamp,
    )

@PreviewTest
@Preview(
    name = "Light",
    group = "clients",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun ClientsLightPreview() = ClientsPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "clients",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ClientsDarkPreview() = ClientsPreview()

@Composable
private fun ClientsPreview() = PreviewScreen {
    ClientsPage(previewClients, listOf(previewPairingSummary))
}

@PreviewTest
@Preview(
    name = "Light",
    group = "clients-empty",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun ClientsEmptyLightPreview() = ClientsEmptyPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "clients-empty",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ClientsEmptyDarkPreview() = ClientsEmptyPreview()

@Composable
private fun ClientsEmptyPreview() = PreviewScreen { ClientsPage(emptyList(), emptyList()) }

@Composable
private fun ClientsPage(clients: List<ClientSummary>, pairings: List<InboxRequestSummary>) {
    PreviewNavigation(MainSection.CLIENTS, empty = clients.isEmpty()) { modifier ->
        ClientList(clients, pairings, null, null, previewIdentity, {}, {}, {}, {}, {}, {}, modifier)
    }
}

@PreviewTest
@Preview(
    name = "Light",
    group = "client-detail",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun ClientDetailLightPreview() = ClientDetailPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "client-detail",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ClientDetailDarkPreview() = ClientDetailPreview()

@Composable private fun ClientDetailPreview() = PreviewScreen { ClientPage(previewClient) }

@PreviewTest
@Preview(
    name = "Light",
    group = "client-suspended",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun ClientSuspendedLightPreview() = ClientSuspendedPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "client-suspended",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ClientSuspendedDarkPreview() = ClientSuspendedPreview()

@Composable
private fun ClientSuspendedPreview() = PreviewScreen {
    ClientPage(previewClient.copy(state = RelayClientState.SUSPENDED))
}

@Composable
private fun ClientPage(client: ClientDetails) {
    ClientDetail(
        client,
        previewGrants,
        AiReviewAccess.ACTIVE,
        {},
        true,
        {},
        {},
        {},
        {},
        Modifier.fillMaxSize(),
    )
}

@PreviewTest
@Preview(
    name = "Light",
    group = "pairing",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun PairingLightPreview() = PairingPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "pairing",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PairingDarkPreview() = PairingPreview()

@Composable private fun PairingPreview() = PreviewScreen { PairingPage(previewPairing) }

@PreviewTest
@Preview(
    name = "Light",
    group = "pairing-failed",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun PairingFailedLightPreview() = PairingFailedPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "pairing-failed",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PairingFailedDarkPreview() = PairingFailedPreview()

@Composable
private fun PairingFailedPreview() = PreviewScreen {
    PairingPage(
        previewPairing.copy(
            pairingState = PairingState.EXCHANGE_FAILED,
            sasOptions = emptyList(),
            error = "The connection ended before pairing completed.",
        )
    )
}

@Composable
private fun PairingPage(pairing: PairingRequestDetails) {
    PairingRequestDetail(
        previewRequest(InboxRequestContent.Pairing(pairing)),
        {},
        true,
        {},
        {},
        Modifier.fillMaxSize(),
    )
}

@PreviewTest
@Preview(
    name = "Light",
    group = "secrets",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun SecretsLightPreview() = SecretsPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "secrets",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun SecretsDarkPreview() = SecretsPreview()

@Composable private fun SecretsPreview() = PreviewScreen { SecretsPage(false) }

@PreviewTest
@Preview(
    name = "Light",
    group = "secrets-empty",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun SecretsEmptyLightPreview() = SecretsEmptyPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "secrets-empty",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun SecretsEmptyDarkPreview() = SecretsEmptyPreview()

@Composable private fun SecretsEmptyPreview() = PreviewScreen { SecretsPage(true) }

@Composable
private fun SecretsPage(empty: Boolean) {
    PreviewNavigation(MainSection.SECRETS, empty = empty) { modifier ->
        SecretList(
            if (empty) emptyList() else previewSecrets,
            previewClients,
            if (empty) emptyList() else listOf(previewUploadSummary),
            null,
            null,
            {},
            {},
            {},
            "Ask before changing production data.",
            AiReviewAccess.ACTIVE,
            {},
            {},
            modifier,
        )
    }
}

@PreviewTest
@Preview(
    name = "Light",
    group = "client-technical-details",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun ClientTechnicalDetailsLightPreview() = ClientTechnicalDetailsPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "client-technical-details",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ClientTechnicalDetailsDarkPreview() = ClientTechnicalDetailsPreview()

@Composable
private fun ClientTechnicalDetailsPreview() = PreviewScreen {
    ClientDetail(
        previewClient,
        previewGrants,
        AiReviewAccess.ACTIVE,
        {},
        true,
        {},
        {},
        {},
        {},
        Modifier.fillMaxSize(),
        scrollState = rememberScrollState(Int.MAX_VALUE),
        informationInitiallyExpanded = true,
    )
}
