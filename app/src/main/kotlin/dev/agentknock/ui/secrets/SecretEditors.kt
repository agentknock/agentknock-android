@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.agentknock.R
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshKeyMetadata
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.NavigationBackButton

private val environmentVariableName = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal enum class SshKeyInputMode { GENERATE, IMPORT }

internal data class SshKeyDraft(
    val inputMode: SshKeyInputMode = SshKeyInputMode.GENERATE,
    val algorithm: SshKeyAlgorithm = SshKeyAlgorithm.ED25519,
    val privateKeyText: String = "",
    val comment: String = "",
    val preparedKey: SshPrivateKey? = null,
    val error: String? = null,
) {
    fun withoutPreparation(): SshKeyDraft = copy(preparedKey = null, error = null)
}

internal data class SecretEditorState(
    val secret: SecretDetails?,
    val name: String,
    val description: String,
    val type: String,
    val sshKeyDraft: SshKeyDraft = SshKeyDraft(),
)

internal data class SshKeyEditorState(
    val secretId: String,
    val secretName: String,
    val currentKey: SshKeyMetadata,
    val sshKeyDraft: SshKeyDraft,
)

internal data class VariableEditorState(
    val secretId: String,
    val variable: EnvironmentVariableMetadata?,
    val currentValue: String?,
    val name: String,
    val value: String,
    val valueEdited: Boolean,
    val sensitive: Boolean,
    val notes: String,
)

@Composable
internal fun SecretEditorScreen(
    editor: SecretEditorState,
    onEditorChange: (SecretEditorState) -> Unit,
    onDismiss: () -> Unit,
    onPrepareSshKey: () -> Unit,
    onSave: (name: String, description: String) -> Unit,
    snackbar: SnackbarHostState,
) {
    val secret = editor.secret
    val name = editor.name
    val description = editor.description
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    var validationError by rememberSaveable(secret?.id) { mutableStateOf<Int?>(null) }
    var confirmDiscard by rememberSaveable(secret?.id) { mutableStateOf(false) }
    val dirty = if (secret == null) {
        name.isNotEmpty() || description.isNotEmpty() ||
            editor.sshKeyDraft.privateKeyText.isNotEmpty() ||
            editor.sshKeyDraft.comment.isNotEmpty() ||
            editor.sshKeyDraft.preparedKey != null
    } else {
        name != secret.name || description != secret.description
    }
    fun requestDismiss() {
        if (dirty) confirmDiscard = true else onDismiss()
    }

    LaunchedEffect(editor.sshKeyDraft.preparedKey) {
        if (editor.sshKeyDraft.preparedKey != null) {
            focusManager.clearFocus()
            // Let the preview and save action participate in layout before moving them
            // into view. Generating a key is a transition to review, not an invitation to
            // keep editing the field that happened to retain focus.
            withFrameNanos { }
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    BackHandler(onBack = ::requestDismiss)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (secret == null) R.string.new_secret else R.string.edit_secret,
                        ),
                    )
                },
                navigationIcon = {
                    NavigationBackButton(::requestDismiss)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (secret == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Secret type", style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = editor.type == ENVIRONMENT_SECRET_TYPE,
                            onClick = {
                                onEditorChange(
                                    editor.copy(
                                        type = ENVIRONMENT_SECRET_TYPE,
                                        sshKeyDraft = editor.sshKeyDraft.withoutPreparation(),
                                    ),
                                )
                            },
                            label = { Text("Environment variables", maxLines = 1) },
                        )
                        FilterChip(
                            selected = editor.type == SSH_SECRET_TYPE,
                            onClick = {
                                onEditorChange(
                                    editor.copy(
                                        type = SSH_SECRET_TYPE,
                                        sshKeyDraft = editor.sshKeyDraft.copy(error = null),
                                    ),
                                )
                            },
                            label = { Text("SSH key", maxLines = 1) },
                        )
                    }
                    Text(
                        "A secret's type cannot be changed after it is created.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                InformationSurface {
                    InformationRow("Type", editor.type.displayName())
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    onEditorChange(editor.copy(name = it))
                    validationError = null
                },
                label = { Text(stringResource(R.string.secret_name)) },
                singleLine = true,
                isError = validationError != null,
                supportingText = validationError?.let { error ->
                    { Text(stringResource(error)) }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { onEditorChange(editor.copy(description = it)) },
                label = { Text(stringResource(R.string.description_optional)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (secret == null && editor.type == SSH_SECRET_TYPE) {
                SshKeyInput(
                    draft = editor.sshKeyDraft,
                    onDraftChange = { draft ->
                        onEditorChange(editor.copy(sshKeyDraft = draft))
                    },
                    onPrepare = onPrepareSshKey,
                )
            }
            if (
                secret != null || editor.type != SSH_SECRET_TYPE ||
                editor.sshKeyDraft.preparedKey != null
            ) {
                Button(
                    onClick = {
                        validationError = when {
                            name.isBlank() -> R.string.secret_name_required
                            name != name.trim() -> R.string.secret_name_whitespace
                            else -> null
                        }
                        if (validationError == null) onSave(name, description)
                    },
                    enabled = name.isNotBlank() && (
                        secret == null ||
                            name != secret.name ||
                            description != secret.description
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (secret == null) R.string.create_secret else R.string.save_secret,
                        ),
                    )
                }
            }
        }
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            onDismiss = { confirmDiscard = false },
            onDiscard = onDismiss,
        )
    }
}

