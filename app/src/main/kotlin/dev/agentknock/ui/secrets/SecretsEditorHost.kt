@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.agentknock.R
import dev.agentknock.storage.secret.EnvironmentVariableMetadata

private data class PendingVariableDeletion(
    val variable: EnvironmentVariableMetadata,
    val editor: SecretsEditor.Active,
)

@Composable
internal fun SecretsEditorHost(
    editor: SecretsEditor,
    viewModel: SecretsViewModel,
    snackbar: SnackbarHostState,
) {
    val activeEditor = editor as? SecretsEditor.Active ?: return
    var variablePendingDeletion by remember(activeEditor.session) {
        mutableStateOf<PendingVariableDeletion?>(null)
    }

    when (val editorState = activeEditor.draft) {
        is SecretEditorState -> {
            SecretEditorScreen(
                editor = editorState,
                enabled = activeEditor.phase == EditorPhase.EDITING,
                onEditorChange = { viewModel.updateSecretEditor(activeEditor.session, it) },
                onDismiss = { viewModel.closeEditor(activeEditor.session) },
                onPrepareSshKey = { viewModel.prepareSshKey(activeEditor) },
                onSave = { viewModel.saveSecretEditor(activeEditor) },
                snackbar = snackbar,
            )
        }
        is SshKeyEditorState -> {
            SshKeyEditorScreen(
                editor = editorState,
                enabled = activeEditor.phase == EditorPhase.EDITING,
                onEditorChange = { viewModel.updateSshKeyEditor(activeEditor.session, it) },
                onDismiss = { viewModel.closeEditor(activeEditor.session) },
                onPrepare = { viewModel.prepareSshKey(activeEditor) },
                onReplace = { viewModel.replaceSshKey(activeEditor) },
                snackbar = snackbar,
            )
        }
        is VariableEditorState -> {
            val variable = editorState.variable
            EnvironmentVariableEditorScreen(
                editor = editorState,
                enabled = activeEditor.phase == EditorPhase.EDITING,
                onEditorChange = { viewModel.updateVariableEditor(activeEditor.session, it) },
                onDismiss = { viewModel.closeEditor(activeEditor.session) },
                onDelete = variable?.let {
                    { variablePendingDeletion = PendingVariableDeletion(it, activeEditor) }
                },
                onSave = { viewModel.saveVariableEditor(activeEditor) },
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
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
        iconContentColor = MaterialTheme.colorScheme.error,
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
