@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        if (requests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("No requests yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (onPairClient != null) {
                            "Pair your computer to start using secrets with commands."
                        } else {
                            "Secret use, Git signing, and SSH authentication requests will appear here."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    onPairClient?.let { pair ->
                        androidx.compose.material3.Button(onClick = pair) { Text("Pair a client") }
                    }
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
                        onDecision = { decision -> onDecision(request.id, decision) },
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
    onDecision: (RequestDecision) -> Unit,
) {
    val approval = request.status as? InboxRequestStatus.Approval
    val canDecide = request.state == InboxRequestState.ACTION_REQUIRED &&
        approval?.state == ApprovalRequestState.APPROVAL_PENDING
    val rejectLabel = "Deny once"
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
            .clip(MaterialTheme.shapes.large)
            .semantics {
                this.selected = selected
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
    val dates = rememberDateTimeFormatter()
    val subdued = request.wasRejected() || request.wasAborted() || request.hasVerificationFailure()
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        actionRequired -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            .compositeOver(MaterialTheme.colorScheme.surface)
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (request.kind) {
                        InboxRequestKind.PAIRING -> Icons.Outlined.Computer
                        InboxRequestKind.SECRET_USE -> Icons.Outlined.Terminal
                        InboxRequestKind.GIT_SIGN -> Icons.Outlined.Edit
                        InboxRequestKind.SSH_AUTHENTICATE -> Icons.Outlined.Key
                        InboxRequestKind.SECRET_UPLOAD -> Icons.Outlined.CloudUpload
                    },
                    contentDescription = null,
                    tint = if (actionRequired) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    request.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.AutoMirrored.Outlined.NavigateNext,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            request.command?.takeUnless {
                request.kind == InboxRequestKind.GIT_SIGN ||
                    request.kind == InboxRequestKind.SSH_AUTHENTICATE
            }?.let {
                Text(
                    renderShellCommand(it, request.arguments),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            request.listSummary?.let {
                Text(
                    it,
                    style = if (request.kind == InboxRequestKind.GIT_SIGN) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (request.kind == InboxRequestKind.GIT_SIGN) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            request.repository?.let {
                Text(
                    "Repository: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (
                request.kind == InboxRequestKind.SSH_AUTHENTICATE &&
                request.command != null
            ) {
                Text(
                    "Triggered by ${renderShellCommand(request.command, request.arguments)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RequestParticipants(request.clientName, request.secretNames, maxLines = 2)
            request.decisionSummary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RequestStatusBadge(request)
                Text(
                    dates.timestamp(request.receivedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RequestStatusBadge(request: InboxRequestSummary) {
    val error = (request.status as? InboxRequestStatus.Approval)?.state ==
        ApprovalRequestState.VERIFICATION_FAILED
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    val accepted = request.wasAccepted()
    val semanticColors = MaterialTheme.agentknockColors
    Surface(
        color = when {
            error -> semanticColors.dangerContainer
            actionRequired -> semanticColors.attentionContainer
            accepted -> semanticColors.successContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = when {
            error -> semanticColors.onDangerContainer
            actionRequired -> semanticColors.onAttentionContainer
            accepted -> semanticColors.onSuccessContainer
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

private fun InboxRequestSummary.wasAccepted(): Boolean =
    approvalStatus()?.completionResult == ApprovalCompletionResult.APPROVED

private fun InboxRequestSummary.approvalStatus(): InboxRequestStatus.Approval? =
    status as? InboxRequestStatus.Approval

private fun InboxRequestSummary.requiredApprovalStatus(): InboxRequestStatus.Approval =
    checkNotNull(approvalStatus()) { "$kind requests require approval status" }
