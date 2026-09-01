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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.R
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.EnvironmentVariableValue
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.ui.components.AdaptiveListDetail
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun SecretsScreen(
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
    val revealedValues by viewModel.revealedValues.collectAsStateWithLifecycle()
    val revealedUploadValues by viewModel.revealedUploadValues.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var secretPendingDeletion by remember { mutableStateOf<SecretDetails?>(null) }

    val target = when (val selected = content) {
        SecretsContent.List -> null
        is SecretsContent.Loading -> selected.target
        is SecretsContent.Stored -> SecretTarget.Stored(selected.details.id)
        is SecretsContent.Upload -> SecretTarget.Upload(selected.request.id)
    }
    LaunchedEffect(target) {
        secretPendingDeletion = null
    }

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { message ->
            snackbar.showSnackbar(
                when (message) {
                    is SecretsUiMessage.Resource -> resources.getString(message.id)
                    is SecretsUiMessage.Text -> message.value
                },
            )
        }
    }

    LaunchedEffect(clipboard) {
        val pending = clipboard ?: return@LaunchedEffect
        copyToClipboard(context, pending.label, pending.value, pending.sensitive)
        viewModel.consumeClipboard(pending)
        snackbar.showSnackbar(
            resources.getString(
                if (pending.sensitive) {
                    R.string.sensitive_copied_to_clipboard
                } else {
                    R.string.copied_to_clipboard
                },
                pending.label,
            ),
        )
    }

    if (editor !is SecretsEditor.None) {
        LaunchedEffect(Unit) { onTopLevelChanged(false) }
        SecretsEditorHost(
            editor = editor,
            viewModel = viewModel,
            snackbar = snackbar,
        )
        return
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
        viewModel.toggleEnvironmentVariableReveal(
            variable = variable,
            protectionTitle = if (variable.sensitive) {
                resources.getString(R.string.reveal_sensitive_value, variable.name)
            } else {
                null
            },
        )
    }

    fun copy(variable: EnvironmentVariableMetadata) {
        viewModel.copyEnvironmentVariable(
            variable = variable,
            protectionTitle = if (variable.sensitive) {
                resources.getString(R.string.copy_sensitive_value, variable.name)
            } else {
                null
            },
        )
    }

    fun edit(variable: EnvironmentVariableMetadata) {
        viewModel.editEnvironmentVariable(
            variable = variable,
            protectionTitle = if (variable.sensitive) {
                resources.getString(R.string.edit_sensitive_value, variable.name)
            } else {
                null
            },
        )
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
        onSaveSshComment = { comment -> viewModel.saveSshComment(secret.id, comment) },
        onCopyPublicKey = { copyPublicKey(secret) },
        onEditVariable = ::edit,
        onReveal = ::reveal,
        onReadValue = ::readValue,
        onCopy = ::copy,
        onSetApprovalMode = { mode -> viewModel.saveApprovalMode(secret.id, mode) },
        onSetClientApprovalOverride = { clientId, mode ->
            viewModel.setClientApprovalOverride(secret.id, clientId, mode)
        },
        onSaveInstructions = { instructions ->
            viewModel.saveInstructions(secret.id, instructions)
        },
        onEndTemporaryAccess = { grant ->
            viewModel.endTemporaryAccess(secret.id, grant.clientId, grant.operation)
        },
    )

    fun saveGeneralInstructions(instructions: String) {
        viewModel.saveGeneralInstructions(instructions)
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
            modifier = Modifier.fillMaxSize().padding(padding),
            list = { listModifier ->
                SecretList(
                    secrets = secrets,
                    pendingUploads = pendingUploads,
                    selectedSecretId = (target as? SecretTarget.Stored)?.id,
                    selectedUploadRequestId = (target as? SecretTarget.Upload)?.requestId,
                    onSelect = viewModel::selectSecret,
                    onSelectUpload = { requestId -> viewModel.selectUpload(requestId) },
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
                            revealedValues = revealedUploadValues,
                            viewModel = viewModel,
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

    secretPendingDeletion?.let { secret ->
        DeleteDialog(
            title = stringResource(R.string.delete_secret_question, secret.name),
            explanation = stringResource(R.string.delete_secret_explanation),
            onDismiss = { secretPendingDeletion = null },
            onDelete = {
                secretPendingDeletion = null
                viewModel.deleteSecret(secret.id)
            },
        )
    }
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

internal fun SecretType.displayName(): String = when (this) {
    SecretType.ENVIRONMENT -> "Environment variables"
    SecretType.SSH -> "SSH key"
}

internal fun SshKeyAlgorithm.displayName(): String = when (this) {
    SshKeyAlgorithm.ED25519 -> "Ed25519"
    SshKeyAlgorithm.RSA -> "RSA"
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
