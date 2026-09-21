package dev.agentknock.ui.settings

import android.content.Intent
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.BACKGROUND_DELIVERY_SUPPORTED
import dev.agentknock.push.PushServiceIssue
import dev.agentknock.push.RequestNotifications
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class NotificationsSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unknownRegistrationDoesNotClaimDeliveryIsRegisteredAndUpdatesWhenConfirmed() {
        assumeTrue(BACKGROUND_DELIVERY_SUPPORTED)
        val registration = mutableStateOf<RelayPushRegistrationState?>(null)
        showSettings(pushState = { registration.value })

        scrollTo("Not yet confirmed")
        compose.onNodeWithText("Not yet confirmed").assertIsDisplayed()
        compose.onNodeWithText("Registered").assertDoesNotExist()

        compose.runOnIdle { registration.value = RelayPushRegistrationState.REGISTERED }

        scrollTo("Registered")
        compose.onNodeWithText("Registered").assertIsDisplayed()
        compose.onNodeWithText("Not yet confirmed").assertDoesNotExist()
    }

    @Test
    fun deliveryRestrictionsOfferSettingsAndRefreshWhenRestrictionsAreLifted() {
        assumeTrue(BACKGROUND_DELIVERY_SUPPORTED)
        val restricted = mutableStateOf(true)
        val openedSettings = mutableListOf<String>()
        showSettings(
            backgroundDataRestricted = { restricted.value },
            backgroundActivityRestricted = { restricted.value },
            openDataSettings = { openedSettings += "data" },
            openBatterySettings = { openedSettings += "battery" },
        )

        // The screen itself is foregrounded. A background restriction must still be
        // actionable here so users can fix delivery before leaving the app.
        scrollTo("Background data")
        compose.onNodeWithText("Restricted on mobile data and metered Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("Background data").performClick()
        scrollTo("Background activity")
        compose
            .onNodeWithText("Restricted — requests may wait until you open Agentknock")
            .assertIsDisplayed()
        compose.onNodeWithText("Background activity").performClick()
        compose.runOnIdle { assertEquals(listOf("data", "battery"), openedSettings) }

        compose.runOnIdle { restricted.value = false }

        scrollTo("Background data")
        compose
            .onNode(hasText("Background data") and hasText("No restriction detected"))
            .assertIsDisplayed()
        compose.onNodeWithText("Restricted on mobile data and metered Wi-Fi").assertDoesNotExist()
        scrollTo("Background activity")
        compose
            .onNode(hasText("Background activity") and hasText("No restriction detected"))
            .assertIsDisplayed()
    }

    @Test
    fun perAppMeteredRestrictionIsVisibleInForegroundAndRefreshesAfterRemoval() {
        assumeTrue(BACKGROUND_DELIVERY_SUPPORTED)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = context.applicationInfo.uid
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val globalStatus = shell("cmd netpolicy get restrict-background").trim()
        check(globalStatus.endsWith("enabled") || globalStatus.endsWith("disabled")) {
            "Unexpected Data Saver status: $globalStatus"
        }
        val dataSaverEnabled = globalStatus.endsWith("enabled")
        fun listed(list: String): Boolean =
            Regex("\\b$uid\\b").containsMatchIn(shell("cmd netpolicy list $list"))
        val wasRestricted = listed("restrict-background-blacklist")
        val wasExempt = listed("restrict-background-whitelist")
        val generation = mutableStateOf(0L)

        try {
            if (dataSaverEnabled) shell("cmd netpolicy set restrict-background false")
            // This is Android's per-UID POLICY_REJECT_METERED_BACKGROUND, independent
            // of global Data Saver. Foreground network access must not hide the policy.
            shell("cmd netpolicy add restrict-background-blacklist $uid")
            compose.waitUntil(timeoutMillis = 5_000) {
                connectivity.restrictBackgroundStatus ==
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            }
            compose.setContent {
                AgentknockTheme {
                    NotificationsSettings(
                        pushState = RelayPushRegistrationState.REGISTERED,
                        refreshGeneration = generation.value,
                        requestNotificationPermission = {},
                        onBack = {},
                        modifier = Modifier.fillMaxSize(),
                        report = {},
                    )
                }
            }
            scrollTo("Background data")
            compose
                .onNodeWithText("Restricted on mobile data and metered Wi-Fi")
                .assertIsDisplayed()

            shell("cmd netpolicy remove restrict-background-blacklist $uid")
            compose.waitUntil(timeoutMillis = 5_000) {
                connectivity.restrictBackgroundStatus !=
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            }
            compose.runOnIdle { generation.value++ }

            compose
                .onNode(hasText("Background data") and hasText("No restriction detected"))
                .assertIsDisplayed()
        } finally {
            shell("cmd netpolicy remove restrict-background-blacklist $uid")
            if (wasRestricted) shell("cmd netpolicy add restrict-background-blacklist $uid")
            if (wasExempt) shell("cmd netpolicy add restrict-background-whitelist $uid")
            if (dataSaverEnabled) shell("cmd netpolicy set restrict-background true")
        }
    }

    @Test
    fun blockedAppNotificationsKeepAppAndCategorySettingsAccessible() {
        val openedSettings = mutableListOf<String>()
        showSettings(
            appNotificationsEnabled = false,
            requestsEnabled = false,
            backgroundEnabled = false,
            openAppNotifications = { openedSettings += "app" },
            openChannel = { openedSettings += it },
        )

        scrollTo("Notifications blocked")
        compose.onNodeWithText("Notifications blocked").performClick()
        scrollTo("Requests needing action")
        compose.onNodeWithText("Requests needing action").performClick()
        scrollTo("Request processing")
        compose.onNodeWithText("Request processing").performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    "app",
                    RequestNotifications.ACTION_CHANNEL_ID,
                    RequestNotifications.PROCESSING_CHANNEL_ID,
                ),
                openedSettings,
            )
        }
    }

    @Test
    fun pushServiceFailureOverridesStaleRegistrationAndOffersRecovery() {
        assumeTrue(BACKGROUND_DELIVERY_SUPPORTED)
        var recoveryRequests = 0
        showSettings(
            // Registration can remain confirmed while Play services becomes unavailable.
            pushServiceIssue = PushServiceIssue("Google Play services is disabled", Intent()),
            resolvePushServiceIssue = { recoveryRequests++ },
        )

        scrollTo("Push registration")
        compose.onNodeWithText("Google Play services is disabled").assertIsDisplayed()
        compose.onNodeWithText("Registered").assertDoesNotExist()
        compose.onNodeWithText("Push registration").performClick()

        compose.runOnIdle { assertEquals(1, recoveryRequests) }
    }

    @Test
    fun foregroundOnlyBuildDoesNotOfferBackgroundDeliverySettings() {
        assumeFalse(BACKGROUND_DELIVERY_SUPPORTED)
        showSettings(
            backgroundDataRestricted = { true },
            backgroundActivityRestricted = { true },
        )

        scrollTo("Receive requests while the app is open")
        compose.onNodeWithText("Receive requests while the app is open").assertIsDisplayed()
        // Check both ends of the list so an offscreen lazy item cannot hide a regression.
        assertNoBackgroundDeliveryRows()
        scrollTo("Request processing")
        assertNoBackgroundDeliveryRows()
    }

    private fun assertNoBackgroundDeliveryRows() {
        compose.onNodeWithText("Push registration").assertDoesNotExist()
        compose.onNodeWithText("Background data").assertDoesNotExist()
        compose.onNodeWithText("Background activity").assertDoesNotExist()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun shell(command: String): String {
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText()
        }
    }

    private fun showSettings(
        pushState: () -> RelayPushRegistrationState? = { RelayPushRegistrationState.REGISTERED },
        appNotificationsEnabled: Boolean = true,
        requestsEnabled: Boolean = true,
        backgroundEnabled: Boolean = true,
        backgroundDataRestricted: () -> Boolean = { false },
        backgroundActivityRestricted: () -> Boolean? = { false },
        pushServiceIssue: PushServiceIssue? = null,
        resolvePushServiceIssue: () -> Unit = {},
        openAppNotifications: () -> Unit = {},
        openDataSettings: () -> Unit = {},
        openBatterySettings: () -> Unit = {},
        openChannel: (String) -> Unit = {},
    ) {
        compose.setContent {
            AgentknockTheme {
                NotificationsSettingsContent(
                    pushState = pushState(),
                    permissionGranted = true,
                    appNotificationsEnabled = appNotificationsEnabled,
                    requestsEnabled = requestsEnabled,
                    backgroundEnabled = backgroundEnabled,
                    requestNotificationPermission = {},
                    openChannel = openChannel,
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                    backgroundDataRestricted = backgroundDataRestricted(),
                    backgroundActivityRestricted = backgroundActivityRestricted(),
                    pushServiceIssue = pushServiceIssue,
                    resolvePushServiceIssue = resolvePushServiceIssue,
                    openAppNotifications = openAppNotifications,
                    openDataSettings = openDataSettings,
                    openBatterySettings = openBatterySettings,
                )
            }
        }
    }
}
