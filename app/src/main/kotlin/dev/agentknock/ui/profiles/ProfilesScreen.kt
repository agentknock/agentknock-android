package dev.agentknock.ui.profiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.R
import dev.agentknock.storage.profile.CreateEnvironmentVariableResult
import dev.agentknock.storage.profile.CreateProfileResult
import dev.agentknock.storage.profile.EnvironmentVariableMetadata
import dev.agentknock.storage.profile.EnvironmentVariableValue
import dev.agentknock.storage.profile.ProfileDetails
import dev.agentknock.storage.profile.ProfileSummary
import dev.agentknock.storage.profile.SaveEnvironmentVariableResult
import dev.agentknock.storage.profile.SaveProfileResult
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private val environmentVariableName = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val twoPaneWidth = 840.dp

private sealed interface ProfileEditor {
    data object New : ProfileEditor
    data class Existing(val profile: ProfileDetails) : ProfileEditor
}

private sealed interface VariableEditor {
    data class New(val profileId: String) : VariableEditor
    data class Existing(val variable: EnvironmentVariableMetadata) : VariableEditor
}

@Composable
internal fun ProfilesScreen(
    authenticate: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    viewModel: ProfilesViewModel = viewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var profileEditor by remember { mutableStateOf<ProfileEditor?>(null) }
    var variableEditor by remember { mutableStateOf<VariableEditor?>(null) }
    var profilePendingDeletion by remember { mutableStateOf<ProfileDetails?>(null) }
    var variablePendingDeletion by remember { mutableStateOf<EnvironmentVariableMetadata?>(null) }
    var revealedValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

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

    fun readValue(
        variable: EnvironmentVariableMetadata,
        onAvailable: (String) -> Unit,
    ) {
        scope.launch {
            when (val value = viewModel.readEnvironmentVariableValue(variable.id)) {
                is EnvironmentVariableValue.Available -> onAvailable(value.value)
                EnvironmentVariableValue.Unavailable -> report(
                    resources.getString(R.string.value_unavailable),
                )
                EnvironmentVariableValue.Corrupted -> report(
                    resources.getString(R.string.corrupted_value),
                )
                EnvironmentVariableValue.UnsupportedFormat -> report(
                    resources.getString(R.string.unsupported_value),
                )
                EnvironmentVariableValue.NotFound -> report(
                    resources.getString(R.string.missing_value),
                )
            }
        }
    }

    fun reveal(variable: EnvironmentVariableMetadata) {
        if (revealedValues.containsKey(variable.id)) {
            revealedValues -= variable.id
            return
        }
        val action = {
            readValue(variable) { value -> revealedValues += variable.id to value }
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
        val action = {
            readValue(variable) { value ->
                copyToClipboard(context, variable.name, value, variable.sensitive)
                report(resources.getString(R.string.copied_to_clipboard, variable.name))
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val twoPane = maxWidth >= twoPaneWidth
            val profile = selectedProfile
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    ProfileList(
                        profiles = profiles,
                        selectedProfileId = selection,
                        onSelect = viewModel::selectProfile,
                        onCreate = { profileEditor = ProfileEditor.New },
                        modifier = Modifier
                            .width(340.dp)
                            .fillMaxHeight(),
                    )
                    VerticalDivider()
                    if (selection == null) {
                        EmptyProfileSelection(Modifier.weight(1f))
                    } else if (profile == null) {
                        Loading(Modifier.weight(1f))
                    } else {
                        ProfileDetail(
                            profile = profile,
                            revealedValues = revealedValues,
                            showBack = false,
                            onBack = {},
                            onEditProfile = {
                                profileEditor = ProfileEditor.Existing(profile)
                            },
                            onDeleteProfile = {
                                profilePendingDeletion = profile
                            },
                            onAddVariable = {
                                variableEditor = VariableEditor.New(profile.id)
                            },
                            onEditVariable = { variableEditor = VariableEditor.Existing(it) },
                            onReveal = ::reveal,
                            onCopy = ::copy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else if (selection == null) {
                ProfileList(
                    profiles = profiles,
                    selectedProfileId = null,
                    onSelect = viewModel::selectProfile,
                    onCreate = { profileEditor = ProfileEditor.New },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (profile == null) {
                Loading(Modifier.fillMaxSize())
            } else {
                BackHandler { viewModel.selectProfile(null) }
                ProfileDetail(
                    profile = profile,
                    revealedValues = revealedValues,
                    showBack = true,
                    onBack = { viewModel.selectProfile(null) },
                    onEditProfile = {
                        profileEditor = ProfileEditor.Existing(profile)
                    },
                    onDeleteProfile = {
                        profilePendingDeletion = profile
                    },
                    onAddVariable = {
                        variableEditor = VariableEditor.New(profile.id)
                    },
                    onEditVariable = { variableEditor = VariableEditor.Existing(it) },
                    onReveal = ::reveal,
                    onCopy = ::copy,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    profileEditor?.let { editor ->
        ProfileDialog(
            profile = (editor as? ProfileEditor.Existing)?.profile,
            onDismiss = { profileEditor = null },
            onSave = { name, description ->
                scope.launch {
                    val error = when (editor) {
                        ProfileEditor.New -> when (
                            val result = viewModel.createProfile(name, description)
                        ) {
                            is CreateProfileResult.Created -> {
                                profileEditor = null
                                viewModel.selectProfile(result.id)
                                null
                            }
                            CreateProfileResult.NameInUse -> resources.getString(
                                R.string.profile_name_in_use,
                            )
                        }
                        is ProfileEditor.Existing -> when (
                            viewModel.saveProfile(editor.profile.id, name, description)
                        ) {
                            SaveProfileResult.SAVED -> {
                                profileEditor = null
                                null
                            }
                            SaveProfileResult.NAME_IN_USE -> resources.getString(
                                R.string.profile_name_in_use,
                            )
                            SaveProfileResult.NOT_FOUND -> resources.getString(
                                R.string.profile_not_found,
                            )
                        }
                    }
                    error?.let(::report)
                }
            },
        )
    }

    variableEditor?.let { editor ->
        val variable = (editor as? VariableEditor.Existing)?.variable
        EnvironmentVariableDialog(
            variable = variable,
            onDismiss = { variableEditor = null },
            onDelete = variable?.let {
                { variablePendingDeletion = it }
            },
            onSave = { name, value, sensitive, notes, replaceValue ->
                afterAuthentication(resources.getString(R.string.confirm_save_variable)) {
                    val error = when (editor) {
                        is VariableEditor.New -> when (
                            viewModel.createEnvironmentVariable(
                                profileId = editor.profileId,
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
                            CreateEnvironmentVariableResult.ProfileNotFound -> resources.getString(
                                R.string.profile_not_found,
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
        )
    }

    profilePendingDeletion?.let { profile ->
        DeleteDialog(
            title = stringResource(R.string.delete_profile_question, profile.name),
            explanation = stringResource(R.string.delete_profile_explanation),
            onDismiss = { profilePendingDeletion = null },
            onDelete = {
                profilePendingDeletion = null
                afterAuthentication(resources.getString(R.string.confirm_delete_profile)) {
                    if (viewModel.deleteProfile(profile.id)) {
                        report(resources.getString(R.string.profile_deleted))
                    } else {
                        report(resources.getString(R.string.profile_not_found))
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
private fun ProfileList(
    profiles: List<ProfileSummary>,
    selectedProfileId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.profiles), style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onCreate) { Text(stringResource(R.string.new_profile)) }
        }
        HorizontalDivider()
        if (profiles.isEmpty()) {
            EmptyMessage(
                title = stringResource(R.string.no_profiles),
                description = stringResource(R.string.no_profiles_description),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(profiles, key = ProfileSummary::id) { profile ->
                    val selected = profile.id == selectedProfileId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(profile.id) }
                            .then(
                                if (selected) {
                                    Modifier.padding(horizontal = 4.dp)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (profile.description.isNotBlank()) {
                            Text(
                                profile.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            pluralStringResource(
                                R.plurals.variable_count,
                                profile.environmentVariableCount,
                                profile.environmentVariableCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileDetail(
    profile: ProfileDetails,
    revealedValues: Map<String, String>,
    showBack: Boolean,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onAddVariable: () -> Unit,
    onEditVariable: (EnvironmentVariableMetadata) -> Unit,
    onReveal: (EnvironmentVariableMetadata) -> Unit,
    onCopy: (EnvironmentVariableMetadata) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (showBack) {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
                Text(stringResource(R.string.back))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.headlineMedium)
                if (profile.description.isNotBlank()) {
                    Text(
                        profile.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onEditProfile) { Text(stringResource(R.string.edit_profile)) }
            TextButton(onClick = onDeleteProfile) { Text(stringResource(R.string.delete_profile)) }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.environment_variable),
                style = MaterialTheme.typography.titleLarge,
            )
            Button(onClick = onAddVariable) { Text(stringResource(R.string.add_variable)) }
        }
        if (profile.environmentVariables.isEmpty()) {
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
                items(profile.environmentVariables, key = EnvironmentVariableMetadata::id) { variable ->
                    EnvironmentVariableCard(
                        variable = variable,
                        revealedValue = revealedValues[variable.id],
                        onReveal = { onReveal(variable) },
                        onCopy = { onCopy(variable) },
                        onEdit = { onEditVariable(variable) },
                    )
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
    onCopy: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                )
                Text(
                    stringResource(
                        if (variable.sensitive) R.string.sensitive else R.string.not_sensitive,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!variable.valueAvailable) {
                Text(
                    stringResource(R.string.value_unavailable),
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (revealedValue == null) {
                Text(
                    stringResource(R.string.value_hidden),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Text(revealedValue, fontFamily = FontFamily.Monospace)
                }
            }
            if (variable.notes.isNotBlank()) {
                Text(variable.notes, style = MaterialTheme.typography.bodyMedium)
            }
            Column {
                Text(
                    stringResource(R.string.created, formatTimestamp(variable.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.updated, formatTimestamp(variable.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.value_updated, formatTimestamp(variable.valueUpdatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onReveal, enabled = variable.valueAvailable) {
                    Text(
                        stringResource(
                            if (revealedValue == null) R.string.show else R.string.hide,
                        ),
                    )
                }
                TextButton(onClick = onCopy, enabled = variable.valueAvailable) {
                    Text(stringResource(R.string.copy))
                }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
            }
        }
    }
}

@Composable
private fun ProfileDialog(
    profile: ProfileDetails?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String) -> Unit,
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var description by remember(profile?.id) { mutableStateOf(profile?.description.orEmpty()) }
    var validationError by remember(profile?.id) { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (profile == null) R.string.create_profile else R.string.edit_profile,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationError = null
                    },
                    label = { Text(stringResource(R.string.profile_name)) },
                    singleLine = true,
                    isError = validationError != null,
                    supportingText = validationError?.let { error ->
                        { Text(stringResource(error)) }
                    },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description_optional)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    validationError = when {
                        name.isBlank() -> R.string.profile_name_required
                        name != name.trim() -> R.string.profile_name_whitespace
                        else -> null
                    }
                    if (validationError == null) onSave(name, description)
                },
            ) {
                Text(
                    stringResource(
                        if (profile == null) R.string.create_profile else R.string.save_profile,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun EnvironmentVariableDialog(
    variable: EnvironmentVariableMetadata?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (
        name: String,
        value: String,
        sensitive: Boolean,
        notes: String,
        replaceValue: Boolean,
    ) -> Unit,
) {
    var name by remember(variable?.id) { mutableStateOf(variable?.name.orEmpty()) }
    var value by remember(variable?.id) { mutableStateOf("") }
    var sensitive by remember(variable?.id) { mutableStateOf(variable?.sensitive ?: true) }
    var notes by remember(variable?.id) { mutableStateOf(variable?.notes.orEmpty()) }
    var replaceValue by remember(variable?.id) {
        mutableStateOf(variable == null || variable.valueAvailable.not())
    }
    var showValue by remember(variable?.id) { mutableStateOf(false) }
    var nameInvalid by remember(variable?.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (variable == null) {
                        R.string.new_environment_variable
                    } else {
                        R.string.environment_variable
                    },
                ),
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    )
                }
                if (variable != null) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = replaceValue,
                                onCheckedChange = { replaceValue = it },
                            )
                            Text(stringResource(R.string.replace_value))
                        }
                    }
                    if (!variable.valueAvailable) {
                        item {
                            Text(
                                stringResource(R.string.replace_unavailable_value),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (variable == null || replaceValue) {
                    item {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = {
                                Text(
                                    stringResource(
                                        if (variable == null) {
                                            R.string.variable_value
                                        } else {
                                            R.string.new_variable_value
                                        },
                                    ),
                                )
                            },
                            visualTransformation = if (sensitive && !showValue) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                            minLines = 2,
                            trailingIcon = if (sensitive) {
                                {
                                    TextButton(onClick = { showValue = !showValue }) {
                                        Text(
                                            stringResource(
                                                if (showValue) R.string.hide else R.string.show,
                                            ),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                        Switch(checked = sensitive, onCheckedChange = { sensitive = it })
                    }
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(stringResource(R.string.notes_optional)) },
                        minLines = 2,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    nameInvalid = !environmentVariableName.matches(name)
                    if (!nameInvalid) onSave(name, value, sensitive, notes, replaceValue)
                },
            ) {
                Text(
                    stringResource(
                        if (variable == null) R.string.create_variable else R.string.save_variable,
                    ),
                )
            }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(onClick = it) { Text(stringResource(R.string.delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
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
private fun EmptyProfileSelection(modifier: Modifier = Modifier) {
    EmptyMessage(
        title = stringResource(R.string.select_profile),
        description = stringResource(R.string.select_profile_description),
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

private fun formatTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun copyToClipboard(
    context: Context,
    label: String,
    value: String,
    sensitive: Boolean,
) {
    val clip = ClipData.newPlainText(label, value)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clip)
}
