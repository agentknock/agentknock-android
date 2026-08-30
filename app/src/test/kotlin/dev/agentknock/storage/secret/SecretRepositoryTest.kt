package dev.agentknock.storage.secret

import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.FakeEncryptionKeyStore
import dev.agentknock.storage.crypto.FakeVaultKeyDao
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRepositoryTest {
    @Test
    fun `stores an SSH private key encrypted and returns only its public key`() = runTest {
        val fixture = Fixture()
        val key = fixture.repository.generateSshKey("test@example")
        val created = fixture.repository.createSshSecret(
            name = "production-ssh",
            description = "Production host access",
            privateKey = key,
        )
        check(created is CreateSecretResult.Created)

        val stored = fixture.dao.sshKeys.value.single()
        assertFalse(stored.ciphertext.contentEquals(key.privateKey))
        assertEquals(key.publicKey.toList(), stored.publicKey.toList())

        val details = checkNotNull(fixture.repository.observeSecret(created.id).first())
        assertEquals(SSH_SECRET_TYPE, details.type)
        assertEquals(key.publicKeyLine, details.sshKey?.publicKey)
        assertEquals(key.fingerprint, details.sshKey?.fingerprint)

        val requested = fixture.repository.requestedSecrets(listOf("production-ssh"))
        check(requested is RequestedSecretsResult.Available)
        assertEquals(
            SecretValues.Ssh("Production host access", key.publicKeyLine),
            requested.secrets.getValue("production-ssh"),
        )
        assertEquals(
            SecretMetadata(
                name = "production-ssh",
                description = "Production host access",
                type = SSH_SECRET_TYPE,
                sshPublicKey = key.publicKeyLine,
            ),
            fixture.repository.listSecretsForClient().single(),
        )
    }

    @Test
    fun `saving an SSH comment cannot restore stale key material`() = runTest {
        val fixture = Fixture()
        val key = fixture.repository.generateSshKey("first@example")
        val created = fixture.repository.createSshSecret("git-signing", "", key)
        check(created is CreateSecretResult.Created)
        val original = fixture.dao.sshKeys.value.single()
        val replacement = original.copy(
            publicKey = byteArrayOf(9, 8, 7),
            encryptionKeyId = "replacement-key",
            nonce = byteArrayOf(6, 5, 4),
            ciphertext = byteArrayOf(3, 2, 1),
            materialUpdatedAt = original.materialUpdatedAt + 100,
        )
        fixture.dao.beforeSshCommentUpdate = {
            fixture.dao.directlyReplaceSshKey(replacement)
        }

        assertEquals(
            SaveSshSecretResult.Saved(created.id),
            fixture.repository.saveSshComment(created.id, "new@example"),
        )

        val stored = fixture.dao.sshKeys.value.single()
        assertEquals(replacement.publicKey.toList(), stored.publicKey.toList())
        assertEquals(replacement.ciphertext.toList(), stored.ciphertext.toList())
        assertEquals(replacement.materialUpdatedAt, stored.materialUpdatedAt)
        assertEquals("new@example", stored.comment)
    }

    @Test
    fun `SSH uploads create and replace one stable secret identity`() = runTest {
        val fixture = Fixture()
        val firstKey = fixture.repository.generateSshKey("first@example")
        val create = SshSecretUpload(
            mode = SecretUploadMode.CREATE,
            name = "client-suggested-name",
            descriptionProvided = true,
            description = "Production host access",
            privateKey = firstKey,
        )
        check(fixture.repository.describeSshSecretUpload(create) is SshSecretUploadResult.Valid)
        val created = fixture.repository.applySshSecretUpload(create, "production-ssh")
        check(created is ApplySshSecretUploadResult.Applied)

        val before = fixture.dao.sshKeys.value.single()
        val secondKey = fixture.repository.generateSshKey("second@example")
        val replace = fixture.repository.applySshSecretUpload(
            SshSecretUpload(
                mode = SecretUploadMode.REPLACE,
                name = "production-ssh",
                descriptionProvided = false,
                description = null,
                privateKey = secondKey,
            ),
            approvedName = "ignored-for-existing-secret",
        )
        check(replace is ApplySshSecretUploadResult.Applied)

        assertEquals(created.secretId, replace.secretId)
        assertEquals(created.secretId, fixture.dao.secrets.value.single().id)
        assertFalse(before.publicKey.contentEquals(fixture.dao.sshKeys.value.single().publicKey))
        assertEquals(secondKey.publicKeyLine, fixture.repository.listSecretsForClient().single().sshPublicKey)
    }

    @Test
    fun `a request may combine environment secrets with one SSH key but not two`() = runTest {
        val fixture = Fixture()
        fixture.createSecret("environment")
        repeat(2) { index ->
            val key = fixture.repository.generateSshKey("key-$index@example")
            fixture.repository.createSshSecret("ssh-$index", "", key)
        }

        assertTrue(
            fixture.repository.requestedSecrets(listOf("environment", "ssh-0"))
                is RequestedSecretsResult.Available,
        )
        assertEquals(
            RequestedSecretsResult.MultipleSshKeys,
            fixture.repository.requestedSecrets(listOf("ssh-0", "ssh-1")),
        )
    }

    @Test
    fun `stores one encrypted row for each secret environment variable`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws-read-only")

        fixture.createVariable(secretId, "AWS_ACCESS_KEY_ID", "AKIAEXAMPLE", sensitive = true)
        fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", sensitive = false)

        val rows = fixture.dao.variables.value
        assertEquals(2, rows.size)
        assertEquals(
            setOf(VaultKeyPurpose.SECRET_VALUES.storedName),
            rows.map { variable ->
                fixture.encryptionMetadata.getKey(variable.encryptionKeyId)?.purpose
            }.toSet(),
        )
        assertTrue(rows.all { it.secretId == secretId })
        assertFalse(rows[0].ciphertext.contentEquals("AKIAEXAMPLE".encodeToByteArray()))
        assertFalse(rows[1].ciphertext.contentEquals("eu-west-1".encodeToByteArray()))

        val secret = fixture.repository.observeSecret(secretId).first()
        checkNotNull(secret)
        assertEquals(2, secret.environmentVariables.size)
        assertTrue(secret.environmentVariables.all { it.valueAvailable })
    }

    @Test
    fun `saving environment notes cannot restore stale encrypted material`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("github")
        val variableId = fixture.createVariable(secretId, "TOKEN", "first", true)
        val original = fixture.dao.variables.value.single()
        val replacement = original.copy(
            encryptionKeyId = "replacement-key",
            nonce = byteArrayOf(9, 8, 7),
            ciphertext = byteArrayOf(6, 5, 4),
            valueUpdatedAt = original.valueUpdatedAt + 100,
        )
        val revisedSecret = fixture.dao.secrets.value.single().copy(
            revision = 9,
            updatedAt = 9_999,
        )
        fixture.dao.beforeEnvironmentNotesUpdate = {
            fixture.dao.directlyReplaceVariable(replacement)
            fixture.dao.secrets.value = listOf(revisedSecret)
        }

        assertEquals(
            SaveEnvironmentVariableResult.SAVED,
            fixture.repository.saveEnvironmentVariable(
                id = variableId,
                name = "TOKEN",
                sensitive = true,
                notes = "Rotated out of band",
                replacementValue = null,
            ),
        )

        val stored = fixture.dao.variables.value.single()
        assertEquals(replacement.encryptionKeyId, stored.encryptionKeyId)
        assertEquals(replacement.nonce.toList(), stored.nonce.toList())
        assertEquals(replacement.ciphertext.toList(), stored.ciphertext.toList())
        assertEquals(replacement.valueUpdatedAt, stored.valueUpdatedAt)
        assertEquals("Rotated out of band", stored.notes)
        assertEquals(9L, fixture.dao.secrets.value.single().revision)
        assertEquals(9_999L, fixture.dao.secrets.value.single().updatedAt)
    }

    @Test
    fun `binds ciphertext to its environment variable metadata`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws-read-only")
        val variableId = fixture.createVariable(
            secretId,
            "AWS_SECRET_ACCESS_KEY",
            "secret-value",
            sensitive = true,
        )

        fixture.dao.directlyReplaceVariable(
            fixture.dao.variables.value.single().copy(name = "AWS_ACCESS_KEY_ID"),
        )

        assertEquals(
            EnvironmentVariableValue.Corrupted,
            fixture.repository.readEnvironmentVariableValue(variableId),
        )
    }

    @Test
    fun `legitimate metadata changes re-encrypt without changing the value timestamp`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws-read-only")
        val variableId = fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", false)
        val before = fixture.dao.variables.value.single()

        val result = fixture.repository.saveEnvironmentVariable(
            id = variableId,
            name = "AWS_DEFAULT_REGION",
            sensitive = true,
            notes = "Used by the AWS CLI",
            replacementValue = null,
        )

        assertEquals(SaveEnvironmentVariableResult.SAVED, result)
        val after = fixture.dao.variables.value.single()
        assertEquals(before.valueUpdatedAt, after.valueUpdatedAt)
        assertNotEquals(before.updatedAt, after.updatedAt)
        assertFalse(before.ciphertext.contentEquals(after.ciphertext))
        val value = fixture.repository.readEnvironmentVariableValue(variableId)
        assertTrue(value is EnvironmentVariableValue.Available)
        assertEquals("eu-west-1", (value as EnvironmentVariableValue.Available).value)
    }

    @Test
    fun `renaming a secret does not re-encrypt its values`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws-read-only")
        fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", false)
        val before = fixture.dao.variables.value.single()

        assertEquals(
            SaveSecretResult.SAVED,
            fixture.repository.saveSecret(secretId, "aws-reader", "Renamed secret"),
        )

        val after = fixture.dao.variables.value.single()
        assertTrue(before.nonce.contentEquals(after.nonce))
        assertTrue(before.ciphertext.contentEquals(after.ciphertext))
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.valueUpdatedAt, after.valueUpdatedAt)
    }

    @Test
    fun `restored rows stay in their secrets until their values are re-entered`() = runTest {
        val original = Fixture(keyId = "original-key")
        val secretId = original.createSecret("cloudflare-read-only")
        val variableId = original.createVariable(secretId, "CF_TOKEN", "old-token", true)
        val originalRow = original.dao.variables.value.single()

        val replacementKeyStore = FakeEncryptionKeyStore()
        val replacementIds = ArrayDeque(listOf("replacement-secret-key", "replacement-device-key"))
        val replacementManager = VaultKeyManager(
            dao = original.encryptionMetadata,
            keyStore = replacementKeyStore,
            newKeyId = { replacementIds.removeFirst() },
            currentTimeMillis = { 500L },
        )
        val restoredRepository = SecretRepository(
            dao = original.dao,
            keyManager = replacementManager,
            encryption = AesGcmEncryption(replacementKeyStore),
            newId = { error("no new records expected") },
            currentTimeMillis = { 600L },
        )

        replacementManager.initialize()
        val restoredSecret = restoredRepository.observeSecret(secretId).first()
        checkNotNull(restoredSecret)
        assertEquals(1, restoredSecret.environmentVariables.size)
        assertFalse(restoredSecret.environmentVariables.single().valueAvailable)
        assertEquals(
            EnvironmentVariableValue.Unavailable,
            restoredRepository.readEnvironmentVariableValue(variableId),
        )

        assertEquals(
            SaveEnvironmentVariableResult.SAVED,
            restoredRepository.saveEnvironmentVariable(
                id = variableId,
                name = "CF_TOKEN",
                sensitive = true,
                notes = "",
                replacementValue = "new-token",
            ),
        )

        val replacementRow = original.dao.variables.value.single()
        assertEquals(originalRow.id, replacementRow.id)
        assertEquals("replacement-secret-key", replacementRow.encryptionKeyId)
        val replacementValue = restoredRepository.readEnvironmentVariableValue(variableId)
        assertTrue(replacementValue is EnvironmentVariableValue.Available)
        assertEquals("new-token", (replacementValue as EnvironmentVariableValue.Available).value)
    }

    @Test
    fun `requested secrets merge equal bindings without exposing metadata values`() = runTest {
        val fixture = Fixture()
        val first = fixture.createSecret("first")
        val second = fixture.createSecret("second")
        fixture.createVariable(first, "SHARED_TOKEN", "same-value", true)
        fixture.createVariable(first, "FIRST_REGION", "eu-west-1", false)
        fixture.createVariable(second, "SHARED_TOKEN", "same-value", true)

        val description = fixture.repository.describeRequestedSecrets(listOf("first", "second"))
        assertEquals(emptyList<String>(), description.missingSecrets)
        assertTrue(description.containsSensitiveMaterial)
        assertEquals(
            listOf("FIRST_REGION", "SHARED_TOKEN"),
            description.secrets.first().environmentVariableNames,
        )
        assertEquals(listOf("first", "second"), description.reviewMetadata.map { it.name })
        assertEquals(
            listOf(
                "FIRST_REGION" to false,
                "SHARED_TOKEN" to true,
            ),
            description.reviewMetadata.first().environmentVariables.map { variable ->
                variable.name to variable.sensitive
            },
        )

        val result = fixture.repository.requestedSecrets(listOf("first", "second"))
        check(result is RequestedSecretsResult.Available)
        assertEquals(
            mapOf(
                "first" to SecretValues.Environment(
                    "",
                    mapOf("FIRST_REGION" to "eu-west-1", "SHARED_TOKEN" to "same-value"),
                ),
                "second" to SecretValues.Environment(
                    "",
                    mapOf("SHARED_TOKEN" to "same-value"),
                ),
            ),
            result.secrets,
        )
        assertEquals(
            listOf(
                SecretMetadata(
                    name = "first",
                    description = "",
                    environmentVariableNames = listOf("FIRST_REGION", "SHARED_TOKEN"),
                ),
                SecretMetadata(
                    name = "second",
                    description = "",
                    environmentVariableNames = listOf("SHARED_TOKEN"),
                ),
            ),
            fixture.repository.listSecretsForClient(),
        )
    }

    @Test
    fun `requested secrets reject conflicting bindings atomically`() = runTest {
        val fixture = Fixture()
        val first = fixture.createSecret("first")
        val second = fixture.createSecret("second")
        fixture.createVariable(first, "TOKEN", "first-value", true)
        fixture.createVariable(second, "TOKEN", "second-value", true)

        assertEquals(
            RequestedSecretsResult.ConflictingVariable("TOKEN"),
            fixture.repository.requestedSecrets(listOf("first", "second")),
        )
        assertEquals(
            RequestedSecretsResult.MissingSecrets(listOf("missing")),
            fixture.repository.requestedSecrets(listOf("missing")),
        )
    }

    @Test
    fun `environment selection limits values metadata and sensitivity`() = runTest {
        val fixture = Fixture()
        val secret = fixture.createSecret("github")
        fixture.createVariable(secret, "GH_HOST", "github.com", false)
        fixture.createVariable(secret, "GH_TOKEN", "secret", true)
        fixture.createVariable(secret, "UNRELATED", "hidden", true)

        val onlyHost = mapOf(
            "github" to EnvironmentVariableSelection(only = setOf("GH_HOST")),
        )
        val description = fixture.repository.describeRequestedSecrets(
            listOf("github"),
            onlyHost,
        )
        assertEquals(listOf("GH_HOST"), description.secrets.single().environmentVariableNames)
        assertEquals(
            listOf("GH_HOST"),
            description.reviewMetadata.single().environmentVariables.map { it.name },
        )
        assertFalse(description.containsSensitiveMaterial)

        val requested = fixture.repository.requestedSecrets(listOf("github"), onlyHost)
        check(requested is RequestedSecretsResult.Available)
        assertEquals(
            mapOf("GH_HOST" to "github.com"),
            (requested.secrets.getValue("github") as SecretValues.Environment).environment,
        )

        val omitted = fixture.repository.requestedSecrets(
            listOf("github"),
            mapOf("github" to EnvironmentVariableSelection(omit = setOf("GH_TOKEN", "ABSENT"))),
        )
        check(omitted is RequestedSecretsResult.Available)
        assertEquals(
            mapOf("GH_HOST" to "github.com", "UNRELATED" to "hidden"),
            (omitted.secrets.getValue("github") as SecretValues.Environment).environment,
        )
    }

    @Test
    fun `environment selection rejects missing exact variables and SSH options`() = runTest {
        val fixture = Fixture()
        val environment = fixture.createSecret("environment")
        fixture.createVariable(environment, "TOKEN", "secret", true)
        val key = fixture.repository.generateSshKey("git@example")
        fixture.repository.createSshSecret("git-signing", "", key)

        assertEquals(
            RequestedSecretsResult.MissingEnvironmentVariables(
                secretName = "environment",
                names = listOf("MISSING"),
            ),
            fixture.repository.requestedSecrets(
                listOf("environment"),
                mapOf(
                    "environment" to EnvironmentVariableSelection(
                        only = setOf("TOKEN", "MISSING"),
                    ),
                ),
            ),
        )
        assertEquals(
            RequestedSecretsResult.EnvironmentOptionsForSshSecret("git-signing"),
            fixture.repository.requestedSecrets(
                listOf("git-signing"),
                mapOf("git-signing" to EnvironmentVariableSelection(omit = setOf("TOKEN"))),
            ),
        )
    }

    @Test
    fun `only sensitive environment values require approval`() = runTest {
        val fixture = Fixture()
        val public = fixture.createSecret("public-context")
        fixture.createVariable(public, "AWS_REGION", "eu-north-1", false)
        val sensitive = fixture.createSecret("credentials")
        fixture.createVariable(sensitive, "AWS_TOKEN", "secret", true)
        val key = fixture.repository.generateSshKey("git@example")
        fixture.repository.createSshSecret("git-signing", "", key)

        assertFalse(
            fixture.repository.describeRequestedSecrets(listOf("public-context"))
                .containsSensitiveMaterial,
        )
        assertFalse(
            fixture.repository.describeRequestedSecrets(listOf("git-signing"))
                .containsSensitiveMaterial,
        )
        assertTrue(
            fixture.repository.describeRequestedSecrets(listOf("public-context", "credentials"))
                .containsSensitiveMaterial,
        )
    }

    @Test
    fun `SSH signing requires the same public key approved for the invocation`() = runTest {
        val fixture = Fixture()
        val first = fixture.repository.generateSshKey("first@example")
        val created = fixture.repository.createSshSecret("git-signing", "", first)
        check(created is CreateSecretResult.Created)

        val signed = fixture.repository.signGitMessage(
            secretName = "git-signing",
            expectedPublicKey = first.publicKeyLine,
            message = "commit object".encodeToByteArray(),
        )
        assertTrue(signed is GitSignatureResult.Signed)

        val second = fixture.repository.generateSshKey("second@example")
        fixture.repository.replaceSshKey(created.id, second)
        assertEquals(
            GitSignatureResult.KeyChanged,
            fixture.repository.signGitMessage(
                secretName = "git-signing",
                expectedPublicKey = first.publicKeyLine,
                message = "commit object".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `uploaded secrets make new environment variables sensitive by default`() = runTest {
        val fixture = Fixture()

        val result = fixture.repository.applyEnvironmentSecretUpload(
            EnvironmentSecretUpload(
                mode = SecretUploadMode.CREATE,
                name = "cloudflare-read-only",
                descriptionProvided = true,
                description = "Cloudflare production account",
                variables = mapOf("CF_ACCOUNT_ID" to "account", "CF_TOKEN" to "token"),
            ),
            approvedName = "cloudflare-read-only",
        )

        check(result is ApplyEnvironmentSecretUploadResult.Applied)
        val secret = fixture.repository.observeSecret(result.secretId).first()
        checkNotNull(secret)
        assertEquals("Cloudflare production account", secret.description)
        assertEquals(SecretApprovalMode.TEMPORARY, secret.approvalMode)
        assertTrue(secret.environmentVariables.all(EnvironmentVariableMetadata::sensitive))
        assertEquals(
            EnvironmentVariableValue.Available("token"),
            fixture.repository.readEnvironmentVariableValue(
                secret.environmentVariables.single { it.name == "CF_TOKEN" }.id,
            ),
        )
    }

    @Test
    fun `replace uploads preserve environment variable sensitivity and notes`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws-read-only")
        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.ASK_AI)
        fixture.repository.saveInstructions(secretId, "Permit read-only AWS operations.")
        val region = fixture.repository.createEnvironmentVariable(
            secretId = secretId,
            name = "AWS_REGION",
            value = "eu-west-1",
            sensitive = false,
            notes = "Safe to display",
        )
        check(region is CreateEnvironmentVariableResult.Created)
        fixture.createVariable(secretId, "OLD_VARIABLE", "old", true)

        val result = fixture.repository.applyEnvironmentSecretUpload(
            EnvironmentSecretUpload(
                mode = SecretUploadMode.REPLACE,
                name = "aws-read-only",
                descriptionProvided = false,
                description = null,
                variables = mapOf(
                    "AWS_REGION" to "eu-north-1",
                    "AWS_ACCESS_KEY_ID" to "new-key",
                ),
            ),
            approvedName = "ignored-for-existing-secret",
        )

        assertTrue(result is ApplyEnvironmentSecretUploadResult.Applied)
        val secret = fixture.repository.observeSecret(secretId).first()
        checkNotNull(secret)
        assertEquals(listOf("AWS_ACCESS_KEY_ID", "AWS_REGION"), secret.environmentVariables.map { it.name })
        val updatedRegion = secret.environmentVariables.single { it.name == "AWS_REGION" }
        assertFalse(updatedRegion.sensitive)
        assertEquals("Safe to display", updatedRegion.notes)
        assertTrue(secret.environmentVariables.single { it.name == "AWS_ACCESS_KEY_ID" }.sensitive)
        assertEquals(SecretApprovalMode.ASK_AI, secret.approvalMode)
        assertEquals("Permit read-only AWS operations.", secret.instructions)
    }

    @Test
    fun `client approval override changes the effective mode without replacing the default`() =
        runTest {
            val fixture = Fixture()
            val secretId = fixture.createSecret("github")

            assertEquals(
                SaveSecretResult.SAVED,
                fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.ASK_AI),
            )
            assertEquals(
                SaveSecretResult.SAVED,
                fixture.repository.saveInstructions(
                    secretId,
                    "Allow issue triage but never publish a release.",
                ),
            )
            assertEquals(
                SaveSecretResult.SAVED,
                fixture.repository.setClientApprovalOverride(
                    secretId,
                    "workstation",
                    SecretApprovalMode.APPROVE,
                ),
            )

            val overridden = fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
            assertEquals(SecretApprovalMode.APPROVE, overridden.mode)
            assertEquals(SecretApprovalMode.ASK_AI, overridden.defaultMode)
            assertTrue(overridden.overridden)
            assertEquals(
                "Allow issue triage but never publish a release.",
                overridden.instructions,
            )

            fixture.repository.setClientApprovalOverride(secretId, "workstation", null)
            val inherited = fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
            assertEquals(SecretApprovalMode.ASK_AI, inherited.mode)
            assertFalse(inherited.overridden)
        }

    @Test
    fun `temporary access is scoped to client secret and operation`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("github")
        val otherSecretId = fixture.createSecret("production")
        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.TEMPORARY)

        fixture.repository.allowTemporaryAccess(
            policies = fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ),
            clientId = "workstation",
            operation = TemporaryAccessOperation.INVOCATION,
            expiresAt = 10_000,
        )

        assertEquals(
            10_000L,
            fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ).single().temporaryAccessExpiresAt,
        )
        assertNull(
            fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "workstation",
                TemporaryAccessOperation.GIT_SIGN,
            ).single().temporaryAccessExpiresAt,
        )
        assertNull(
            fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "other-client",
                TemporaryAccessOperation.INVOCATION,
            ).single().temporaryAccessExpiresAt,
        )
        assertNull(
            fixture.repository.approvalPoliciesForNames(
                listOf("production"),
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ).single { it.secretId == otherSecretId }.temporaryAccessExpiresAt,
        )
    }

    @Test
    fun `temporary access cannot be extended and can be ended explicitly`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("github")
        val initialPolicy = fixture.repository.approvalPoliciesForNames(
            listOf("github"),
            "workstation",
            TemporaryAccessOperation.INVOCATION,
        )
        assertTrue(
            fixture.repository.allowTemporaryAccess(
                policies = initialPolicy,
                clientId = "workstation",
                operation = TemporaryAccessOperation.INVOCATION,
                expiresAt = 10_000,
            ),
        )

        val activePolicy = fixture.repository.approvalPoliciesForNames(
            listOf("github"),
            "workstation",
            TemporaryAccessOperation.INVOCATION,
        )
        assertFalse(
            fixture.repository.allowTemporaryAccess(
                policies = activePolicy,
                clientId = "workstation",
                operation = TemporaryAccessOperation.INVOCATION,
                expiresAt = 20_000,
            ),
        )
        assertEquals(10_000L, fixture.dao.temporaryAccessGrants.value.single().expiresAt)

        assertTrue(
            fixture.repository.endTemporaryAccess(
                secretId,
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ),
        )
        assertTrue(fixture.dao.temporaryAccessGrants.value.isEmpty())
        assertFalse(
            fixture.repository.endTemporaryAccess(
                secretId,
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ),
        )
    }

    @Test
    fun `new secrets use four-hour approval by default`() = runTest {
        val fixture = Fixture()

        val secretId = fixture.createSecret("github")

        assertEquals(
            SecretApprovalMode.TEMPORARY,
            fixture.repository.observeSecret(secretId).first()?.approvalMode,
        )
    }

    @Test
    fun `temporary access is not inserted after secret material changes`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("github")
        val variableId = fixture.createVariable(secretId, "GITHUB_TOKEN", "old-token", true)
        val stalePolicy = fixture.repository.approvalPoliciesForNames(
            listOf("github"),
            "workstation",
            TemporaryAccessOperation.INVOCATION,
        )

        fixture.repository.saveEnvironmentVariable(
            id = variableId,
            name = "GITHUB_TOKEN",
            sensitive = true,
            notes = "",
            replacementValue = "new-token",
        )
        val inserted = fixture.repository.allowTemporaryAccess(
            policies = stalePolicy,
            clientId = "workstation",
            operation = TemporaryAccessOperation.INVOCATION,
            expiresAt = 10_000,
        )

        assertFalse(inserted)
        assertTrue(fixture.dao.temporaryAccessGrants.value.isEmpty())
    }

    @Test
    fun `renaming a secret ends its temporary access`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("staging")
        fixture.repository.allowTemporaryAccess(
            policies = fixture.repository.approvalPoliciesForNames(
                listOf("staging"),
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ),
            clientId = "workstation",
            operation = TemporaryAccessOperation.INVOCATION,
            expiresAt = 10_000,
        )

        fixture.repository.saveSecret(secretId, "production", "")

        assertTrue(fixture.dao.temporaryAccessGrants.value.isEmpty())
    }

    @Test
    fun `approval and secret changes end temporary access`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("github")
        val variableId = fixture.createVariable(secretId, "GITHUB_TOKEN", "token", true)
        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.TEMPORARY)
        fixture.repository.allowTemporaryAccess(
            policies = fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ),
            clientId = "workstation",
            operation = TemporaryAccessOperation.INVOCATION,
            expiresAt = 10_000,
        )

        fixture.repository.saveEnvironmentVariable(
            id = variableId,
            name = "GITHUB_TOKEN",
            sensitive = true,
            notes = "",
            replacementValue = "new-token",
        )
        assertNull(
            fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ).single().temporaryAccessExpiresAt,
        )

        fixture.repository.allowTemporaryAccess(
            policies = fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ),
            clientId = "workstation",
            operation = TemporaryAccessOperation.INVOCATION,
            expiresAt = 10_000,
        )
        fixture.repository.setClientApprovalOverride(
            secretId,
            "workstation",
            SecretApprovalMode.ASK_ME,
        )
        assertNull(
            fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            ).single().temporaryAccessExpiresAt,
        )
    }

    @Test
    fun `update uploads leave omitted environment variables untouched`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws-read-only")
        fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", false)
        val tokenId = fixture.createVariable(secretId, "AWS_TOKEN", "old-token", true)

        val upload = EnvironmentSecretUpload(
            mode = SecretUploadMode.UPDATE,
            name = "aws-read-only",
            descriptionProvided = false,
            description = null,
            variables = mapOf("AWS_TOKEN" to "new-token"),
        )
        val description = fixture.repository.describeEnvironmentSecretUpload(upload)
        check(description is EnvironmentSecretUploadResult.Valid)
        assertEquals(listOf("AWS_REGION"), description.summary.unchangedVariables)

        val result = fixture.repository.applyEnvironmentSecretUpload(
            upload,
            approvedName = "ignored-for-existing-secret",
        )

        assertTrue(result is ApplyEnvironmentSecretUploadResult.Applied)
        val secret = fixture.repository.observeSecret(secretId).first()
        checkNotNull(secret)
        assertEquals(listOf("AWS_REGION", "AWS_TOKEN"), secret.environmentVariables.map { it.name })
        assertEquals(
            EnvironmentVariableValue.Available("new-token"),
            fixture.repository.readEnvironmentVariableValue(tokenId),
        )
    }

    private class Fixture(keyId: String = "storage-key") {
        val encryptionMetadata = FakeVaultKeyDao()
        val keyStore = FakeEncryptionKeyStore()
        val dao = FakeSecretDao()
        private var id = 0
        private var time = 100L
        private val keyIds = ArrayDeque(listOf("$keyId-secret", "$keyId-device"))
        private val keyManager = VaultKeyManager(
            dao = encryptionMetadata,
            keyStore = keyStore,
            newKeyId = { keyIds.removeFirst() },
            currentTimeMillis = { nextTime() },
        )
        val repository = SecretRepository(
            dao = dao,
            keyManager = keyManager,
            encryption = AesGcmEncryption(keyStore),
            newId = { "id-${++id}" },
            currentTimeMillis = { nextTime() },
        )

        suspend fun createSecret(name: String): String {
            val result = repository.createEnvironmentSecret(name, "")
            check(result is CreateSecretResult.Created)
            return result.id
        }

        suspend fun createVariable(
            secretId: String,
            name: String,
            value: String,
            sensitive: Boolean,
        ): String {
            val result = repository.createEnvironmentVariable(
                secretId = secretId,
                name = name,
                value = value,
                sensitive = sensitive,
                notes = "",
            )
            check(result is CreateEnvironmentVariableResult.Created)
            return result.id
        }

        private fun nextTime(): Long = ++time
    }
}

