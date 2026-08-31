@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.storage.request.ApprovalCompletionResult
import dev.agentknock.storage.request.ApprovalDecision
import dev.agentknock.storage.request.ApprovalRequestState
import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestStatus
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.ui.components.ClientIdentity
import dev.agentknock.ui.components.SecretIdentities
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun RequestList(
    requests: List<InboxRequestSummary>,
    selectedRequestId: String?,
    syncing: Boolean,
    syncProblem: String?,
    onRefresh: () -> Unit,
    onShowSyncProblem: (String) -> Unit,
    onOpenSettings: () -> Unit,
    notificationsEnabled: Boolean,
    onOpen: (String) -> Unit,
    onApprove: (InboxRequestSummary) -> Unit,
    onReject: (InboxRequestSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var previousNewestRequestId by remember { mutableStateOf<String?>(null) }
    val newestRequestId = requests.firstOrNull()?.id
    LaunchedEffect(newestRequestId) {
        val previousNewest = previousNewestRequestId
        if (newestRequestId != null && previousNewest != null && newestRequestId != previousNewest) {
            val previousNewestIndex = requests.indexOfFirst { it.id == previousNewest }
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
            title = { Text("Requests") },
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
        if (requests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("No requests yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Secret use, Git signing, and SSH authentication requests will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(requests, key = { _, request -> request.id }) { _, request ->
                    RequestRow(
                        request = request,
                        selected = request.id == selectedRequestId,
                        onClick = { onOpen(request.id) },
                        onApprove = { onApprove(request) },
                        onReject = { onReject(request) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    request: InboxRequestSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val approval = request.status as? InboxRequestStatus.Approval
    val canApprove = request.userDecisionAvailable &&
        approval?.state == ApprovalRequestState.APPROVAL_PENDING
    val canReject = request.userDecisionAvailable && request.canReject()
    val rejectLabel = "Deny once"
    val swipeState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.65f },
    )
    LaunchedEffect(swipeState.currentValue) {
        val completedSwipe = swipeState.currentValue
        if (completedSwipe == SwipeToDismissBoxValue.Settled) return@LaunchedEffect

        when (completedSwipe) {
            SwipeToDismissBoxValue.StartToEnd -> if (canApprove) onApprove()
            SwipeToDismissBoxValue.EndToStart -> if (canReject) onReject()
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        // The decision runs in its own UI coroutine. Snap back synchronously while it starts;
        // an animated reset can be interrupted by the backing-list update and strand the row.
        swipeState.snapTo(SwipeToDismissBoxValue.Settled)
    }
    SwipeToDismissBox(
        state = swipeState,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .semantics {
                this.selected = selected
                customActions = buildList {
                    if (canApprove) {
                        add(CustomAccessibilityAction("Approve once") {
                            onApprove()
                            true
                        })
                    }
                    if (canReject) {
                        add(CustomAccessibilityAction(rejectLabel) {
                            onReject()
                            true
                        })
                    }
                }
            },
        enableDismissFromStartToEnd = canApprove,
        enableDismissFromEndToStart = canReject,
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
                        Text(if (approving) "Approve once" else rejectLabel, color = contentColor)
                    }
                }
            }
        },
    ) {
        RequestRowContent(request, selected, onClick)
    }
}

@Composable
private fun RequestRowContent(
    request: InboxRequestSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val subdued = request.wasRejected() || request.wasAborted() || request.hasVerificationFailure()
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED &&
        request.userDecisionAvailable
    val semanticColors = MaterialTheme.agentknockColors
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        subdued -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        color = containerColor,
        contentColor = when {
            subdued && !selected -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier.width(5.dp).fillMaxHeight().background(
                    if (actionRequired) semanticColors.attentionAccent else Color.Transparent,
                ),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        request.title,
                        style = if (request.command == null) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        color = if (request.command == null) {
                            Color.Unspecified
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    RequestStatusBadge(request)
                    Icon(
                        Icons.AutoMirrored.Outlined.NavigateNext,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                request.command?.takeUnless {
                    request.kind == InboxRequestKind.GIT_SIGN ||
                        request.kind == InboxRequestKind.SSH_AUTHENTICATE
                }?.let {
                    Text(
                        renderShellCommand(it, request.arguments),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                request.listSummary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (
                    (request.kind == InboxRequestKind.GIT_SIGN ||
                        request.kind == InboxRequestKind.SSH_AUTHENTICATE) &&
                    request.command != null
                ) {
                    Text(
                        "Triggered by ${renderShellCommand(request.command, request.arguments)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    ClientIdentity(request.clientName)
                    if (request.secretNames.isNotEmpty()) {
                        SecretIdentities(
                            request.secretNames,
                            unavailable = request.hasInvalidSecretReference(),
                        )
                    }
                }
                Text(
                    formatTimestamp(request.receivedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun RequestStatusBadge(request: InboxRequestSummary) {
    val error = (request.status as? InboxRequestStatus.Approval)?.state ==
        ApprovalRequestState.VERIFICATION_FAILED
    val rejected = request.wasRejected()
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED &&
        request.userDecisionAvailable
    val accepted = request.wasAccepted()
    val semanticColors = MaterialTheme.agentknockColors
    Surface(
        color = when {
            error -> semanticColors.dangerContainer
            actionRequired -> semanticColors.attentionContainer
            accepted -> semanticColors.successContainer
            rejected -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = when {
            error -> semanticColors.onDangerContainer
            actionRequired -> semanticColors.onAttentionContainer
            accepted -> semanticColors.onSuccessContainer
            rejected -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            request.statusLabel(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

private fun InboxRequestSummary.statusLabel(): String = when {
    state == InboxRequestState.REVIEWING -> "AI reviewing"
    !userDecisionAvailable && approvalStatus()?.state ==
        ApprovalRequestState.APPROVAL_PENDING -> "AI reviewing"
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

private fun InboxRequestSummary.canReject(): Boolean =
    approvalStatus()?.state == ApprovalRequestState.APPROVAL_PENDING

private fun InboxRequestSummary.wasRejected(): Boolean =
    approvalStatus()?.let {
        it.decision == ApprovalDecision.DENIED ||
            it.completionResult == ApprovalCompletionResult.DENIED
    } == true

private fun InboxRequestSummary.wasAborted(): Boolean =
    approvalStatus()?.completionResult == ApprovalCompletionResult.ABORTED

private fun InboxRequestSummary.hasVerificationFailure(): Boolean =
    approvalStatus()?.state == ApprovalRequestState.VERIFICATION_FAILED

private fun InboxRequestSummary.wasAccepted(): Boolean =
    approvalStatus()?.completionResult == ApprovalCompletionResult.APPROVED

private fun InboxRequestSummary.hasInvalidSecretReference(): Boolean =
    approvalStatus()?.completionReason == "INVALID_REQUEST"

private fun InboxRequestSummary.approvalStatus(): InboxRequestStatus.Approval? =
    status as? InboxRequestStatus.Approval

private fun InboxRequestSummary.requiredApprovalStatus(): InboxRequestStatus.Approval =
    checkNotNull(approvalStatus()) { "$kind requests require approval status" }