@Composable
private fun SshKeyInput(
    draft: SshKeyDraft,
    onDraftChange: (SshKeyDraft) -> Unit,
    onPrepare: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Key material", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.inputMode == SshKeyInputMode.GENERATE,
                onClick = {
                    onDraftChange(
                        draft.copy(inputMode = SshKeyInputMode.GENERATE).withoutPreparation(),
                    )
                },
                label = { Text("Generate") },
            )
            FilterChip(
                selected = draft.inputMode == SshKeyInputMode.IMPORT,
                onClick = {
                    onDraftChange(
                        draft.copy(inputMode = SshKeyInputMode.IMPORT).withoutPreparation(),
                    )
                },
                label = { Text("Import") },
            )
        }
        if (draft.inputMode == SshKeyInputMode.GENERATE) {
            Text("Algorithm", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = draft.algorithm == SshKeyAlgorithm.ED25519,
                    onClick = {
                        onDraftChange(
                            draft.copy(algorithm = SshKeyAlgorithm.ED25519)
                                .withoutPreparation(),
                        )
                    },
                    label = { Text("Ed25519") },
                )
                FilterChip(
                    selected = draft.algorithm == SshKeyAlgorithm.RSA,
                    onClick = {
                        onDraftChange(
                            draft.copy(algorithm = SshKeyAlgorithm.RSA).withoutPreparation(),
                        )
                    },
                    label = { Text("RSA") },
                )
            }
            Text(
                when (draft.algorithm) {
                    SshKeyAlgorithm.ED25519 ->
                        "Generate a new Ed25519 key on this device. Recommended for new keys."
                    SshKeyAlgorithm.RSA ->
                        "Generate a new 3072-bit RSA key for systems that require RSA."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft.comment,
                onValueChange = { comment ->
                    onDraftChange(draft.copy(comment = comment).withoutPreparation())
                },
                label = { Text("Public key comment (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "Paste an unencrypted OpenSSH Ed25519 or RSA private key. Encrypted keys must be " +
                    "decrypted before import.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft.privateKeyText,
                onValueChange = { privateKeyText ->
                    onDraftChange(
                        draft.copy(privateKeyText = privateKeyText).withoutPreparation(),
                    )
                },
                label = { Text("OpenSSH private key") },
                minLines = 6,
                maxLines = 12,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (draft.preparedKey == null) {
            FilledTonalButton(
                onClick = onPrepare,
                enabled = draft.inputMode == SshKeyInputMode.GENERATE ||
                    draft.privateKeyText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (draft.inputMode == SshKeyInputMode.GENERATE) {
                        "Generate and review"
                    } else {
                        "Review key"
                    },
                )
            }
        } else {
            SshKeyPreview(draft.preparedKey)
        }
        draft.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SshKeyPreview(key: SshPrivateKey) {
    InformationSurface {
        Text("Ready to save", style = MaterialTheme.typography.titleMedium)
        InformationRow("Algorithm", key.algorithm.storedName.sshAlgorithmDisplayName())
        InformationRow("Fingerprint", key.fingerprint)
        if (key.comment.isNotBlank()) InformationRow("Comment", key.comment)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "OpenSSH public key",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    key.publicKeyLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
internal fun SshKeyEditorScreen(
    editor: SshKeyEditorState,
    onEditorChange: (SshKeyEditorState) -> Unit,
    onDismiss: () -> Unit,
    onPrepare: () -> Unit,
    onReplace: () -> Unit,
    snackbar: SnackbarHostState,
) {
    var confirmDiscard by rememberSaveable(editor.secretId) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val draft = editor.sshKeyDraft
    val dirty = draft.privateKeyText.isNotEmpty() || draft.preparedKey != null ||
        draft.comment != editor.currentKey.comment ||
        draft.algorithm.storedName != editor.currentKey.algorithm
    LaunchedEffect(draft.preparedKey) {
        if (draft.preparedKey != null) {
            withFrameNanos { }
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    fun requestDismiss() {
        if (dirty) confirmDiscard = true else onDismiss()
    }
    BackHandler(onBack = ::requestDismiss)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Replace SSH key") },
                navigationIcon = {
                    NavigationBackButton(::requestDismiss)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            InformationSurface {
                Text(editor.secretName, style = MaterialTheme.typography.titleMedium)
                InformationRow("Current fingerprint", editor.currentKey.fingerprint)
                Text(
                    "Replacing the private key changes the public key while keeping the secret's " +
                        "name and request references.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SshKeyInput(
                draft = draft,
                onDraftChange = { sshKeyDraft ->
                    onEditorChange(editor.copy(sshKeyDraft = sshKeyDraft))
                },
                onPrepare = {
                    focusManager.clearFocus()
                    onPrepare()
                },
            )
            Button(
                onClick = onReplace,
                enabled = draft.preparedKey != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Replace key") }
        }
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            onDismiss = { confirmDiscard = false },
            onDiscard = onDismiss,
        )
    }
}

@Composable
internal fun EnvironmentVariableEditorScreen(
    editor: VariableEditorState,
    onEditorChange: (VariableEditorState) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (
        name: String,
        value: String,
        sensitive: Boolean,
        notes: String,
        replaceValue: Boolean,
    ) -> Unit,
    snackbar: SnackbarHostState,
) {
    val variable = editor.variable
    val currentValue = editor.currentValue
    val name = editor.name
    val value = editor.value
    val valueEdited = editor.valueEdited
    val sensitive = editor.sensitive
    val notes = editor.notes
    val editorKey = variable?.id ?: "new:${editor.secretId}"
    var showValue by remember(editorKey) { mutableStateOf(false) }
    var nameInvalid by rememberSaveable(editorKey) { mutableStateOf(false) }
    var menuExpanded by rememberSaveable(editorKey) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(editorKey) { mutableStateOf(false) }
    val dirty = if (variable == null) {
        name.isNotEmpty() || value.isNotEmpty() || notes.isNotEmpty() || !sensitive
    } else {
        name != variable.name ||
            sensitive != variable.sensitive ||
            notes != variable.notes ||
            (currentValue != null && value != currentValue) ||
            (currentValue == null && valueEdited)
    }
    fun requestDismiss() {
        if (dirty) confirmDiscard = true else onDismiss()
    }

    BackHandler(onBack = ::requestDismiss)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (variable == null) {
                                R.string.new_environment_variable
                            } else {
                                R.string.edit_environment_variable
                            },
                        ),
                    )
                },
                navigationIcon = {
                    NavigationBackButton(::requestDismiss)
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            onEditorChange(editor.copy(name = it))
                            nameInvalid = false
                        },
                        label = { Text(stringResource(R.string.variable_name)) },
                        singleLine = true,
                        isError = nameInvalid,
                        supportingText = if (nameInvalid) {
                            { Text(stringResource(R.string.variable_name_invalid)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (variable != null && currentValue == null) {
                    item {
                        Text(
                            stringResource(R.string.replace_unavailable_value),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            onEditorChange(editor.copy(value = it, valueEdited = true))
                        },
                        label = { Text(stringResource(R.string.variable_value)) },
                        visualTransformation = if (sensitive && !showValue) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        minLines = 1,
                        maxLines = 6,
                        trailingIcon = if (sensitive) {
                            {
                                IconButton(onClick = { showValue = !showValue }) {
                                    Icon(
                                        if (showValue) {
                                            Icons.Outlined.VisibilityOff
                                        } else {
                                            Icons.Outlined.Visibility
                                        },
                                        contentDescription = stringResource(
                                            if (showValue) R.string.hide else R.string.show,
                                        ),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = sensitive,
                                role = Role.Switch,
                                onValueChange = {
                                    onEditorChange(editor.copy(sensitive = it))
                                },
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sensitive))
                            Text(
                                stringResource(R.string.sensitive_explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = sensitive, onCheckedChange = null)
                    }
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { onEditorChange(editor.copy(notes = it)) },
                        label = { Text(stringResource(R.string.notes_optional)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Button(
                        onClick = {
                            nameInvalid = !environmentVariableName.matches(name)
                            if (!nameInvalid) {
                                val valueChanged = when {
                                    variable == null -> true
                                    currentValue != null -> value != currentValue
                                    else -> valueEdited
                                }
                                onSave(name, value, sensitive, notes, valueChanged)
                            }
                        },
                        enabled = environmentVariableName.matches(name) && (
                            variable == null ||
                                name != variable.name ||
                                sensitive != variable.sensitive ||
                                notes != variable.notes ||
                                (currentValue != null && value != currentValue) ||
                                (currentValue == null && valueEdited)
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (variable == null) {
                                    R.string.create_variable
                                } else {
                                    R.string.save_variable
                                },
                            ),
                        )
                    }
                }
            }
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            onDismiss = { confirmDiscard = false },
            onDiscard = onDismiss,
        )
    }
}

@Composable
private fun DiscardChangesDialog(onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard changes?") },
        text = { Text("Your unsaved changes will be lost.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep editing") } },
    )
}
