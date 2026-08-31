@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.R
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.EnvironmentVariableValue
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SaveEnvironmentVariableResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.storage.secret.SaveSshSecretResult
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.ui.components.AdaptiveListDetail
import kotlinx.coroutines.launch
import java.util.UUID

private data class PendingVariableDeletion(
    val variable: EnvironmentVariableMetadata,
    val editorSession: Long,
)

@Composable
internal fun SecretsScreen(
    authorizeProtectedAction: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    onOpenSettings: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    aiReviewActive: Boolean,
    viewModel: SecretsViewModel,
) {
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val pendingUploads by viewModel.pendingUploads.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var secretPendingDeletion by remember { mutableStateOf<SecretDetails?>(null) }
    var variablePendingDeletion by remember { mutableStateOf<PendingVariableDeletion?>(null) }
    var revealedValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                revealedValues = emptyMap()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val target = when (val selected = content) {
        SecretsContent.List -> null
        is SecretsContent.Loading -> selected.target
        is SecretsContent.Stored -> SecretTarget.Stored(selected.details.id)
        is SecretsContent.Upload -> SecretTarget.Upload(selected.request.id)
    }
    LaunchedEffect(target) {
        revealedValues = emptyMap()
        secretPendingDeletion = null
        variablePendingDeletion = null
    }

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun afterProtection(title: String, action: suspend () -> Unit) {
        authorizeProtectedAction(
            title,
            { scope.launch { action() } },
            ::report,
        )
    }

    suspend fun readValue(variable: EnvironmentVariableMetadata): String? =
        when (val value = viewModel.readEnvironmentVariableValue(variable.id)) {
            is EnvironmentVariableValue.Available -> value.value
            EnvironmentVariableValue.Unavailable -> null.also {
                report(resources.getString(R.string.value_unavailable))
            }
            EnvironmentVariableValue.Corrupted -> null.also {
                report(resources.getString(R.string.corrupted_value))
            }
            EnvironmentVariableValue.UnsupportedFormat -> null.also {
                report(resources.getString(R.string.unsupported_value))
            }
            EnvironmentVariableValue.NotFound -> null.also {
                report(resources.getString(R.string.missing_value))
            }
        }

    fun reveal(variable: EnvironmentVariableMetadata) {
        if (revealedValues.containsKey(variable.id)) {
            revealedValues -= variable.id
            return
        }
        val action: () -> Unit = action@{
            val epoch = viewModel.beginSecretRead(variable.secretId) ?: return@action
            scope.launch {
                if (!viewModel.secretReadIsCurrent(variable.secretId, epoch)) return@launch
                val value = readValue(variable) ?: return@launch
                if (viewModel.secretReadIsCurrent(variable.secretId, epoch)) {
                    revealedValues += variable.id to value
                }
            }
        }
        if (variable.sensitive) {
            authorizeProtectedAction(
                resources.getString(R.string.reveal_sensitive_value, variable.name),
                action,
                ::report,
            )
        } else {
            action()
        }
    }

    fun copy(variable: EnvironmentVariableMetadata) {
        val action: () -> Unit = action@{
            val epoch = viewModel.beginSecretRead(variable.secretId) ?: return@action
            scope.launch {
                if (!viewModel.secretReadIsCurrent(variable.secretId, epoch)) return@launch
                val value = readValue(variable) ?: return@launch
                if (!viewModel.secretReadIsCurrent(variable.secretId, epoch)) return@launch
                copyToClipboard(context, variable.name, value, variable.sensitive)
                report(
                    resources.getString(
                        if (variable.sensitive) {
                            R.string.sensitive_copied_to_clipboard
                        } else {
                            R.string.copied_to_clipboard
                        },
                        variable.name,
                    ),
                )
            }
        }
        if (variable.sensitive) {
            authorizeProtectedAction(
                resources.getString(R.string.copy_sensitive_value, variable.name),
                action,
                ::report,
            )
        } else {
            action()
        }
    }

    fun edit(variable: EnvironmentVariableMetadata) {
        if (!variable.valueAvailable) {
            viewModel.startEditingEnvironmentVariable(variable, null)
            return
        }
        val action: () -> Unit = action@{
            val epoch = viewModel.beginSecretRead(variable.secretId) ?: return@action
            scope.launch {
                if (!viewModel.secretReadIsCurrent(variable.secretId, epoch)) return@launch
                val value = readValue(variable) ?: return@launch
                if (viewModel.secretReadIsCurrent(variable.secretId, epoch)) {
                    viewModel.startEditingEnvironmentVariable(variable, value)
                }
            }
        }
        if (variable.sensitive) {
            authorizeProtectedAction(
                resources.getString(R.string.edit_sensitive_value, variable.name),
                action,
                ::report,
            )
        } else {
            action()
        }
    }

    fun copyPublicKey(secret: SecretDetails) {
        val publicKey = secret.sshKey?.publicKey ?: return
        copyToClipboard(context, secret.name, publicKey, sensitive = false)
        report("Public key copied")
    }

    fun secretDetailActions(
        secret: SecretDetails,
        onBack: () -> Unit,
    ) = SecretDetailActions(
        onBack = onBack,
        onEditSecret = { viewModel.startEditingSecret(secret) },
        onDeleteSecret = { secretPendingDeletion = secret },
        onAddVariable = { viewModel.startNewEnvironmentVariable(secret.id) },
        onReplaceSshKey = { viewModel.startReplacingSshKey(secret) },
        onSaveSshComment = { comment ->
            scope.launch {
                when (viewModel.saveSshComment(secret.id, comment)) {
                    is SaveSshSecretResult.Saved -> report("Public key comment updated")
                    else -> report("Public key comment could not be updated")
                }
            }
        },
        onCopyPublicKey = { copyPublicKey(secret) },
        onEditVariable = ::edit,
        onReveal = ::reveal,
        onReadValue = ::readValue,
        onCopy = ::copy,
        onSetApprovalMode = { mode ->
            scope.launch {
                val result = viewModel.saveApprovalMode(secret.id, mode)
                report(
                    if (result == SaveSecretResult.SAVED) {
                        "Default approval updated"
                    } else {
                        "Approval setting could not be updated"
                    },
                )
            }
        },
        onSetClientApprovalOverride = { clientId, mode ->
            scope.launch {
                val result = viewModel.setClientApprovalOverride(secret.id, clientId, mode)
                report(
                    if (result == SaveSecretResult.SAVED) {
                        "Client approval updated"
                    } else {
                        "Client approval could not be updated"
                    },
                )
            }
        },
        onSaveInstructions = { instructions ->
            scope.launch {
                val result = viewModel.saveInstructions(secret.id, instructions)
                report(
                    if (result == SaveSecretResult.SAVED) {
                        "Instructions updated"
                    } else {
                        "Instructions could not be updated"
                    },
                )
            }
        },
        onEndTemporaryAccess = { grant ->
            scope.launch {
                val ended = viewModel.endTemporaryAccess(
                    secret.id,
                    grant.clientId,
                    grant.operation,
                )
                report(
                    if (ended) {
                        "Temporary access ended"
                    } else {
                        "Temporary access had already ended"
                    },
                )
            }
        },
    )

    fun saveGeneralInstructions(instructions: String) {
        scope.launch {
            report(
                if (viewModel.saveGeneralInstructions(instructions)) {
                    "General instructions updated"
                } else {
                    "General instructions could not be updated"
                },
            )
        }
    }

    fun clearSelection() {
        when (target) {
            null -> Unit
            is SecretTarget.Stored -> viewModel.selectSecret(null)
            is SecretTarget.Upload -> viewModel.selectUpload(null)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        AdaptiveListDetail(
            hasDetail = content !is SecretsContent.List,
            listWidth = 320.dp,
            onBack = ::clearSelection,
            onTopLevelChanged = onTopLevelChanged,
            obscured = editor !is SecretsEditor.None,
            modifier = Modifier.fillMaxSize().padding(padding),
            list = { listModifier ->
                SecretList(
                    secrets = secrets,
                    pendingUploads = pendingUploads,
                    selectedSecretId = (target as? SecretTarget.Stored)?.id,
                    selectedUploadRequestId = (target as? SecretTarget.Upload)?.requestId,
                    onSelect = viewModel::selectSecret,
                    onSelectUpload = viewModel::selectUpload,
                    onCreate = viewModel::startNewSecret,
                    generalInstructions = configuration?.active?.instructions.orEmpty(),
                    aiReviewActive = aiReviewActive,
                    onSaveGeneralInstructions = ::saveGeneralInstructions,
                    onOpenSettings = onOpenSettings,
                    modifier = listModifier,
                )
            },
            emptyDetail = { detailModifier -> EmptySecretSelection(detailModifier) },
            detail = { showBack, detailModifier ->
                when (val selected = content) {
                    SecretsContent.List -> EmptySecretSelection(detailModifier)
                    is SecretsContent.Loading -> Loading(detailModifier)
                    is SecretsContent.Upload -> key(selected.request.id) {
                        SecretUploadSelectionDetail(
                            request = selected.request,
                            authorizeProtectedAction = authorizeProtectedAction,
                            viewModel = viewModel,
                            report = ::report,
                            onBack = ::clearSelection,
                            showBack = showBack,
                            modifier = detailModifier,
                        )
                    }
                    is SecretsContent.Stored -> key(selected.details.id) {
                        SecretDetail(
                            secret = selected.details,
                            clients = clients,
                            revealedValues = revealedValues,
                            showBack = showBack,
                            actions = secretDetailActions(
                                selected.details,
                                onBack = { if (showBack) clearSelection() },
                            ),
                            modifier = detailModifier,
                        )
                    }
                }
            },
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
                                val result = if (editorState.type == SSH_SECRET_TYPE) {
                                    viewModel.createSshSecret(
                                        name,
                                        description,
                                        checkNotNull(editorState.preparedSshKey),
                                    )
                                } else {
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
                                checkNotNull(editorState.preparedKey),
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
                                    revealedValues -= editorState.variable.id
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

    secretPendingDeletion?.let { secret ->
        DeleteDialog(
            title = stringResource(R.string.delete_secret_question, secret.name),
            explanation = stringResource(R.string.delete_secret_explanation),
            onDismiss = { secretPendingDeletion = null },
            onDelete = {
                secretPendingDeletion = null
                scope.launch {
                    if (viewModel.deleteSecret(secret.id)) {
                        report(resources.getString(R.string.secret_deleted))
                    } else {
                        report(resources.getString(R.string.secret_not_found))
                    }
                }
            },
        )
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
                        revealedValues -= variable.id
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
private fun DeleteDialog(
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

@Composable
private fun EmptySecretSelection(modifier: Modifier = Modifier) {
    EmptyMessage(
        title = stringResource(R.string.select_secret),
        description = stringResource(R.string.select_secret_description),
        modifier = modifier,
    )
}

@Composable
internal fun EmptyMessage(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun Loading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

internal fun String.displayName(): String = when (this) {
    "environment" -> "Environment variables"
    "ssh" -> "SSH key"
    else -> this
}

internal fun String.sshAlgorithmDisplayName(): String = when (this) {
    "ed25519", "ssh-ed25519" -> "Ed25519"
    "rsa", "ssh-rsa" -> "RSA"
    else -> this
}

private fun copyToClipboard(
    context: Context,
    label: String,
    value: String,
    sensitive: Boolean,
) {
    val clip = ClipData.newPlainText(label, value)
    var sensitiveClipId: String? = null
    if (sensitive) {
        sensitiveClipId = UUID.randomUUID().toString()
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putString(SENSITIVE_CLIP_ID, sensitiveClipId)
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clip)
    sensitiveClipId?.let { clipId ->
        Handler(Looper.getMainLooper()).postDelayed(
            {
                if (clipboard.primaryClipDescription?.extras?.getString(SENSITIVE_CLIP_ID) == clipId) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard.clearPrimaryClip()
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            },
            SENSITIVE_CLIP_LIFETIME_MILLIS,
        )
    }
}

private const val SENSITIVE_CLIP_ID = "dev.agentknock.clipboard.ID"
private const val SENSITIVE_CLIP_LIFETIME_MILLIS = 60_000L
