package dev.agentknock.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver

@Composable
internal fun ActionListSurface(
    actionRequired: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else if (actionRequired) {
                MaterialTheme.colorScheme.primary
                    .copy(alpha = 0.08f)
                    .compositeOver(MaterialTheme.colorScheme.surface)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}
