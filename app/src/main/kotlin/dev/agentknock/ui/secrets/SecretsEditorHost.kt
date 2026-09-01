@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.agentknock.R
import dev.agentknock.storage.secret.EnvironmentVariableMetadata

private data class PendingVariableDeletion(
    val variable: EnvironmentVariableMetadata,
    val editor: SecretsEditor.Variable,
)

@Composable
internal fun SecretsEditorHost(
    editor: SecretsEditor,
    viewModel: SecretsViewModel,
    snackbar: SnackbarHostState,
) {
    val resources = LocalResources.current
    val editorSession = when (editor) {
        SecretsEditor.None -> null
        is SecretsEditor.Secret -> editor.session
        is SecretsEditor.SshKey -> editor.session
        is SecretsEditor.Variable -> editor.session
    }
    var variablePendingDeletion by remember(editorSession) {
        mutableStateOf<PendingVariableDeletion?>(null)
    }

    when (val activeEditor = editor) {
        SecretsEditor.None -> Unit
        is SecretsEditor.Secret -> {
            val editorState = activeEditor.state
            SecretEditorScreen(
                editor = editorState,
                enabled = activeEditor.phase == EditorPhase.EDITING,
                onEditorChange = { viewModel.updateSecretEditor(activeEditor.session, it) },
                onDismiss = { viewModel.closeEditor(activeEditor.session) },
                onPrepareSshKey = viewModel::prepareSecretSshKey,
                onSave = { name, description ->
                    viewModel.saveSecretEditor(activeEditor, name, description)
                },
                snackbar = snackbar,
            )
        }
        is SecretsEditor.SshKey -> {
            val editorState = activeEditor.state
            SshKeyEditorScreen(
                editor = editorState,
                enabled = activeEditor.phase == EditorPhase.EDITING,
                onEditorChange = { viewModel.updateSshKeyEditor(activeEditor.session, it) },
                onDismiss = { viewModel.closeEditor(activeEditor.session) },
                onPrepare = viewModel::prepareReplacementSshKey,
                onReplace = { viewModel.replaceSshKey(activeEditor) },
                snackbar = snackbar,
            )
        }
        is SecretsEditor.Variable -> {
            val editorState = activeEditor.state
            val variable = editorState.variable
            EnvironmentVariableEditorScreen(
                editor = editorState,
                enabled = activeEditor.phase == EditorPhase.EDITING,
                onEditorChange = { viewModel.updateVariableEditor(activeEditor.session, it) },
                onDismiss = { viewModel.closeEditor(activeEditor.session) },
                onDelete = variable?.let {
                    { variablePendingDeletion = PendingVariableDeletion(it, activeEditor) }
                },
                onSave = { name, value, sensitive, notes, replaceValue ->
                    val weakensProtection = !sensitive && editorState.variable?.sensitive != false
                    viewModel.saveVariableEditor(
                        expected = activeEditor,
                        name = name,
                        value = value,
                        sensitive = sensitive,
                        notes = notes,
                        replaceValue = replaceValue,
                        protectionTitle = if (weakensProtection) {
                            resources.getString(
                                R.string.confirm_mark_variable_non_sensitive,
                                name,
                            )
                        } else {
                            null
                        },
                    )
                },
                snackbar = snackbar,
            )
        }
    }

    variablePendingDeletion?.let { pending ->
        val variable = pending.variable
        DeleteDialog(
            title = stringResource(R.string.delete_variable_question, variable.name),
            explanation = stringResource(R.string.delete_variable_explanation),
            onDismiss = { variablePendingDeletion = null },
            onDelete = {
                variablePendingDeletion = null
                viewModel.deleteEnvironmentVariable(pending.editor)
            },
        )
    }
}
@Composable
internal fun DeleteDialog(
    title: String,
    explanation: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(explanation) },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(
                    stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
