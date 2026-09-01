@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshKeyMetadata
import dev.agentknock.storage.secret.SshPrivateKey

internal enum class SshKeyInputMode { GENERATE, IMPORT }

internal data class SshKeyDraft(
    val inputMode: SshKeyInputMode = SshKeyInputMode.GENERATE,
    val algorithm: SshKeyAlgorithm = SshKeyAlgorithm.ED25519,
    val privateKeyText: String = "",
    val comment: String = "",
    val preparedKey: SshPrivateKey? = null,
    val error: String? = null,
) {
    fun withoutPreparation(): SshKeyDraft = copy(preparedKey = null, error = null)
}

internal data class SecretEditorState(
    val secret: SecretDetails?,
    val name: String,
    val description: String,
    val type: SecretType,
    val sshKeyDraft: SshKeyDraft = SshKeyDraft(),
)

internal data class SshKeyEditorState(
    val secretId: String,
    val secretName: String,
    val currentKey: SshKeyMetadata,
    val sshKeyDraft: SshKeyDraft,
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
internal fun DiscardChangesDialog(onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard changes?") },
        text = { Text("Your unsaved changes will be lost.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep editing") } },
    )
}
