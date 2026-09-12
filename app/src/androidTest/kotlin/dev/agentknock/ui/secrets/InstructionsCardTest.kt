package dev.agentknock.ui.secrets

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.components.AiInstructionsScope
import dev.agentknock.ui.components.InstructionsCard
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InstructionsCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savedInstructionsStayVisibleAndEditableAfterAccessExpires() {
        val access = mutableStateOf(AiReviewAccess.ACTIVE)
        var edits = 0
        compose.setContent {
            AgentknockTheme {
                InstructionsCard(
                    AiInstructionsScope.SECRET,
                    "Only approve deployments to staging.",
                    access.value,
                    { edits++ },
                )
            }
        }
        compose.runOnIdle { access.value = AiReviewAccess.INACTIVE }
        compose.onNodeWithText("Only approve deployments to staging.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit instructions").performClick()
        assertEquals(1, edits)
    }

    @Test
    fun emptyInstructionsAreCollapsedForFreeUsersAndCanBePreparedWithoutSubscribing() {
        var edits = 0
        compose.setContent {
            AgentknockTheme {
                InstructionsCard(
                    AiInstructionsScope.SECRET,
                    "",
                    AiReviewAccess.INACTIVE,
                    { edits++ },
                )
            }
        }
        compose.onNodeWithText("No instructions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show instructions").performClick()
        compose.onNodeWithText("No instructions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit instructions").performClick()
        assertEquals(1, edits)
    }
}
