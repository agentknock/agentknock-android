package dev.agentknock.ui.secrets

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvironmentVariableFieldsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sensitivityAndVisibilityControlMaskingWithoutChangingTheValue() {
        val value = mutableStateOf("initial value")
        val sensitive = mutableStateOf(true)
        val visible = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Column {
                    EnvironmentVariableValueField(
                        value = value.value,
                        sensitive = sensitive.value,
                        visible = visible.value,
                        enabled = enabled.value,
                        onValueChange = { value.value = it },
                        onVisibilityChange = { visible.value = it },
                    )
                    EnvironmentVariableSensitivity(
                        sensitive = sensitive.value,
                        enabled = enabled.value,
                        onChange = { sensitive.value = it },
                    )
                }
            }
        }
        val field = compose.onNodeWithText("Value")
        fun renderedText(): String {
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.text.text
        }
        val masked = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        field.assert(masked)
        assertNotEquals(value.value, renderedText())
        field.performTextReplacement("line one\nline two")
        compose.onNodeWithContentDescription("Show").performClick()
        assertEquals(value.value, renderedText())
        compose.onNodeWithContentDescription("Hide").performClick()
        field.assert(masked)
        assertNotEquals(value.value, renderedText())
        compose.onNodeWithText("Sensitive").performClick()
        compose.runOnIdle { assertFalse(sensitive.value) }
        assertEquals(value.value, renderedText())
        compose.onNodeWithContentDescription("Show").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("line one\nline two", value.value)
            enabled.value = false
        }
        field.assertIsNotEnabled()
        compose.onNodeWithText("Sensitive").assertIsNotEnabled()
    }
}