private class FakeSecretDao : SecretDao {
    val secrets = MutableStateFlow<List<SecretEntity>>(emptyList())
    val variables = MutableStateFlow<List<EnvironmentVariableEntity>>(emptyList())
    val sshKeys = MutableStateFlow<List<SshKeyEntity>>(emptyList())
    val approvalOverrides =
        MutableStateFlow<List<SecretClientApprovalOverrideEntity>>(emptyList())
    val temporaryAccessGrants = MutableStateFlow<List<TemporaryAccessGrantEntity>>(emptyList())
    var temporaryAccessClientAvailable = true
    var beforeEnvironmentNotesUpdate: (() -> Unit)? = null
    var beforeSshCommentUpdate: (() -> Unit)? = null

    override fun observeSecrets(): Flow<List<SecretSummaryRow>> = combine(
        secrets,
        variables,
    ) { currentSecrets, currentVariables ->
        currentSecrets
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SecretEntity::name))
            .map { secret ->
                SecretSummaryRow(
                    id = secret.id,
                    name = secret.name,
                    description = secret.description,
                    type = secret.type,
                    createdAt = secret.createdAt,
                    updatedAt = secret.updatedAt,
                    environmentVariableCount = currentVariables.count {
                        it.secretId == secret.id
                    },
                )
            }
    }

    override fun observeSecret(id: String): Flow<SecretEntity?> =
        secrets.map { all -> all.find { it.id == id } }

    override fun observeClientApprovalOverrides(
        secretId: String,
    ): Flow<List<SecretClientApprovalOverrideEntity>> = approvalOverrides.map { all ->
        all.filter { it.secretId == secretId }.sortedBy { it.clientId }
    }

    override fun observeTemporaryAccessGrants(): Flow<List<TemporaryAccessGrantRow>> =
        combine(temporaryAccessGrants, secrets) { grants, currentSecrets ->
            val names = currentSecrets.associate { it.id to it.name }
            grants.mapNotNull { grant ->
                names[grant.secretId]?.let { secretName ->
                    TemporaryAccessGrantRow(
                        secretId = grant.secretId,
                        secretName = secretName,
                        clientId = grant.clientId,
                        operation = grant.operation,
                        expiresAt = grant.expiresAt,
                    )
                }
            }
        }

    override fun observeSshKey(secretId: String): Flow<SshKeyMetadataRow?> =
        sshKeys.map { all ->
            all.find { it.secretId == secretId }?.let { key ->
                SshKeyMetadataRow(
                    secretId = key.secretId,
                    algorithm = key.algorithm,
                    publicKey = key.publicKey,
                    comment = key.comment,
                    encryptionKeyId = key.encryptionKeyId,
                    materialUpdatedAt = key.materialUpdatedAt,
                )
            }
        }

    override fun observeEnvironmentVariables(
        secretId: String,
    ): Flow<List<EnvironmentVariableMetadataRow>> = variables.map { all ->
        all.filter { it.secretId == secretId }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EnvironmentVariableEntity::name))
            .map { variable ->
                EnvironmentVariableMetadataRow(
                    id = variable.id,
                    secretId = variable.secretId,
                    name = variable.name,
                    sensitive = variable.sensitive,
                    notes = variable.notes,
                    encryptionKeyId = variable.encryptionKeyId,
                    createdAt = variable.createdAt,
                    updatedAt = variable.updatedAt,
                    valueUpdatedAt = variable.valueUpdatedAt,
                )
            }
    }

    override suspend fun getSecret(id: String): SecretEntity? = secrets.value.find { it.id == id }

    override suspend fun getEnvironmentVariable(id: String): EnvironmentVariableEntity? =
        variables.value.find { it.id == id }

    override suspend fun getSshKey(secretId: String): SshKeyEntity? =
        sshKeys.value.find { it.secretId == secretId }

    override suspend fun getSecrets(): List<SecretEntity> = secrets.value
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SecretEntity::name))

    override suspend fun getEnvironmentVariables(): List<EnvironmentVariableEntity> =
        variables.value.sortedWith(
            compareBy<EnvironmentVariableEntity> { it.secretId }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

    override suspend fun getSecretsByName(names: List<String>): List<SecretEntity> =
        secrets.value.filter { it.name in names }

    override suspend fun getClientApprovalOverrides(
        clientId: String,
        secretIds: List<String>,
    ): List<SecretClientApprovalOverrideEntity> = approvalOverrides.value.filter {
        it.clientId == clientId && it.secretId in secretIds
    }

    override suspend fun getActiveTemporaryAccessGrants(
        clientId: String,
        secretIds: List<String>,
        operation: String,
        now: Long,
    ): List<TemporaryAccessGrantEntity> = temporaryAccessGrants.value.filter {
        it.clientId == clientId && it.secretId in secretIds &&
            it.operation == operation && it.expiresAt > now
    }

    override suspend fun clientCanReceiveTemporaryAccess(clientId: String): Boolean =
        temporaryAccessClientAvailable

    override suspend fun getEnvironmentVariablesForSecrets(
        secretIds: List<String>,
    ): List<EnvironmentVariableEntity> = variables.value.filter { it.secretId in secretIds }

    override suspend fun getSshKeysForSecrets(secretIds: List<String>): List<SshKeyEntity> =
        sshKeys.value.filter { it.secretId in secretIds }

    override suspend fun secretNameInUse(name: String, excludingId: String): Boolean =
        secrets.value.any { it.name == name && it.id != excludingId }

    override suspend fun environmentVariableNameInUse(
        secretId: String,
        name: String,
        excludingId: String,
    ): Boolean = variables.value.any {
        it.secretId == secretId && it.name == name && it.id != excludingId
    }

    override suspend fun insertSecret(secret: SecretEntity) {
        check(secrets.value.none { it.id == secret.id || it.name == secret.name })
        secrets.value += secret
    }

    override suspend fun updateSecret(secret: SecretEntity): Int {
        if (secrets.value.none { it.id == secret.id }) return 0
        secrets.value = secrets.value.map { if (it.id == secret.id) secret else it }
        return 1
    }

    override suspend fun updateSecretApprovalMode(
        secretId: String,
        approvalMode: String,
        updatedAt: Long,
    ): Int {
        if (secrets.value.none { it.id == secretId }) return 0
        secrets.value = secrets.value.map { secret ->
            if (secret.id == secretId) {
                secret.copy(
                    approvalMode = approvalMode,
                    updatedAt = updatedAt,
                    revision = secret.revision + 1,
                )
            } else {
                secret
            }
        }
        return 1
    }

    override suspend fun updateSecretInstructions(
        secretId: String,
        instructions: String,
        updatedAt: Long,
    ): Int {
        if (secrets.value.none { it.id == secretId }) return 0
        secrets.value = secrets.value.map { secret ->
            if (secret.id == secretId) {
                secret.copy(
                    instructions = instructions,
                    updatedAt = updatedAt,
                    revision = secret.revision + 1,
                )
            } else {
                secret
            }
        }
        return 1
    }

    override suspend fun upsertClientApprovalOverride(
        override: SecretClientApprovalOverrideEntity,
    ) {
        approvalOverrides.value = approvalOverrides.value.filterNot {
            it.secretId == override.secretId && it.clientId == override.clientId
        } + override
    }

    override suspend fun upsertTemporaryAccessGrants(grants: List<TemporaryAccessGrantEntity>) {
        val keys = grants.map { Triple(it.secretId, it.clientId, it.operation) }.toSet()
        temporaryAccessGrants.value = temporaryAccessGrants.value.filterNot {
            Triple(it.secretId, it.clientId, it.operation) in keys
        } + grants
    }

    override suspend fun deleteClientApprovalOverride(secretId: String, clientId: String): Int {
        val before = approvalOverrides.value.size
        approvalOverrides.value = approvalOverrides.value.filterNot {
            it.secretId == secretId && it.clientId == clientId
        }
        return before - approvalOverrides.value.size
    }

    override suspend fun deleteTemporaryAccessGrant(
        secretId: String,
        clientId: String,
        operation: String,
    ): Int {
        val before = temporaryAccessGrants.value.size
        temporaryAccessGrants.value = temporaryAccessGrants.value.filterNot {
            it.secretId == secretId && it.clientId == clientId && it.operation == operation
        }
        return before - temporaryAccessGrants.value.size
    }

    override suspend fun deleteTemporaryAccessGrantsForSecret(secretId: String): Int {
        val before = temporaryAccessGrants.value.size
        temporaryAccessGrants.value = temporaryAccessGrants.value.filterNot {
            it.secretId == secretId
        }
        return before - temporaryAccessGrants.value.size
    }

    override suspend fun deleteTemporaryAccessGrantsForSecretClient(
        secretId: String,
        clientId: String,
    ): Int {
        val before = temporaryAccessGrants.value.size
        temporaryAccessGrants.value = temporaryAccessGrants.value.filterNot {
            it.secretId == secretId && it.clientId == clientId
        }
        return before - temporaryAccessGrants.value.size
    }

    override suspend fun deleteExpiredTemporaryAccessGrants(now: Long): Int {
        val before = temporaryAccessGrants.value.size
        temporaryAccessGrants.value = temporaryAccessGrants.value.filter { it.expiresAt > now }
        return before - temporaryAccessGrants.value.size
    }

    override suspend fun deleteAllTemporaryAccessGrants(): Int {
        val before = temporaryAccessGrants.value.size
        temporaryAccessGrants.value = emptyList()
        return before
    }

    override suspend fun deleteSecret(secret: SecretEntity) {
        secrets.value = secrets.value.filterNot { it.id == secret.id }
        variables.value = variables.value.filterNot { it.secretId == secret.id }
        sshKeys.value = sshKeys.value.filterNot { it.secretId == secret.id }
        approvalOverrides.value = approvalOverrides.value.filterNot { it.secretId == secret.id }
        temporaryAccessGrants.value = temporaryAccessGrants.value.filterNot {
            it.secretId == secret.id
        }
    }

    override suspend fun insertSshKeyRow(key: SshKeyEntity) {
        check(sshKeys.value.none { it.secretId == key.secretId })
        sshKeys.value += key
    }

    override suspend fun updateSshKeyRow(key: SshKeyEntity): Int {
        if (sshKeys.value.none { it.secretId == key.secretId }) return 0
        sshKeys.value = sshKeys.value.map { if (it.secretId == key.secretId) key else it }
        return 1
    }

    override suspend fun updateSshKeyCommentRow(secretId: String, comment: String): Int {
        beforeSshCommentUpdate?.also {
            beforeSshCommentUpdate = null
            it()
        }
        if (sshKeys.value.none { it.secretId == secretId }) return 0
        sshKeys.value = sshKeys.value.map { key ->
            if (key.secretId == secretId) key.copy(comment = comment) else key
        }
        return 1
    }

    override suspend fun insertEnvironmentVariableRow(variable: EnvironmentVariableEntity) {
        check(variables.value.none { it.id == variable.id })
        variables.value += variable
    }

    override suspend fun updateEnvironmentVariableRow(variable: EnvironmentVariableEntity): Int {
        if (variables.value.none { it.id == variable.id }) return 0
        directlyReplaceVariable(variable)
        return 1
    }

    override suspend fun updateEnvironmentVariableNotesRow(
        variableId: String,
        secretId: String,
        notes: String,
        updatedAt: Long,
    ): Int {
        beforeEnvironmentNotesUpdate?.also {
            beforeEnvironmentNotesUpdate = null
            it()
        }
        if (variables.value.none { it.id == variableId && it.secretId == secretId }) return 0
        variables.value = variables.value.map { variable ->
            if (variable.id == variableId && variable.secretId == secretId) {
                variable.copy(notes = notes, updatedAt = updatedAt)
            } else {
                variable
            }
        }
        return 1
    }

    override suspend fun deleteEnvironmentVariableRow(variable: EnvironmentVariableEntity) {
        variables.value = variables.value.filterNot { it.id == variable.id }
    }

    override suspend fun touchSecret(secretId: String, updatedAt: Long) {
        secrets.value = secrets.value.map {
            if (it.id == secretId) it.copy(updatedAt = maxOf(it.updatedAt, updatedAt)) else it
        }
    }

    override suspend fun reviseSecret(secretId: String, updatedAt: Long) {
        secrets.value = secrets.value.map {
            if (it.id == secretId) {
                it.copy(updatedAt = maxOf(it.updatedAt, updatedAt), revision = it.revision + 1)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAllEnvironmentVariables(secretId: String): Int {
        val before = variables.value.size
        variables.value = variables.value.filterNot { it.secretId == secretId }
        return before - variables.value.size
    }

    override suspend fun deleteEnvironmentVariablesExcept(
        secretId: String,
        names: List<String>,
    ): Int {
        val before = variables.value.size
        variables.value = variables.value.filterNot {
            it.secretId == secretId && it.name !in names
        }
        return before - variables.value.size
    }

    fun directlyReplaceVariable(variable: EnvironmentVariableEntity) {
        variables.value = variables.value.map { if (it.id == variable.id) variable else it }
    }

    fun directlyReplaceSshKey(key: SshKeyEntity) {
        sshKeys.value = sshKeys.value.map { if (it.secretId == key.secretId) key else it }
    }
}
