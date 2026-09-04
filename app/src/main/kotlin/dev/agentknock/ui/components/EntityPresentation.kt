package dev.agentknock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun ClientIdentity(
    name: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = "Client $name" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Computer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            name,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SecretIdentities(
    names: List<String>,
    modifier: Modifier = Modifier,
    unavailable: Boolean = false,
    subdued: Boolean = false,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        names.forEach { name -> SecretIdentity(name, unavailable = unavailable, subdued = subdued) }
    }
}

@Composable
internal fun SecretIdentity(
    name: String,
    modifier: Modifier = Modifier,
    unavailable: Boolean = false,
    subdued: Boolean = false,
) {
    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (unavailable) "Unavailable secret $name" else "Secret $name"
        },
        color = if (unavailable) {
            MaterialTheme.agentknockColors.dangerContainer
        } else if (subdued) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        contentColor = if (unavailable) {
            MaterialTheme.agentknockColors.onDangerContainer
        } else if (subdued) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(name, style = MaterialTheme.typography.labelMedium)
        }
    }
}
