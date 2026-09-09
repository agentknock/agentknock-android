package dev.agentknock.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.ui.secrets.*
import dev.agentknock.ui.components.ProseEditorScreen

@PreviewTest
@Preview(name = "Light", group = "create-secret", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun CreateSecretLightPreview() = CreateSecretPreview()

@PreviewTest
@Preview(name = "Dark", group = "create-secret", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CreateSecretDarkPreview() = CreateSecretPreview()

@Composable
private fun CreateSecretPreview() = PreviewScreen {
    SecretEditorScreen(SecretEditorState(null, "", "", SecretType.ENVIRONMENT), true, {}, {}, {}, {}, SnackbarHostState())
}

@PreviewTest
@Preview(name = "Light", group = "edit-secret", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun EditSecretLightPreview() = EditSecretPreview()

@PreviewTest
@Preview(name = "Dark", group = "edit-secret", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun EditSecretDarkPreview() = EditSecretPreview()

@Composable
private fun EditSecretPreview() = PreviewScreen {
    SecretEditorScreen(SecretEditorState(previewSecret, previewSecret.name, previewSecret.description, previewSecret.type), true, {}, {}, {}, {}, SnackbarHostState())
}

@PreviewTest
@Preview(name = "Light", group = "create-ssh-key", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun CreateSshKeyLightPreview() = CreateSshKeyPreview()

@PreviewTest
@Preview(name = "Dark", group = "create-ssh-key", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CreateSshKeyDarkPreview() = CreateSshKeyPreview()

@Composable
private fun CreateSshKeyPreview() = PreviewScreen {
    SecretEditorScreen(SecretEditorState(null, "deploy-ssh-staging", "", SecretType.SSH), true, {}, {}, {}, {}, SnackbarHostState())
}

@PreviewTest
@Preview(name = "Light", group = "replace-ssh-key", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun ReplaceSshKeyLightPreview() = ReplaceSshKeyPreview()

@PreviewTest
@Preview(name = "Dark", group = "replace-ssh-key", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ReplaceSshKeyDarkPreview() = ReplaceSshKeyPreview()

@Composable
private fun ReplaceSshKeyPreview() = PreviewScreen {
    SshKeyEditorScreen(SshKeyEditorState(previewSshSecret.id, previewSshSecret.name, previewSshKey, SshKeyDraft()), true, {}, {}, {}, {}, SnackbarHostState())
}

@PreviewTest
@Preview(name = "Light", group = "import-ssh-key", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun ImportSshKeyLightPreview() = ImportSshKeyPreview()

@PreviewTest
@Preview(name = "Dark", group = "import-ssh-key", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ImportSshKeyDarkPreview() = ImportSshKeyPreview()

@Composable
private fun ImportSshKeyPreview() = PreviewScreen {
    SshKeyEditorScreen(SshKeyEditorState(previewSshSecret.id, previewSshSecret.name, previewSshKey,
        SshKeyDraft(inputMode = SshKeyInputMode.IMPORT, error = "This is not a supported OpenSSH private key.")), true, {}, {}, {}, {}, SnackbarHostState())
}

@PreviewTest
@Preview(name = "Light", group = "environment-variable", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun EnvironmentVariableLightPreview() = EnvironmentVariablePreview()

@PreviewTest
@Preview(name = "Dark", group = "environment-variable", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun EnvironmentVariableDarkPreview() = EnvironmentVariablePreview()

@Composable
private fun EnvironmentVariablePreview() = PreviewScreen {
    val variable = previewSecret.environmentVariables.single()
    EnvironmentVariableEditorScreen(VariableEditorState(previewSecret.id, previewSecret.name, variable,
        "m8Jq4wZr7vNp2xTk9sLc", variable.name, "m8Jq4wZr7vNp2xTk9sLc", false, true), true, {}, {}, {}, {}, SnackbarHostState())
}

@PreviewTest
@Preview(name = "Light", group = "create-variable", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun CreateVariableLightPreview() = CreateVariablePreview()

@PreviewTest
@Preview(name = "Dark", group = "create-variable", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CreateVariableDarkPreview() = CreateVariablePreview()

@Composable
private fun CreateVariablePreview() = PreviewScreen {
    EnvironmentVariableEditorScreen(VariableEditorState(previewSecret.id, previewSecret.name, null,
        null, "PGHOST", "db.prod.example.com", true, false), true, {}, {}, null, {}, SnackbarHostState())
}

@PreviewTest
@Preview(name = "Light", group = "instructions", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun InstructionsLightPreview() = InstructionsPreview()

@PreviewTest
@Preview(name = "Dark", group = "instructions", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InstructionsDarkPreview() = InstructionsPreview()

@Composable
private fun InstructionsPreview() = PreviewScreen {
    ProseEditorScreen("Secret instructions", previewSecret.instructions, previewSecret.instructions,
        "Tell the AI reviewer when this secret may and may not be used. Do not include secret values. Used when AI review is active.",
        {}, {}, {}, owner = previewSecret.name)
}

@PreviewTest
@Preview(name = "Light", group = "discard-changes", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun DiscardChangesLightPreview() = DiscardChangesPreview()

@PreviewTest
@Preview(name = "Dark", group = "discard-changes", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DiscardChangesDarkPreview() = DiscardChangesPreview()

@Composable
private fun DiscardChangesPreview() = PreviewScreen { DiscardChangesDialog(true, {}, {}, modifier = previewDialogModifier()) }

@PreviewTest
@Preview(name = "Light", group = "delete-secret", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun DeleteSecretLightPreview() = DeleteSecretPreview()

@PreviewTest
@Preview(name = "Dark", group = "delete-secret", widthDp = 360, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DeleteSecretDarkPreview() = DeleteSecretPreview()

@Composable
private fun DeleteSecretPreview() = PreviewScreen {
    DeleteDialog("Delete ${previewSecret.name}?", "This secret and its stored values will be permanently deleted.", {}, {}, modifier = previewDialogModifier())
}
