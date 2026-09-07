package dev.agentknock.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.agentknock.ui.components.NoticeTone
import dev.agentknock.ui.theme.agentknockColors

/*
 * Presentation shared by the request detail screens: the status header, the identity
 * columns at the top of a request ticket, the quoted client reason, and section titles.
 * Behavioural helpers stay in RequestDetailComponents.kt.
 */

internal data class StatusSummary(
    val title: String,
    val tone: NoticeTone = NoticeTone.NEUTRAL,
    val icon: ImageVector? = null,
    val detail: String? = null,
)

@Composable
internal fun StatusHeader(status: StatusSummary, requestedAt: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(status)
            Text(
                requestedAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Requested $requestedAt"
                },
            )
        }
        status.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusChip(status: StatusSummary, modifier: Modifier = Modifier) {
    val semanticColors = MaterialTheme.agentknockColors
    val container = when (status.tone) {
        NoticeTone.NEUTRAL, NoticeTone.SUBDUED -> MaterialTheme.colorScheme.surfaceContainerHighest
        NoticeTone.ATTENTION -> semanticColors.attentionContainer
        NoticeTone.SUCCESS -> semanticColors.successContainer
        NoticeTone.DANGER -> semanticColors.dangerContainer
    }
    val content = when (status.tone) {
        NoticeTone.NEUTRAL, NoticeTone.SUBDUED -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeTone.ATTENTION -> semanticColors.onAttentionContainer
        NoticeTone.SUCCESS -> semanticColors.onSuccessContainer
        NoticeTone.DANGER -> semanticColors.onDangerContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(100.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = if (status.icon == null) 12.dp else 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            status.icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(status.title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * Client and secret identities side by side, or stacked when a name would not fit half the
 * width. [secretRole] names the secret's part in this request, such as "Signing key".
 */
@Composable
internal fun IdentityColumns(
    clientName: String,
    secretNames: List<String>,
    secretRole: String = "Secret",
    secretsRole: String = "Secrets",
) {
    val textMeasurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.titleMedium
    val density = LocalDensity.current
    val secretLabel = if (secretNames.size == 1) secretRole else secretsRole
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = with(density) { ((maxWidth - 16.dp) / 2 - 26.dp).roundToPx() }
        val stack = (listOf(clientName) + secretNames).any { name ->
            textMeasurer.measure(name, nameStyle, softWrap = false).size.width > columnWidth
        }
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Identity(Icons.Outlined.Computer, "Client", listOf(clientName))
                if (secretNames.isNotEmpty()) Identity(Icons.Outlined.Key, secretLabel, secretNames)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Identity(Icons.Outlined.Computer, "Client", listOf(clientName), Modifier.weight(1f))
                if (secretNames.isNotEmpty()) {
                    Identity(Icons.Outlined.Key, secretLabel, secretNames, Modifier.weight(1f))
                }
            }
        }
    }
}

/** One or more names with an icon and the role shown beneath, announced as "role names". */
@Composable
internal fun Identity(
    icon: ImageVector,
    role: String,
    names: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "$role ${names.joinToString(", ")}"
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            names.forEach { name ->
                Text(name.breakableAtHyphens(), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                role,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ReasonQuote(reason: String, clientName: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .padding(vertical = 2.dp)
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer {
                Text(reason, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Reason reported by $clientName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Lets long hyphenated names wrap after a hyphen instead of mid-word. */
internal fun String.breakableAtHyphens(): String = replace("-", "-\u200B")
