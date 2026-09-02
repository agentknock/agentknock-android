@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentknock.R
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.secret.SecretSummary
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.ui.components.ActionListSurface
import dev.agentknock.ui.components.TonalIcon
import dev.agentknock.ui.components.ProseEditorScreen

@Composable
internal fun SecretList(
    secrets: List<SecretSummary>,
    pendingUploads: List<InboxRequestSummary>,
    selectedSecretId: String?,
    selectedUploadRequestId: String?,
    onSelect: (String) -> Unit,
    onSelectUpload: (String) -> Unit,
    onCreate: () -> Unit,
    generalInstructions: String,
    aiReviewActive: Boolean,
    onSaveGeneralInstructions: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGeneralInstructions by rememberSaveable { mutableStateOf(false) }
    var editedGeneralInstructions by rememberSaveable(generalInstructions) {
        mutableStateOf(generalInstructions)
    }
    if (showGeneralInstructions) {
        ProseEditorScreen(
            title = "AI review instructions",
            value = editedGeneralInstructions,
            originalValue = generalInstructions,
            supportingText =
                "These instructions apply to every AI review. Secret and client instructions " +
                    "add more specific context.",
            onValueChange = { editedGeneralInstructions = it },
            onSave = {
                showGeneralInstructions = false
                onSaveGeneralInstructions(editedGeneralInstructions.trim())
            },
            onBack = { showGeneralInstructions = false },
        )
        return
    }
    Column(modifier) {
        TopAppBar(
            title = { Text(stringResource(R.string.secrets)) },
            actions = {
                IconButton(onClick = onCreate) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.new_secret),
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            },
        )
        if (secrets.isEmpty() && pendingUploads.isEmpty()) {
            EmptyMessage(
                title = stringResource(R.string.no_secrets),
                description = stringResource(R.string.no_secrets_description),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (aiReviewActive) {
                    item(key = "ai_review_instructions") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.large,
                            onClick = {
                                editedGeneralInstructions = generalInstructions
                                showGeneralInstructions = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TonalIcon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                Column(Modifier.weight(1f)) {
                                    Text("AI review instructions", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        if (generalInstructions.isBlank()) {
                                            "No general instructions"
                                        } else {
                                            "Applied to every AI review"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(Icons.Outlined.Edit, contentDescription = "Edit AI review instructions")
                            }
                        }
                    }
                }
                if (pendingUploads.isNotEmpty()) {
                    item(key = "incoming_uploads_heading") {
                        SecretListSectionHeading(
                            title = "Incoming uploads",
                            count = pendingUploads.size,
                        )
                    }
                    items(
                        pendingUploads,
                        key = { request -> "upload_${request.id}" },
                    ) { request ->
                        PendingSecretUploadRow(
                            request = request,
                            selected = request.id == selectedUploadRequestId,
                            onClick = { onSelectUpload(request.id) },
                        )
                    }
                    item(key = "stored_secrets_heading") {
                        SecretListSectionHeading(
                            title = "Stored secrets",
                            count = secrets.size,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                if (secrets.isEmpty()) {
                    item(key = "no_stored_secrets") {
                        Text(
                            "No secrets are stored yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
                items(secrets, key = SecretSummary::id) { secret ->
                    val selected = secret.id == selectedSecretId
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = MaterialTheme.shapes.large,
                        onClick = { onSelect(secret.id) },
                        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    secret.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (secret.description.isNotBlank()) {
                                        Text(
                                            secret.description,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        when (secret.type) {
                                            SecretType.SSH ->
                                                secret.sshKey?.fingerprint?.let {
                                                    "SSH key · $it"
                                                } ?: "SSH key unavailable"
                                            SecretType.ENVIRONMENT ->
                                                "${secret.environmentVariableCount} environment " +
                                                    if (secret.environmentVariableCount == 1) {
                                                        "variable"
                                                    } else {
                                                        "variables"
                                                    }
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (secret.temporaryAccessCount > 0) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Outlined.Schedule,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                "Temporary access for " +
                                                    "${secret.temporaryAccessCount} " +
                                                    if (secret.temporaryAccessCount == 1) {
                                                        "client"
                                                    } else {
                                                        "clients"
                                                    },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            },
                            leadingContent = {
                                TonalIcon(
                                    when (secret.type) {
                                        SecretType.SSH -> Icons.Outlined.Key
                                        SecretType.ENVIRONMENT -> Icons.Outlined.DataObject
                                    },
                                    contentDescription = null,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Outlined.NavigateNext,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingSecretUploadRow(
    request: InboxRequestSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val actionRequired = request.state == InboxRequestState.ACTION_REQUIRED
    val secretName = request.secretNames.singleOrNull() ?: "Unnamed secret"
    ActionListSurface(
        actionRequired = actionRequired,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
    ) {
        ListItem(
            headlineContent = {
                Text(secretName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        listOfNotNull(request.title, request.listSummary)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "From ${request.clientName} · ${formatTimestamp(request.receivedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingContent = { TonalIcon(Icons.Outlined.Lock, contentDescription = null) },
            trailingContent = {
                Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = null)
            },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SecretListSectionHeading(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
