@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.ui.components.AdaptiveListDetail
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.theme.agentknockColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class AuditFilter(val label: String) {
    ALL("All"),
    SECRET_USE("Sensitive use"),
    UPLOADS("Uploads"),
    PAIRINGS("Pairings"),
    CHANGES("Changes"),
}

@Composable
internal fun AuditSettings(
    viewModel: AuditViewModel,
    onBack: () -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val selectedEventId by viewModel.selectedEventId.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val events = (history as? AuditHistoryState.Loaded)?.events
    val loadedDetail = (detail as? AuditDetailState.Loaded)
        ?.takeIf { it.eventId == selectedEventId }
    val selected = loadedDetail?.event
    val back = {
        if (selectedEventId == null) onBack() else viewModel.selectEvent(null)
    }
    BackHandler(enabled = selectedEventId != null) { viewModel.selectEvent(null) }
    AuditBrowser(
        events = events,
        selectedEventId = selectedEventId,
        selected = selected,
        detailLoaded = loadedDetail != null,
        onBack = back,
        onOpen = viewModel::selectEvent,
        report = report,
        modifier = modifier,
    )
}

@Composable
internal fun AuditBrowser(
    events: List<AuditEvent>?,
    selectedEventId: Long?,
    selected: AuditEvent?,
    detailLoaded: Boolean,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    AdaptiveListDetail(
        hasDetail = selectedEventId != null,
        listWidth = 360.dp,
        onBack = onBack,
        onTopLevelChanged = {},
        modifier = modifier,
        list = { listModifier ->
            AuditList(
                events = events,
                selectedEventId = selectedEventId,
                onBack = onBack,
                onOpen = onOpen,
                modifier = listModifier,
            )
        },
        emptyDetail = { detailModifier ->
            Box(detailModifier, contentAlignment = Alignment.Center) {
                Text(
                    "Select an event to view its details",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        detail = { showBack, detailModifier ->
            when {
                selected != null -> key(selected.id) {
                    AuditDetail(
                        event = selected,
                        report = report,
                        onBack = onBack,
                        showBack = showBack,
                        modifier = detailModifier,
                    )
                }
                !detailLoaded -> AuditDetailPlaceholder(
                    loading = true,
                    onBack = onBack,
                    showBack = showBack,
                    modifier = detailModifier,
                )
                else -> AuditDetailPlaceholder(
                    loading = false,
                    onBack = onBack,
                    showBack = showBack,
                    modifier = detailModifier,
                )
            }
        },
    )
}

@Composable
private fun AuditList(
    events: List<AuditEvent>?,
    selectedEventId: Long?,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(AuditFilter.ALL) }
    val visibleEvents = events?.filter(filter::matches)
    val dayGroups = visibleEvents?.groupBy { it.occurredAt.auditDate() }.orEmpty()
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
        if (events == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (visibleEvents.isNullOrEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (events.isEmpty()) "No security activity recorded yet" else "No matching activity",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                dayGroups.forEach { (day, dayEvents) ->
                    item(key = "day_$day") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                day,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "${dayEvents.size} ${if (dayEvents.size == 1) "event" else "events"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(
                        items = dayEvents,
                        key = { "event_${it.id}" },
                    ) { event ->
                        val index = dayEvents.indexOf(event)
                        AuditTimelineRow(
                            event = event,
                            selected = event.id == selectedEventId,
                            firstInDay = index == 0,
                            lastInDay = index == dayEvents.lastIndex,
                            onClick = { onOpen(event.id) },
                        )
                    }
                    item(key = "day_end_$day") {
                        Box(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditTimelineRow(
    event: AuditEvent,
    selected: Boolean,
    firstInDay: Boolean,
    lastInDay: Boolean,
    onClick: () -> Unit,
) {
    val context = event.contextLine()
    val presentation = event.presentation()
    val accent = event.outcome.accentColor()
    val timeline = MaterialTheme.colorScheme.outlineVariant
    val rowShape: Shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
                else Color.Transparent,
                rowShape,
            )
            .height(IntrinsicSize.Min).clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            event.occurredAt.auditTime(),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 68.dp).padding(top = 14.dp),
        )
        Canvas(Modifier.width(12.dp).fillMaxHeight()) {
            val markerY = 22.dp.toPx().coerceAtMost(size.height / 2f)
            if (!firstInDay) {
                drawLine(timeline, start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width / 2f, markerY), strokeWidth = 2.dp.toPx())
            }
            if (!lastInDay) {
                drawLine(timeline, start = androidx.compose.ui.geometry.Offset(size.width / 2f, markerY),
                    end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height), strokeWidth = 2.dp.toPx())
            }
            drawCircle(accent, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width / 2f, markerY))
        }
        Column(
            Modifier.weight(1f).padding(top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                presentation.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                context ?: presentation.category.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AuditDetailPlaceholder(
    loading: Boolean,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    Column(modifier) {
        PageTopBar("Audit event", onBack, showBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Text(
                    "This audit event is no longer available",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AuditDetail(
    event: AuditEvent,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val presentation = event.presentation()
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatTimestamp(event.occurredAt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                        Surface(
                            color = event.outcome.accentColor().copy(alpha = 0.18f),
                            contentColor = event.outcome.accentColor(),
                            shape = RoundedCornerShape(100.dp),
                        ) {
                            Text(
                                event.outcome.displayName(),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                    Text(presentation.title, style = MaterialTheme.typography.headlineSmall)
                }
                val distinctClientName = event.clientName
                    ?.takeIf(String::isNotBlank)
                    ?.takeUnless { it == event.subject }
                if (
                    event.subject != null || event.context != null || event.detail != null ||
                    distinctClientName != null || event.decisionSource != null ||
                    event.expiresAt != null
                ) {
                    InformationSurface {
                        event.subject?.let {
                            InformationRow(presentation.subjectLabel ?: "Subject", it)
                        }
                        event.context?.let {
                            InformationRow(presentation.contextLabel ?: "Context", it)
                        }
                        event.detail?.let {
                            InformationRow(presentation.detailLabel ?: "Details", it)
                        }
                        distinctClientName?.let {
                            InformationRow("Client", it)
                        }
                        event.decisionSource?.let {
                            InformationRow("Decision source", it.displayName())
                        }
                        event.expiresAt?.let {
                            InformationRow("Valid until", formatTimestamp(it))
                        }
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

private fun AuditEvent.contextLine(): String? {
    val presentation = presentation()
    val subjectContext = subject?.let { value ->
        presentation.subjectLabel?.let { label -> "$label: $value" } ?: value
    }
    val eventContext = context?.let { value ->
        presentation.contextLabel?.let { label -> "$label: $value" } ?: value
    }
    val displayedClient = clientName?.takeIf(String::isNotBlank)
        ?: clientId?.let { id -> if (id.length > 8) "…${id.takeLast(6)}" else id }
    val clientContext = displayedClient?.takeUnless { it == subject }?.let { "Client: $it" }
    return listOfNotNull(subjectContext, eventContext, clientContext)
        .joinToString(" · ")
        .ifBlank { null }
}

private fun AuditOutcome.displayName(): String = when (this) {
    AuditOutcome.RECEIVED -> "Received"
    AuditOutcome.APPROVED -> "Approved"
    AuditOutcome.DENIED -> "Denied"
    AuditOutcome.REJECTED -> "Rejected"
    AuditOutcome.ABORTED -> "Aborted"
    AuditOutcome.COMPLETED -> "Completed"
    AuditOutcome.CHANGED -> "Changed"
    AuditOutcome.FAILED -> "Failed"
    AuditOutcome.DEFERRED -> "Needs user review"
}

@Composable
private fun AuditOutcome.accentColor(): Color = when (this) {
    AuditOutcome.APPROVED,
    AuditOutcome.COMPLETED,
    AuditOutcome.CHANGED,
    -> MaterialTheme.agentknockColors.success
    AuditOutcome.FAILED -> MaterialTheme.agentknockColors.danger
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun AuditFilter.matches(event: AuditEvent): Boolean {
    val presentation = event.presentation()
    return when (this) {
        AuditFilter.ALL -> true
        AuditFilter.SECRET_USE -> presentation.sensitiveUse
        AuditFilter.UPLOADS -> presentation.category == AuditCategory.SECRET_UPLOAD
        AuditFilter.PAIRINGS -> presentation.category == AuditCategory.PAIRING
        AuditFilter.CHANGES -> presentation.category == AuditCategory.SECRET ||
            presentation.category == AuditCategory.CLIENT ||
            presentation.category == AuditCategory.DEVICE ||
            presentation.category == AuditCategory.APPROVAL
    }
}

private val auditDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val auditTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun Long.auditDate(): String = Instant.ofEpochMilli(this)
    .atZone(ZoneId.systemDefault())
    .format(auditDateFormatter)

private fun Long.auditTime(): String = Instant.ofEpochMilli(this)
    .atZone(ZoneId.systemDefault())
    .format(auditTimeFormatter)
