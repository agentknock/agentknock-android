package dev.agentknock.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProseEditorScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun ownerAndUnsavedInstructionsRemainClearWhenLeavingEditor() {
        val instructions = mutableStateOf("Read-only queries only.")
        var saved: String? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                ProseEditorScreen(
                    title = "Secret instructions",
                    owner = "production-db",
                    value = instructions.value,
                    originalValue = "Read-only queries only.",
                    supportingText = "Applies to AI review of this secret.",
                    onValueChange = { instructions.value = it },
                    onSave = { saved = instructions.value },
                    onBack = { dismissed = true },
                )
            }
        }
        compose.onNodeWithText("production-db").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Instructions").performTextReplacement("Ask before exporting data.")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.runOnIdle { assertFalse(dismissed) }
        // Test the Save action without depending on dialog/IME window touch coordinates.
        compose
            .onNodeWithText("Save")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals("Ask before exporting data.", saved) }
    }
}
