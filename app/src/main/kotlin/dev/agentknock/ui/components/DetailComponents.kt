@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun DetailPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    showBack: Boolean,
    scrollResetKey: Any? = null,
    titleContent: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
    bottomContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    var previousScrollResetKey by remember { mutableStateOf(scrollResetKey) }
    LaunchedEffect(scrollResetKey) {
        if (previousScrollResetKey != scrollResetKey) {
            previousScrollResetKey = scrollResetKey
            scrollState.scrollTo(0)
        }
    }
    Column(modifier) {
        TopAppBar(
            title = titleContent,
            navigationIcon = {
                if (showBack) {
                    NavigationBackButton(onBack)
                }
            },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
        bottomContent?.invoke()
    }
}

@Composable
internal fun Disclosure(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded }
                    .semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun Notice(
    title: String,
    detail: String,
    tone: NoticeTone = NoticeTone.NEUTRAL,
) {
    val semanticColors = MaterialTheme.agentknockColors
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (tone) {
                NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerLow
                NoticeTone.ATTENTION -> semanticColors.attentionContainer
                NoticeTone.SUCCESS -> semanticColors.successContainer
                NoticeTone.DANGER -> semanticColors.dangerContainer
                NoticeTone.SUBDUED -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = when (tone) {
                NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
                NoticeTone.ATTENTION -> semanticColors.onAttentionContainer
                NoticeTone.SUCCESS -> semanticColors.onSuccessContainer
                NoticeTone.DANGER -> semanticColors.onDangerContainer
                NoticeTone.SUBDUED -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail)
        }
    }
}

@Composable
internal fun StatusLine(
    label: String,
    error: Boolean = false,
    attention: Boolean = false,
    subdued: Boolean = false,
) {
    val semanticColors = MaterialTheme.agentknockColors
    Surface(
        color = when {
            error -> semanticColors.dangerContainer
            attention -> semanticColors.attentionContainer
            subdued -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> semanticColors.successContainer
        },
        contentColor = when {
            error -> semanticColors.onDangerContainer
            attention -> semanticColors.onAttentionContainer
            subdued -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> semanticColors.onSuccessContainer
        },
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
internal fun DetailValue(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(value, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default)
        }
    }
}

internal enum class NoticeTone {
    NEUTRAL,
    ATTENTION,
    SUCCESS,
    DANGER,
    SUBDUED,
}
