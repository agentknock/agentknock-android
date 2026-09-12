package dev.agentknock.ui.secrets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ApprovalModeRowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun freeUsersCanExploreAiWithoutChangingTheirApprovalMode() {
        val access = mutableStateOf(AiReviewAccess.INACTIVE)
        val selected = mutableStateOf(SecretApprovalMode.ASK_ME)
        var plansOpened = 0
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Default for all clients",
                    selected = selected.value,
                    inherited = false,
                    defaultMode = selected.value,
                    aiReviewAccess = access.value,
                    onSelect = { selected.value = it },
                    onOpenPlan = { plansOpened++ },
                )
            }
        }
        compose
            .onNodeWithText("AI")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Requires subscription",
                )
            )
        compose.onNodeWithText("AI").performClick()
        compose.onNodeWithText("View plans").performClick()
        assertEquals(1, plansOpened)
        assertEquals(SecretApprovalMode.ASK_ME, selected.value)

        compose.runOnIdle { access.value = AiReviewAccess.ACTIVE }
        compose.onNodeWithText("Ask").assertIsSelected()
        compose.onNodeWithText("AI").performClick()
        assertEquals(SecretApprovalMode.ASK_AI, selected.value)
    }

    @Test
    fun expiryPreservesAiPreferenceButAnExplicitManualChoiceSurvivesRenewal() {
        val access = mutableStateOf(AiReviewAccess.ACTIVE)
        val selected = mutableStateOf(SecretApprovalMode.ASK_AI)
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Default for all clients",
                    selected = selected.value,
                    inherited = false,
                    defaultMode = selected.value,
                    aiReviewAccess = access.value,
                    onSelect = { selected.value = it },
                    onOpenPlan = {},
                )
            }
        }
        compose.runOnIdle { access.value = AiReviewAccess.INACTIVE }
        compose.onNodeWithText("AI").assertIsSelected()
        compose.onNodeWithText("Ask").performClick()
        compose.runOnIdle { access.value = AiReviewAccess.ACTIVE }
        compose.onNodeWithText("Ask").assertIsSelected()
        assertEquals(SecretApprovalMode.ASK_ME, selected.value)
    }

    @Test
    fun unknownAndActivatingAccessNeverOfferAnUpgradeOrBlockManualChoices() {
        val access = mutableStateOf(AiReviewAccess.CHECKING)
        val selected = mutableStateOf(SecretApprovalMode.ASK_AI)
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Default for all clients",
                    selected = selected.value,
                    inherited = false,
                    defaultMode = selected.value,
                    aiReviewAccess = access.value,
                    onSelect = { selected.value = it },
                    onOpenPlan = { error("No plan navigation") },
                )
            }
        }
        for (state in
            listOf(
                AiReviewAccess.CHECKING,
                AiReviewAccess.ACTIVATING,
                AiReviewAccess.UNAVAILABLE,
            )) {
            compose.runOnIdle { access.value = state }
            compose.onNodeWithText("AI").assertIsNotEnabled()
            compose.onNodeWithText("Deny").performClick()
            compose.onNodeWithText("Ask").performClick()
            assertEquals(SecretApprovalMode.ASK_ME, selected.value)
        }
    }

    @Test
    fun aDismissedSubscriptionExplanationNeverReappearsOnLaterExpiry() {
        val access = mutableStateOf(AiReviewAccess.INACTIVE)
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Default for all clients",
                    selected = SecretApprovalMode.ASK_ME,
                    inherited = false,
                    defaultMode = SecretApprovalMode.ASK_ME,
                    aiReviewAccess = access.value,
                    onSelect = {},
                    onOpenPlan = {},
                )
            }
        }
        compose.onNodeWithText("AI").performClick()
        compose.onNodeWithText("View plans").assertIsDisplayed()
        compose.runOnIdle { access.value = AiReviewAccess.ACTIVE }
        compose.onNodeWithText("View plans").assertDoesNotExist()
        compose.runOnIdle { access.value = AiReviewAccess.INACTIVE }
        compose.onNodeWithText("View plans").assertDoesNotExist()
    }

    @Test
    fun inheritingAnAiDefaultUsesTheSameSubscriptionGate() {
        var defaultUsed = false
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Laptop",
                    selected = SecretApprovalMode.ASK_ME,
                    inherited = false,
                    defaultMode = SecretApprovalMode.ASK_AI,
                    aiReviewAccess = AiReviewAccess.INACTIVE,
                    onSelect = {},
                    onOpenPlan = {},
                    onUseDefault = { defaultUsed = true },
                )
            }
        }
        compose.onNodeWithText("Use default").performClick()
        compose.onNodeWithText("View plans").assertIsDisplayed()
        assertEquals(false, defaultUsed)
    }

    @Test
    fun anInheritedSettingHighlightsTheDefaultAndStillAllowsAnExplicitMatchingOverride() {
        val inherited = mutableStateOf(true)
        val selected = mutableStateOf(SecretApprovalMode.ASK_ME)
        compose.setContent {
            AgentknockTheme {
                ApprovalModeRow(
                    title = "Work laptop",
                    isClient = true,
                    selected = selected.value,
                    inherited = inherited.value,
                    defaultMode = SecretApprovalMode.ASK_ME,
                    aiReviewAccess = AiReviewAccess.ACTIVE,
                    onSelect = {
                        selected.value = it
                        inherited.value = false
                    },
                    onOpenPlan = {},
                    onUseDefault =
                        if (inherited.value) null
                        else {
                            {
                                selected.value = SecretApprovalMode.ASK_ME
                                inherited.value = true
                            }
                        },
                )
            }
        }
        compose.onNodeWithText("Ask").assertIsSelected()
        compose.onNodeWithText("Use default").assertDoesNotExist()
        compose.onNodeWithText("Ask").performClick()
        assertEquals(false, inherited.value)
        compose.onNodeWithText("Ask").assertIsSelected()
        compose.onNodeWithText("Deny").performClick()
        compose.onNodeWithText("Deny").assertIsSelected()
        compose.onNodeWithText("Ask").assertIsNotSelected()
        compose.onNodeWithText("Use default").performClick()
        assertEquals(true, inherited.value)
        compose.onNodeWithText("Ask").assertIsSelected()
        compose.onNodeWithText("Deny").assertIsNotSelected()
        compose.onNodeWithText("Use default").assertDoesNotExist()
    }

    @Test
    fun narrowControlKeepsApprovalChoicesReadableAtLargeText() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.3f)
            ) {
                AgentknockTheme {
                    // A 320 dp phone, including the screen's 20 dp and card's 16 dp side padding.
                    Box(Modifier.width(320.dp).padding(horizontal = 36.dp)) {
                        ApprovalModeRow(
                            title = "Default for all clients",
                            selected = SecretApprovalMode.ASK_AI,
                            inherited = false,
                            defaultMode = SecretApprovalMode.ASK_AI,
                            aiReviewAccess = AiReviewAccess.INACTIVE,
                            onSelect = {},
                            onOpenPlan = {},
                        )
                    }
                }
            }
        }
        for (label in listOf("Deny", "Ask", "AI", "Allow")) {
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
            node.assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val segment = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$label fits its control",
                bounds.left >= segment.left &&
                    bounds.right <= segment.right &&
                    bounds.top >= segment.top &&
                    bounds.bottom <= segment.bottom,
            )
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // GetTextLayoutResult recreates a paragraph at the parent's maximum width, even
            // when Text draws at its smaller intrinsic width. Check the label's required width.
            assertTrue(
                "$label fits without wrapping or clipping",
                layout.multiParagraph.maxIntrinsicWidth <= layout.size.width,
            )
            assertTrue("$label fits vertically", !layout.didOverflowHeight)
            assertTrue(
                "$label is not ellipsized",
                (0 until layout.lineCount).none { layout.isLineEllipsized(it) },
            )
        }
    }
}
