@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.R
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.secret.SecretSummary
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.components.AiInstructionsScope
import dev.agentknock.ui.components.InstructionsCard
import dev.agentknock.ui.components.TonalIcon
import dev.agentknock.ui.components.rememberDateTimeFormatter

@Composable
internal fun SecretList(
    secrets: List<SecretSummary>,
    clients: List<ClientSummary>,
    pendingUploads: List<InboxRequestSummary>,
    selectedSecretId: String?,
    selectedUploadRequestId: String?,
    onSelect: (String) -> Unit,
    onSelectUpload: (String) -> Unit,
    onCreate: () -> Unit,
    generalInstructions: String,
    aiReviewAccess: AiReviewAccess,
    onEditGeneralInstructions: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.secrets),
                    style = MaterialTheme.typography.headlineMedium,
                )
            },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            },
        )
        Box(Modifier.weight(1f)) {
            if (secrets.isEmpty() && pendingUploads.isEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    InstructionsCard(
                        scope = AiInstructionsScope.GLOBAL,
                        value = generalInstructions,
                        access = aiReviewAccess,
                        onEdit = onEditGeneralInstructions,
                        modifier =
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                    )
                    EmptyMessage(
                        title = stringResource(R.string.no_secrets),
                        description = stringResource(R.string.no_secrets_description),
                        modifier = Modifier.fillMaxSize(),
                        icon = Icons.Outlined.Key,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "global_instructions") {
                        InstructionsCard(
                            scope = AiInstructionsScope.GLOBAL,
                            value = generalInstructions,
                            access = aiReviewAccess,
                            onEdit = onEditGeneralInstructions,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    if (pendingUploads.isNotEmpty()) {
                        item(key = "incoming_uploads_heading") {
                            SectionHeading("Incoming uploads", pendingUploads.size)
                        }
                        itemsIndexed(
                            pendingUploads,
                            key = { _, request -> "upload_${request.id}" },
                        ) { index, request ->
                            PendingSecretUploadRow(
                                request = request,
                                selected = request.id == selectedUploadRequestId,
                                shape = groupShape(index, pendingUploads.lastIndex),
                                onClick = { onSelectUpload(request.id) },
                            )
                        }
                    }
                    item(key = "stored_secrets_heading") {
                        SectionHeading("Stored secrets", secrets.size)
                    }
                    if (secrets.isEmpty()) {
                        item(key = "no_stored_secrets") {
                            Text(
                                "No secrets are stored yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(secrets, key = { _, secret -> secret.id }) { index, secret ->
                        SecretRow(
                            secret = secret,
                            clients = clients,
                            selected = secret.id == selectedSecretId,
                            shape = groupShape(index, secrets.lastIndex),
                            onClick = { onSelect(secret.id) },
                        )
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_secret)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun SecretRow(
    secret: SecretSummary,
    clients: List<ClientSummary>,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val dates = rememberDateTimeFormatter()
    Surface(
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        shape = shape,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonalIcon(
                when (secret.type) {
                    SecretType.SSH -> Icons.Outlined.Key
                    SecretType.ENVIRONMENT -> Icons.Outlined.DataObject
                },
                contentDescription = null,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    secret.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secret.description.isNotBlank()) {
                    Text(
                        secret.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    when (secret.type) {
                        SecretType.SSH ->
                            secret.sshKey?.let { "SSH key · ${it.algorithm.displayName()}" }
                                ?: "SSH key unavailable"
                        SecretType.ENVIRONMENT ->
                            "${secret.environmentVariableCount} environment " +
                                if (secret.environmentVariableCount == 1) "variable"
                                else "variables"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (secret.temporaryAccessGrants.isNotEmpty()) {
                    val clientIds = secret.temporaryAccessGrants.map { it.clientId }.distinct()
                    val singleClient =
                        clientIds.singleOrNull()?.let { id ->
                            clients.firstOrNull { it.clientId == id }?.name
                        }
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            singleClient?.let { "Temporary access: $it" }
                                ?: "Temporary access for ${clientIds.size} clients",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        (if (secret.temporaryAccessGrants.size == 1) "Ends " else "Next expiry ") +
                            dates.timestamp(secret.temporaryAccessGrants.minOf { it.expiresAt }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.NavigateNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingSecretUploadRow(
    request: InboxRequestSummary,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val dates = rememberDateTimeFormatter()
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    val secretName = request.secretNames.singleOrNull() ?: "Unnamed secret"
    Surface(
        color =
            when {
                selected -> MaterialTheme.colorScheme.secondaryContainer
                actionRequired ->
                    MaterialTheme.colorScheme.primary
                        .copy(alpha = 0.08f)
                        .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
        shape = shape,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (request.uploadSecretType == SecretType.SSH.storedName) Icons.Outlined.Key
                else Icons.Outlined.DataObject,
                contentDescription = null,
                tint =
                    if (actionRequired) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    secretName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(request.title, request.listSummary)
                        .filter(String::isNotBlank)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Uploaded by ${request.clientName} · ${dates.relativeTime(request.receivedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.NavigateNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, count: Int) {
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
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

/** Large outer corners with small inner ones, so consecutive rows read as one group. */
private fun groupShape(index: Int, lastIndex: Int): Shape {
    val outer = 20.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == lastIndex) outer else inner,
        bottomEnd = if (index == lastIndex) outer else inner,
    )
}
