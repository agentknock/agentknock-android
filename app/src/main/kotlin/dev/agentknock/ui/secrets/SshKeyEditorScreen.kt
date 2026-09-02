@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.secret.SshKeyCodec
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.NavigationBackButton

@Composable
internal fun SshKeyInput(
    draft: SshKeyDraft,
    enabled: Boolean,
    onDraftChange: (SshKeyDraft) -> Unit,
    onPrepare: () -> Unit,
) {
    val sourceEnabled = enabled && !draft.preparing
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Key material", style = MaterialTheme.typography.titleMedium)
        if (draft.preparedKey != null) {
            SshKeyPreview(draft.preparedKey)
            FilledTonalButton(
                onClick = {
                    onDraftChange(
                        draft.withoutPreparation().copy(privateKeyText = ""),
                    )
                },
                enabled = sourceEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose another key")
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.inputMode == SshKeyInputMode.GENERATE,
                enabled = sourceEnabled,
                onClick = {
                    onDraftChange(
                        draft.copy(inputMode = SshKeyInputMode.GENERATE).withoutPreparation(),
                    )
                },
                label = { Text("Generate") },
            )
            FilterChip(
                selected = draft.inputMode == SshKeyInputMode.IMPORT,
                enabled = sourceEnabled,
                onClick = {
                    onDraftChange(
                        draft.copy(inputMode = SshKeyInputMode.IMPORT).withoutPreparation(),
                    )
                },
                label = { Text("Import") },
            )
        }
        if (draft.inputMode == SshKeyInputMode.GENERATE) {
            Text("Algorithm", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = draft.algorithm == SshKeyAlgorithm.ED25519,
                    enabled = sourceEnabled,
                    onClick = {
                        onDraftChange(
                            draft.copy(algorithm = SshKeyAlgorithm.ED25519)
                                .withoutPreparation(),
                        )
                    },
                    label = { Text("Ed25519") },
                )
                FilterChip(
                    selected = draft.algorithm == SshKeyAlgorithm.RSA,
                    enabled = sourceEnabled,
                    onClick = {
                        onDraftChange(
                            draft.copy(algorithm = SshKeyAlgorithm.RSA).withoutPreparation(),
                        )
                    },
                    label = { Text("RSA") },
                )
            }
            Text(
                when (draft.algorithm) {
                    SshKeyAlgorithm.ED25519 ->
                        "Generate a new Ed25519 key on this device. Recommended for new keys."
                    SshKeyAlgorithm.RSA ->
                        "Generate a new 3072-bit RSA key for systems that require RSA."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft.comment,
                onValueChange = { comment ->
                    onDraftChange(draft.copy(comment = comment).withoutPreparation())
                },
                label = { Text("Public key comment (optional)") },
                enabled = sourceEnabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "Paste an unencrypted OpenSSH Ed25519 or RSA private key. Encrypted keys must be " +
                    "decrypted before import.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft.privateKeyText,
                onValueChange = { privateKeyText ->
                    onDraftChange(
                        draft.copy(privateKeyText = privateKeyText).withoutPreparation(),
                    )
                },
                label = { Text("OpenSSH private key") },
                enabled = sourceEnabled,
                minLines = 6,
                maxLines = 12,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                ),
                visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (draft.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
        FilledTonalButton(
                onClick = onPrepare,
                enabled = enabled && !draft.preparing && (
                    draft.inputMode == SshKeyInputMode.GENERATE ||
                        draft.privateKeyText.isNotBlank()
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (draft.preparing) {
                        "Preparing key…"
                    } else if (draft.inputMode == SshKeyInputMode.GENERATE) {
                        "Generate and review"
                    } else {
                        "Review key"
                    },
                )
            }
        draft.error?.let {
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
    val publicKey = remember(key) {
        SshKeyCodec().publicKey(key.algorithm, key.publicKey, key.comment)
    }
    InformationSurface {
        Text("Ready to save", style = MaterialTheme.typography.titleMedium)
        InformationRow(
            "Algorithm",
            "${key.algorithm.displayName()} · ${SshKeyCodec().bitLength(publicKey)} bits",
        )
        InformationRow("OpenSSH fingerprint", key.fingerprint, monospace = true)
        InformationRow("SHA-256 fingerprint (hex)", publicKey.fingerprintHex, monospace = true)
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
internal fun SshKeyEditorScreen(
    editor: SshKeyEditorState,
    enabled: Boolean,
    onEditorChange: (SshKeyEditorState) -> Unit,
    onDismiss: () -> Unit,
    onPrepare: () -> Unit,
    onReplace: () -> Unit,
    snackbar: SnackbarHostState,
) {
    var confirmDiscard by rememberSaveable(editor.secretId) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val draft = editor.sshKeyDraft
    val dirty = draft.privateKeyText.isNotEmpty() || draft.preparedKey != null ||
        draft.comment != editor.currentKey.comment ||
        draft.algorithm != editor.currentKey.algorithm
    LaunchedEffect(draft.preparedKey) {
        if (draft.preparedKey != null) {
            withFrameNanos { }
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
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
                    title = { Text("Replace SSH key") },
                    navigationIcon = {
                        NavigationBackButton(
                            onClick = ::requestDismiss,
                            enabled = enabled,
                        )
                    },
                )
                if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 720.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
            InformationSurface {
                Text(editor.secretName, style = MaterialTheme.typography.titleMedium)
                InformationRow("Current OpenSSH fingerprint", editor.currentKey.fingerprint)
                InformationRow(
                    "Current SHA-256 fingerprint (hex)",
                    editor.currentKey.fingerprintHex,
                )
                Text(
                    "Replacing the private key changes the public key while keeping the secret's " +
                        "name and request references.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SshKeyInput(
                draft = draft,
                enabled = enabled,
                onDraftChange = { sshKeyDraft ->
                    onEditorChange(editor.copy(sshKeyDraft = sshKeyDraft))
                },
                onPrepare = {
                    focusManager.clearFocus()
                    onPrepare()
                },
            )
            Button(
                onClick = onReplace,
                enabled = enabled && draft.preparedKey != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Replace key") }
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
