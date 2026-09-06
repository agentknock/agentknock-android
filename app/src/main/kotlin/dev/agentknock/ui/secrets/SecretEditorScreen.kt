@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.agentknock.R
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.ui.components.NavigationBackButton

@Composable
internal fun SecretEditorScreen(
    editor: SecretEditorState,
    enabled: Boolean,
    onEditorChange: (SecretEditorState) -> Unit,
    onDismiss: () -> Unit,
    onPrepareSshKey: () -> Unit,
    onSave: () -> Unit,
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
            editor.sshKeyDraft.preparedKey != null ||
            editor.environmentVariables != listOf(EnvironmentVariableDraft())
    } else {
        name != secret.name || description != secret.description
    }
    fun requestDismiss() {
        if (!enabled) return
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
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (secret == null) R.string.new_secret else R.string.edit_secret,
                            ),
                        )
                    },
                    navigationIcon = {
                        NavigationBackButton(
                            onClick = ::requestDismiss,
                            enabled = enabled,
                        )
                    },
                )
                if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 720.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(scrollState)
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
                            selected = editor.type == SecretType.ENVIRONMENT,
                            enabled = enabled,
                            onClick = {
                                onEditorChange(
                                    editor.copy(
                                        type = SecretType.ENVIRONMENT,
                                        sshKeyDraft = editor.sshKeyDraft.withoutPreparation(),
                                    ),
                                )
                            },
                            label = { Text("Environment variables", maxLines = 1) },
                        )
                        FilterChip(
                            selected = editor.type == SecretType.SSH,
                            enabled = enabled,
                            onClick = {
                                onEditorChange(
                                    editor.copy(
                                        type = SecretType.SSH,
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
                Text(editor.type.displayName(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    onEditorChange(editor.copy(name = it))
                    validationError = null
                },
                label = { Text(stringResource(R.string.secret_name)) },
                enabled = enabled,
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
            if (secret != null) {
                Text("Commands that request this secret by name must use the new name after renaming.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = description,
                onValueChange = { onEditorChange(editor.copy(description = it)) },
                label = { Text(stringResource(R.string.description_optional)) },
                enabled = enabled,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (secret == null && editor.type == SecretType.SSH) {
                SshKeyInput(
                    draft = editor.sshKeyDraft,
                    enabled = enabled,
                    onDraftChange = { draft ->
                        onEditorChange(editor.copy(sshKeyDraft = draft))
                    },
                    onPrepare = onPrepareSshKey,
                )
            }
            if (secret == null && editor.type == SecretType.ENVIRONMENT) {
                EnvironmentVariableDrafts(
                    variables = editor.environmentVariables,
                    enabled = enabled,
                    onChange = { variables ->
                        onEditorChange(editor.copy(environmentVariables = variables))
                    },
                )
            }
            if (
                secret != null || editor.type != SecretType.SSH ||
                editor.sshKeyDraft.preparedKey != null
            ) {
                Button(
                    onClick = {
                        validationError = when {
                            name.isBlank() -> R.string.secret_name_required
                            name != name.trim() -> R.string.secret_name_whitespace
                            else -> null
                        }
                        if (validationError == null) onSave()
                    },
                    enabled = enabled && name.isNotBlank() &&
                        editor.environmentVariables
                            .filter { it.name.isNotBlank() || it.value.isNotEmpty() }
                            .let { variables ->
                                variables.all { environmentVariableName.matches(it.name) } &&
                                    variables.map { it.name }.distinct().size == variables.size
                            } && (
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
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            enabled = enabled,
            onDismiss = { confirmDiscard = false },
            onDiscard = onDismiss,
        )
    }
}
