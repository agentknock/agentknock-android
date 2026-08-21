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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.EnvironmentVariableValue
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretSummary
import dev.agentknock.storage.secret.SaveEnvironmentVariableResult
import dev.agentknock.storage.secret.SaveSecretResult
import kotlinx.coroutines.launch
import java.util.UUID

private val environmentVariableName = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val twoPaneWidth = 840.dp

private sealed interface SecretEditor {
    data object New : SecretEditor
    data class Existing(val secret: SecretDetails) : SecretEditor
}

private sealed interface VariableEditor {
    data class New(val secretId: String) : VariableEditor
    data class Existing(
        val variable: EnvironmentVariableMetadata,
        val currentValue: String?,
    ) : VariableEditor
}

@Composable
internal fun SecretsScreen(
    authenticate: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    onOpenSettings: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    viewModel: SecretsViewModel = viewModel(),
) {
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectedSecret by viewModel.selectedSecret.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var secretEditor by remember { mutableStateOf<SecretEditor?>(null) }
    var variableEditor by remember { mutableStateOf<VariableEditor?>(null) }
    var secretPendingDeletion by remember { mutableStateOf<SecretDetails?>(null) }
    var variablePendingDeletion by remember { mutableStateOf<EnvironmentVariableMetadata?>(null) }
    var revealedValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                revealedValues = emptyMap()
                if ((variableEditor as? VariableEditor.Existing)?.variable?.sensitive == true) {
                    variableEditor = null
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(selection) {
        revealedValues = emptyMap()
    }

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun afterAuthentication(title: String, action: suspend () -> Unit) {
        authenticate(
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
            authenticate(
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
            authenticate(
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
            variableEditor = VariableEditor.Existing(variable, null)
            return
        }
        val action: () -> Unit = {
            scope.launch {
                readValue(variable)?.let { value ->
                    variableEditor = VariableEditor.Existing(variable, value)
                }
            }
        }
        if (variable.sensitive) {
            authenticate(
                resources.getString(R.string.edit_sensitive_value, variable.name),
                action,
                ::report,
            )
        } else {
            action()
        }
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
            LaunchedEffect(selection, secretEditor, variableEditor, twoPane) {
                onTopLevelChanged(
                    (twoPane || selection == null) &&
                        secretEditor == null &&
                        variableEditor == null,
                )
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    SecretList(
                        secrets = secrets,
                        selectedSecretId = selection,
                        onSelect = viewModel::selectSecret,
                        onCreate = { secretEditor = SecretEditor.New },
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier
                            .width(340.dp)
                            .fillMaxHeight(),
                    )
                    VerticalDivider()
                    if (selection == null) {
                        EmptySecretSelection(Modifier.weight(1f).fillMaxHeight())
                    } else if (secret == null) {
                        Loading(Modifier.weight(1f).fillMaxHeight())
                    } else {
                        SecretDetail(
                            secret = secret,
                            revealedValues = revealedValues,
                            showBack = false,
                            onBack = {},
                            onEditSecret = {
                                secretEditor = SecretEditor.Existing(secret)
                            },
                            onDeleteSecret = {
                                secretPendingDeletion = secret
                            },
                            onAddVariable = {
                                variableEditor = VariableEditor.New(secret.id)
                            },
                            onEditVariable = ::edit,
                            onReveal = ::reveal,
                            onReadValue = ::readValue,
                            onCopy = ::copy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else if (selection == null) {
                SecretList(
                    secrets = secrets,
                    selectedSecretId = null,
                    onSelect = viewModel::selectSecret,
                    onCreate = { secretEditor = SecretEditor.New },
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (secret == null) {
                Loading(Modifier.fillMaxSize())
            } else {
                BackHandler { viewModel.selectSecret(null) }
                SecretDetail(
                    secret = secret,
                    revealedValues = revealedValues,
                    showBack = true,
                    onBack = { viewModel.selectSecret(null) },
                    onEditSecret = {
                        secretEditor = SecretEditor.Existing(secret)
                    },
                    onDeleteSecret = {
                        secretPendingDeletion = secret
                    },
                    onAddVariable = {
                        variableEditor = VariableEditor.New(secret.id)
                    },
                    onEditVariable = ::edit,
                    onReveal = ::reveal,
                    onReadValue = ::readValue,
                    onCopy = ::copy,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    secretEditor?.let { editor ->
        SecretEditorScreen(
            secret = (editor as? SecretEditor.Existing)?.secret,
            onDismiss = { secretEditor = null },
            onSave = { name, description ->
                afterAuthentication("Confirm saving secret") {
                    val error = when (editor) {
                        SecretEditor.New -> when (
                            val result = viewModel.createSecret(name, description)
                        ) {
                            is CreateSecretResult.Created -> {
                                secretEditor = null
                                viewModel.selectSecret(result.id)
                                null
                            }
                            CreateSecretResult.NameInUse -> resources.getString(
                                R.string.secret_name_in_use,
                            )
                        }
                        is SecretEditor.Existing -> when (
                            viewModel.saveSecret(editor.secret.id, name, description)
                        ) {
                            SaveSecretResult.SAVED -> {
                                secretEditor = null
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

    variableEditor?.let { editor ->
        val variable = (editor as? VariableEditor.Existing)?.variable
        EnvironmentVariableEditorScreen(
            variable = variable,
            currentValue = (editor as? VariableEditor.Existing)?.currentValue,
            onDismiss = { variableEditor = null },
            onDelete = variable?.let {
                { variablePendingDeletion = it }
            },
            onSave = { name, value, sensitive, notes, replaceValue ->
                afterAuthentication(resources.getString(R.string.confirm_save_variable)) {
                    val error = when (editor) {
                        is VariableEditor.New -> when (
                            viewModel.createEnvironmentVariable(
                                secretId = editor.secretId,
                                name = name,
                                value = value,
                                sensitive = sensitive,
                                notes = notes,
                            )
                        ) {
                            is CreateEnvironmentVariableResult.Created -> {
                                variableEditor = null
                                null
                            }
                            CreateEnvironmentVariableResult.NameInUse -> resources.getString(
                                R.string.variable_name_in_use,
                            )
                            CreateEnvironmentVariableResult.SecretNotFound -> resources.getString(
                                R.string.secret_not_found,
                            )
                        }
                        is VariableEditor.Existing -> when (
                            viewModel.saveEnvironmentVariable(
                                id = editor.variable.id,
                                name = name,
                                sensitive = sensitive,
                                notes = notes,
                                replacementValue = value.takeIf { replaceValue },
                            )
                        ) {
                            SaveEnvironmentVariableResult.SAVED -> {
                                variableEditor = null
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
                afterAuthentication(resources.getString(R.string.confirm_delete_secret)) {
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
                afterAuthentication(resources.getString(R.string.confirm_delete_variable)) {
                    if (viewModel.deleteEnvironmentVariable(variable.id)) {
                        variableEditor = null
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
private fun SecretList(
    secrets: List<SecretSummary>,
    selectedSecretId: String?,
    onSelect: (String) -> Unit,
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
        HorizontalDivider()
        if (secrets.isEmpty()) {
            EmptyMessage(
                title = stringResource(R.string.no_secrets),
                description = stringResource(R.string.no_secrets_description),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(secrets, key = { _, secret -> secret.id }) { index, secret ->
                    val selected = secret.id == selectedSecretId
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
                                    "${secret.type.displayName()} " +
                                        "(${secret.environmentVariableCount})",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Outlined.NavigateNext,
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(secret.id) }
                            .semantics { this.selected = selected },
                    )
                    if (index < secrets.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretDetail(
    secret: SecretDetails,
    revealedValues: Map<String, String>,
    showBack: Boolean,
    onBack: () -> Unit,
    onEditSecret: () -> Unit,
    onDeleteSecret: () -> Unit,
    onAddVariable: () -> Unit,
    onEditVariable: (EnvironmentVariableMetadata) -> Unit,
    onReveal: (EnvironmentVariableMetadata) -> Unit,
    onReadValue: suspend (EnvironmentVariableMetadata) -> String?,
    onCopy: (EnvironmentVariableMetadata) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(secret.id) { mutableStateOf(false) }
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (secret.description.isNotBlank()) {
                Text(
                    secret.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.secret_type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                secret.type.displayName(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        HorizontalDivider()
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = if (fontScale >= 1.5f) 1 else Int.MAX_VALUE,
        ) {
            Text(
                "Environment variables",
                style = MaterialTheme.typography.titleLarge,
            )
            FilledTonalButton(onClick = onAddVariable) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_variable))
            }
        }
        if (secret.environmentVariables.isEmpty()) {
            EmptyMessage(
                title = stringResource(R.string.no_variables),
                description = stringResource(R.string.no_variables_description),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(secret.environmentVariables, key = EnvironmentVariableMetadata::id) { variable ->
                    EnvironmentVariableCard(
                        variable = variable,
                        revealedValue = revealedValues[variable.id],
                        onReveal = { onReveal(variable) },
                        onReadValue = { onReadValue(variable) },
                        onCopy = { onCopy(variable) },
                        onEdit = { onEditVariable(variable) },
                    )
                }
                item {
                    Column(
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            stringResource(R.string.created, formatTimestamp(secret.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.updated, formatTimestamp(secret.updatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
        shape = RoundedCornerShape(12.dp),
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
    secret: SecretDetails?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String) -> Unit,
    snackbar: SnackbarHostState,
) {
    var name by remember(secret?.id) { mutableStateOf(secret?.name.orEmpty()) }
    var description by remember(secret?.id) { mutableStateOf(secret?.description.orEmpty()) }
    var validationError by remember(secret?.id) { mutableStateOf<Int?>(null) }
    var confirmDiscard by remember(secret?.id) { mutableStateOf(false) }
    val dirty = if (secret == null) {
        name.isNotEmpty() || description.isNotEmpty()
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
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        stringResource(R.string.secret_type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.environment_variables),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (secret == null) {
                        Text(
                            stringResource(R.string.secret_type_immutable) + " " +
                                stringResource(R.string.secret_variables_after_creation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
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
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.description_optional)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
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
            secret?.let {
                Text(
                    "${stringResource(R.string.created, formatTimestamp(it.createdAt))} · " +
                        stringResource(R.string.updated, formatTimestamp(it.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun EnvironmentVariableEditorScreen(
    variable: EnvironmentVariableMetadata?,
    currentValue: String?,
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
    var name by remember(variable?.id) { mutableStateOf(variable?.name.orEmpty()) }
    var value by remember(variable?.id) { mutableStateOf(currentValue.orEmpty()) }
    var valueEdited by remember(variable?.id) { mutableStateOf(false) }
    var sensitive by remember(variable?.id) { mutableStateOf(variable?.sensitive ?: true) }
    var notes by remember(variable?.id) { mutableStateOf(variable?.notes.orEmpty()) }
    var showValue by remember(variable?.id) { mutableStateOf(false) }
    var nameInvalid by remember(variable?.id) { mutableStateOf(false) }
    var menuExpanded by remember(variable?.id) { mutableStateOf(false) }
    var confirmDiscard by remember(variable?.id) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
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

    DisposableEffect(lifecycle, sensitive) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && sensitive) onDismiss()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
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
                            name = it
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
                            value = it
                            valueEdited = true
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
                                onValueChange = { sensitive = it },
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
                        onValueChange = { notes = it },
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
                if (variable != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(
                                    R.string.created,
                                    formatTimestamp(variable.createdAt),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(
                                    R.string.updated,
                                    formatTimestamp(variable.updatedAt),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(
                                    R.string.value_updated,
                                    formatTimestamp(variable.valueUpdatedAt),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
