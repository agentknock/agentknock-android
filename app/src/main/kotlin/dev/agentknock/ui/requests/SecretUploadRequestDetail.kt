package dev.agentknock.ui.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.SecretUploadRequestDetails
import dev.agentknock.storage.request.SecretUploadRequestState
import dev.agentknock.storage.request.SecretUploadVariableValue
import dev.agentknock.ui.components.ClientIdentity
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.theme.agentknockColors
import kotlinx.coroutines.launch

@Composable
internal fun SecretUploadRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onApprove: (String) -> Unit,
    onReject: () -> Unit,
    authorizeProtectedAction: (String, () -> Unit, (String) -> Unit) -> Unit,
    onReveal: suspend (String) -> SecretUploadVariableValue,
    onSensitivityChange: suspend (String, Boolean) -> Boolean,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val upload = (request.content as InboxRequestContent.SecretUpload).details
    var approvedName by remember(upload.uploadedName, upload.approvedName) {
        mutableStateOf(upload.approvedName ?: upload.uploadedName)
    }
    var editingName by remember { mutableStateOf(false) }
    var revealedValues by remember(request.id) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, request.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) revealedValues = emptyMap()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DetailPage(
        title = "Secret upload",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = upload.state,
        bottomContent = if (upload.state == SecretUploadRequestState.REVIEW_PENDING) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onReject,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.agentknockColors.danger,
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.agentknockColors.danger,
                            ),
                        ) {
                            Text("Reject")
                        }
                        Button(
                            onClick = { onApprove(approvedName.trim()) },
                            enabled = approvedName.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.agentknockColors.success,
                                contentColor = MaterialTheme.agentknockColors.onSuccess,
                            ),
                        ) {
                            Text("Approve")
                        }
                    }
                }
            }
        } else {
            null
        },
    ) {
        InformationSurface {
            StatusLine(
                upload.state.label(),
                upload.state == SecretUploadRequestState.VERIFICATION_FAILED,
                attention = upload.state == SecretUploadRequestState.REVIEW_PENDING,
                subdued = upload.state == SecretUploadRequestState.REJECTED,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    approvedName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (
                    upload.mode == SecretUploadMode.CREATE &&
                    upload.state == SecretUploadRequestState.REVIEW_PENDING
                ) {
                    IconButton(onClick = { editingName = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Rename secret")
                    }
                }
            }
            InformationRow(
                "Type",
                if (upload.secretType == "ssh") "SSH key" else "Environment variables",
            )
            ClientIdentity(upload.clientName)
            InformationRow("Received", formatTimestamp(request.receivedAt))
            upload.description?.takeIf(String::isNotBlank)?.let {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Description",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(it)
                }
            }
        }

        if (upload.secretType == "ssh") {
            SshKeyUploadDetails(upload)
        } else {
            EnvironmentVariableUploadDetails(
                upload = upload,
                revealedValues = revealedValues,
                onSetRevealedValues = { revealedValues = it },
                authorizeProtectedAction = authorizeProtectedAction,
                onReveal = onReveal,
                onSensitivityChange = onSensitivityChange,
                report = report,
            )
        }

        if (upload.state != SecretUploadRequestState.REVIEW_PENDING) {
            upload.approvedName?.takeIf { it != upload.uploadedName }?.let {
                DetailValue("Uploaded name", upload.uploadedName)
            }
            if (upload.state == SecretUploadRequestState.APPROVED) {
                val name = upload.approvedName ?: upload.uploadedName
                Notice(
                    "Upload approved",
                    when (upload.mode) {
                        SecretUploadMode.CREATE -> "$name was created."
                        SecretUploadMode.UPDATE -> "$name was updated."
                        SecretUploadMode.REPLACE -> "$name was replaced."
                    },
                    NoticeTone.SUCCESS,
                )
            }
            upload.error?.let {
                Notice("Upload could not be verified", it, NoticeTone.DANGER)
            }
        }

        Disclosure("Technical details") {
            upload.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            DetailValue("Client ID", upload.clientId, true)
            DetailValue("Request ID", request.id, true)
        }
    }
    if (editingName) {
        var editedName by remember(approvedName) { mutableStateOf(approvedName) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Secret name") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editedName.isNotBlank() && editedName.trim() != approvedName,
                    onClick = {
                        approvedName = editedName.trim()
                        editingName = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EnvironmentVariableUploadDetails(
    upload: SecretUploadRequestDetails,
    revealedValues: Map<String, String>,
    onSetRevealedValues: (Map<String, String>) -> Unit,
    authorizeProtectedAction: (String, () -> Unit, (String) -> Unit) -> Unit,
    onReveal: suspend (String) -> SecretUploadVariableValue,
    onSensitivityChange: suspend (String, Boolean) -> Boolean,
    report: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (upload.mode != SecretUploadMode.CREATE) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Changes to the existing secret",
                    style = MaterialTheme.typography.titleMedium,
                )
                ChangeGroup("New", upload.addedVariables)
                ChangeGroup("Updated", upload.changedVariables)
                ChangeGroup("Removed", upload.removedVariables)
                ChangeGroup("Unchanged", upload.unchangedVariables, subdued = true)
            }
        }
    }

    Text(
        if (upload.state == SecretUploadRequestState.REVIEW_PENDING) {
            "Uploaded environment variables"
        } else {
            "Uploaded environment variable names"
        },
        style = MaterialTheme.typography.titleMedium,
    )
    if (upload.state == SecretUploadRequestState.REVIEW_PENDING) {
        Text(
            "Values are hidden by default. Reveal any value you want to inspect, and " +
                "choose which values remain sensitive after saving.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (upload.variableNames.isEmpty()) Text("No environment variables")
    if (upload.state != SecretUploadRequestState.REVIEW_PENDING) {
        SelectionContainer {
            Text(upload.variableNames.joinToString("\n"), fontFamily = FontFamily.Monospace)
        }
        Text(
            "Uploaded values were discarded after this upload was decided.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (upload.state == SecretUploadRequestState.REVIEW_PENDING) {
        upload.variables.forEach { variable ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val value = revealedValues[variable.id]
                    val changeLabel = when {
                        variable.name in upload.addedVariables -> "New"
                        variable.name in upload.changedVariables -> "Updated"
                        else -> null
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            variable.name,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        changeLabel?.let {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = RoundedCornerShape(100.dp),
                            ) {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Sensitive", style = MaterialTheme.typography.labelMedium)
                            Text(
                                if (variable.sensitive) {
                                    "Protected by approval settings"
                                } else {
                                    "Provided without approval"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = variable.sensitive,
                            enabled = upload.state == SecretUploadRequestState.REVIEW_PENDING,
                            onCheckedChange = { sensitive ->
                                val change: () -> Unit = {
                                    scope.launch {
                                        if (!onSensitivityChange(variable.id, sensitive)) {
                                            report("Sensitivity could not be changed")
                                        }
                                    }
                                }
                                if (sensitive) {
                                    change()
                                } else {
                                    authorizeProtectedAction(
                                        "Mark ${variable.name} non-sensitive",
                                        change,
                                        report,
                                    )
                                }
                            },
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "Sensitive handling for ${variable.name}"
                            },
                        )
                    }
                    OutlinedTextField(
                        value = value ?: "••••••••",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Uploaded value") },
                        minLines = 1,
                        maxLines = 6,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (value != null) {
                                        onSetRevealedValues(revealedValues - variable.id)
                                        return@IconButton
                                    }
                                    val reveal: () -> Unit = {
                                        scope.launch {
                                            when (val result = onReveal(variable.id)) {
                                                is SecretUploadVariableValue.Available -> {
                                                    onSetRevealedValues(
                                                        revealedValues +
                                                            (variable.id to result.value),
                                                    )
                                                }
                                                SecretUploadVariableValue.NotFound ->
                                                    report(
                                                        "This environment variable is no longer available",
                                                    )
                                                SecretUploadVariableValue.Unavailable ->
                                                    report("The encryption key is unavailable")
                                                SecretUploadVariableValue.Corrupted ->
                                                    report(
                                                        "The uploaded value could not be authenticated",
                                                    )
                                                SecretUploadVariableValue.UnsupportedEncryption ->
                                                    report(
                                                        "The uploaded value uses unsupported encryption",
                                                    )
                                            }
                                        }
                                    }
                                    authorizeProtectedAction(
                                        "Show uploaded value",
                                        reveal,
                                        report,
                                    )
                                },
                            ) {
                                Icon(
                                    if (value == null) {
                                        Icons.Outlined.Visibility
                                    } else {
                                        Icons.Outlined.VisibilityOff
                                    },
                                    contentDescription = if (value == null) {
                                        "Show uploaded value for ${variable.name}"
                                    } else {
                                        "Hide uploaded value for ${variable.name}"
                                    },
                                )
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SshKeyUploadDetails(upload: SecretUploadRequestDetails) {
    if (upload.mode != SecretUploadMode.CREATE) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Changes to the existing SSH key",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (upload.keyChanged) {
                    upload.previousFingerprint?.let {
                        DetailValue("Current fingerprint", it, true)
                    }
                    upload.fingerprint?.let { DetailValue("New fingerprint", it, true) }
                } else if (upload.publicKey != upload.previousPublicKey) {
                    Text(
                        "The public key comment will change; the key material is unchanged.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "The key material is unchanged.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    upload.publicKey?.let { publicKey ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (upload.keyChanged || upload.mode == SecretUploadMode.CREATE) {
                        "Incoming public key"
                    } else {
                        "Public key"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                DetailValue(
                    "Algorithm",
                    when (publicKey.substringBefore(' ')) {
                        "ssh-ed25519" -> "Ed25519"
                        "ssh-rsa" -> "RSA"
                        else -> publicKey.substringBefore(' ')
                    },
                )
                upload.fingerprint?.let { DetailValue("Fingerprint", it, true) }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "OpenSSH public key",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            publicKey,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    } ?: Text(
        "No replacement key was included.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChangeGroup(label: String, names: List<String>, subdued: Boolean = false) {
    if (names.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            names.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            color = if (subdued) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                Color.Unspecified
            },
        )
    }
}

private fun SecretUploadRequestState.label(): String = when (this) {
    SecretUploadRequestState.REVIEW_PENDING -> "Needs review"
    SecretUploadRequestState.APPROVED -> "Approved"
    SecretUploadRequestState.REJECTED -> "Rejected"
    SecretUploadRequestState.VERIFICATION_FAILED -> "Verification failed"
}
