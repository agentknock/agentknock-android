@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.agentknock.R
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.SaveEnvironmentVariableResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.storage.secret.SaveSshSecretResult
import dev.agentknock.storage.secret.SecretType
import kotlinx.coroutines.launch

private data class PendingVariableDeletion(
    val variable: EnvironmentVariableMetadata,
    val editorSession: Long,
)

@Composable
internal fun SecretsEditorHost(
    editor: SecretsEditor,
    selectedTarget: SecretTarget?,
    authorizeProtectedAction: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    viewModel: SecretsViewModel,
    snackbar: SnackbarHostState,
    report: (String) -> Unit,
    onVariableValueHidden: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var variablePendingDeletion by remember { mutableStateOf<PendingVariableDeletion?>(null) }

    LaunchedEffect(selectedTarget) {
        variablePendingDeletion = null
    }

    fun afterProtection(title: String, action: suspend () -> Unit) {
        authorizeProtectedAction(
            title,
            { scope.launch { action() } },
            report,
        )
    }

    when (val activeEditor = editor) {
        SecretsEditor.None -> Unit
        is SecretsEditor.Secret -> {
            val editorState = activeEditor.state
            SecretEditorScreen(
                editor = editorState,
                onEditorChange = { viewModel.updateSecretEditor(activeEditor.session, it) },
                onDismiss = { viewModel.closeEditor(activeEditor.session) },
                onPrepareSshKey = viewModel::prepareSecretSshKey,
                onSave = { name, description ->
                    scope.launch {
                        if (!viewModel.editorIsCurrent(activeEditor)) return@launch
                        val error = if (editorState.secret == null) {
                            when (
                                val result = when (editorState.type) {
                                    SecretType.SSH -> viewModel.createSshSecret(
                                            name,
                                            description,
                                            checkNotNull(editorState.sshKeyDraft.preparedKey),
                                        )
                                    SecretType.ENVIRONMENT ->
                                        viewModel.createSecret(name, description)
                                }
                            ) {
                                is CreateSecretResult.Created -> {
                                    viewModel.showCreatedSecretIfEditorCurrent(
                                        activeEditor,
                                        result.id,
                                    )
                                    null
                                }
                                CreateSecretResult.NameInUse -> resources.getString(
                                    R.string.secret_name_in_use,
                                )
                            }
                        } else {
                            when (
                                viewModel.saveSecret(editorState.secret.id, name, description)
                            ) {
                                SaveSecretResult.SAVED -> {
                                    viewModel.closeEditorIfCurrent(activeEditor)
                                    null
                                }
                                SaveSecretResult.NAME_IN_USE -> resources.getString(
                                    R.string.secret_name_in_use,
                                )
                                SaveSecretResult.NOT_FOUND -> resources.getString(
                                    R.string.secret_not_found,
                                )
                            }
                        }
                        if (error != null && viewModel.editorIsCurrent(activeEditor)) report(error)
                    }
                },
                snackbar = snackbar,
            )
        }
        is SecretsEditor.SshKey -> {
            val editorState = activeEditor.state
            SshKeyEditorScreen(
                editor = editorState,
                onEditorChange = { viewModel.updateSshKeyEditor(activeEditor.session, it) },
                onDismiss = { viewModel.closeEditor(activeEditor.session) },
                onPrepare = viewModel::prepareReplacementSshKey,
                onReplace = {
                    scope.launch {
                        if (!viewModel.editorIsCurrent(activeEditor)) return@launch
                        when (
                            viewModel.replaceSshKey(
                                editorState.secretId,
                                checkNotNull(editorState.sshKeyDraft.preparedKey),
                            )
                        ) {
                            is SaveSshSecretResult.Saved -> {
                                viewModel.closeEditorIfCurrent(activeEditor)
                                report("SSH key replaced")
                            }
                            else -> report("SSH key could not be replaced")
                        }
                    }
                },
                snackbar = snackbar,
            )
        }
        is SecretsEditor.Variable -> {
            val editorState = activeEditor.state
            val variable = editorState.variable
            EnvironmentVariableEditorScreen(
                editor = editorState,
                onEditorChange = { viewModel.updateVariableEditor(activeEditor.session, it) },
                onDismiss = { viewModel.closeEditor(activeEditor.session) },
                onDelete = variable?.let {
                    { variablePendingDeletion = PendingVariableDeletion(it, activeEditor.session) }
                },
                onSave = { name, value, sensitive, notes, replaceValue ->
                    val save: suspend () -> Unit = save@{
                        if (!viewModel.editorIsCurrent(activeEditor)) return@save
                        val error = if (editorState.variable == null) {
                            when (
                                viewModel.createEnvironmentVariable(
                                    secretId = editorState.secretId,
                                    name = name,
                                    value = value,
                                    sensitive = sensitive,
                                    notes = notes,
                                )
                            ) {
                                is CreateEnvironmentVariableResult.Created -> {
                                    viewModel.closeEditorIfCurrent(activeEditor)
                                    null
                                }
                                CreateEnvironmentVariableResult.NameInUse -> resources.getString(
                                    R.string.variable_name_in_use,
                                )
                                CreateEnvironmentVariableResult.SecretNotFound -> resources.getString(
                                    R.string.secret_not_found,
                                )
                            }
                        } else {
                            when (
                                viewModel.saveEnvironmentVariable(
                                    id = editorState.variable.id,
                                    name = name,
                                    sensitive = sensitive,
                                    notes = notes,
                                    replacementValue = value.takeIf { replaceValue },
                                )
                            ) {
                                SaveEnvironmentVariableResult.SAVED -> {
                                    viewModel.closeEditorIfCurrent(activeEditor)
                                    onVariableValueHidden(editorState.variable.id)
                                    null
                                }
                                SaveEnvironmentVariableResult.NAME_IN_USE -> resources.getString(
                                    R.string.variable_name_in_use,
                                )
                                SaveEnvironmentVariableResult.NOT_FOUND -> resources.getString(
                                    R.string.variable_not_found,
                                )
                                SaveEnvironmentVariableResult.VALUE_UNAVAILABLE -> resources.getString(
                                    R.string.stored_value_unavailable,
                                )
                                SaveEnvironmentVariableResult.VALUE_CORRUPTED -> resources.getString(
                                    R.string.stored_value_corrupted,
                                )
                                SaveEnvironmentVariableResult.UNSUPPORTED_FORMAT -> resources.getString(
                                    R.string.stored_value_unsupported,
                                )
                            }
                        }
                        if (error != null && viewModel.editorIsCurrent(activeEditor)) report(error)
                    }
                    val weakensProtection = !sensitive && editorState.variable?.sensitive != false
                    if (weakensProtection) {
                        afterProtection(
                            resources.getString(
                                R.string.confirm_mark_variable_non_sensitive,
                                name,
                            ),
                            save,
                        )
                    } else {
                        scope.launch { save() }
                    }
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
                scope.launch {
                    if (viewModel.deleteEnvironmentVariable(variable.id)) {
                        viewModel.closeEditor(pending.editorSession)
                        onVariableValueHidden(variable.id)
                        report(resources.getString(R.string.variable_deleted))
                    } else {
                        report(resources.getString(R.string.variable_not_found))
                    }
                }
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
