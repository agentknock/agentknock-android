package dev.agentknock.ui.secrets

import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshKeyMetadata
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.secret.SecretType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsEditorCommitLifecycleTest {
    @Test
    fun `repeated submit starts one commit for every editor`() {
        editingEditors().forEach { editor ->
            val state = MutableStateFlow<SecretsEditor>(editor)
            val first = state.beginEditorCommit(editor)
            val second = state.beginEditorCommit(editor)

            assertNotNull(first)
            assertNull(second)
            assertEquals(EditorPhase.COMMITTING, (state.value as SecretsEditor.Active).phase)
        }
    }

    @Test
    fun `draft updates and back are ignored while every editor commits`() {
        editingEditors().forEach { editor ->
            val state = MutableStateFlow<SecretsEditor>(editor)
            val commit = state.beginEditorCommit(editor)
            assertNotNull(commit)
            var updateInvoked = false

            assertFalse(
                state.mutateEditing(SESSION) {
                    updateInvoked = true
                    changedDraft(it)
                },
            )
            assertFalse(state.mutateEditing(SESSION) { SecretsEditor.None })
            assertFalse(updateInvoked)
            assertEquals(commit!!.committing, state.value)
        }
    }

    @Test
    fun `failed commit restores editing and accepts changes again`() {
        editingEditors().forEach { editor ->
            val state = MutableStateFlow<SecretsEditor>(editor)
            val commit = checkNotNull(state.beginEditorCommit(editor))

            assertTrue(state.failEditorCommit(commit))
            assertEquals(editor, state.value)
            assertTrue(state.mutateEditing(SESSION, ::changedDraft))
            assertEquals(changedDraft(editor), state.value)
        }
    }

    @Test
    fun `successful and stale completions preserve exact editor CAS`() {
        editingEditors().forEach { editor ->
            val successState = MutableStateFlow<SecretsEditor>(editor)
            val success = checkNotNull(successState.beginEditorCommit(editor))

            assertTrue(successState.completeEditorCommit(success))
            assertEquals(SecretsEditor.None, successState.value)

            val staleState = MutableStateFlow<SecretsEditor>(editor)
            val stale = checkNotNull(staleState.beginEditorCommit(editor))
            val replacement = editor.copy(session = SESSION + 1)
            staleState.value = replacement

            assertFalse(staleState.completeEditorCommit(stale))
            assertFalse(staleState.failEditorCommit(stale))
            assertEquals(replacement, staleState.value)
        }
    }

    @Test
    fun `submit callback with a stale draft cannot start a commit`() {
        editingEditors().forEach { editor ->
            val state = MutableStateFlow<SecretsEditor>(changedDraft(editor))

            assertNull(state.beginEditorCommit(editor))
            assertEquals(changedDraft(editor), state.value)
        }
    }

    @Test
    fun `background clear preserves a non-sensitive commit until completion`() {
        val editor = editingEditors().single { it.draft is SecretEditorState }
        val state = MutableStateFlow<SecretsEditor>(editor)
        val commit = checkNotNull(state.beginEditorCommit(editor))

        state.clearSensitiveEditorState { SESSION + 1 }

        assertEquals(commit.committing, state.value)
        assertTrue(state.completeEditorCommit(commit))
        assertEquals(SecretsEditor.None, state.value)
    }

    @Test
    fun `background clear releases generated key preparation`() {
        val editor = SecretsEditor.Active(
            session = SESSION,
            draft = SshKeyEditorState(
                secretId = "secret-id",
                secretName = "secret",
                currentKey = SshKeyMetadata(
                    algorithm = SshKeyAlgorithm.ED25519,
                    bits = 256,
                    publicKey = "public-key",
                    fingerprint = "fingerprint",
                    fingerprintHex = "fingerprint-hex",
                    comment = "",
                    privateKeyAvailable = true,
                ),
                sshKeyDraft = SshKeyDraft(preparing = true),
            ),
        )
        val state = MutableStateFlow<SecretsEditor>(editor)

        state.clearSensitiveEditorState { SESSION + 1 }

        val cleared = state.value as SecretsEditor.Active
        val draft = cleared.draft as SshKeyEditorState
        assertEquals(SESSION + 1, cleared.session)
        assertFalse(draft.sshKeyDraft.preparing)
        assertNull(draft.sshKeyDraft.preparedKey)
    }

    @Test
    fun `background clear makes sensitive commit completion stale`() {
        val editor = editingEditors().single { it.draft is VariableEditorState }
        val state = MutableStateFlow<SecretsEditor>(editor)
        val commit = checkNotNull(state.beginEditorCommit(editor))

        state.clearSensitiveEditorState { SESSION + 1 }

        assertEquals(SecretsEditor.None, state.value)
        assertFalse(state.completeEditorCommit(commit))
        assertFalse(state.failEditorCommit(commit))
    }

    @Test
    fun `background clear removes entered sensitive values from new secret drafts`() {
        SecretType.entries.forEach { type ->
            val editor = newSecretWithVariable(
                EnvironmentVariableDraft(name = "TOKEN", value = "private value"),
                type = type,
            )
            val editing = MutableStateFlow<SecretsEditor>(editor)
            val committing = MutableStateFlow<SecretsEditor>(editor)
            val commit = checkNotNull(committing.beginEditorCommit(editor))

            editing.clearSensitiveEditorState { SESSION + 1 }
            committing.clearSensitiveEditorState { SESSION + 1 }

            assertEquals(SecretsEditor.None, editing.value)
            assertEquals(SecretsEditor.None, committing.value)
            assertFalse(committing.completeEditorCommit(commit))
            assertFalse(committing.failEditorCommit(commit))
        }
    }

    @Test
    fun `background clear preserves empty sensitive and populated public new secret drafts`() {
        listOf(
            EnvironmentVariableDraft(name = "TOKEN"),
            EnvironmentVariableDraft(name = "REGION", value = "public value", sensitive = false),
        ).forEach { variable ->
            val editor = newSecretWithVariable(variable)
            val editing = MutableStateFlow<SecretsEditor>(editor)
            val committing = MutableStateFlow<SecretsEditor>(editor)
            val commit = checkNotNull(committing.beginEditorCommit(editor))

            editing.clearSensitiveEditorState { SESSION + 1 }
            committing.clearSensitiveEditorState { SESSION + 1 }

            assertEquals(editor.copy(session = SESSION + 1), editing.value)
            assertEquals(commit.committing, committing.value)
            assertTrue(committing.completeEditorCommit(commit))
        }
    }

    @Test
    fun `background clear removes imported and prepared SSH material from either editor`() {
        listOf(
            SshKeyDraft(privateKeyText = "private key"),
            SshKeyDraft(preparedKey = preparedKey),
        ).flatMap(::sshEditors).forEach { editor ->
            val state = MutableStateFlow<SecretsEditor>(editor)

            state.clearSensitiveEditorState { SESSION + 1 }

            assertEquals(SecretsEditor.None, state.value)
        }
    }

    @Test
    fun `SSH preparation updates either editor without replacing other edits`() {
        val source = SshKeyDraft(inputMode = SshKeyInputMode.IMPORT, privateKeyText = "imported key")
        sshEditors(source.copy(preparing = true)).forEach { editor ->
            val edited = changedDraft(editor)
            val state = MutableStateFlow<SecretsEditor>(edited)

            state.completeSshKeyPreparation(SESSION, source, Result.success(preparedKey))

            assertEquals(
                withSshDraft(edited, source.copy(privateKeyText = "", preparedKey = preparedKey)),
                state.value,
            )
        }
    }

    @Test
    fun `SSH preparation failure preserves import text for correction`() {
        val source = SshKeyDraft(inputMode = SshKeyInputMode.IMPORT, privateKeyText = "invalid key")
        sshEditors(source.copy(preparing = true)).forEach { editor ->
            val state = MutableStateFlow<SecretsEditor>(editor)

            state.completeSshKeyPreparation(
                SESSION, source, Result.failure(IllegalArgumentException("Invalid key")),
            )

            assertEquals(withSshDraft(editor, source.copy(error = "Invalid key")), state.value)
        }
    }

    @Test
    fun `SSH preparation cannot repopulate closed backgrounded or changed editors`() {
        val source = SshKeyDraft(preparing = true)
        sshEditors(source).forEach { editor ->
            val backgrounded = MutableStateFlow<SecretsEditor>(editor).also {
                it.clearSensitiveEditorState { SESSION + 1 }
            }.value
            val committing = MutableStateFlow<SecretsEditor>(editor).also { it.beginEditorCommit(editor) }.value
            val staleEditors = listOf(
                SecretsEditor.None,
                editor.copy(session = SESSION + 1),
                withSshDraft(editor, source.copy(comment = "changed")),
                withSshDraft(editor, source.copy(algorithm = SshKeyAlgorithm.RSA)),
                backgrounded,
                committing,
            ) + editingEditors().filter { it.draft is SecretEditorState }
            staleEditors.forEach { stale ->
                val state = MutableStateFlow(stale)
                state.completeSshKeyPreparation(SESSION, source, Result.success(preparedKey))
                assertEquals(stale, state.value)
            }
        }
    }

    private fun sshEditors(draft: SshKeyDraft): List<SecretsEditor.Active> =
        editingEditors().mapNotNull { editor ->
            when (val state = editor.draft) {
                is SecretEditorState -> editor.copy(
                    draft = state.copy(type = SecretType.SSH, sshKeyDraft = draft),
                )
                is SshKeyEditorState -> withSshDraft(editor, draft)
                is VariableEditorState -> null
            }
        }

    private fun newSecretWithVariable(
        variable: EnvironmentVariableDraft,
        type: SecretType = SecretType.ENVIRONMENT,
    ): SecretsEditor.Active = SecretsEditor.Active(
        session = SESSION,
        draft = SecretEditorState(
            secret = null,
            name = "secret",
            description = "",
            type = type,
            environmentVariables = listOf(variable),
        ),
    )

    private fun withSshDraft(
        editor: SecretsEditor.Active,
        draft: SshKeyDraft,
    ): SecretsEditor.Active = editor.copy(
        draft = when (val state = editor.draft) {
            is SecretEditorState -> state.copy(sshKeyDraft = draft)
            is SshKeyEditorState -> state.copy(sshKeyDraft = draft)
            is VariableEditorState -> error("Not an SSH editor")
        },
    )

    private val preparedKey = SshPrivateKey(
        algorithm = SshKeyAlgorithm.ED25519,
        privateKey = ByteArray(32),
        publicKey = ByteArray(32),
        comment = "",
    )

    private fun editingEditors(): List<SecretsEditor.Active> = listOf(
        SecretsEditor.Active(
            session = SESSION,
            draft = SecretEditorState(
                secret = null,
                name = "secret",
                description = "",
                type = SecretType.ENVIRONMENT,
            ),
        ),
        SecretsEditor.Active(
            session = SESSION,
            draft = VariableEditorState(
                secretId = "secret-id",
                secretName = "secret",
                variable = null,
                currentValue = null,
                name = "VARIABLE",
                value = "value",
                valueEdited = true,
                sensitive = true,
            ),
        ),
        SecretsEditor.Active(
            session = SESSION,
            draft = SshKeyEditorState(
                secretId = "secret-id",
                secretName = "secret",
                currentKey = SshKeyMetadata(
                    algorithm = SshKeyAlgorithm.ED25519,
                    bits = 256,
                    publicKey = "public-key",
                    fingerprint = "fingerprint",
                    fingerprintHex = "fingerprint-hex",
                    comment = "",
                    privateKeyAvailable = true,
                ),
                sshKeyDraft = SshKeyDraft(),
            ),
        ),
    )

    private fun changedDraft(editor: SecretsEditor.Active): SecretsEditor.Active = editor.copy(
        draft = when (val state = editor.draft) {
            is SecretEditorState -> state.copy(name = "changed-secret")
            is VariableEditorState -> state.copy(name = "CHANGED_VARIABLE")
            is SshKeyEditorState -> state.copy(secretName = "changed-secret")
        },
    )

    private companion object {
        const val SESSION = 42L
    }
}
