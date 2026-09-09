package dev.agentknock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.device.DeviceIdentity
import dev.agentknock.storage.request.ClientDetails
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.auth.DeviceAuthenticationChoices
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.clients.ClientDetail
import dev.agentknock.ui.clients.ClientList
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DesignInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pairingSwitchRemainsTappableOnANarrowScreenWithLargeText() {
        val identity = mutableStateOf(testIdentity)
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.5f)
            ) {
                AgentknockTheme(darkTheme = true, dynamicColor = false) {
                    Box(Modifier.width(360.dp).fillMaxHeight()) {
                        ClientList(
                            clients = emptyList(),
                            pendingPairings = emptyList(),
                            selectedClientId = null,
                            selectedPairingRequestId = null,
                            identity = identity.value,
                            onOpen = {},
                            onOpenPairing = {},
                            onChangePairingAddress = {},
                            onSetPairingEnabled = {
                                changes += it
                                identity.value = identity.value.copy(pairingEnabled = it)
                            },
                            onOpenSettings = {},
                            report = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        val pairingSwitch = compose.onNodeWithContentDescription("Allow new pairings")
        pairingSwitch.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        pairingSwitch.assertIsDisplayed().assertIsOn().performTouchInput { click() }
        pairingSwitch.assertIsOff()
        compose.onNodeWithText("New pairings paused").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(false), changes) }

        pairingSwitch.assertIsDisplayed().performTouchInput { click() }
        pairingSwitch.assertIsOn()
        compose.onNodeWithText("New pairings on").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(false, true), changes) }
    }

    @Test
    fun clientInformationRevealsAndHidesTheClientId() {
        showClientDetail()

        val information = compose.onNodeWithText("Client information")
        information.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")
        )
        compose.onNodeWithText(testClient.clientId).assertDoesNotExist()

        information.performScrollTo().performClick()
        information.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")
        )
        compose.onNodeWithText(testClient.clientId).performScrollTo().assertIsDisplayed()

        information.performScrollTo().performClick()
        information.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")
        )
        compose.onNodeWithText(testClient.clientId).assertDoesNotExist()
    }

    @Test
    fun suspendRequestsTheSuspendedState() {
        val changes = mutableListOf<RelayClientState>()
        showClientDetail(onSetState = { changes += it })

        compose.onNodeWithText("Suspend").performClick()

        compose.runOnIdle { assertEquals(listOf(RelayClientState.SUSPENDED), changes) }
    }

    @Test
    fun revokeRequiresConfirmationAndCancelLeavesTheClientUnchanged() {
        val changes = mutableListOf<RelayClientState>()
        showClientDetail(onSetState = { changes += it })

        compose.onNodeWithText("Revoke client…").performScrollTo().performClick()
        compose.onNodeWithText("Revoke Work laptop?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<RelayClientState>(), changes) }

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Revoke Work laptop?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<RelayClientState>(), changes) }

        compose.onNodeWithText("Revoke client…").performScrollTo().performClick()
        compose.onNodeWithText("Revoke").performClick()
        compose.onNodeWithText("Revoke Work laptop?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(RelayClientState.REVOKED), changes) }
    }

    @Test
    fun authenticationChoicesExposeSelectionAndIgnoreTouchesWhileDisabled() {
        val selected = mutableStateOf(DeviceAuthenticationMode.DEVICE_LOCK)
        val enabled = mutableStateOf(true)
        val changes = mutableListOf<DeviceAuthenticationMode>()
        compose.setContent {
            AgentknockTheme(darkTheme = true, dynamicColor = false) {
                DeviceAuthenticationChoices(
                    selected = selected.value,
                    enabled = enabled.value,
                    onSelect = {
                        changes += it
                        selected.value = it
                    },
                )
            }
        }

        compose.onNodeWithText("Rely on device lock").assertIsSelected()
        compose.onNodeWithText("Lock Agentknock").performClick().assertIsSelected()
        compose.onNodeWithText("Rely on device lock").assertIsNotSelected()
        compose.runOnIdle { enabled.value = false }

        for (label in
            listOf("Rely on device lock", "Protect sensitive actions", "Lock Agentknock")) {
            compose.onNodeWithText(label).assertIsNotEnabled()
        }
        compose.onNodeWithText("Protect sensitive actions").performTouchInput { click() }
        compose.onNodeWithText("Lock Agentknock").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(DeviceAuthenticationMode.APP_LOCK), changes) }
    }

    private fun showClientDetail(onSetState: (RelayClientState) -> Unit = {}) {
        compose.setContent {
            AgentknockTheme(darkTheme = true, dynamicColor = false) {
                ClientDetail(
                    client = testClient,
                    temporaryAccessGrants = emptyList(),
                    aiReviewAccess = AiReviewAccess.ACTIVE,
                    onBack = {},
                    showBack = true,
                    onRename = {},
                    onSetState = onSetState,
                    onSaveInstructions = {},
                    onEndTemporaryAccess = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private val testIdentity =
    DeviceIdentity(
        id = "test-identity",
        address = "amber-river-maple",
        deviceId = "test-device",
        credentialsAvailable = true,
        pairingEnabled = true,
        createdAt = 1_704_067_200_000,
        instructions = "",
    )

private val testClient =
    ClientDetails(
        clientId = "test-client-id",
        name = "Work laptop",
        hostname = "workstation",
        platform = "linux",
        architecture = "x86_64",
        osVersion = "NixOS",
        machineId = "test-machine",
        clientSoftware = null,
        instructions = "",
        state = RelayClientState.ACTIVE,
        desiredState = null,
        pairedAt = 1_704_067_200_000,
        lastRequestAt = null,
    )
