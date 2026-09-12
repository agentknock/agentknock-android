package dev.agentknock.ui.secrets

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshKeyCodec
import dev.agentknock.storage.secret.SshKeyMetadata
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SecretDetailPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptySecretListKeepsSavedGlobalInstructionsVisibleAndEditable() {
        var edits = 0
        val instructions = "Ask before changing production resources."
        compose.setContent {
            AgentknockTheme(dynamicColor = false) {
                SecretList(
                    secrets = emptyList(),
                    clients = emptyList(),
                    pendingUploads = emptyList(),
                    selectedSecretId = null,
                    selectedUploadRequestId = null,
                    onSelect = {},
                    onSelectUpload = {},
                    onCreate = {},
                    generalInstructions = instructions,
                    aiReviewAccess = AiReviewAccess.INACTIVE,
                    onEditGeneralInstructions = { edits++ },
                    onOpenSettings = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithText(instructions).assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit instructions").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test
    fun sshCommentIsVisibleAndSecretEditingIsSeparateFromPublicKeyActions() {
        val actions = mutableListOf<String>()
        compose.setContent {
            AgentknockTheme(dynamicColor = false) {
                SecretDetail(
                    secret = sshSecret,
                    clients = emptyList(),
                    revealedValues = emptyMap(),
                    showBack = true,
                    aiReviewAccess = AiReviewAccess.INACTIVE,
                    onOpenPlan = {},
                    actions =
                        SecretDetailActions(
                            onBack = {},
                            onEditSecret = { actions += "edit secret" },
                            onDeleteSecret = {},
                            onAddVariable = {},
                            onReplaceSshKey = {},
                            onSaveSshComment = { actions += "save comment: $it" },
                            onCopyPublicKey = { actions += "copy public key" },
                            onEditVariable = {},
                            onReveal = {},
                            onReadValue = { null },
                            onCopy = {},
                            onSetApprovalMode = {},
                            onSetClientApprovalOverride = { _, _ -> },
                            onSaveInstructions = {},
                            onEndTemporaryAccess = {},
                        ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithText(keyComment).assertIsDisplayed()

        compose.onNodeWithText("Edit secret").performClick()
        compose.onNodeWithText("Copy public key").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("edit secret", "copy public key"), actions) }

        compose
            .onNodeWithContentDescription("Edit public key comment")
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Edit public key comment").assertIsDisplayed()
        compose.onNodeWithText("Comment (optional)").performTextReplacement("deploy@new-host")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Comment (optional)").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(
                listOf("edit secret", "copy public key", "save comment: deploy@new-host"),
                actions,
            )
        }

        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText(publicKey.line).performScrollTo().assertIsDisplayed()
    }

    private val keyComment = "deploy@build-host"
    private val publicKey =
        SshKeyCodec()
            .publicKey(
                SshKeyAlgorithm.ED25519,
                ByteArray(32) { 1 },
                keyComment,
            )
    private val sshSecret =
        SecretDetails(
            id = "test-ssh-secret",
            name = "deployment-key",
            description = "Deployment signing key.",
            type = SecretType.SSH,
            environmentVariables = emptyList(),
            sshKey =
                SshKeyMetadata(
                    algorithm = SshKeyAlgorithm.ED25519,
                    bits = 256,
                    publicKey = publicKey.line,
                    fingerprint = publicKey.fingerprint,
                    fingerprintHex = publicKey.fingerprintHex,
                    comment = keyComment,
                    privateKeyAvailable = true,
                ),
            approvalMode = SecretApprovalMode.ASK_ME,
            instructions = "",
            clientApprovalOverrides = emptyList(),
            temporaryAccessGrants = emptyList(),
            createdAt = 1_704_110_400_000L,
            updatedAt = 1_704_110_400_000L,
        )
}
