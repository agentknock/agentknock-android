@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.storage.audit.AuditCategory
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.theme.agentknockColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class AuditFilter(val label: String) {
    ALL("All"),
    SECRET_USE("Secret use"),
    UPLOADS("Uploads"),
    PAIRINGS("Pairings"),
}

@Composable
internal fun AuditBrowser(
    events: List<AuditEvent>,
    selected: AuditEvent?,
    clients: List<ClientSummary>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val clientNames = clients.associate { it.clientId to it.name }
    BoxWithConstraints(modifier) {
        val twoPane = maxWidth >= 840.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                AuditList(
                    events = events,
                    selectedEventId = selected?.id,
                    onBack = onBack,
                    onOpen = onOpen,
                    clientNames = clientNames,
                    modifier = Modifier.width(440.dp).fillMaxHeight(),
                )
                VerticalDivider()
                if (selected == null) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            "Select an event to view its details",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    AuditDetail(
                        event = selected,
                        clientName = selected.clientId?.let(clientNames::get),
                        report = report,
                        onBack = onBack,
                        showBack = false,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        } else if (selected == null) {
            AuditList(
                events = events,
                selectedEventId = null,
                onBack = onBack,
                onOpen = onOpen,
                clientNames = clientNames,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AuditDetail(
                event = selected,
                clientName = selected.clientId?.let(clientNames::get),
                report = report,
                onBack = onBack,
                showBack = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AuditList(
    events: List<AuditEvent>,
    selectedEventId: Long?,
    clientNames: Map<String, String>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(AuditFilter.ALL) }
    val visibleEvents = events.filter(filter::matches)
    val dayGroups = visibleEvents.groupBy { it.occurredAt.auditDate() }
    Column(modifier) {
        PageTopBar("Audit log", onBack)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(AuditFilter.entries, key = AuditFilter::name) { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                )
            }
        }
        if (visibleEvents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (events.isEmpty()) "No security activity recorded yet" else "No matching activity",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                dayGroups.forEach { (day, dayEvents) ->
                    item(key = "day_$day") {
                        Column {
                            SettingsSectionLabel(day)
                            SettingsGroup {
                                dayEvents.forEachIndexed { index, event ->
                                    AuditTimelineRow(
                                        event = event,
                                        clientName = event.clientId?.let(clientNames::get),
                                        selected = event.id == selectedEventId,
                                        onClick = { onOpen(event.id) },
                                    )
                                    if (index != dayEvents.lastIndex) SettingsGroupDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditTimelineRow(
    event: AuditEvent,
    clientName: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = event.contextLine(clientName)
    val accent = event.outcome.accentColor()
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            event.occurredAt.auditTime(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(66.dp).padding(top = 2.dp),
        )
        Surface(
            color = accent,
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(10.dp),
        ) {}
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    event.outcome.displayName(),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
            Text(
                context ?: event.category.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AuditDetail(
    event: AuditEvent,
    clientName: String?,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    fun copy(label: String, value: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(label, value),
        )
        report("$label copied")
    }

    Column(modifier) {
        PageTopBar("Audit event", onBack, showBack)
        SelectionContainer {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        formatTimestamp(event.occurredAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(event.title, style = MaterialTheme.typography.headlineSmall)
                    AuditOutcomeBadge(event.outcome)
                }
                InformationSurface {
                    InformationRow("Event type", event.category.displayName)
                    InformationRow("Outcome", event.outcome.displayName())
                    event.detail.takeIf(String::isNotBlank)?.let { detail ->
                        InformationRow(event.detailLabel(), detail)
                    }
                    clientName?.takeUnless { it == event.detail }?.let {
                        InformationRow("Client", it)
                    }
                }
                if (event.clientId != null || event.relayRequestId != null) {
                    InformationSurface {
                        Text("Technical information", style = MaterialTheme.typography.titleMedium)
                        event.clientId?.let {
                            InformationRow(
                                label = "Client ID",
                                value = it,
                                monospace = true,
                                trailingContent = { CopyTextButton { copy("Client ID", it) } },
                            )
                        }
                        event.relayRequestId?.let {
                            InformationRow(
                                label = "Request ID",
                                value = it,
                                monospace = true,
                                trailingContent = { CopyTextButton { copy("Request ID", it) } },
                            )
                        }
                        InformationRow("Sequence", event.id.toString(), monospace = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyTextButton(onClick: () -> Unit) {
    Text(
        "Copy",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
    )
}

@Composable
private fun AuditOutcomeBadge(outcome: AuditOutcome) {
    val positive = outcome == AuditOutcome.APPROVED ||
        outcome == AuditOutcome.COMPLETED || outcome == AuditOutcome.CHANGED
    val failure = outcome == AuditOutcome.FAILED
    val container = when {
        positive -> MaterialTheme.agentknockColors.successContainer
        failure -> MaterialTheme.agentknockColors.dangerContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        positive -> MaterialTheme.agentknockColors.onSuccessContainer
        failure -> MaterialTheme.agentknockColors.onDangerContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge) {
        Text(
            outcome.displayName(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

private fun AuditEvent.contextLine(clientName: String?): String? {
    val detailContext = detail.takeIf(String::isNotBlank)?.let { "${detailLabel()}: $it" }
    val clientContext = clientName?.takeUnless { it == detail }?.let { "Client: $it" }
    return listOfNotNull(detailContext, clientContext).joinToString(" · ").ifBlank { null }
}

private fun AuditEvent.detailLabel(): String = when (category) {
    AuditCategory.PAIRING,
    AuditCategory.CLIENT,
    -> "Client"
    AuditCategory.SECRET -> when {
        title.startsWith("Environment variable") -> "Environment variable"
        else -> "Secret"
    }
    AuditCategory.SECRET_USE -> "Secret"
    AuditCategory.GIT_SIGN -> "Git signing"
    AuditCategory.SECRET_LIST -> "Secret list"
    AuditCategory.SECRET_UPLOAD -> "Secret upload"
    AuditCategory.DEVICE -> if (title.contains("address", ignoreCase = true)) "Pairing address" else "Change"
    AuditCategory.VERIFICATION -> "Reason"
    AuditCategory.APPROVAL,
    AuditCategory.RULE,
    -> "Approval"
}

private fun AuditOutcome.displayName(): String = storedName.replaceFirstChar(Char::uppercase)

@Composable
private fun AuditOutcome.accentColor(): Color = when (this) {
    AuditOutcome.APPROVED,
    AuditOutcome.COMPLETED,
    AuditOutcome.CHANGED,
    -> MaterialTheme.agentknockColors.success
    AuditOutcome.FAILED -> MaterialTheme.agentknockColors.danger
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun AuditFilter.matches(event: AuditEvent): Boolean = when (this) {
    AuditFilter.ALL -> true
    AuditFilter.SECRET_USE -> event.category == AuditCategory.SECRET_USE ||
        event.category == AuditCategory.GIT_SIGN
    AuditFilter.UPLOADS -> event.category == AuditCategory.SECRET_UPLOAD
    AuditFilter.PAIRINGS -> event.category == AuditCategory.PAIRING
}

private val auditDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val auditTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun Long.auditDate(): String = Instant.ofEpochMilli(this)
    .atZone(ZoneId.systemDefault())
    .format(auditDateFormatter)

private fun Long.auditTime(): String = Instant.ofEpochMilli(this)
    .atZone(ZoneId.systemDefault())
    .format(auditTimeFormatter)
