@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    onSave: (
        name: String,
        value: String,
        sensitive: Boolean,
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
            (currentValue != null && value != currentValue) ||
            (currentValue == null && valueEdited)
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 720.dp)
                    .align(Alignment.TopCenter),
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
                        enabled = enabled,
                        visualTransformation = if (sensitive && !showValue) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = if (sensitive) {
                                KeyboardType.Password
                            } else {
                                KeyboardType.Text
                            },
                        ),
                        minLines = 1,
                        maxLines = 6,
                        trailingIcon = if (sensitive) {
                            {
                                IconButton(
                                    onClick = { showValue = !showValue },
                                    enabled = enabled,
                                ) {
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
                                enabled = enabled,
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
                        Switch(
                            checked = sensitive,
                            onCheckedChange = null,
                            enabled = enabled,
                        )
                    }
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
                                onSave(name, value, sensitive, valueChanged)
                            }
                        },
                        enabled = enabled && environmentVariableName.matches(name) && (
                            variable == null ||
                                name != variable.name ||
                                sensitive != variable.sensitive ||
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
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            enabled = enabled,
            onDismiss = { confirmDiscard = false },
            onDiscard = onDismiss,
        )
    }
}
