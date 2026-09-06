package dev.agentknock.ui.secrets

import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableEditorStateTest {
    @Test
    fun `new variables submit their value even when empty`() {
        assertTrue(editor.copy(variable = null, currentValue = null, value = "").valueChanged)
    }

    @Test
    fun `metadata changes do not replace an unchanged stored value`() {
        assertFalse(editor.copy(name = "RENAMED", sensitive = false).valueChanged)
        assertFalse(editor.copy(valueEdited = true).valueChanged)
        assertFalse(editor.copy(currentValue = "", value = "", valueEdited = true).valueChanged)
    }

    @Test
    fun `changed available values include an explicitly empty replacement`() {
        assertTrue(editor.copy(value = "replacement").valueChanged)
        assertTrue(editor.copy(value = "").valueChanged)
    }

    @Test
    fun `unavailable values are only replaced after editing`() {
        val unavailable = editor.copy(currentValue = null, value = "")

        assertFalse(unavailable.valueChanged)
        assertFalse(unavailable.copy(name = "RENAMED", sensitive = false).valueChanged)
        assertTrue(unavailable.copy(valueEdited = true).valueChanged)
        assertTrue(unavailable.copy(value = "replacement", valueEdited = true).valueChanged)
    }

    @Test
    fun `changing sensitivity does not make an originally sensitive draft safe to retain`() {
        assertTrue(editor.copy(sensitive = false).containsSensitiveData)
        assertTrue(editor.copy(variable = editor.variable!!.copy(sensitive = false)).containsSensitiveData)
        assertFalse(
            editor.copy(
                variable = editor.variable.copy(sensitive = false),
                sensitive = false,
            ).containsSensitiveData,
        )
    }

    private val editor = VariableEditorState(
        secretId = "secret-id",
        secretName = "secret",
        variable = EnvironmentVariableMetadata(
            id = "variable-id",
            secretId = "secret-id",
            name = "VARIABLE",
            sensitive = true,
            valueAvailable = true,
            valueUpdatedAt = 1L,
        ),
        currentValue = "original",
        name = "VARIABLE",
        value = "original",
        valueEdited = false,
        sensitive = true,
    )
}
