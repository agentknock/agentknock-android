@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.agentknock.R
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.relay.RelayClientState
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.TonalIcon
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.EnvironmentVariableValue
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.storage.secret.SecretSummary
import dev.agentknock.storage.secret.SaveEnvironmentVariableResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.storage.secret.SaveSshSecretResult
import dev.agentknock.storage.secret.SshKeyMetadata
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.SecretUploadRequestState
import dev.agentknock.ui.requests.SecretUploadRequestDetail
import dev.agentknock.ui.requests.message
import dev.agentknock.ui.theme.agentknockColors
import kotlinx.coroutines.launch
import java.util.UUID

private val environmentVariableName = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val twoPaneWidth = 840.dp

internal data class SecretEditorState(
    val secret: SecretDetails?,
    val name: String,
    val description: String,
    val type: String,
    val sshInputMode: SshKeyInputMode = SshKeyInputMode.GENERATE,
    val sshPrivateKeyText: String = "",
    val sshComment: String = "",
    val preparedSshKey: SshPrivateKey? = null,
    val sshError: String? = null,
)

internal enum class SshKeyInputMode { GENERATE, IMPORT }

internal data class SshKeyEditorState(
    val secretId: String,
    val secretName: String,
    val currentKey: SshKeyMetadata,
    val inputMode: SshKeyInputMode,
    val privateKeyText: String,
    val comment: String,
    val preparedKey: SshPrivateKey?,
    val error: String?,
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
internal fun SecretsScreen(
    authorizeProtectedAction: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    onOpenSettings: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    viewModel: SecretsViewModel = viewModel(),
) {
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val pendingUploads by viewModel.pendingUploads.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val uploadSelection by viewModel.uploadSelection.collectAsStateWithLifecycle()
    val selectedSecret by viewModel.selectedSecret.collectAsStateWithLifecycle()
    val selectedUpload by viewModel.selectedUpload.collectAsStateWithLifecycle()
    val secretEditor by viewModel.secretEditor.collectAsStateWithLifecycle()
    val variableEditor by viewModel.variableEditor.collectAsStateWithLifecycle()
    val sshKeyEditor by viewModel.sshKeyEditor.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var secretPendingDeletion by remember { mutableStateOf<SecretDetails?>(null) }
    var variablePendingDeletion by remember { mutableStateOf<EnvironmentVariableMetadata?>(null) }
    var revealedValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current

    DisposableEffect(lifecycle, activity) {
        fun clearSensitiveEditor() {
            val editor = viewModel.variableEditor.value
            if (editor?.variable?.sensitive == true || editor?.sensitive == true) {
                viewModel.updateVariableEditor(null)
            }
            if (viewModel.sshKeyEditor.value != null) {
                viewModel.updateSshKeyEditor(null)
            }
            val secretEditor = viewModel.secretEditor.value
            if (secretEditor?.sshPrivateKeyText?.isNotEmpty() == true ||
                secretEditor?.preparedSshKey != null
            ) {
                viewModel.updateSecretEditor(null)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_STOP &&
                activity?.isChangingConfigurations != true
            ) {
                revealedValues = emptyMap()
                clearSensitiveEditor()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) clearSensitiveEditor()
        }
    }

    LaunchedEffect(selection, uploadSelection) {
        revealedValues = emptyMap()
    }

    LaunchedEffect(uploadSelection, selectedUpload?.secretUpload?.state) {
        if (
            uploadSelection != null &&
            selectedUpload?.secretUpload?.state?.let {
                it != SecretUploadRequestState.REVIEW_PENDING
            } == true
        ) {
            viewModel.selectUpload(null)
        }
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
        val action: () -> Unit = {
            scope.launch {
                readValue(variable)?.let { value -> revealedValues += variable.id to value }
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
        val action: () -> Unit = {
            scope.launch {
                val value = readValue(variable) ?: return@launch
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
        val action: () -> Unit = {
            scope.launch {
                readValue(variable)?.let { value ->
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val twoPane = maxWidth >= twoPaneWidth
            val secret = selectedSecret
            val hasSelection = selection != null || uploadSelection != null
            LaunchedEffect(hasSelection, secretEditor, variableEditor, sshKeyEditor, twoPane) {
                onTopLevelChanged(
                    (twoPane || !hasSelection) &&
                        secretEditor == null &&
                        variableEditor == null &&
                        sshKeyEditor == null,
                )
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    SecretList(
                        secrets = secrets,
                        pendingUploads = pendingUploads,
                        selectedSecretId = selection,
                        selectedUploadRequestId = uploadSelection,
                        onSelect = viewModel::selectSecret,
                        onSelectUpload = viewModel::selectUpload,
                        onCreate = viewModel::startNewSecret,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier
                            .width(340.dp)
                            .fillMaxHeight(),
                    )
                    VerticalDivider()
                    if (!hasSelection) {
                        EmptySecretSelection(Modifier.weight(1f).fillMaxHeight())
                    } else if (uploadSelection != null) {
                        SecretUploadSelectionDetail(
                            request = selectedUpload,
                            authorizeProtectedAction = authorizeProtectedAction,
                            viewModel = viewModel,
                            report = ::report,
                            onBack = { viewModel.selectUpload(null) },
                            showBack = false,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    } else if (secret == null) {
                        Loading(Modifier.weight(1f).fillMaxHeight())
                    } else {
                        SecretDetail(
                            secret = secret,
                            clients = clients,
                            revealedValues = revealedValues,
                            showBack = false,
                            onBack = {},
                            onEditSecret = {
                                viewModel.startEditingSecret(secret)
                            },
                            onDeleteSecret = {
                                secretPendingDeletion = secret
                            },
                            onAddVariable = {
                                viewModel.startNewEnvironmentVariable(secret.id)
                            },
                            onReplaceSshKey = { viewModel.startReplacingSshKey(secret) },
                            onSaveSshComment = { comment ->
                                scope.launch {
                                    when (viewModel.saveSshComment(secret.id, comment)) {
                                        is SaveSshSecretResult.Saved -> report("Public-key comment updated")
                                        else -> report("Public-key comment could not be updated")
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
                                    report(if (result == SaveSecretResult.SAVED) {
                                        "Default approval updated"
                                    } else {
                                        "Approval setting could not be updated"
                                    })
                                }
                            },
                            onSetClientApprovalOverride = { clientId, mode ->
                                scope.launch {
                                    val result = viewModel.setClientApprovalOverride(
                                        secret.id,
                                        clientId,
                                        mode,
                                    )
                                    report(if (result == SaveSecretResult.SAVED) {
                                        "Client approval updated"
                                    } else {
                                        "Client approval could not be updated"
                                    })
                                }
                            },
                            onSaveInstructions = { instructions ->
                                scope.launch {
                                    val result = viewModel.saveInstructions(secret.id, instructions)
                                    report(if (result == SaveSecretResult.SAVED) {
                                        "Instructions updated"
                                    } else {
                                        "Instructions could not be updated"
                                    })
                                }
                            },
                            onEndTemporaryAccess = { grant ->
                                scope.launch {
                                    val ended = viewModel.endTemporaryAccess(
                                        secret.id,
                                        grant.clientId,
                                        grant.operation,
                                    )
                                    report(if (ended) {
                                        "Temporary access ended"
                                    } else {
                                        "Temporary access had already ended"
                                    })
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else if (!hasSelection) {
                SecretList(
                    secrets = secrets,
                    pendingUploads = pendingUploads,
                    selectedSecretId = null,
                    selectedUploadRequestId = null,
                    onSelect = viewModel::selectSecret,
                    onSelectUpload = viewModel::selectUpload,
                    onCreate = viewModel::startNewSecret,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (uploadSelection != null) {
                BackHandler { viewModel.selectUpload(null) }
                SecretUploadSelectionDetail(
                    request = selectedUpload,
                    authorizeProtectedAction = authorizeProtectedAction,
                    viewModel = viewModel,
                    report = ::report,
                    onBack = { viewModel.selectUpload(null) },
                    showBack = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (secret == null) {
                Loading(Modifier.fillMaxSize())
            } else {
                BackHandler { viewModel.selectSecret(null) }
                SecretDetail(
                    secret = secret,
                    clients = clients,
                    revealedValues = revealedValues,
                    showBack = true,
                    onBack = { viewModel.selectSecret(null) },
                    onEditSecret = {
                        viewModel.startEditingSecret(secret)
                    },
                    onDeleteSecret = {
                        secretPendingDeletion = secret
                    },
                    onAddVariable = {
                        viewModel.startNewEnvironmentVariable(secret.id)
                    },
                    onReplaceSshKey = { viewModel.startReplacingSshKey(secret) },
                    onSaveSshComment = { comment ->
                        scope.launch {
                            when (viewModel.saveSshComment(secret.id, comment)) {
                                is SaveSshSecretResult.Saved -> report("Public-key comment updated")
                                else -> report("Public-key comment could not be updated")
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
                            report(if (result == SaveSecretResult.SAVED) {
                                "Default approval updated"
                            } else {
                                "Approval setting could not be updated"
                            })
                        }
                    },
                    onSetClientApprovalOverride = { clientId, mode ->
                        scope.launch {
                            val result = viewModel.setClientApprovalOverride(
                                secret.id,
                                clientId,
                                mode,
                            )
                            report(if (result == SaveSecretResult.SAVED) {
                                "Client approval updated"
                            } else {
                                "Client approval could not be updated"
                            })
                        }
                    },
                    onSaveInstructions = { instructions ->
                        scope.launch {
                            val result = viewModel.saveInstructions(secret.id, instructions)
                            report(if (result == SaveSecretResult.SAVED) {
                                "Instructions updated"
                            } else {
                                "Instructions could not be updated"
                            })
                        }
                    },
                    onEndTemporaryAccess = { grant ->
                        scope.launch {
                            val ended = viewModel.endTemporaryAccess(
                                secret.id,
                                grant.clientId,
                                grant.operation,
                            )
                            report(if (ended) {
                                "Temporary access ended"
                            } else {
                                "Temporary access had already ended"
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    secretEditor?.let { editor ->
        SecretEditorScreen(
            editor = editor,
            onEditorChange = viewModel::updateSecretEditor,
            onDismiss = { viewModel.updateSecretEditor(null) },
            onPrepareSshKey = {
                scope.launch {
                    runCatching {
                        when (editor.sshInputMode) {
                            SshKeyInputMode.GENERATE -> viewModel.generateSshKey(editor.sshComment)
                            SshKeyInputMode.IMPORT -> viewModel.importSshKey(editor.sshPrivateKeyText)
                        }
                    }.onSuccess { key ->
                        viewModel.updateSecretEditor(
                            editor.copy(preparedSshKey = key, sshError = null),
                        )
                    }.onFailure { error ->
                        viewModel.updateSecretEditor(
                            editor.copy(
                                preparedSshKey = null,
                                sshError = error.message ?: "The private key is not valid",
                            ),
                        )
                    }
                }
            },
            onSave = { name, description ->
                scope.launch {
                    val error = if (editor.secret == null) {
                        when (
                            val result = if (editor.type == SSH_SECRET_TYPE) {
                                viewModel.createSshSecret(
                                    name,
                                    description,
                                    checkNotNull(editor.preparedSshKey),
                                )
                            } else {
                                viewModel.createSecret(name, description)
                            }
                        ) {
                            is CreateSecretResult.Created -> {
                                viewModel.updateSecretEditor(null)
                                viewModel.selectSecret(result.id)
                                null
                            }
                            CreateSecretResult.NameInUse -> resources.getString(
                                R.string.secret_name_in_use,
                            )
                        }
                    } else {
                        when (
                            viewModel.saveSecret(editor.secret.id, name, description)
                        ) {
                            SaveSecretResult.SAVED -> {
                                viewModel.updateSecretEditor(null)
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
                    error?.let(::report)
                }
            },
            snackbar = snackbar,
        )
    }

    sshKeyEditor?.let { editor ->
        SshKeyEditorScreen(
            editor = editor,
            onEditorChange = viewModel::updateSshKeyEditor,
            onDismiss = { viewModel.updateSshKeyEditor(null) },
            onPrepare = {
                scope.launch {
                    runCatching {
                        when (editor.inputMode) {
                            SshKeyInputMode.GENERATE -> viewModel.generateSshKey(editor.comment)
                            SshKeyInputMode.IMPORT -> viewModel.importSshKey(editor.privateKeyText)
                        }
                    }.onSuccess { key ->
                        viewModel.updateSshKeyEditor(
                            editor.copy(preparedKey = key, error = null),
                        )
                    }.onFailure { error ->
                        viewModel.updateSshKeyEditor(
                            editor.copy(
                                preparedKey = null,
                                error = error.message ?: "The private key is not valid",
                            ),
                        )
                    }
                }
            },
            onReplace = {
                scope.launch {
                    when (
                        viewModel.replaceSshKey(editor.secretId, checkNotNull(editor.preparedKey))
                    ) {
                        is SaveSshSecretResult.Saved -> {
                            viewModel.updateSshKeyEditor(null)
                            report("SSH key replaced")
                        }
                        else -> report("SSH key could not be replaced")
                    }
                }
            },
            snackbar = snackbar,
        )
    }

    variableEditor?.let { editor ->
        val variable = editor.variable
        EnvironmentVariableEditorScreen(
            editor = editor,
            onEditorChange = viewModel::updateVariableEditor,
            onDismiss = { viewModel.updateVariableEditor(null) },
            onDelete = variable?.let {
                { variablePendingDeletion = it }
            },
            onSave = { name, value, sensitive, notes, replaceValue ->
                val save: suspend () -> Unit = {
                    val error = if (editor.variable == null) {
                        when (
                            viewModel.createEnvironmentVariable(
                                secretId = editor.secretId,
                                name = name,
                                value = value,
                                sensitive = sensitive,
                                notes = notes,
                            )
                        ) {
                            is CreateEnvironmentVariableResult.Created -> {
                                viewModel.updateVariableEditor(null)
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
                                id = editor.variable.id,
                                name = name,
                                sensitive = sensitive,
                                notes = notes,
                                replacementValue = value.takeIf { replaceValue },
                            )
                        ) {
                            SaveEnvironmentVariableResult.SAVED -> {
                                viewModel.updateVariableEditor(null)
                                revealedValues -= editor.variable.id
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
                    error?.let(::report)
                }
                val weakensProtection = !sensitive && editor.variable?.sensitive != false
                if (weakensProtection) {
                    afterProtection(
                        resources.getString(R.string.confirm_mark_variable_non_sensitive, name),
                        save,
                    )
                } else {
                    scope.launch { save() }
                }
            },
            snackbar = snackbar,
        )
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

    variablePendingDeletion?.let { variable ->
        DeleteDialog(
            title = stringResource(R.string.delete_variable_question, variable.name),
            explanation = stringResource(R.string.delete_variable_explanation),
            onDismiss = { variablePendingDeletion = null },
            onDelete = {
                variablePendingDeletion = null
                scope.launch {
                    if (viewModel.deleteEnvironmentVariable(variable.id)) {
                        viewModel.updateVariableEditor(null)
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
private fun SecretUploadSelectionDetail(
    request: InboxRequestDetails?,
    authorizeProtectedAction: (String, () -> Unit, (String) -> Unit) -> Unit,
    viewModel: SecretsViewModel,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    if (request?.secretUpload == null) {
        Loading(modifier)
        return
    }
    SecretUploadRequestDetail(
        request = request,
        onBack = onBack,
        showBack = showBack,
        onApprove = { name ->
            scope.launch {
                report(viewModel.approveSecretUpload(request.id, name).message())
                viewModel.selectUpload(null)
            }
        },
        onReject = {
            scope.launch {
                report(viewModel.rejectSecretUpload(request.id).message())
                viewModel.selectUpload(null)
            }
        },
        authorizeProtectedAction = authorizeProtectedAction,
        onReveal = { variableId ->
            viewModel.readSecretUploadVariable(request.id, variableId)
        },
        onSensitivityChange = { variableId, sensitive ->
            viewModel.setSecretUploadVariableSensitivity(request.id, variableId, sensitive)
        },
        report = report,
        modifier = modifier,
    )
}

@Composable
private fun SecretList(
    secrets: List<SecretSummary>,
    pendingUploads: List<InboxRequestSummary>,
    selectedSecretId: String?,
    selectedUploadRequestId: Long?,
    onSelect: (String) -> Unit,
    onSelectUpload: (Long) -> Unit,
    onCreate: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                                        if (secret.type == SSH_SECRET_TYPE) {
                                            secret.sshKey?.fingerprint ?: "SSH key unavailable"
                                        } else {
                                            "${secret.environmentVariableCount} environment " +
                                                if (secret.environmentVariableCount == 1) {
                                                    "variable"
                                                } else {
                                                    "variables"
                                                }
                                        },
                                        style = MaterialTheme.typography.labelMedium,
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
                                                "${secret.temporaryAccessCount} temporary " +
                                                    if (secret.temporaryAccessCount == 1) {
                                                        "approval"
                                                    } else {
                                                        "approvals"
                                                    },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            },
                            leadingContent = {
                                TonalIcon(Icons.Outlined.Lock, contentDescription = null)
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
    val colors = MaterialTheme.agentknockColors
    val containerColor = when {
        actionRequired -> colors.attentionContainer
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (actionRequired) {
        colors.onAttentionContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secretName = request.secretNames.singleOrNull() ?: "Unnamed secret"
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        border = if (actionRequired) BorderStroke(1.dp, colors.attentionAccent) else null,
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Review", style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = null)
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                headlineColor = contentColor,
                supportingColor = contentColor.copy(alpha = 0.78f),
                trailingIconColor = contentColor,
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

@Composable
private fun SecretDetail(
    secret: SecretDetails,
    clients: List<ClientSummary>,
    revealedValues: Map<String, String>,
    showBack: Boolean,
    onBack: () -> Unit,
    onEditSecret: () -> Unit,
    onDeleteSecret: () -> Unit,
    onAddVariable: () -> Unit,
    onReplaceSshKey: () -> Unit,
    onSaveSshComment: (String) -> Unit,
    onCopyPublicKey: () -> Unit,
    onEditVariable: (EnvironmentVariableMetadata) -> Unit,
    onReveal: (EnvironmentVariableMetadata) -> Unit,
    onReadValue: suspend (EnvironmentVariableMetadata) -> String?,
    onCopy: (EnvironmentVariableMetadata) -> Unit,
    onSetApprovalMode: (SecretApprovalMode) -> Unit,
    onSetClientApprovalOverride: (String, SecretApprovalMode?) -> Unit,
    onSaveInstructions: (String) -> Unit,
    onEndTemporaryAccess: (TemporaryAccessGrant) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(secret.id) { mutableStateOf(false) }
    var editingSshComment by remember(secret.id) { mutableStateOf(false) }
    var sshComment by remember(secret.id, secret.sshKey?.comment) {
        mutableStateOf(secret.sshKey?.comment.orEmpty())
    }
    var editingDefaultApproval by remember(secret.id) { mutableStateOf(false) }
    var editingClientApproval by remember(secret.id) { mutableStateOf<String?>(null) }
    var editingInstructions by remember(secret.id) { mutableStateOf(false) }
    var instructions by remember(secret.id, secret.instructions) {
        mutableStateOf(secret.instructions)
    }
    val overrides = secret.clientApprovalOverrides.associateBy { it.clientId }
    val duplicateClientNames = clients.groupingBy(ClientSummary::name).eachCount()
        .filterValues { it > 1 }
        .keys
    val fontScale = LocalDensity.current.fontScale
    Column(modifier) {
        TopAppBar(
            title = { Text(secret.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = onEditSecret) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.edit_secret),
                    )
                }
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
                                stringResource(R.string.delete_secret),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDeleteSecret()
                        },
                    )
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 8.dp,
                bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InformationSurface {
                    if (secret.description.isNotBlank()) {
                        Text(
                            secret.description,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    InformationRow(stringResource(R.string.secret_type), secret.type.displayName())
                }
            }
            if (secret.type == ENVIRONMENT_SECRET_TYPE) {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        itemVerticalAlignment = Alignment.CenterVertically,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = if (fontScale >= 1.5f) 1 else Int.MAX_VALUE,
                    ) {
                        Text("Environment variables", style = MaterialTheme.typography.titleLarge)
                        FilledTonalButton(onClick = onAddVariable) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_variable))
                        }
                    }
                }
                if (secret.environmentVariables.isEmpty()) {
                    item {
                        EmptyMessage(
                            title = stringResource(R.string.no_variables),
                            description = stringResource(R.string.no_variables_description),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        )
                    }
                } else {
                    items(
                        secret.environmentVariables,
                        key = EnvironmentVariableMetadata::id,
                    ) { variable ->
                        EnvironmentVariableCard(
                            variable = variable,
                            revealedValue = revealedValues[variable.id],
                            onReveal = { onReveal(variable) },
                            onReadValue = { onReadValue(variable) },
                            onCopy = { onCopy(variable) },
                            onEdit = { onEditVariable(variable) },
                        )
                    }
                }
            } else {
                secret.sshKey?.let { key ->
                    item {
                        Text("Public key", style = MaterialTheme.typography.titleLarge)
                    }
                    item {
                        SshPublicKeyCard(
                            key = key,
                            onCopy = onCopyPublicKey,
                            onEditComment = { editingSshComment = true },
                            onReplace = onReplaceSshKey,
                        )
                    }
                } ?: item {
                    EmptyMessage(
                        title = "SSH key unavailable",
                        description = "The encrypted private key could not be recovered on this device.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                }
            }
            item {
                Text("Approval", style = MaterialTheme.typography.titleLarge)
            }
            item {
                InformationSurface {
                    Text(
                        "Controls protected uses, such as providing sensitive values or using a private key.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (secret.temporaryAccessGrants.isNotEmpty()) {
                        SecretTemporaryApprovals(
                            grants = secret.temporaryAccessGrants,
                            clients = clients,
                            onEnd = onEndTemporaryAccess,
                        )
                        HorizontalDivider()
                    }
                    ApprovalSettingRow(
                        title = "Default",
                        value = secret.approvalMode.displayName(),
                        onClick = { editingDefaultApproval = true },
                    )
                    if (clients.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Client overrides", style = MaterialTheme.typography.titleMedium)
                        clients.forEach { client ->
                            val override = overrides[client.clientId]
                            val configuredMode = override?.mode?.displayName()
                                ?: "Use default · ${secret.approvalMode.displayName()}"
                            ApprovalSettingRow(
                                title = client.approvalLabel(duplicateClientNames),
                                value = configuredMode,
                                onClick = { editingClientApproval = client.clientId },
                            )
                        }
                    }
                    HorizontalDivider()
                    ApprovalSettingRow(
                        title = "AI instructions",
                        value = secret.instructions.ifBlank { "None" },
                        onClick = { editingInstructions = true },
                    )
                }
            }
            item {
                InformationSurface(modifier = Modifier.padding(top = 8.dp)) {
                    InformationRow("Created", formatTimestamp(secret.createdAt))
                    InformationRow("Updated", formatTimestamp(secret.updatedAt))
                }
            }
        }
    }
    if (editingSshComment) {
        AlertDialog(
            onDismissRequest = { editingSshComment = false },
            title = { Text("Edit public-key comment") },
            text = {
                OutlinedTextField(
                    value = sshComment,
                    onValueChange = { sshComment = it },
                    label = { Text("Comment (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { editingSshComment = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingSshComment = false
                        onSaveSshComment(sshComment)
                    },
                ) { Text("Save") }
            },
        )
    }
    if (editingDefaultApproval) {
        ApprovalModeDialog(
            title = "Default approval",
            selected = secret.approvalMode,
            defaultMode = null,
            secretType = secret.type,
            onSelect = { mode ->
                checkNotNull(mode)
                editingDefaultApproval = false
                onSetApprovalMode(mode)
            },
            onDismiss = { editingDefaultApproval = false },
        )
    }
    editingClientApproval?.let { clientId ->
        val client = clients.firstOrNull { it.clientId == clientId }
        if (client != null) {
            ApprovalModeDialog(
                title = client.approvalLabel(duplicateClientNames),
                selected = overrides[clientId]?.mode,
                defaultMode = secret.approvalMode,
                secretType = secret.type,
                onSelect = { mode ->
                    editingClientApproval = null
                    onSetClientApprovalOverride(clientId, mode)
                },
                onDismiss = { editingClientApproval = null },
            )
        }
    }
    if (editingInstructions) {
        AlertDialog(
            onDismissRequest = { editingInstructions = false },
            title = { Text("Secret instructions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Tell AI review what this secret may and may not be used for. Do not include secret values.",
                    )
                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = { Text("Instructions") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { editingInstructions = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingInstructions = false
                        onSaveInstructions(instructions)
                    },
                ) { Text("Save") }
            },
        )
    }
}

@Composable
private fun SecretTemporaryApprovals(
    grants: List<TemporaryAccessGrant>,
    clients: List<ClientSummary>,
    onEnd: (TemporaryAccessGrant) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Temporary access", style = MaterialTheme.typography.titleMedium)
        Text(
            "These uses are already approved and will not ask you or AI again before they end.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        grants.forEachIndexed { index, grant ->
            if (index > 0) HorizontalDivider()
            val client = clients.firstOrNull { it.clientId == grant.clientId }
            val paused = client == null || client.state != RelayClientState.ACTIVE ||
                client.desiredState?.let { it != RelayClientState.ACTIVE } == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(client?.name ?: "Unknown client", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${grant.operation.displayName()} · Ends ${formatTimestamp(grant.expiresAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (paused) {
                        Text(
                            "Paused until this client is active",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { onEnd(grant) }) { Text("End") }
            }
        }
    }
}

private fun ClientSummary.approvalLabel(duplicateNames: Set<String>): String =
    if (name in duplicateNames) "$name · ${clientId.takeLast(6)}" else name

@Composable
private fun ApprovalSettingRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = null)
    }
}

@Composable
private fun ApprovalModeDialog(
    title: String,
    selected: SecretApprovalMode?,
    defaultMode: SecretApprovalMode?,
    secretType: String,
    onSelect: (SecretApprovalMode?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = buildList<Pair<SecretApprovalMode?, String>> {
        if (defaultMode != null) add(null to "Use default · ${defaultMode.displayName()}")
        listOf(
            SecretApprovalMode.ASK_AI,
            SecretApprovalMode.TEMPORARY,
            SecretApprovalMode.ASK_ME,
            SecretApprovalMode.APPROVE,
            SecretApprovalMode.DENY,
        ).forEach { mode -> add(mode to mode.displayName()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(mode) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == mode, onClick = { onSelect(mode) })
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            mode?.let {
                                Text(
                                    it.description(secretType),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun SecretApprovalMode.displayName(): String = when (this) {
    SecretApprovalMode.APPROVE -> "Approve automatically"
    SecretApprovalMode.ASK_AI -> "Ask AI"
    SecretApprovalMode.TEMPORARY -> "Ask me, with 4-hour access"
    SecretApprovalMode.ASK_ME -> "Ask every time"
    SecretApprovalMode.DENY -> "Always deny"
}

private fun SecretApprovalMode.description(secretType: String): String = when (this) {
    SecretApprovalMode.APPROVE -> "Allow protected use without asking."
    SecretApprovalMode.ASK_AI ->
        "Requires AI review access. AI may approve, deny, or ask you; otherwise, you decide " +
            "and can allow 4-hour access."
    SecretApprovalMode.TEMPORARY -> if (secretType == SSH_SECRET_TYPE) {
        "When asked, you can sign once or allow that client to request Git signatures with " +
            "this key for any repository for 4 hours."
    } else {
        "When asked, you can approve once or allow that client to receive protected values " +
            "from this secret for any command for 4 hours."
    }
    SecretApprovalMode.ASK_ME -> "Always require your decision."
    SecretApprovalMode.DENY -> "Reject protected use without asking."
}

private fun TemporaryAccessOperation.displayName(): String = when (this) {
    TemporaryAccessOperation.INVOCATION -> "Environment values for any command"
    TemporaryAccessOperation.GIT_SIGN -> "Git signing for any repository"
}

@Composable
private fun SshPublicKeyCard(
    key: SshKeyMetadata,
    onCopy: () -> Unit,
    onEditComment: () -> Unit,
    onReplace: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InformationRow("Algorithm", "Ed25519")
            InformationRow("Fingerprint", key.fingerprint)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Comment",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(key.comment.ifBlank { "No comment" })
                }
                IconButton(onClick = onEditComment) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit public-key comment")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "OpenSSH public key",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        key.publicKey,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!key.privateKeyAvailable) {
                Text(
                    "Private key unavailable",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onReplace) { Text("Replace key") }
                FilledTonalButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy public key")
                }
            }
        }
    }
}

@Composable
private fun EnvironmentVariableCard(
    variable: EnvironmentVariableMetadata,
    revealedValue: String?,
    onReveal: () -> Unit,
    onReadValue: suspend () -> String?,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
) {
    var publicValue by remember(
        variable.id,
        variable.valueUpdatedAt,
        variable.sensitive,
    ) { mutableStateOf<String?>(null) }
    var publicValueUnavailable by remember(
        variable.id,
        variable.valueUpdatedAt,
        variable.sensitive,
    ) { mutableStateOf(false) }

    LaunchedEffect(variable.id, variable.valueUpdatedAt, variable.sensitive, variable.valueAvailable) {
        if (!variable.sensitive && variable.valueAvailable) {
            publicValue = onReadValue()
            publicValueUnavailable = publicValue == null
        }
    }

    val displayedValue = if (variable.sensitive) revealedValue else publicValue
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    variable.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (variable.sensitive) {
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                stringResource(R.string.sensitive),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (!variable.valueAvailable || publicValueUnavailable) {
                        Text(
                            stringResource(R.string.value_unavailable),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (displayedValue == null) {
                        if (variable.sensitive) {
                            Text(
                                stringResource(R.string.masked_value),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clearAndSetSemantics {
                                    contentDescription = "Value hidden"
                                },
                            )
                        } else {
                            Text(
                                stringResource(R.string.loading_value),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                displayedValue,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (variable.sensitive) {
                    IconButton(onClick = onReveal, enabled = variable.valueAvailable) {
                        Icon(
                            if (revealedValue == null) {
                                Icons.Outlined.Visibility
                            } else {
                                Icons.Outlined.VisibilityOff
                            },
                            contentDescription = stringResource(
                                if (revealedValue == null) R.string.show else R.string.hide,
                            ) + " ${variable.name}",
                        )
                    }
                }
                IconButton(onClick = onCopy, enabled = variable.valueAvailable) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy ${variable.name} value",
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Edit ${variable.name}",
                    )
                }
            }
            if (variable.notes.isNotBlank()) {
                Text(
                    variable.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SecretEditorScreen(
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
    var validationError by rememberSaveable(secret?.id) { mutableStateOf<Int?>(null) }
    var confirmDiscard by rememberSaveable(secret?.id) { mutableStateOf(false) }
    val dirty = if (secret == null) {
        name.isNotEmpty() || description.isNotEmpty() ||
            editor.type != ENVIRONMENT_SECRET_TYPE ||
            editor.sshPrivateKeyText.isNotEmpty() || editor.sshComment.isNotEmpty() ||
            editor.preparedSshKey != null
    } else {
        name != secret.name || description != secret.description
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
                            if (secret == null) R.string.new_secret else R.string.edit_secret,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::requestDismiss) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (secret == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Secret type", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = editor.type == ENVIRONMENT_SECRET_TYPE,
                            onClick = {
                                onEditorChange(
                                    editor.copy(
                                        type = ENVIRONMENT_SECRET_TYPE,
                                        preparedSshKey = null,
                                        sshError = null,
                                    ),
                                )
                            },
                            label = { Text("Environment variables") },
                        )
                        FilterChip(
                            selected = editor.type == SSH_SECRET_TYPE,
                            onClick = {
                                onEditorChange(editor.copy(type = SSH_SECRET_TYPE, sshError = null))
                            },
                            label = { Text("SSH key") },
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
                    mode = editor.sshInputMode,
                    privateKeyText = editor.sshPrivateKeyText,
                    comment = editor.sshComment,
                    preparedKey = editor.preparedSshKey,
                    error = editor.sshError,
                    onModeChange = { mode ->
                        onEditorChange(
                            editor.copy(
                                sshInputMode = mode,
                                preparedSshKey = null,
                                sshError = null,
                            ),
                        )
                    },
                    onPrivateKeyChange = { value ->
                        onEditorChange(
                            editor.copy(
                                sshPrivateKeyText = value,
                                preparedSshKey = null,
                                sshError = null,
                            ),
                        )
                    },
                    onCommentChange = { value ->
                        onEditorChange(
                            editor.copy(
                                sshComment = value,
                                preparedSshKey = null,
                                sshError = null,
                            ),
                        )
                    },
                    onPrepare = onPrepareSshKey,
                )
            }
            Button(
                onClick = {
                    validationError = when {
                        name.isBlank() -> R.string.secret_name_required
                        name != name.trim() -> R.string.secret_name_whitespace
                        else -> null
                    }
                    if (validationError == null) onSave(name, description)
                },
                enabled = name.isNotBlank() &&
                    (secret != null || editor.type != SSH_SECRET_TYPE || editor.preparedSshKey != null) && (
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
    if (confirmDiscard) {
        DiscardChangesDialog(
            onDismiss = { confirmDiscard = false },
            onDiscard = onDismiss,
        )
    }
}

@Composable
private fun SshKeyInput(
    mode: SshKeyInputMode,
    privateKeyText: String,
    comment: String,
    preparedKey: SshPrivateKey?,
    error: String?,
    onModeChange: (SshKeyInputMode) -> Unit,
    onPrivateKeyChange: (String) -> Unit,
    onCommentChange: (String) -> Unit,
    onPrepare: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Key material", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == SshKeyInputMode.GENERATE,
                onClick = { onModeChange(SshKeyInputMode.GENERATE) },
                label = { Text("Generate") },
            )
            FilterChip(
                selected = mode == SshKeyInputMode.IMPORT,
                onClick = { onModeChange(SshKeyInputMode.IMPORT) },
                label = { Text("Import") },
            )
        }
        if (mode == SshKeyInputMode.GENERATE) {
            Text(
                "Generate a new Ed25519 key on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = comment,
                onValueChange = onCommentChange,
                label = { Text("Public-key comment (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "Paste an unencrypted OpenSSH Ed25519 private key. Encrypted keys must be " +
                    "decrypted before import.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = privateKeyText,
                onValueChange = onPrivateKeyChange,
                label = { Text("OpenSSH private key") },
                minLines = 6,
                maxLines = 12,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (preparedKey == null) {
            FilledTonalButton(
                onClick = onPrepare,
                enabled = mode == SshKeyInputMode.GENERATE || privateKeyText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (mode == SshKeyInputMode.GENERATE) "Generate and review" else "Review key")
            }
        } else {
            SshKeyPreview(preparedKey)
        }
        error?.let {
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
        InformationRow("Algorithm", "Ed25519")
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
private fun SshKeyEditorScreen(
    editor: SshKeyEditorState,
    onEditorChange: (SshKeyEditorState) -> Unit,
    onDismiss: () -> Unit,
    onPrepare: () -> Unit,
    onReplace: () -> Unit,
    snackbar: SnackbarHostState,
) {
    var confirmDiscard by rememberSaveable(editor.secretId) { mutableStateOf(false) }
    val dirty = editor.privateKeyText.isNotEmpty() || editor.preparedKey != null ||
        editor.comment != editor.currentKey.comment
    fun requestDismiss() {
        if (dirty) confirmDiscard = true else onDismiss()
    }
    BackHandler(onBack = ::requestDismiss)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Replace SSH key") },
                navigationIcon = {
                    IconButton(onClick = ::requestDismiss) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
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
                mode = editor.inputMode,
                privateKeyText = editor.privateKeyText,
                comment = editor.comment,
                preparedKey = editor.preparedKey,
                error = editor.error,
                onModeChange = { mode ->
                    onEditorChange(
                        editor.copy(inputMode = mode, preparedKey = null, error = null),
                    )
                },
                onPrivateKeyChange = { value ->
                    onEditorChange(
                        editor.copy(privateKeyText = value, preparedKey = null, error = null),
                    )
                },
                onCommentChange = { value ->
                    onEditorChange(
                        editor.copy(comment = value, preparedKey = null, error = null),
                    )
                },
                onPrepare = onPrepare,
            )
            Button(
                onClick = onReplace,
                enabled = editor.preparedKey != null,
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
private fun EnvironmentVariableEditorScreen(
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
                    IconButton(onClick = ::requestDismiss) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
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
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
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
private fun EmptyMessage(
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
private fun Loading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private fun String.displayName(): String = when (this) {
    "environment" -> "Environment variables"
    "ssh" -> "SSH key"
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
