package dev.agentknock.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.agentknock.subscription.*
import dev.agentknock.storage.crypto.*
import dev.agentknock.storage.audit.*
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.settings.*
import kotlinx.serialization.json.JsonObject

internal val previewProtection = VaultProtection.ActiveKeysAvailable(
    VaultKeyPurpose.entries.associateWith { EncryptionKeyBacking.TRUSTED_ENVIRONMENT }, emptySet(),
)
internal val previewSubscription = SubscriptionUiState(
    access = AiReviewAccess.INACTIVE, playStore = PlayStoreAvailability.AVAILABLE,
    offers = listOf(PlaySubscriptionOffer(PlaySubscriptionOfferId("agentknock_subscription", "monthly", null),
        "$5.00", "per month", true)),
)

@PreviewTest
@Preview(name = "Light", group = "settings", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SettingsLightPreview() = SettingsPreview()

@PreviewTest
@Preview(name = "Dark", group = "settings", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SettingsDarkPreview() = SettingsPreview()

@Composable
private fun SettingsPreview() = PreviewScreen {
    SettingsOverviewContent(DeviceAuthenticationMode.APP_LOCK, previewProtection, true, null, previewSubscription, {}, {}, Modifier.fillMaxSize())
}

@PreviewTest
@Preview(name = "Light", group = "security", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SecurityLightPreview() = SecurityPreview()

@PreviewTest
@Preview(name = "Dark", group = "security", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SecurityDarkPreview() = SecurityPreview()

@Composable
private fun SecurityPreview() = PreviewScreen {
    SecuritySettingsContent(previewProtection, DeviceAuthenticationMode.APP_LOCK, true, {}, {}, Modifier.fillMaxSize())
}

@PreviewTest
@Preview(name = "Light", group = "notifications", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun NotificationsLightPreview() = NotificationsPreview()

@PreviewTest
@Preview(name = "Dark", group = "notifications", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun NotificationsDarkPreview() = NotificationsPreview()

@Composable
private fun NotificationsPreview() = PreviewScreen { NotificationsSettingsContent(null, true, true, true, true, {}, {}, {}, Modifier.fillMaxSize()) }

@PreviewTest
@Preview(name = "Light", group = "about", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun AboutLightPreview() = AboutPreview()

@PreviewTest
@Preview(name = "Dark", group = "about", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AboutDarkPreview() = AboutPreview()

@Composable
private fun AboutPreview() = PreviewScreen { AboutSettings(previewIdentity, {}, {}, Modifier.fillMaxSize()) }

@PreviewTest
@Preview(name = "Light", group = "factory-reset", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun FactoryResetLightPreview() = FactoryResetPreview()

@PreviewTest
@Preview(name = "Dark", group = "factory-reset", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FactoryResetDarkPreview() = FactoryResetPreview()

@Composable
private fun FactoryResetPreview() = PreviewScreen { ResetPage(FactoryResetUiState.Idle) }

@PreviewTest
@Preview(name = "Light", group = "factory-reset-confirmation", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun FactoryResetConfirmationLightPreview() = FactoryResetConfirmationPreview()

@PreviewTest
@Preview(name = "Dark", group = "factory-reset-confirmation", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FactoryResetConfirmationDarkPreview() = FactoryResetConfirmationPreview()

@Composable
private fun FactoryResetConfirmationPreview() = PreviewScreen { ResetPage(FactoryResetUiState.ConfirmLocalClear) }

@Composable private fun ResetPage(state: FactoryResetUiState) {
    FactoryResetSettings({}, state, {}, {}, {}, {}, {}, Modifier.fillMaxSize(), confirmationModifier = previewDialogModifier())
}

@PreviewTest
@Preview(name = "Light", group = "subscription", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SubscriptionLightPreview() = SubscriptionPreview()

@PreviewTest
@Preview(name = "Dark", group = "subscription", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SubscriptionDarkPreview() = SubscriptionPreview()

@Composable
private fun SubscriptionPreview() = PreviewScreen { SubscriptionPage(previewSubscription) }

@PreviewTest
@Preview(name = "Light", group = "subscription-active", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SubscriptionActiveLightPreview() = SubscriptionActivePreview()

@PreviewTest
@Preview(name = "Dark", group = "subscription-active", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SubscriptionActiveDarkPreview() = SubscriptionActivePreview()

@Composable
private fun SubscriptionActivePreview() = PreviewScreen {
    SubscriptionPage(previewSubscription.copy(access = AiReviewAccess.ACTIVE, googlePlayPurchase = GooglePlayPurchaseState.PURCHASED,
        googlePlayProductId = "agentknock_subscription"))
}

@PreviewTest
@Preview(name = "Light", group = "subscription-pending", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SubscriptionPendingLightPreview() = SubscriptionPendingPreview()

@PreviewTest
@Preview(name = "Dark", group = "subscription-pending", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SubscriptionPendingDarkPreview() = SubscriptionPendingPreview()

@Composable
private fun SubscriptionPendingPreview() = PreviewScreen {
    SubscriptionPage(previewSubscription.copy(googlePlayPurchase = GooglePlayPurchaseState.PENDING))
}

@PreviewTest
@Preview(name = "Light", group = "subscription-unavailable", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SubscriptionUnavailableLightPreview() = SubscriptionUnavailablePreview()

@PreviewTest
@Preview(name = "Dark", group = "subscription-unavailable", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SubscriptionUnavailableDarkPreview() = SubscriptionUnavailablePreview()

@Composable
private fun SubscriptionUnavailablePreview() = PreviewScreen {
    SubscriptionPage(previewSubscription.copy(playStore = PlayStoreAvailability.UNAVAILABLE, offers = emptyList(), statusUnavailable = true))
}

@Composable private fun SubscriptionPage(state: SubscriptionUiState) {
    SubscriptionAndBillingScreen(state, {}, {}, {}, {}, {}, Modifier.fillMaxSize())
}

internal val previewAuditEvent = AuditEvent(
    id = 1, occurredAt = previewTimestamp, type = AuditEventType.SECRET_USE_DECIDED, outcome = AuditOutcome.APPROVED,
    decisionSource = AuditDecisionSource.USER, subject = previewSecret.name, context = null, detail = null,
    expiresAt = null, clientId = "preview-laptop", clientName = "maya-thinkpad", relayRequestId = "preview-request",
    data = JsonObject(auditDataOf("command" to previewInvocation.command, "arguments" to previewInvocation.arguments, "secrets" to previewInvocation.secrets)),
)
internal val previewAuditEvents = listOf(previewAuditEvent,
    previewAuditEvent.copy(id = 2, occurredAt = previewTimestamp - 900_000, type = AuditEventType.CLIENT_SUSPENDED, outcome = AuditOutcome.CHANGED, subject = "build-runner-01", clientId = "preview-server", clientName = "build-runner-01", relayRequestId = null,
        decisionSource = null, data = JsonObject(auditDataOf("name" to "build-runner-01", "relay_state" to "suspended"))),
    previewAuditEvent.copy(id = 3, occurredAt = previewTimestamp - 3_600_000, outcome = AuditOutcome.DENIED, decisionSource = AuditDecisionSource.AI_REVIEW,
        data = JsonObject(auditDataOf("command" to "psql", "arguments" to listOf("-h", "db.prod.example.com", "-U", "orders_app", "-d", "orders", "-c", "TRUNCATE orders;"), "secrets" to previewInvocation.secrets))),
)

@PreviewTest
@Preview(name = "Light", group = "audit", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun AuditLightPreview() = AuditPreview()

@PreviewTest
@Preview(name = "Dark", group = "audit", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AuditDarkPreview() = AuditPreview()

@Composable
private fun AuditPreview() = PreviewScreen { AuditPage(previewAuditEvents, null) }

@PreviewTest
@Preview(name = "Light", group = "audit-empty", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun AuditEmptyLightPreview() = AuditEmptyPreview()

@PreviewTest
@Preview(name = "Dark", group = "audit-empty", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AuditEmptyDarkPreview() = AuditEmptyPreview()

@Composable
private fun AuditEmptyPreview() = PreviewScreen { AuditPage(emptyList(), null) }

@PreviewTest
@Preview(name = "Light", group = "audit-detail", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun AuditDetailLightPreview() = AuditDetailPreview()

@PreviewTest
@Preview(name = "Dark", group = "audit-detail", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AuditDetailDarkPreview() = AuditDetailPreview()

@Composable
private fun AuditDetailPreview() = PreviewScreen { AuditPage(previewAuditEvents, previewAuditEvent) }

@PreviewTest @Preview(name = "Tablet", group = "audit-tablet", widthDp = 1000, heightDp = 800, locale = "en") @Composable
fun AuditTabletPreview() = PreviewScreen { AuditPage(previewAuditEvents, previewAuditEvent) }

@Composable private fun AuditPage(events: List<AuditEvent>, selected: AuditEvent?) {
    AuditBrowser(events, selected?.id, selected, true, {}, {}, {}, Modifier.fillMaxSize())
}

@PreviewTest
@Preview(name = "Light", group = "notifications-blocked", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun NotificationsBlockedLightPreview() = NotificationsBlockedPreview()

@PreviewTest
@Preview(name = "Dark", group = "notifications-blocked", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun NotificationsBlockedDarkPreview() = NotificationsBlockedPreview()

@Composable
private fun NotificationsBlockedPreview() = PreviewScreen {
    NotificationsSettingsContent(null, false, false, false, true, {}, {}, {}, Modifier.fillMaxSize())
}

@PreviewTest
@Preview(name = "Light", group = "security-restored", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SecurityRestoredLightPreview() = SecurityRestoredPreview()

@PreviewTest
@Preview(name = "Dark", group = "security-restored", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SecurityRestoredDarkPreview() = SecurityRestoredPreview()

@Composable
private fun SecurityRestoredPreview() = PreviewScreen {
    SecuritySettingsContent(VaultProtection.ActiveKeysUnavailable(VaultKeyPurpose.entries.toSet(), VaultKeyPurpose.entries.toSet()),
        DeviceAuthenticationMode.APP_LOCK, false, {}, {}, Modifier.fillMaxSize())
}

@PreviewTest
@Preview(name = "Light", group = "security-backup", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SecurityBackupLightPreview() = SecurityBackupPreview()

@PreviewTest
@Preview(name = "Dark", group = "security-backup", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SecurityBackupDarkPreview() = SecurityBackupPreview()

@Composable
private fun SecurityBackupPreview() = PreviewScreen {
    SecuritySettingsContent(previewProtection, DeviceAuthenticationMode.APP_LOCK, true, {}, {},
        Modifier.fillMaxSize(), listState = rememberLazyListState(2))
}

@PreviewTest
@Preview(name = "Light", group = "about-device", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun AboutDeviceLightPreview() = AboutDevicePreview()

@PreviewTest
@Preview(name = "Dark", group = "about-device", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AboutDeviceDarkPreview() = AboutDevicePreview()

@Composable
private fun AboutDevicePreview() = PreviewScreen {
    AboutSettings(previewIdentity, {}, {}, Modifier.fillMaxSize(), listState = rememberLazyListState(2))
}

@PreviewTest
@Preview(name = "Light", group = "factory-reset-action", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun FactoryResetActionLightPreview() = FactoryResetActionPreview()

@PreviewTest
@Preview(name = "Dark", group = "factory-reset-action", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FactoryResetActionDarkPreview() = FactoryResetActionPreview()

@Composable
private fun FactoryResetActionPreview() = PreviewScreen {
    FactoryResetSettings({}, FactoryResetUiState.Idle, {}, {}, {}, {}, {}, Modifier.fillMaxSize(),
        scrollState = rememberScrollState(Int.MAX_VALUE))
}
