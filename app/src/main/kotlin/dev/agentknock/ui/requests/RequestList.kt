@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.storage.request.ApprovalCompletionResult
import dev.agentknock.storage.request.ApprovalDecision
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestStatus
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.RequestDecision
import dev.agentknock.ui.components.rememberDateTimeFormatter
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun RequestList(
    onPairClient: (() -> Unit)?,
    requests: List<InboxRequestSummary>,
    selectedRequestId: String?,
    syncing: Boolean,
    syncProblem: String?,
    onRefresh: () -> Unit,
    onShowSyncProblem: (String) -> Unit,
    onOpenSettings: () -> Unit,
    notificationsEnabled: Boolean,
    onOpen: (String) -> Unit,
    onDecision: (String, RequestDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val entries = remember(requests) { requests.toInboxEntries() }
    var previousNewestRequestId by remember { mutableStateOf<String?>(null) }
    val newestRequestId = requests.firstOrNull()?.id
    LaunchedEffect(newestRequestId) {
        val previousNewest = previousNewestRequestId
        if (newestRequestId != null && previousNewest != null && newestRequestId != previousNewest) {
            val previousNewestIndex = entries.indexOfFirst { entry ->
                entry is InboxEntry.Row && entry.request.id == previousNewest
            }
            if (
                listState.firstVisibleItemIndex == 0 ||
                listState.firstVisibleItemIndex == previousNewestIndex
            ) {
                listState.animateScrollToItem(0)
            }
        }
        previousNewestRequestId = newestRequestId
    }
    Column(modifier) {
        TopAppBar(
            title = { Text("Requests", style = MaterialTheme.typography.headlineMedium) },
            actions = {
                if (!notificationsEnabled) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.NotificationsOff,
                            contentDescription = "Notifications disabled",
                        )
                    }
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    syncProblem?.let { problem ->
                        IconButton(onClick = { onShowSyncProblem(problem) }) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = "Connection problem",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                IconButton(onClick = onRefresh, enabled = !syncing) {
                    if (syncing) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp).semantics {
                                contentDescription = "Refreshing requests"
                            },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            },
        )
        syncProblem?.let { problem ->
            SyncProblemBanner(problem, syncing, onRefresh)
        }
        if (requests.isEmpty()) {
            EmptyInbox(onPairClient)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.key }, contentType = { it::class }) { entry ->
                    when (entry) {
                        is InboxEntry.Header -> SectionHeading(
                            entry.section.title,
                            entry.count,
                            modifier = Modifier.padding(top = if (entry.first) 4.dp else 18.dp, bottom = 6.dp),
                        )
                        is InboxEntry.Row -> RequestRow(
                            request = entry.request,
                            selected = entry.request.id == selectedRequestId,
                            shape = entry.position.shape(),
                            onClick = { onOpen(entry.request.id) },
                            onDecision = { decision -> onDecision(entry.request.id, decision) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncProblemBanner(problem: String, syncing: Boolean, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Text(
                problem,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry, enabled = !syncing) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyInbox(onPairClient: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Inbox, contentDescription = null, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("No requests yet", style = MaterialTheme.typography.titleLarge)
            Text(
                if (onPairClient != null) {
                    "Pair your computer to start using secrets with commands."
                } else {
                    "Secret use, Git signing, and SSH authentication requests will appear here."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            onPairClient?.let { pair ->
                Spacer(Modifier.height(8.dp))
                Button(onClick = pair) { Text("Pair a client") }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RequestRow(
    request: InboxRequestSummary,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    onDecision: (RequestDecision) -> Unit,
) {
    val approval = request.status as? InboxRequestStatus.Approval
    val canDecide = request.state == InboxRequestState.ACTION_REQUIRED &&
        approval?.state == ApprovalRequestState.APPROVAL_PENDING
    val rejectLabel = "Deny once"
    val statusLabel = request.statusLabel()
    val swipeState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.65f },
    )
    LaunchedEffect(swipeState.currentValue) {
        val completedSwipe = swipeState.currentValue
        if (completedSwipe == SwipeToDismissBoxValue.Settled) return@LaunchedEffect

        when (completedSwipe) {
            SwipeToDismissBoxValue.StartToEnd -> if (canDecide) {
                onDecision(RequestDecision.APPROVE)
            }
            SwipeToDismissBoxValue.EndToStart -> if (canDecide) {
                onDecision(RequestDecision.DENY)
            }
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        // The view model owns the decision job. Snap back synchronously while it starts;
        // an animated reset can be interrupted by the backing-list update and strand the row.
        swipeState.snapTo(SwipeToDismissBoxValue.Settled)
    }
    SwipeToDismissBox(
        state = swipeState,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .semantics {
                this.selected = selected
                stateDescription = statusLabel
                customActions = buildList {
                    if (canDecide) {
                        add(CustomAccessibilityAction("Approve once") {
                            onDecision(RequestDecision.APPROVE)
                            true
                        })
                        add(CustomAccessibilityAction(rejectLabel) {
                            onDecision(RequestDecision.DENY)
                            true
                        })
                    }
                }
            },
        enableDismissFromStartToEnd = canDecide,
        enableDismissFromEndToStart = canDecide,
        backgroundContent = {
            val direction = swipeState.dismissDirection
            val approving = direction == SwipeToDismissBoxValue.StartToEnd
            val semanticColors = MaterialTheme.agentknockColors
            val backgroundColor = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> semanticColors.successContainer
                SwipeToDismissBoxValue.EndToStart -> semanticColors.dangerContainer
                SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> semanticColors.onSuccessContainer
                SwipeToDismissBoxValue.EndToStart -> semanticColors.onDangerContainer
                SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (approving) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (approving) Icons.Outlined.Check else Icons.Outlined.Close,
                            contentDescription = null,
                            tint = contentColor,
                        )
                        Text(
                            if (approving) "Approve once" else rejectLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor,
                        )
                    }
                }
            }
        },
    ) {
        RequestRowContent(request, selected, shape, statusLabel, onClick)
    }
}

@Composable
private fun RequestRowContent(
    request: InboxRequestSummary,
    selected: Boolean,
    shape: Shape,
    statusLabel: String,
    onClick: () -> Unit,
) {
    val dates = rememberDateTimeFormatter()
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    val subdued = request.wasRejected() || request.wasAborted() || request.hasVerificationFailure()
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        actionRequired -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        color = containerColor,
        contentColor = when {
            subdued && !selected -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        shape = shape,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                request.kindIcon(),
                contentDescription = null,
                tint = if (actionRequired) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        request.kindLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        dates.timestamp(request.receivedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = "Received ${dates.timestamp(request.receivedAt)}"
                        },
                    )
                }
                RequestHeadline(request)
                RequestSupportingText(request)
                Identities(request.clientName, request.secretNames)
                if (!actionRequired) {
                    RequestStatusLine(request, statusLabel)
                }
            }
        }
    }
}

@Composable
private fun RequestHeadline(request: InboxRequestSummary) {
    val command = request.command?.takeIf { request.kind == InboxRequestKind.SECRET_USE }
    when {
        command != null -> CommandHeadline(command, request.arguments)
        request.kind == InboxRequestKind.SECRET_UPLOAD -> Text(
            listOfNotNull(request.title, request.secretNames.firstOrNull()).joinToString(" "),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        else -> Text(
            request.listSummary ?: request.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Executables at least this long get their directory on a separate line in list rows. */
private const val SPLIT_EXECUTABLE_MIN_LENGTH = 24

/**
 * The executable's directory sits on its own muted line so the program name leads the
 * command; the text order is unchanged and the full path remains in the detail.
 */
@Composable
private fun CommandHeadline(command: String, arguments: List<String>) {
    val (directory, name) = renderedExecutable(command)
    val splitDirectory = directory.isNotEmpty() &&
        directory.length + name.length >= SPLIT_EXECUTABLE_MIN_LENGTH
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (splitDirectory) {
            Text(
                directory,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Text(
            annotatedCommand(
                command = command,
                arguments = arguments,
                listed = false,
                mutedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                optionColor = MaterialTheme.colorScheme.tertiary,
                includeDirectory = !splitDirectory,
            ),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RequestSupportingText(request: InboxRequestSummary) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when (request.kind) {
        InboxRequestKind.GIT_SIGN -> request.repository?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        InboxRequestKind.SSH_AUTHENTICATE -> request.command?.let { command ->
            Text(
                buildAnnotatedString {
                    append("Triggered by ")
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(renderShellCommand(command, request.arguments))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        InboxRequestKind.SECRET_UPLOAD -> request.listSummary?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        InboxRequestKind.PAIRING,
        InboxRequestKind.SECRET_USE,
        -> Unit
    }
}

@Composable
private fun Identities(clientName: String, secretNames: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Identity(Icons.Outlined.Computer, "Client", clientName)
        if (secretNames.isNotEmpty()) {
            Identity(
                Icons.Outlined.Key,
                if (secretNames.size == 1) "Secret" else "Secrets",
                secretNames.joinToString(", "),
            )
        }
    }
}

@Composable
private fun Identity(icon: ImageVector, role: String, name: String) {
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$role $name" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Text(
            name.breakableAtHyphens(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RequestStatusLine(request: InboxRequestSummary, statusLabel: String) {
    val status = request.statusPresentation()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        status.icon?.let {
            Icon(it, contentDescription = null, tint = status.color, modifier = Modifier.size(14.dp))
        }
        Text(
            listOfNotNull(statusLabel, request.decisionSummary).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = status.color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class StatusPresentation(val color: Color, val icon: ImageVector?)

@Composable
private fun InboxRequestSummary.statusPresentation(): StatusPresentation {
    val semanticColors = MaterialTheme.agentknockColors
    return when {
        hasVerificationFailure() || isInvalidRequest() ->
            StatusPresentation(semanticColors.danger, Icons.Outlined.ErrorOutline)
        state == InboxRequestState.ACTION_REQUIRED ->
            StatusPresentation(semanticColors.attentionAccent, null)
        state == InboxRequestState.REVIEWING ->
            StatusPresentation(MaterialTheme.colorScheme.onSurfaceVariant, Icons.Outlined.AutoAwesome)
        state == InboxRequestState.WAITING ->
            StatusPresentation(MaterialTheme.colorScheme.onSurfaceVariant, Icons.Outlined.HourglassTop)
        wasAccepted() -> StatusPresentation(semanticColors.success, Icons.Outlined.CheckCircle)
        wasRejected() || wasAborted() ->
            StatusPresentation(MaterialTheme.colorScheme.onSurfaceVariant, Icons.Outlined.Block)
        else -> StatusPresentation(MaterialTheme.colorScheme.onSurfaceVariant, null)
    }
}

private fun InboxRequestSummary.kindIcon(): ImageVector = when (kind) {
    InboxRequestKind.PAIRING -> Icons.Outlined.Computer
    InboxRequestKind.SECRET_USE -> Icons.Outlined.Terminal
    InboxRequestKind.GIT_SIGN -> Icons.Outlined.Edit
    InboxRequestKind.SSH_AUTHENTICATE -> Icons.Outlined.Key
    InboxRequestKind.SECRET_UPLOAD -> Icons.Outlined.CloudUpload
}

private fun InboxRequestSummary.kindLabel(): String = when (kind) {
    InboxRequestKind.SECRET_UPLOAD -> "Secret upload"
    else -> title
}

private enum class InboxSection(val title: String) {
    WAITING_FOR_YOU("Waiting for you"),
    IN_PROGRESS("In progress"),
    EARLIER("Earlier"),
}

private enum class GroupPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST,
}

@Composable
private fun GroupPosition.shape(): Shape {
    val outer = 20.dp
    val inner = 4.dp
    return when (this) {
        GroupPosition.SINGLE -> RoundedCornerShape(outer)
        GroupPosition.FIRST -> RoundedCornerShape(
            topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner,
        )
        GroupPosition.MIDDLE -> RoundedCornerShape(inner)
        GroupPosition.LAST -> RoundedCornerShape(
            topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer,
        )
    }
}

private sealed interface InboxEntry {
    val key: String

    data class Header(val section: InboxSection, val count: Int, val first: Boolean) : InboxEntry {
        override val key: String get() = "section_${section.name}"
    }

    data class Row(val request: InboxRequestSummary, val position: GroupPosition) : InboxEntry {
        override val key: String get() = "request_${request.id}"
    }
}

private fun InboxRequestSummary.section(): InboxSection = when (state) {
    InboxRequestState.ACTION_REQUIRED -> InboxSection.WAITING_FOR_YOU
    InboxRequestState.REVIEWING,
    InboxRequestState.WAITING,
    -> InboxSection.IN_PROGRESS
    InboxRequestState.COMPLETED -> InboxSection.EARLIER
}

private fun List<InboxRequestSummary>.toInboxEntries(): List<InboxEntry> {
    val grouped = groupBy { it.section() }
    return buildList {
        InboxSection.entries.forEach { section ->
            val rows = grouped[section].orEmpty()
            if (rows.isEmpty()) return@forEach
            add(InboxEntry.Header(section, rows.size, first = isEmpty()))
            rows.forEachIndexed { index, request ->
                val position = when {
                    rows.size == 1 -> GroupPosition.SINGLE
                    index == 0 -> GroupPosition.FIRST
                    index == rows.lastIndex -> GroupPosition.LAST
                    else -> GroupPosition.MIDDLE
                }
                add(InboxEntry.Row(request, position))
            }
        }
    }
}

private fun InboxRequestSummary.statusLabel(): String = when {
    state == InboxRequestState.REVIEWING -> "AI reviewing"
    kind == InboxRequestKind.SECRET_USE -> requiredApprovalStatus().let {
        secretUseStatusLabel(it.state, it.completionResult, it.completionReason)
    }
    kind == InboxRequestKind.GIT_SIGN -> requiredApprovalStatus().let {
        gitSignStatusLabel(it.state, it.completionResult, it.completionReason)
    }
    kind == InboxRequestKind.SSH_AUTHENTICATE -> requiredApprovalStatus().let {
        sshAuthenticationStatusLabel(it.state, it.completionResult, it.completionReason)
    }
    state == InboxRequestState.ACTION_REQUIRED -> "Needs attention"
    state == InboxRequestState.WAITING -> "Waiting"
    else -> "Completed"
}

private fun InboxRequestSummary.wasRejected(): Boolean =
    approvalStatus()?.let {
        it.decision == ApprovalDecision.DENIED ||
            it.completionResult == ApprovalCompletionResult.DENIED
    } == true

private fun InboxRequestSummary.wasAborted(): Boolean =
    approvalStatus()?.completionResult == ApprovalCompletionResult.ABORTED

private fun InboxRequestSummary.hasVerificationFailure(): Boolean =
    approvalStatus()?.state == ApprovalRequestState.VERIFICATION_FAILED

private fun InboxRequestSummary.isInvalidRequest(): Boolean =
    approvalStatus()?.completionReason == "INVALID_REQUEST"

private fun InboxRequestSummary.wasAccepted(): Boolean =
    approvalStatus()?.completionResult == ApprovalCompletionResult.APPROVED

private fun InboxRequestSummary.approvalStatus(): InboxRequestStatus.Approval? =
    status as? InboxRequestStatus.Approval

private fun InboxRequestSummary.requiredApprovalStatus(): InboxRequestStatus.Approval =
    checkNotNull(approvalStatus()) { "$kind requests require approval status" }
