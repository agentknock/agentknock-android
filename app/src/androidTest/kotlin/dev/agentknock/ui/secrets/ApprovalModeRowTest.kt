package dev.agentknock.ui.secrets

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ApprovalModeRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun freeUsersCanExploreAiWithoutChangingTheirApprovalMode() {
        val access = mutableStateOf(AiReviewAccess.INACTIVE)
        val selected = mutableStateOf(SecretApprovalMode.ASK_ME)
        var plansOpened = 0
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Default for future uses", selected = selected.value, inherited = false,
                    defaultMode = selected.value, aiReviewAccess = access.value,
                    onSelect = { selected.value = it }, onOpenPlan = { plansOpened++ },
                )
            }
        }
        compose.onNodeWithText("Requires subscription").assertIsDisplayed()
        compose.onNodeWithText("Ask AI").performClick()
        compose.onNodeWithText("View plans").performClick()
        assertEquals(1, plansOpened)
        assertEquals(SecretApprovalMode.ASK_ME, selected.value)

        compose.runOnIdle { access.value = AiReviewAccess.ACTIVE }
        compose.onNodeWithText("Ask me").assertIsSelected()
        compose.onNodeWithText("Ask AI").performClick()
        assertEquals(SecretApprovalMode.ASK_AI, selected.value)
    }

    @Test fun expiryPreservesAiPreferenceButAnExplicitManualChoiceSurvivesRenewal() {
        val access = mutableStateOf(AiReviewAccess.ACTIVE)
        val selected = mutableStateOf(SecretApprovalMode.ASK_AI)
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Default for future uses", selected = selected.value, inherited = false,
                    defaultMode = selected.value, aiReviewAccess = access.value,
                    onSelect = { selected.value = it }, onOpenPlan = {},
                )
            }
        }
        compose.runOnIdle { access.value = AiReviewAccess.INACTIVE }
        compose.onNodeWithText("Ask AI").assertIsSelected()
        compose.onNodeWithText("Inactive").assertIsDisplayed()
        compose.onNodeWithText("Requests will ask you instead. Your Ask AI setting will resume when AI review is active.")
            .assertIsDisplayed()
        compose.onNodeWithText("Ask me").performClick()
        compose.runOnIdle { access.value = AiReviewAccess.ACTIVE }
        compose.onNodeWithText("Ask me").assertIsSelected()
        assertEquals(SecretApprovalMode.ASK_ME, selected.value)
    }

    @Test fun unknownAndActivatingAccessNeverOfferAnUpgradeOrBlockManualChoices() {
        val access = mutableStateOf(AiReviewAccess.CHECKING)
        val selected = mutableStateOf(SecretApprovalMode.ASK_AI)
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Default for future uses", selected = selected.value, inherited = false,
                    defaultMode = selected.value, aiReviewAccess = access.value,
                    onSelect = { selected.value = it }, onOpenPlan = { error("No plan navigation") },
                )
            }
        }
        for (state in listOf(AiReviewAccess.CHECKING, AiReviewAccess.ACTIVATING, AiReviewAccess.UNAVAILABLE)) {
            compose.runOnIdle { access.value = state }
            compose.onNodeWithText("Ask AI").assertIsNotEnabled()
            compose.onNodeWithText("Requires subscription").assertDoesNotExist()
            compose.onNodeWithText("Inactive").assertDoesNotExist()
            compose.onNodeWithText("Deny").performClick()
            compose.onNodeWithText("Ask me").performClick()
            assertEquals(SecretApprovalMode.ASK_ME, selected.value)
        }
    }

    @Test fun aDismissedSubscriptionExplanationNeverReappearsOnLaterExpiry() {
        val access = mutableStateOf(AiReviewAccess.INACTIVE)
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Default for future uses", selected = SecretApprovalMode.ASK_ME,
                    inherited = false, defaultMode = SecretApprovalMode.ASK_ME,
                    aiReviewAccess = access.value, onSelect = {}, onOpenPlan = {},
                )
            }
        }
        compose.onNodeWithText("Ask AI").performClick()
        compose.onNodeWithText("View plans").assertIsDisplayed()
        compose.runOnIdle { access.value = AiReviewAccess.ACTIVE }
        compose.onNodeWithText("View plans").assertDoesNotExist()
        compose.runOnIdle { access.value = AiReviewAccess.INACTIVE }
        compose.onNodeWithText("View plans").assertDoesNotExist()
    }

    @Test fun inheritingAnAiDefaultUsesTheSameSubscriptionGate() {
        var defaultUsed = false
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Laptop", selected = SecretApprovalMode.ASK_ME, inherited = false,
                    defaultMode = SecretApprovalMode.ASK_AI, aiReviewAccess = AiReviewAccess.INACTIVE,
                    onSelect = {}, onOpenPlan = {}, onUseDefault = { defaultUsed = true },
                )
            }
        }
        compose.onNodeWithText("Use default").performClick()
        compose.onNodeWithText("View plans").assertIsDisplayed()
        assertEquals(false, defaultUsed)
    }
}
