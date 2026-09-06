@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.secrets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshKeyMetadata
import dev.agentknock.storage.secret.SshPrivateKey

internal enum class SshKeyInputMode { GENERATE, IMPORT }

internal enum class EditorPhase { EDITING, COMMITTING }

internal sealed interface SecretsEditorDraft {
    val containsSensitiveData: Boolean
}

internal data class SshKeyDraft(
    val inputMode: SshKeyInputMode = SshKeyInputMode.GENERATE,
    val algorithm: SshKeyAlgorithm = SshKeyAlgorithm.ED25519,
    val privateKeyText: String = "",
    val comment: String = "",
    val preparedKey: SshPrivateKey? = null,
    val preparing: Boolean = false,
    val error: String? = null,
) {
    val containsPrivateKeyMaterial: Boolean
        get() = privateKeyText.isNotEmpty() || preparedKey != null

    fun withoutPreparation(): SshKeyDraft = copy(
        preparedKey = null,
        preparing = false,
        error = null,
    )
}

internal data class SecretEditorState(
    val secret: SecretDetails?,
    val name: String,
    val description: String,
    val type: SecretType,
    val sshKeyDraft: SshKeyDraft = SshKeyDraft(),
    val environmentVariables: List<EnvironmentVariableDraft> = listOf(EnvironmentVariableDraft()),
) : SecretsEditorDraft {
    override val containsSensitiveData: Boolean
        get() = sshKeyDraft.containsPrivateKeyMaterial ||
            (secret == null && environmentVariables.any { it.sensitive && it.value.isNotEmpty() })
}

internal data class EnvironmentVariableDraft(
    val id: Long = 0,
    val name: String = "",
    val value: String = "",
    val sensitive: Boolean = true,
)

internal data class SshKeyEditorState(
    val secretId: String,
    val secretName: String,
    val currentKey: SshKeyMetadata,
    val sshKeyDraft: SshKeyDraft,
) : SecretsEditorDraft {
    override val containsSensitiveData: Boolean
        get() = sshKeyDraft.containsPrivateKeyMaterial
}

internal data class VariableEditorState(
    val secretId: String,
    val secretName: String,
    val variable: EnvironmentVariableMetadata?,
    val currentValue: String?,
    val name: String,
    val value: String,
    val valueEdited: Boolean,
    val sensitive: Boolean,
) : SecretsEditorDraft {
    override val containsSensitiveData: Boolean
        get() = variable?.sensitive == true || sensitive

    val valueChanged: Boolean
        get() = when {
            variable == null -> true
            currentValue != null -> value != currentValue
            else -> valueEdited
        }
}

@Composable
internal fun DiscardChangesDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text("Discard changes?") },
        text = { Text("Your unsaved changes will be lost.") },
        confirmButton = {
            TextButton(onClick = onDiscard, enabled = enabled) { Text("Discard") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) { Text("Keep editing") }
        },
    )
}
