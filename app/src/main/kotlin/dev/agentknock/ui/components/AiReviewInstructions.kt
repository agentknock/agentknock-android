package dev.agentknock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.subscription.AiReviewAccess

internal enum class AiInstructionsScope(val title: String, val description: String) {
    GLOBAL("Global AI instructions", "Applies to every secret and client."),
    SECRET("Secret AI instructions", "Additional guidance for this secret."),
    CLIENT("Client AI instructions", "Additional guidance for this client."),
}

@Composable
internal fun AiReviewInstructions(
    value: String,
    access: AiReviewAccess,
    onEdit: () -> Unit,
    scope: AiInstructionsScope,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(access) {
        if (access == AiReviewAccess.ACTIVE) expanded = true
    }
    val showInstructions = expanded || value.isNotBlank() || access == AiReviewAccess.ACTIVE
    Surface(
        onClick = { if (showInstructions) onEdit() else expanded = true },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(scope.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Icon(
                    if (showInstructions) Icons.Outlined.Edit else Icons.Outlined.ExpandMore,
                    contentDescription = if (showInstructions) "Edit instructions" else "Show instructions",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(scope.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showInstructions) {
                Text(
                    value.ifBlank { "No instructions" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (access != AiReviewAccess.ACTIVE) {
                    Text(
                        "Used when AI review is active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A compact instructions card for list and detail pages: an icon, the scope title, the
 * instructions and the scope description as a caption. Same behaviour as
 * [AiReviewInstructions]: collapsed until opened when there is nothing to show and AI review
 * is inactive, otherwise a tap edits.
 */
@Composable
internal fun InstructionsCard(
    scope: AiInstructionsScope,
    value: String,
    access: AiReviewAccess,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(access) {
        if (access == AiReviewAccess.ACTIVE) expanded = true
    }
    val showInstructions = expanded || value.isNotBlank() || access == AiReviewAccess.ACTIVE
    Surface(
        onClick = { if (showInstructions) onEdit() else expanded = true },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(scope.title, style = MaterialTheme.typography.titleSmall)
                if (showInstructions) {
                    Text(
                        value.ifBlank { "No instructions" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (showInstructions && access != AiReviewAccess.ACTIVE) {
                        "${scope.description} Used when AI review is active."
                    } else {
                        scope.description
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (showInstructions) Icons.Outlined.Edit else Icons.Outlined.ExpandMore,
                contentDescription = if (showInstructions) "Edit instructions" else "Show instructions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
        }
    }
}
