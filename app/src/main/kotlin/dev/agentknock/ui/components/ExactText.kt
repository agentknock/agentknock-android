package dev.agentknock.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily

/** Preserve actual line boundaries for signed objects and structured log output. */
@Composable
internal fun ExactText(value: String) {
    Column {
        Text(
            "Scroll horizontally to read long lines. Select text to copy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                value,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    }
}
