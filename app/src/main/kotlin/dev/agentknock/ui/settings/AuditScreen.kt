@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.storage.audit.AuditCategory
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.InboxRequestSummary
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
internal fun AuditBrowser(
    events: List<AuditEvent>,
    selected: AuditEvent?,
    clients: List<ClientSummary>,
    requests: List<InboxRequestSummary>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val clientNames = clients.associate { it.clientId to it.name }
    val requestClientNames = requests.mapNotNull { request ->
        request.relayRequestId?.let { it to request.clientName }
    }.toMap()
    fun clientName(event: AuditEvent): String? =
        event.clientId?.let(clientNames::get) ?: event.relayRequestId?.let(requestClientNames::get)
    BoxWithConstraints(modifier) {
        val twoPane = maxWidth >= 720.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                AuditList(
                    events = events,
                    selectedEventId = selected?.id,
                    onBack = onBack,
                    onOpen = onOpen,
                    clientNames = clientNames,
                    requestClientNames = requestClientNames,
                    modifier = Modifier.width(360.dp).fillMaxHeight(),
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
                        clientName = clientName(selected),
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
                requestClientNames = requestClientNames,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AuditDetail(
                event = selected,
                clientName = clientName(selected),
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
    requestClientNames: Map<String, String>,
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
                            clientName = event.clientId?.let(clientNames::get)
                                ?: event.relayRequestId?.let(requestClientNames::get),
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
    clientName: String?,
    selected: Boolean,
    firstInDay: Boolean,
    lastInDay: Boolean,
    onClick: () -> Unit,
) {
    val context = event.contextLine(clientName)
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
                event.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
                    Text(event.title, style = MaterialTheme.typography.headlineSmall)
                }
                val detail = event.displayDetail().takeIf(String::isNotBlank)
                val distinctClientName = clientName?.takeUnless { it == detail }
                if (detail != null || distinctClientName != null) {
                    InformationSurface {
                        detail?.let {
                            InformationRow(event.detailLabel(), detail)
                        }
                        distinctClientName?.let {
                            InformationRow("Client", it)
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

private fun AuditEvent.contextLine(clientName: String?): String? {
    val detail = displayDetail()
    val detailContext = detail.takeIf(String::isNotBlank)?.let { "${detailLabel()}: $it" }
    val clientContext = clientName?.takeUnless { it == detail }?.let { "Client: $it" }
    return listOfNotNull(detailContext, clientContext).joinToString(" · ").ifBlank { null }
}

private fun AuditEvent.displayDetail(): String {
    val displayed = when (category) {
        AuditCategory.SECRET_UPLOAD -> {
            val transportVerb = listOf("create ", "replace ", "update ")
                .firstOrNull(detail::startsWith)
            transportVerb?.let(detail::removePrefix) ?: detail
        }
        AuditCategory.SECRET_LIST -> detail.substringBefore(" sent to ")
        AuditCategory.VERIFICATION -> when (detail) {
            "INVALID_REQUEST" -> "The request could not be understood."
            "UNSUPPORTED_METHOD" -> "The requested operation is not supported."
            "INVALID_STATE" -> "The requested operation is not available in the current state."
            else -> detail
        }
        else -> when {
            title.startsWith("Temporary access") -> detail.temporaryAccessDetail()
            title.contains("AI review", ignoreCase = true) &&
                detail.startsWith("AI review ", ignoreCase = true) ->
                detail.drop("AI review ".length).replaceFirstChar(Char::uppercase)
            else -> detail
        }
    }
    return displayed
        .replace("**", "")
        .replace("`", "")
        .trim()
}

private fun AuditEvent.detailLabel(): String = when (category) {
    AuditCategory.PAIRING,
    AuditCategory.CLIENT,
    -> "Client"
    AuditCategory.SECRET -> when {
        title.startsWith("Environment variable") -> "Environment variable"
        else -> "Secret"
    }
    AuditCategory.SECRET_USE -> when {
        title.startsWith("Temporary access", ignoreCase = true) -> "Access"
        title.contains("AI review", ignoreCase = true) -> "AI review"
        title.contains("rejected", ignoreCase = true) -> "Reason"
        else -> "Secrets"
    }
    AuditCategory.GIT_SIGN -> when {
        title.contains("AI review", ignoreCase = true) -> "AI review"
        title.contains("rejected", ignoreCase = true) -> "Reason"
        else -> "SSH key"
    }
    AuditCategory.SECRET_LIST -> "Result"
    AuditCategory.SECRET_UPLOAD -> if (title.contains("automatically", ignoreCase = true)) {
        "Reason"
    } else {
        "Upload"
    }
    AuditCategory.DEVICE -> if (title.contains("address", ignoreCase = true)) "Pairing address" else "Change"
    AuditCategory.VERIFICATION -> "Reason"
    AuditCategory.APPROVAL,
    AuditCategory.RULE,
    -> "Approval"
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

private fun AuditFilter.matches(event: AuditEvent): Boolean = when (this) {
    AuditFilter.ALL -> true
    AuditFilter.SECRET_USE -> event.outcome == AuditOutcome.APPROVED &&
        (event.category == AuditCategory.SECRET_USE || event.category == AuditCategory.GIT_SIGN) &&
        (
            event.title.startsWith("Secret use approved", ignoreCase = true) ||
                event.title.startsWith("Git signature approved", ignoreCase = true)
        ) &&
        !event.title.startsWith("Non-sensitive", ignoreCase = true)
    AuditFilter.UPLOADS -> event.category == AuditCategory.SECRET_UPLOAD
    AuditFilter.PAIRINGS -> event.category == AuditCategory.PAIRING
    AuditFilter.CHANGES -> event.category == AuditCategory.SECRET ||
        event.category == AuditCategory.CLIENT || event.category == AuditCategory.DEVICE ||
        event.category == AuditCategory.APPROVAL ||
        event.category == AuditCategory.RULE
}

private fun String.temporaryAccessDetail(): String {
    val normalized = replace("environment values", "secret values")
    val marker = " · expires "
    val instantText = normalized.substringAfterLast(marker, missingDelimiterValue = "")
    if (instantText.isEmpty()) return normalized
    val expiresAt = runCatching { Instant.parse(instantText).toEpochMilli() }.getOrNull()
        ?: return normalized
    return normalized.substringBeforeLast(marker) + " · until " + formatTimestamp(expiresAt)
}

private val auditDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val auditTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun Long.auditDate(): String = Instant.ofEpochMilli(this)
    .atZone(ZoneId.systemDefault())
    .format(auditDateFormatter)

private fun Long.auditTime(): String = Instant.ofEpochMilli(this)
    .atZone(ZoneId.systemDefault())
    .format(auditTimeFormatter)
