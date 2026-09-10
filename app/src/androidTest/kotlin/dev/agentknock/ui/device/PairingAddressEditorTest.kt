package dev.agentknock.ui.device

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingAddressEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun typingKeepsCursorAndSuggestionMovesItToEnd() {
        val address = mutableStateOf("amber-river-maple")
        compose.setContent {
            MaterialTheme {
                PairingAddressEditor(
                    address = address.value,
                    activeAddress = "amber-river-maple",
                    candidateAddress = null,
                    claiming = false,
                    result = null,
                    onAddressChange = { address.value = it },
                    onGenerate = { address.value = "ocean-feather-sunset" },
                    onSubmit = {},
                    beforeSubmit = {},
                )
            }
        }
        compose.onNodeWithText("Change pairing address").assertIsNotEnabled()
        val field = compose.onNodeWithText("Pairing address")
        field.performTextInputSelection(TextRange(2))
        field.performTextInput("x")
        field.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3))
        )
        compose.onNodeWithText("Change pairing address").assertIsEnabled()
        // Exercise the suggestion action without depending on the IME window's touch coordinates.
        compose
            .onNodeWithText("Another suggestion")
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        field.assert(
            SemanticsMatcher("Pairing address is the generated suggestion") {
                it.config[SemanticsProperties.EditableText].text == "ocean-feather-sunset"
            }
        )
        field.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(20))
        )
    }
}
