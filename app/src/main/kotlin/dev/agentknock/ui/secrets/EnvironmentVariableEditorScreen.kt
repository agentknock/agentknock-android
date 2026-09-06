@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.agentknock.R
import dev.agentknock.ui.components.NavigationBackButton

internal val environmentVariableName = Regex("[A-Za-z_][A-Za-z0-9_]*")

@Composable
internal fun EnvironmentVariableEditorScreen(
    editor: VariableEditorState,
    enabled: Boolean,
    onEditorChange: (VariableEditorState) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val variable = editor.variable
    val currentValue = editor.currentValue
    val name = editor.name
    val value = editor.value
    val sensitive = editor.sensitive
    val editorKey = variable?.id ?: "new:${editor.secretId}"
    var showValue by remember(editorKey) { mutableStateOf(false) }
    var nameInvalid by rememberSaveable(editorKey) { mutableStateOf(false) }
    var menuExpanded by rememberSaveable(editorKey) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(editorKey) { mutableStateOf(false) }
    val dirty = if (variable == null) {
        name.isNotEmpty() || value.isNotEmpty() || !sensitive
    } else {
        name != variable.name ||
            sensitive != variable.sensitive ||
            editor.valueChanged
    }
    fun requestDismiss() {
        if (!enabled) return
        if (dirty) confirmDiscard = true else onDismiss()
    }

    BackHandler(onBack = ::requestDismiss)
    Scaffold(
        topBar = {
            Column {
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
                        NavigationBackButton(
                            onClick = ::requestDismiss,
                            enabled = enabled,
                        )
                    },
                    actions = {
                        if (onDelete != null) {
                            IconButton(
                                onClick = { menuExpanded = true },
                                enabled = enabled,
                            ) {
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
                                    enabled = enabled,
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    },
                )
                if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 720.dp)
                    .align(Alignment.TopCenter),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(editor.secretName, style = MaterialTheme.typography.titleMedium)
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            onEditorChange(editor.copy(name = it))
                            nameInvalid = false
                        },
                        label = { Text(stringResource(R.string.variable_name)) },
                        enabled = enabled,
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
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
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
                    EnvironmentVariableValueField(
                        value = value,
                        sensitive = sensitive,
                        visible = showValue,
                        onVisibilityChange = { showValue = it },
                        onValueChange = {
                            onEditorChange(editor.copy(value = it, valueEdited = true))
                        },
                        enabled = enabled,
                    )
                }
                item {
                    EnvironmentVariableSensitivity(
                        sensitive = sensitive,
                        enabled = enabled,
                        onChange = { onEditorChange(editor.copy(sensitive = it)) },
                    )
                }
                item {
                    Button(
                        onClick = {
                            nameInvalid = !environmentVariableName.matches(name)
                            if (!nameInvalid) onSave()
                        },
                        enabled = enabled && environmentVariableName.matches(name) &&
                            (variable == null || dirty),
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
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            enabled = enabled,
            onDismiss = { confirmDiscard = false },
            onDiscard = onDismiss,
        )
    }
}
