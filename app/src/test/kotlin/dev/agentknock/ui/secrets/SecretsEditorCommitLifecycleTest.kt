package dev.agentknock.ui.secrets

import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshKeyMetadata
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
            assertEquals(EditorPhase.COMMITTING, state.value.phase())
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
            val replacement = editor.withSession(SESSION + 1)
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
        val editor = editingEditors().filterIsInstance<SecretsEditor.Secret>().single()
        val state = MutableStateFlow<SecretsEditor>(editor)
        val commit = checkNotNull(state.beginEditorCommit(editor))

        state.clearSensitiveEditorState { SESSION + 1 }

        assertEquals(commit.committing, state.value)
        assertTrue(state.completeEditorCommit(commit))
        assertEquals(SecretsEditor.None, state.value)
    }

    @Test
    fun `background clear releases generated key preparation`() {
        val editor = SecretsEditor.SshKey(
            session = SESSION,
            state = SshKeyEditorState(
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

        val cleared = state.value as SecretsEditor.SshKey
        assertEquals(SESSION + 1, cleared.session)
        assertFalse(cleared.state.sshKeyDraft.preparing)
        assertNull(cleared.state.sshKeyDraft.preparedKey)
    }

    @Test
    fun `background clear makes sensitive commit completion stale`() {
        val editor = editingEditors().filterIsInstance<SecretsEditor.Variable>().single()
        val state = MutableStateFlow<SecretsEditor>(editor)
        val commit = checkNotNull(state.beginEditorCommit(editor))

        state.clearSensitiveEditorState { SESSION + 1 }

        assertEquals(SecretsEditor.None, state.value)
        assertFalse(state.completeEditorCommit(commit))
        assertFalse(state.failEditorCommit(commit))
    }

    private fun editingEditors(): List<SecretsEditor> = listOf(
        SecretsEditor.Secret(
            session = SESSION,
            state = SecretEditorState(
                secret = null,
                name = "secret",
                description = "",
                type = SecretType.ENVIRONMENT,
            ),
        ),
        SecretsEditor.Variable(
            session = SESSION,
            state = VariableEditorState(
                secretId = "secret-id",
                variable = null,
                currentValue = null,
                name = "VARIABLE",
                value = "value",
                valueEdited = true,
                sensitive = true,
            ),
        ),
        SecretsEditor.SshKey(
            session = SESSION,
            state = SshKeyEditorState(
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

    private fun changedDraft(editor: SecretsEditor): SecretsEditor = when (editor) {
        SecretsEditor.None -> error("None has no draft")
        is SecretsEditor.Secret -> editor.copy(
            state = editor.state.copy(name = "changed-secret"),
        )
        is SecretsEditor.Variable -> editor.copy(
            state = editor.state.copy(name = "CHANGED_VARIABLE"),
        )
        is SecretsEditor.SshKey -> editor.copy(
            state = editor.state.copy(secretName = "changed-secret"),
        )
    }

    private fun SecretsEditor.withSession(session: Long): SecretsEditor = when (this) {
        SecretsEditor.None -> error("None has no session")
        is SecretsEditor.Secret -> copy(session = session, phase = EditorPhase.EDITING)
        is SecretsEditor.Variable -> copy(session = session, phase = EditorPhase.EDITING)
        is SecretsEditor.SshKey -> copy(session = session, phase = EditorPhase.EDITING)
    }

    private fun SecretsEditor.phase(): EditorPhase? = when (this) {
        SecretsEditor.None -> null
        is SecretsEditor.Secret -> phase
        is SecretsEditor.Variable -> phase
        is SecretsEditor.SshKey -> phase
    }

    private companion object {
        const val SESSION = 42L
    }
}
