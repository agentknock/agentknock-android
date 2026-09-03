package dev.agentknock.storage.secret

import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.storage.ImmediateWriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.NoOpAuditSink
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.FakeEncryptionKeyStore
import dev.agentknock.storage.crypto.FakeVaultKeyDao
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "test@example")
        val created = fixture.repository.createSshSecret(
            name = "production-ssh",
            description = "Production host access",
            privateKey = key,
        )
        check(created is CreateSecretResult.Created)

        val stored = fixture.dao.sshKeys.value.single()
        assertFalse(stored.encryptedPrivateKey.ciphertext.contentEquals(key.privateKey))
        assertEquals(key.publicKey.toList(), stored.publicKey.toList())

        val details = checkNotNull(fixture.repository.observeSecret(created.id).first())
        assertEquals(SecretType.SSH, details.type)
        assertEquals(key.publicKeyLine, details.sshKey?.publicKey)
        assertEquals(key.fingerprint, details.sshKey?.fingerprint)
        assertEquals(SshKeyAlgorithm.ED25519, details.sshKey?.algorithm)

        val summary = fixture.repository.observeSecrets().first().single()
        assertEquals(SecretType.SSH, summary.type)
        assertEquals(SshKeyAlgorithm.ED25519, summary.sshKey?.algorithm)

        val requested = fixture.resolver.resolve(
            names = listOf("production-ssh"),
            includeValues = true,
        ).values
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
    fun `stores generated RSA material with its canonical algorithm`() = runTest {
        val fixture = Fixture()
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.RSA, "rsa@example")
        val created = fixture.repository.createSshSecret("legacy-host", "", key)
        check(created is CreateSecretResult.Created)

        val stored = fixture.dao.sshKeys.value.single()
        assertEquals("rsa", stored.algorithm)
        assertEquals(RSA_PRIVATE_KEY_FORMAT, SshKeyAlgorithm.RSA.canonicalPrivateKeyFormat())
        assertFalse(stored.encryptedPrivateKey.ciphertext.contentEquals(key.privateKey))
        val details = checkNotNull(fixture.repository.observeSecret(created.id).first())
        assertEquals(SshKeyAlgorithm.RSA, details.sshKey?.algorithm)
        assertTrue(details.sshKey?.publicKey?.startsWith("ssh-rsa ") == true)
    }

    @Test
    fun `omits a persisted secret with an unknown type from UI models`() = runTest {
        val fixture = Fixture()
        fixture.dao.secrets.value = listOf(
            SecretEntity(
                id = "corrupt",
                name = "corrupt",
                description = "",
                type = "unknown",
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        assertTrue(fixture.repository.observeSecrets().first().isEmpty())
        assertNull(fixture.repository.observeSecret("corrupt").first())
        assertTrue(fixture.repository.listSecretsForClient().isEmpty())
        val description = fixture.repository.describeRequestedSecrets(listOf("corrupt"))
        assertTrue(description.secrets.isEmpty())
        assertTrue(description.reviewMetadata.isEmpty())
        assertEquals(
            RequestedSecretsResult.UnsupportedSecretType,
            fixture.resolver.resolve(listOf("corrupt"), includeValues = true).values,
        )
    }

    @Test
    fun `omits SSH metadata with an unknown persisted algorithm`() = runTest {
        val fixture = Fixture()
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "test@example")
        val created = fixture.repository.createSshSecret("production-ssh", "", key)
        check(created is CreateSecretResult.Created)
        val stored = fixture.dao.sshKeys.value.single()
        fixture.dao.directlyReplaceSshKey(stored.copy(algorithm = "unknown"))

        val summary = fixture.repository.observeSecrets().first().single()
        assertEquals(SecretType.SSH, summary.type)
        assertNull(summary.sshKey)
        val details = checkNotNull(fixture.repository.observeSecret(created.id).first())
        assertEquals(SecretType.SSH, details.type)
        assertNull(details.sshKey)
        assertTrue(fixture.repository.listSecretsForClient().isEmpty())
        val description = fixture.repository.describeRequestedSecrets(listOf("production-ssh"))
        assertTrue(description.secrets.isEmpty())
        assertTrue(description.reviewMetadata.isEmpty())
        assertEquals(
            RequestedSecretsResult.SecretCorrupted,
            fixture.resolver.resolve(listOf("production-ssh"), includeValues = true).values,
        )
    }

    @Test
    fun `rejects SSH ciphertext with the wrong algorithm or record binding`() = runTest {
        val algorithmFixture = Fixture()
        val ed25519 = algorithmFixture.repository.generateSshKey(
            SshKeyAlgorithm.ED25519,
            "ed25519@example",
        )
        algorithmFixture.repository.createSshSecret("algorithm-bound", "", ed25519)
        val rsa = algorithmFixture.repository.generateSshKey(SshKeyAlgorithm.RSA, "rsa@example")
        val stored = algorithmFixture.dao.sshKeys.value.single()
        algorithmFixture.dao.directlyReplaceSshKey(
            stored.copy(
                algorithm = rsa.algorithm.storedName,
                publicKey = rsa.publicKey,
                comment = rsa.comment,
            ),
        )

        assertEquals(
            GitSignatureResult.SecretCorrupted,
            algorithmFixture.repository.signGitMessage(
                "algorithm-bound",
                rsa.publicKeyLine,
                "commit".encodeToByteArray(),
            ),
        )

        val recordFixture = Fixture()
        val first = recordFixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "first@example")
        val firstSecret = recordFixture.repository.createSshSecret("first", "", first)
        check(firstSecret is CreateSecretResult.Created)
        val second = recordFixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "second@example")
        val secondSecret = recordFixture.repository.createSshSecret("second", "", second)
        check(secondSecret is CreateSecretResult.Created)
        val rows = recordFixture.dao.sshKeys.value.associateBy(SshKeyEntity::secretId)
        val firstRow = rows.getValue(firstSecret.id)
        recordFixture.dao.directlyReplaceSshKey(
            firstRow.copy(
                encryptedPrivateKey = rows.getValue(secondSecret.id).encryptedPrivateKey,
            ),
        )

        assertEquals(
            GitSignatureResult.SecretCorrupted,
            recordFixture.repository.signGitMessage(
                "first",
                first.publicKeyLine,
                "commit".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `saving an SSH comment cannot restore stale key material`() = runTest {
        val fixture = Fixture()
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "first@example")
        val created = fixture.repository.createSshSecret("git-signing", "", key)
        check(created is CreateSecretResult.Created)
        val original = fixture.dao.sshKeys.value.single()
        val replacement = original.copy(
            publicKey = byteArrayOf(9, 8, 7),
            encryptedPrivateKey = original.encryptedPrivateKey.copy(
                keyId = "replacement-key",
                nonce = byteArrayOf(6, 5, 4),
                ciphertext = byteArrayOf(3, 2, 1),
            ),
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
        assertEquals(
            replacement.encryptedPrivateKey.ciphertext.toList(),
            stored.encryptedPrivateKey.ciphertext.toList(),
        )
        assertEquals("new@example", stored.comment)
    }

    @Test
    fun `SSH uploads create and replace one stable secret identity`() = runTest {
        val fixture = Fixture()
        val firstKey = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "first@example")
        val create = SshSecretUpload(
            mode = SecretUploadMode.CREATE,
            name = "client-suggested-name",
            descriptionProvided = true,
            description = "Production host access",
            privateKey = firstKey,
        )
        check(fixture.repository.describeSshSecretUpload(create) is SshSecretUploadResult.Valid)
        val createPreparation = fixture.uploads.prepareSshSecretUpload(
            create,
            approvedName = "production-ssh",
            target = null,
        )
        check(createPreparation is SshSecretUploadPreparation.Ready)
        val created = fixture.uploads.applyPreparedSshSecretUpload(createPreparation.upload)
        check(created is ApplySshSecretUploadResult.Applied)

        val before = fixture.dao.sshKeys.value.single()
        val secondKey = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "second@example")
        val replaceUpload = SshSecretUpload(
            mode = SecretUploadMode.REPLACE,
            name = "production-ssh",
            descriptionProvided = false,
            description = null,
            privateKey = secondKey,
        )
        val replacePlan = fixture.repository.describeSshSecretUpload(replaceUpload)
        check(replacePlan is SshSecretUploadResult.Valid)
        val replacePreparation = fixture.uploads.prepareSshSecretUpload(
            replaceUpload,
            approvedName = "ignored-for-existing-secret",
            target = replacePlan.summary.target,
        )
        check(replacePreparation is SshSecretUploadPreparation.Ready)
        val replace = fixture.uploads.applyPreparedSshSecretUpload(replacePreparation.upload)
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
            val key = fixture.repository.generateSshKey(
                SshKeyAlgorithm.ED25519,
                "key-$index@example",
            )
            fixture.repository.createSshSecret("ssh-$index", "", key)
        }

        assertTrue(
            fixture.resolver.resolve(
                listOf("environment", "ssh-0"),
                includeValues = true,
            ).values
                is RequestedSecretsResult.Available,
        )
        assertEquals(
            RequestedSecretsResult.MultipleSshKeys,
            fixture.resolver.resolve(
                listOf("ssh-0", "ssh-1"),
                includeValues = true,
            ).values,
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
                fixture.encryptionMetadata.getKey(variable.encryptedValue.keyId)?.purpose
            }.toSet(),
        )
        assertTrue(rows.all { it.secretId == secretId })
        assertFalse(
            rows[0].encryptedValue.ciphertext.contentEquals("AKIAEXAMPLE".encodeToByteArray()),
        )
        assertFalse(
            rows[1].encryptedValue.ciphertext.contentEquals("eu-west-1".encodeToByteArray()),
        )

        val secret = fixture.repository.observeSecret(secretId).first()
        checkNotNull(secret)
        assertEquals(2, secret.environmentVariables.size)
        assertTrue(secret.environmentVariables.all { it.valueAvailable })
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
            fixture.repository.readEnvironmentVariableValue(
                variableId,
                sensitiveAccessAuthorized = true,
            ),
        )
    }

    @Test
    fun `sensitive environment values require explicit protected access`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("github")
        val tokenId = fixture.createVariable(secretId, "GITHUB_TOKEN", "token", true)
        val regionId = fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", false)

        assertEquals(
            EnvironmentVariableValue.AuthenticationRequired("GITHUB_TOKEN"),
            fixture.repository.readEnvironmentVariableValue(
                tokenId,
                sensitiveAccessAuthorized = false,
            ),
        )
        assertEquals(
            EnvironmentVariableValue.Available("AWS_REGION", "eu-west-1", sensitive = false),
            fixture.repository.readEnvironmentVariableValue(
                regionId,
                sensitiveAccessAuthorized = false,
            ),
        )
    }

    @Test
    fun `creating a non-sensitive environment value requires explicit authorization`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws")

        assertEquals(
            CreateEnvironmentVariableResult.AuthenticationRequired,
            fixture.repository.createEnvironmentVariable(
                secretId = secretId,
                name = "AWS_REGION",
                value = "eu-west-1",
                sensitive = false,
                nonSensitiveCreationAuthorized = false,
            ),
        )
        assertTrue(fixture.dao.variables.value.isEmpty())

        assertTrue(
            fixture.repository.createEnvironmentVariable(
                secretId = secretId,
                name = "AWS_REGION",
                value = "eu-west-1",
                sensitive = false,
                nonSensitiveCreationAuthorized = true,
            ) is CreateEnvironmentVariableResult.Created,
        )
    }

    @Test
    fun `reducing environment value sensitivity requires explicit authorization`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("github")
        val variableId = fixture.createVariable(secretId, "GITHUB_TOKEN", "token", true)

        assertEquals(
            SaveEnvironmentVariableResult.AUTHENTICATION_REQUIRED,
            fixture.repository.saveEnvironmentVariable(
                id = variableId,
                name = "GITHUB_TOKEN",
                sensitive = false,
                replacementValue = null,
                sensitivityReductionAuthorized = false,
            ),
        )
        assertTrue(fixture.dao.variables.value.single().sensitive)

        assertEquals(
            SaveEnvironmentVariableResult.SAVED,
            fixture.repository.saveEnvironmentVariable(
                id = variableId,
                name = "GITHUB_TOKEN",
                sensitive = false,
                replacementValue = null,
                sensitivityReductionAuthorized = true,
            ),
        )
        assertFalse(fixture.dao.variables.value.single().sensitive)
    }

    @Test
    fun `legitimate metadata changes re-encrypt without changing the value timestamp`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws-read-only")
        val variableId = fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", false)
        val before = fixture.dao.variables.value.single()
        val secretUpdatedAt = fixture.dao.secrets.value.single().updatedAt

        val result = fixture.repository.saveEnvironmentVariable(
            id = variableId,
            name = "AWS_DEFAULT_REGION",
            sensitive = true,
            replacementValue = null,
            sensitivityReductionAuthorized = true,
        )

        assertEquals(SaveEnvironmentVariableResult.SAVED, result)
        val after = fixture.dao.variables.value.single()
        assertEquals(before.valueUpdatedAt, after.valueUpdatedAt)
        assertNotEquals(secretUpdatedAt, fixture.dao.secrets.value.single().updatedAt)
        assertFalse(before.encryptedValue.ciphertext.contentEquals(after.encryptedValue.ciphertext))
        val value = fixture.repository.readEnvironmentVariableValue(
            variableId,
            sensitiveAccessAuthorized = true,
        )
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
        assertTrue(before.encryptedValue.nonce.contentEquals(after.encryptedValue.nonce))
        assertTrue(before.encryptedValue.ciphertext.contentEquals(after.encryptedValue.ciphertext))
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
            audit = NoOpAuditSink,
            writeTransaction = ImmediateWriteTransaction,
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
            restoredRepository.readEnvironmentVariableValue(
                variableId,
                sensitiveAccessAuthorized = true,
            ),
        )

        assertEquals(
            SaveEnvironmentVariableResult.SAVED,
            restoredRepository.saveEnvironmentVariable(
                id = variableId,
                name = "CF_TOKEN",
                sensitive = true,
                replacementValue = "new-token",
                sensitivityReductionAuthorized = true,
            ),
        )

        val replacementRow = original.dao.variables.value.single()
        assertEquals(originalRow.id, replacementRow.id)
        assertEquals("replacement-secret-key", replacementRow.encryptedValue.keyId)
        val replacementValue = restoredRepository.readEnvironmentVariableValue(
            variableId,
            sensitiveAccessAuthorized = true,
        )
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

        val result = fixture.resolver.resolve(
            listOf("first", "second"),
            includeValues = true,
        ).values
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
                    type = ENVIRONMENT_SECRET_TYPE,
                    environmentVariableNames = listOf("FIRST_REGION", "SHARED_TOKEN"),
                ),
                SecretMetadata(
                    name = "second",
                    description = "",
                    type = ENVIRONMENT_SECRET_TYPE,
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
            fixture.resolver.resolve(
                listOf("first", "second"),
                includeValues = true,
            ).values,
        )
        assertEquals(
            RequestedSecretsResult.MissingSecrets(listOf("missing")),
            fixture.resolver.resolve(listOf("missing"), includeValues = true).values,
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
            listOf("GH_HOST", "GH_TOKEN", "UNRELATED"),
            description.reviewMetadata.single().environmentVariables.map { it.name },
        )
        assertEquals(
            EnvironmentVariableReviewDestination.Environment("GH_HOST"),
            description.reviewMetadata.single()
                .environmentVariableDestinations.getValue("GH_HOST"),
        )
        assertEquals(
            EnvironmentVariableReviewDestination.Omitted,
            description.reviewMetadata.single()
                .environmentVariableDestinations.getValue("GH_TOKEN"),
        )
        assertFalse(description.containsSensitiveMaterial)

        val requested = fixture.resolver.resolve(
            listOf("github"),
            onlyHost,
            includeValues = true,
        ).values
        check(requested is RequestedSecretsResult.Available)
        assertEquals(
            mapOf("GH_HOST" to "github.com"),
            (requested.secrets.getValue("github") as SecretValues.Environment).environment,
        )

        val omitted = fixture.resolver.resolve(
            listOf("github"),
            mapOf("github" to EnvironmentVariableSelection(omit = setOf("GH_TOKEN", "ABSENT"))),
            includeValues = true,
        ).values
        check(omitted is RequestedSecretsResult.Available)
        assertEquals(
            mapOf("GH_HOST" to "github.com", "UNRELATED" to "hidden"),
            (omitted.secrets.getValue("github") as SecretValues.Environment).environment,
        )
    }

    @Test
    fun `review context includes omitted non-sensitive values without making them releasable`() =
        runTest {
            val fixture = Fixture()
            val secret = fixture.createSecret("database")
            fixture.createVariable(secret, "PGPASSWORD", "secret", true)
            val hostId = fixture.createVariable(
                secret,
                "PGHOST",
                "production-db.example.com",
                false,
            )
            val selection = mapOf(
                "database" to EnvironmentVariableSelection(only = setOf("PGPASSWORD")),
            )

            val resolved = fixture.resolver.resolve(
                listOf("database"),
                selection,
                includeValues = true,
            )
            val available = resolved.values as RequestedSecretsResult.Available
            assertEquals(
                mapOf("PGPASSWORD" to "secret"),
                (available.secrets.getValue("database") as SecretValues.Environment).environment,
            )
            assertEquals(
                mapOf("PGHOST" to "production-db.example.com"),
                resolved.nonSensitiveEnvironmentValues.getValue("database"),
            )
            assertEquals(
                EnvironmentVariableReviewDestination.Omitted,
                resolved.description.reviewMetadata.single()
                    .environmentVariableDestinations.getValue("PGHOST"),
            )

            val host = fixture.dao.variables.value.single { it.id == hostId }
            fixture.dao.directlyReplaceVariable(
                host.copy(
                    encryptedValue = host.encryptedValue.copy(ciphertext = byteArrayOf(1)),
                ),
            )
            val withoutOptionalContext = fixture.resolver.resolve(
                listOf("database"),
                selection,
                includeValues = true,
            )

            assertTrue(withoutOptionalContext.values is RequestedSecretsResult.Available)
            assertEquals(
                emptyMap<String, String>(),
                withoutOptionalContext.nonSensitiveEnvironmentValues.getValue("database"),
            )
        }

    @Test
    fun `environment selection rejects missing exact variables and SSH options`() = runTest {
        val fixture = Fixture()
        val environment = fixture.createSecret("environment")
        fixture.createVariable(environment, "TOKEN", "secret", true)
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "git@example")
        fixture.repository.createSshSecret("git-signing", "", key)

        assertEquals(
            RequestedSecretsResult.MissingEnvironmentVariables(
                secretName = "environment",
                names = listOf("MISSING"),
            ),
            fixture.resolver.resolve(
                listOf("environment"),
                mapOf(
                    "environment" to EnvironmentVariableSelection(
                        only = setOf("TOKEN", "MISSING"),
                    ),
                ),
                includeValues = true,
            ).values,
        )
        assertEquals(
            RequestedSecretsResult.EnvironmentOptionsForSshSecret("git-signing"),
            fixture.resolver.resolve(
                listOf("git-signing"),
                mapOf("git-signing" to EnvironmentVariableSelection(omit = setOf("TOKEN"))),
                includeValues = true,
            ).values,
        )
    }

    @Test
    fun `environment renaming is recorded and detects delivered-name conflicts`() = runTest {
        val fixture = Fixture()
        val first = fixture.createSecret("first")
        val second = fixture.createSecret("second")
        fixture.createVariable(first, "FIRST_TOKEN", "one", true)
        fixture.createVariable(second, "TOKEN", "two", true)
        val delivery = mapOf(
            "first" to EnvironmentVariableSelection(
                rename = mapOf("FIRST_TOKEN" to "TOKEN"),
            ),
        )

        val description = fixture.repository.describeRequestedSecrets(listOf("first"), delivery)
        assertEquals(
            mapOf("FIRST_TOKEN" to "TOKEN"),
            description.secrets.single().environmentVariableRename,
        )
        assertEquals(
            RequestedSecretsResult.ConflictingVariable("TOKEN"),
            fixture.resolver.resolve(
                listOf("first", "second"),
                delivery,
                includeValues = true,
            ).values,
        )
        assertEquals(
            RequestedSecretsResult.MissingEnvironmentVariables("first", listOf("ABSENT")),
            fixture.resolver.resolve(
                listOf("first"),
                mapOf("first" to EnvironmentVariableSelection(rename = mapOf("ABSENT" to "TOKEN"))),
                includeValues = true,
            ).values,
        )
    }

    @Test
    fun `standard input delivery is recorded and excluded from environment conflicts`() = runTest {
        val fixture = Fixture()
        val input = fixture.createSecret("input")
        val environment = fixture.createSecret("environment")
        fixture.createVariable(input, "TOKEN", "stdin-value", true)
        fixture.createVariable(environment, "TOKEN", "environment-value", true)
        val delivery = mapOf(
            "input" to EnvironmentVariableSelection(stdin = "TOKEN"),
        )

        val description = fixture.repository.describeRequestedSecrets(listOf("input"), delivery)
        assertEquals("TOKEN", description.secrets.single().environmentVariableStdin)
        assertEquals(
            EnvironmentVariableReviewDestination.StandardInput,
            description.reviewMetadata.single()
                .environmentVariableDestinations.getValue("TOKEN"),
        )
        val requested = fixture.resolver.resolve(
            listOf("input", "environment"),
            delivery,
            includeValues = true,
        ).values
        check(requested is RequestedSecretsResult.Available)
        assertEquals(
            mapOf("TOKEN" to "stdin-value"),
            (requested.secrets.getValue("input") as SecretValues.Environment).environment,
        )
        assertEquals(
            RequestedSecretsResult.MissingEnvironmentVariables("input", listOf("MISSING")),
            fixture.resolver.resolve(
                listOf("input"),
                mapOf("input" to EnvironmentVariableSelection(stdin = "MISSING")),
                includeValues = true,
            ).values,
        )
    }

    @Test
    fun `only sensitive environment values require approval`() = runTest {
        val fixture = Fixture()
        val public = fixture.createSecret("public-context")
        fixture.createVariable(public, "AWS_REGION", "eu-north-1", false)
        val sensitive = fixture.createSecret("credentials")
        fixture.createVariable(sensitive, "AWS_TOKEN", "secret", true)
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "git@example")
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
        val first = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "first@example")
        val created = fixture.repository.createSshSecret("git-signing", "", first)
        check(created is CreateSecretResult.Created)

        val signed = fixture.repository.signGitMessage(
            secretName = "git-signing",
            expectedPublicKey = first.publicKeyLine,
            message = "commit object".encodeToByteArray(),
        )
        assertTrue(signed is GitSignatureResult.Signed)

        val second = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "second@example")
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

        val preparation = fixture.uploads.prepareEnvironmentSecretUpload(
            EnvironmentSecretUpload(
                mode = SecretUploadMode.CREATE,
                name = "cloudflare-read-only",
                descriptionProvided = true,
                description = "Cloudflare production account",
                variables = mapOf("CF_ACCOUNT_ID" to "account", "CF_TOKEN" to "token"),
            ),
            approvedName = "cloudflare-read-only",
            target = null,
        )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        check(result is ApplyEnvironmentSecretUploadResult.Applied)
        val secret = fixture.repository.observeSecret(result.secretId).first()
        checkNotNull(secret)
        assertEquals(SecretType.ENVIRONMENT, secret.type)
        assertEquals(
            SecretType.ENVIRONMENT,
            fixture.repository.observeSecrets().first().single().type,
        )
        assertEquals("Cloudflare production account", secret.description)
        assertEquals(SecretApprovalMode.ASK_ME, secret.approvalMode)
        assertTrue(secret.environmentVariables.all(EnvironmentVariableMetadata::sensitive))
        assertEquals(
            EnvironmentVariableValue.Available("CF_TOKEN", "token", sensitive = true),
            fixture.repository.readEnvironmentVariableValue(
                secret.environmentVariables.single { it.name == "CF_TOKEN" }.id,
                sensitiveAccessAuthorized = true,
            ),
        )
    }

    @Test
    fun `replace uploads preserve environment variable sensitivity`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws-read-only")
        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.ASK_AI)
        fixture.repository.saveInstructions(secretId, "Permit read-only AWS operations.")
        val region = fixture.repository.createEnvironmentVariable(
            secretId = secretId,
            name = "AWS_REGION",
            value = "eu-west-1",
            sensitive = false,
            nonSensitiveCreationAuthorized = true,
        )
        check(region is CreateEnvironmentVariableResult.Created)
        fixture.createVariable(secretId, "OLD_VARIABLE", "old", true)

        val preparation = fixture.uploads.prepareEnvironmentSecretUpload(
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
            target = SecretUploadTarget(
                secretId,
                checkNotNull(fixture.dao.getSecret(secretId)).revision,
            ),
        )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertTrue(result is ApplyEnvironmentSecretUploadResult.Applied)
        val secret = fixture.repository.observeSecret(secretId).first()
        checkNotNull(secret)
        assertEquals(listOf("AWS_ACCESS_KEY_ID", "AWS_REGION"), secret.environmentVariables.map { it.name })
        val updatedRegion = secret.environmentVariables.single { it.name == "AWS_REGION" }
        assertFalse(updatedRegion.sensitive)
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
            assertEquals(
                "Allow issue triage but never publish a release.",
                overridden.instructions,
            )

            fixture.repository.setClientApprovalOverride(
                secretId,
                "workstation",
                null,
            )
            val inherited = fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
            assertEquals(SecretApprovalMode.ASK_AI, inherited.mode)
        }

    @Test
    fun `reselecting an explicit client approval override changes nothing`() = runTest {
        val audit = RecordingAuditSink()
        val fixture = Fixture(audit = audit)
        val secretId = fixture.createSecret("github")
        fixture.repository.setClientApprovalOverride(
            secretId,
            "workstation",
            SecretApprovalMode.ASK_AI,
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
        val overrideRows = fixture.dao.approvalOverrides.value.toList()
        val revision = checkNotNull(fixture.dao.getSecret(secretId)).revision
        val grants = fixture.dao.temporaryAccessGrants.value.toList()
        val auditRecords = audit.records.toList()
        assertEquals(1, overrideRows.size)
        assertEquals(1, grants.size)

        assertEquals(
            SaveSecretResult.SAVED,
            fixture.repository.setClientApprovalOverride(
                secretId,
                "workstation",
                SecretApprovalMode.ASK_AI,
            ),
        )

        assertEquals(overrideRows, fixture.dao.approvalOverrides.value)
        assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertEquals(grants, fixture.dao.temporaryAccessGrants.value)
        assertEquals(auditRecords, audit.records)
    }

    @Test
    fun `reselecting inherited client approval changes nothing`() = runTest {
        val audit = RecordingAuditSink()
        val fixture = Fixture(audit = audit)
        val secretId = fixture.createSecret("github")
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
        val overrideRows = fixture.dao.approvalOverrides.value.toList()
        val revision = checkNotNull(fixture.dao.getSecret(secretId)).revision
        val grants = fixture.dao.temporaryAccessGrants.value.toList()
        val auditRecords = audit.records.toList()
        assertTrue(overrideRows.isEmpty())
        assertEquals(1, grants.size)

        assertEquals(
            SaveSecretResult.SAVED,
            fixture.repository.setClientApprovalOverride(secretId, "workstation", null),
        )

        assertEquals(overrideRows, fixture.dao.approvalOverrides.value)
        assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertEquals(grants, fixture.dao.temporaryAccessGrants.value)
        assertEquals(auditRecords, audit.records)
    }

    @Test
    fun `explicit and inherited forms of the same client mode preserve temporary access`() =
        runTest {
            val fixture = Fixture()
            val secretId = fixture.createSecret("github")
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
            val revision = checkNotNull(fixture.dao.getSecret(secretId)).revision

            fixture.repository.setClientApprovalOverride(
                secretId,
                "workstation",
                SecretApprovalMode.ASK_ME,
            )

            assertEquals(1, fixture.dao.temporaryAccessGrants.value.size)
            assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)

            fixture.repository.setClientApprovalOverride(secretId, "workstation", null)

            assertEquals(1, fixture.dao.temporaryAccessGrants.value.size)
            assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        }

    @Test
    fun `changing the default mode ends only access inherited from that default`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("github")
        fixture.repository.setClientApprovalOverride(
            secretId,
            "pinned-client",
            SecretApprovalMode.ASK_ME,
        )
        listOf("inheriting-client", "pinned-client").forEach { clientId ->
            fixture.repository.allowTemporaryAccess(
                policies = fixture.repository.approvalPoliciesForNames(
                    listOf("github"),
                    clientId,
                    TemporaryAccessOperation.INVOCATION,
                ),
                clientId = clientId,
                operation = TemporaryAccessOperation.INVOCATION,
                expiresAt = 10_000,
            )
        }
        val revision = checkNotNull(fixture.dao.getSecret(secretId)).revision

        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.ASK_AI)

        assertEquals(
            listOf("pinned-client"),
            fixture.dao.temporaryAccessGrants.value.map { it.clientId },
        )
        assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertEquals(
            SecretApprovalMode.ASK_AI,
            fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "inheriting-client",
                TemporaryAccessOperation.INVOCATION,
            ).single().mode,
        )
        assertEquals(
            SecretApprovalMode.ASK_ME,
            fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "pinned-client",
                TemporaryAccessOperation.INVOCATION,
            ).single().mode,
        )
    }

    @Test
    fun `client approval override changes touch the secret and end scoped temporary access`() =
        runTest {
        val audit = RecordingAuditSink()
        val fixture = Fixture(audit = audit)
        val secretId = fixture.createSecret("github")
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
        val initialRevision = checkNotNull(fixture.dao.getSecret(secretId)).revision
        val initialAuditCount = audit.records.size
        assertEquals(1, fixture.dao.temporaryAccessGrants.value.size)

        fixture.repository.setClientApprovalOverride(
            secretId,
            "workstation",
            SecretApprovalMode.ASK_AI,
        )

        assertEquals(
            listOf(
                SecretClientApprovalOverrideEntity(
                    secretId = secretId,
                    clientId = "workstation",
                    approvalMode = SecretApprovalMode.ASK_AI.storedName,
                ),
            ),
            fixture.dao.approvalOverrides.value,
        )
        assertEquals(initialRevision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertTrue(fixture.dao.temporaryAccessGrants.value.isEmpty())
        assertEquals(initialAuditCount + 1, audit.records.size)
        assertEquals(
            AuditEventType.CLIENT_APPROVAL_OVERRIDE_CHANGED,
            audit.records.last().type,
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
        val overriddenRevision = checkNotNull(fixture.dao.getSecret(secretId)).revision
        val overriddenAuditCount = audit.records.size
        assertEquals(1, fixture.dao.temporaryAccessGrants.value.size)

        fixture.repository.setClientApprovalOverride(secretId, "workstation", null)

        assertTrue(fixture.dao.approvalOverrides.value.isEmpty())
        assertEquals(overriddenRevision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertTrue(fixture.dao.temporaryAccessGrants.value.isEmpty())
        assertEquals(overriddenAuditCount + 1, audit.records.size)
        assertEquals(
            AuditEventType.CLIENT_APPROVAL_OVERRIDE_CHANGED,
            audit.records.last().type,
        )
    }

    @Test
    fun `temporary access is scoped to client secret and operation`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("github")
        val otherSecretId = fixture.createSecret("production")
        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.ASK_ME)

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
    fun `temporary access audit cancellation propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        var cancelAudit = false
        val fixture = Fixture(
            audit = object : AuditSink {
                override suspend fun record(record: AuditRecord) {
                    if (cancelAudit) throw cancellation
                }

                override suspend fun append(records: List<AuditRecord>, occurredAt: Long) {
                    if (cancelAudit) throw cancellation
                }
            },
        )
        val secretId = fixture.createSecret("github")
        cancelAudit = true
        val policies = fixture.repository.approvalPoliciesForNames(
            listOf("github"),
            "workstation",
            TemporaryAccessOperation.INVOCATION,
        )

        val thrown = try {
            fixture.repository.allowTemporaryAccess(
                policies = policies,
                clientId = "workstation",
                operation = TemporaryAccessOperation.INVOCATION,
                expiresAt = 10_000,
            )
            null
        } catch (failure: CancellationException) {
            failure
        }

        assertEquals(cancellation, thrown)
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
            SecretApprovalMode.ASK_ME,
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
            replacementValue = "new-token",
            sensitivityReductionAuthorized = true,
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
        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.ASK_ME)
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
            replacementValue = "new-token",
            sensitivityReductionAuthorized = true,
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
            SecretApprovalMode.DENY,
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

        val preparation = fixture.uploads.prepareEnvironmentSecretUpload(
            upload,
            approvedName = "ignored-for-existing-secret",
            target = description.summary.target,
        )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertTrue(result is ApplyEnvironmentSecretUploadResult.Applied)
        val secret = fixture.repository.observeSecret(secretId).first()
        checkNotNull(secret)
        assertEquals(listOf("AWS_REGION", "AWS_TOKEN"), secret.environmentVariables.map { it.name })
        assertEquals(
            EnvironmentVariableValue.Available("AWS_TOKEN", "new-token", sensitive = true),
            fixture.repository.readEnvironmentVariableValue(
                tokenId,
                sensitiveAccessAuthorized = true,
            ),
        )
    }

    @Test
    fun `update upload preserves a description edited after review`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("production")
        fixture.createVariable(secretId, "TOKEN", "old", true)
        val upload = EnvironmentSecretUpload(
            mode = SecretUploadMode.UPDATE,
            name = "production",
            descriptionProvided = false,
            description = null,
            variables = mapOf("TOKEN" to "new"),
        )
        val plan = fixture.repository.describeEnvironmentSecretUpload(upload)
        check(plan is EnvironmentSecretUploadResult.Valid)
        assertEquals(
            SaveSecretResult.SAVED,
            fixture.repository.saveSecret(secretId, "production", "Edited on the phone"),
        )

        val preparation = fixture.uploads.prepareEnvironmentSecretUpload(
            upload,
            approvedName = "ignored",
            target = plan.summary.target,
        )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertTrue(result is ApplyEnvironmentSecretUploadResult.Applied)
        assertEquals(
            "Edited on the phone",
            fixture.repository.observeSecret(secretId).first()?.description,
        )
    }

    @Test
    fun `an upload cannot retarget after its secret is renamed`() = runTest {
        val fixture = Fixture()
        val originalId = fixture.createSecret("production")
        val originalVariable = fixture.createVariable(originalId, "TOKEN", "old", true)
        val upload = EnvironmentSecretUpload(
            mode = SecretUploadMode.UPDATE,
            name = "production",
            descriptionProvided = false,
            description = null,
            variables = mapOf("TOKEN" to "uploaded"),
        )
        val plan = fixture.repository.describeEnvironmentSecretUpload(upload)
        check(plan is EnvironmentSecretUploadResult.Valid)
        val preparation = fixture.uploads.prepareEnvironmentSecretUpload(
            upload,
            approvedName = "ignored",
            target = plan.summary.target,
        )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)

        assertEquals(SaveSecretResult.SAVED, fixture.repository.saveSecret(originalId, "archive", ""))
        val replacementId = fixture.createSecret("production")
        val replacementVariable = fixture.createVariable(replacementId, "TOKEN", "replacement", true)

        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertTrue(result is ApplyEnvironmentSecretUploadResult.Invalid)
        assertEquals(
            EnvironmentVariableValue.Available("TOKEN", "old", sensitive = true),
            fixture.repository.readEnvironmentVariableValue(
                originalVariable,
                sensitiveAccessAuthorized = true,
            ),
        )
        assertEquals(
            EnvironmentVariableValue.Available("TOKEN", "replacement", sensitive = true),
            fixture.repository.readEnvironmentVariableValue(
                replacementVariable,
                sensitiveAccessAuthorized = true,
            ),
        )
    }

    @Test
    fun `an authorization revision invalidates a pending upload`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("production")
        fixture.createVariable(secretId, "TOKEN", "old", true)
        val upload = EnvironmentSecretUpload(
            mode = SecretUploadMode.UPDATE,
            name = "production",
            descriptionProvided = false,
            description = null,
            variables = mapOf("TOKEN" to "uploaded"),
        )
        val plan = fixture.repository.describeEnvironmentSecretUpload(upload)
        check(plan is EnvironmentSecretUploadResult.Valid)
        val preparation = fixture.uploads.prepareEnvironmentSecretUpload(
            upload,
            approvedName = "ignored",
            target = plan.summary.target,
        )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        fixture.repository.saveInstructions(secretId, "Only for production deploys")

        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertTrue(result is ApplyEnvironmentSecretUploadResult.Invalid)
    }

    private class Fixture(
        keyId: String = "storage-key",
        audit: AuditSink = NoOpAuditSink,
    ) {
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
        private val encryption = AesGcmEncryption(keyStore)
        private val sshKeys = SshKeyCodec()
        private val material = SecretMaterialStore(
            keyManager = keyManager,
            encryption = encryption,
            sshKeys = sshKeys,
            cryptographyDispatcher = Dispatchers.IO,
        )
        val repository = SecretRepository(
            dao = dao,
            keyManager = keyManager,
            encryption = encryption,
            audit = audit,
            writeTransaction = ImmediateWriteTransaction,
            sshKeys = sshKeys,
            newId = { nextId() },
            currentTimeMillis = { nextTime() },
        )
        val uploads = SecretUploads(
            dao = dao,
            material = material,
            newId = { nextId() },
            currentTimeMillis = { nextTime() },
        )
        val resolver = SecretResolver(
            dao = dao,
            material = material,
            sshKeys = sshKeys,
            cryptographyDispatcher = Dispatchers.IO,
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
                nonSensitiveCreationAuthorized = true,
            )
            check(result is CreateEnvironmentVariableResult.Created)
            return result.id
        }

        private fun nextId(): String = "id-${++id}"

        private fun nextTime(): Long = ++time
    }
}

private class RecordingAuditSink : AuditSink {
    val records = mutableListOf<AuditRecord>()

    override suspend fun record(record: AuditRecord) {
        records += record
    }

    override suspend fun append(records: List<AuditRecord>, occurredAt: Long) {
        this.records += records
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
    var beforeSshCommentUpdate: (() -> Unit)? = null

    override fun observeSecrets(): Flow<List<SecretSummaryRow>> = combine(
        secrets,
        variables,
        sshKeys,
    ) { currentSecrets, currentVariables, currentSshKeys ->
        val keysBySecret = currentSshKeys.associateBy(SshKeyEntity::secretId)
        currentSecrets
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SecretEntity::name))
            .map { secret ->
                val key = keysBySecret[secret.id]
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
                    sshAlgorithm = key?.algorithm,
                    sshPublicKey = key?.publicKey,
                    sshComment = key?.comment,
                    sshEncryptionKeyId = key?.encryptedPrivateKey?.keyId,
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
                    encryptionKeyId = key.encryptedPrivateKey.keyId,
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
                    encryptionKeyId = variable.encryptedValue.keyId,
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

    override suspend fun getSshKeys(): List<SshKeyEntity> = sshKeys.value.sortedBy { it.secretId }

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

    override suspend fun updateSecretMetadataIfCurrent(
        secretId: String,
        expectedRevision: Long,
        expectedType: String,
        name: String,
        description: String,
        updatedAt: Long,
    ): Int {
        val current = secrets.value.singleOrNull {
            it.id == secretId && it.revision == expectedRevision && it.type == expectedType
        } ?: return 0
        if (secrets.value.any { it.id != secretId && it.name == name }) return 0
        val revision = if (current.name == name) current.revision else current.revision + 1
        secrets.value = secrets.value.map {
            if (it.id == secretId) {
                current.copy(
                    name = name,
                    description = description,
                    updatedAt = updatedAt,
                    revision = revision,
                )
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun reviseSecretForUploadIfCurrent(
        secretId: String,
        expectedRevision: Long,
        expectedName: String,
        expectedType: String,
        description: String,
        updatedAt: Long,
    ): Int = updateSecretForUpload(
        secretId,
        expectedRevision,
        expectedName,
        expectedType,
        description,
        updatedAt,
    )

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

    override suspend fun deleteTemporaryAccessGrantsUsingDefaultApproval(secretId: String): Int {
        val overriddenClients = approvalOverrides.value
            .filter { it.secretId == secretId }
            .mapTo(mutableSetOf(), SecretClientApprovalOverrideEntity::clientId)
        val before = temporaryAccessGrants.value.size
        temporaryAccessGrants.value = temporaryAccessGrants.value.filterNot {
            it.secretId == secretId && it.clientId !in overriddenClients
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

    override suspend fun updateSshKeyMaterialRow(
        secretId: String,
        algorithm: String,
        publicKey: ByteArray,
        comment: String,
        encryptionFormat: Int,
        encryptionKeyId: String,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): Int {
        val current = sshKeys.value.singleOrNull { it.secretId == secretId } ?: return 0
        directlyReplaceSshKey(
            current.copy(
                algorithm = algorithm,
                publicKey = publicKey,
                comment = comment,
                encryptedPrivateKey = current.encryptedPrivateKey.copy(
                    formatVersion = encryptionFormat,
                    keyId = encryptionKeyId,
                    nonce = nonce,
                    ciphertext = ciphertext,
                ),
            ),
        )
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

    override suspend fun updateEnvironmentVariableMaterialRow(
        variableId: String,
        secretId: String,
        name: String,
        sensitive: Boolean,
        encryptionFormat: Int,
        encryptionKeyId: String,
        nonce: ByteArray,
        ciphertext: ByteArray,
        valueUpdatedAt: Long,
    ): Int {
        val current = variables.value.singleOrNull {
            it.id == variableId && it.secretId == secretId
        } ?: return 0
        directlyReplaceVariable(
            current.copy(
                name = name,
                sensitive = sensitive,
                encryptedValue = current.encryptedValue.copy(
                    formatVersion = encryptionFormat,
                    keyId = encryptionKeyId,
                    nonce = nonce,
                    ciphertext = ciphertext,
                ),
                valueUpdatedAt = valueUpdatedAt,
            ),
        )
        return 1
    }

    override suspend fun updateUploadedEnvironmentVariableRow(
        variableId: String,
        secretId: String,
        sensitive: Boolean,
        encryptionFormat: Int,
        encryptionKeyId: String,
        nonce: ByteArray,
        ciphertext: ByteArray,
        valueUpdatedAt: Long,
    ): Int {
        val current = variables.value.singleOrNull {
            it.id == variableId && it.secretId == secretId
        } ?: return 0
        directlyReplaceVariable(
            current.copy(
                sensitive = sensitive,
                encryptedValue = current.encryptedValue.copy(
                    formatVersion = encryptionFormat,
                    keyId = encryptionKeyId,
                    nonce = nonce,
                    ciphertext = ciphertext,
                ),
                valueUpdatedAt = valueUpdatedAt,
            ),
        )
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

    override suspend fun reviseSecretIfCurrent(
        secretId: String,
        expectedRevision: Long,
        expectedType: String,
        updatedAt: Long,
    ): Int {
        val current = secrets.value.singleOrNull {
            it.id == secretId && it.revision == expectedRevision && it.type == expectedType
        } ?: return 0
        secrets.value = secrets.value.map {
            if (it.id == secretId) {
                current.copy(updatedAt = maxOf(current.updatedAt, updatedAt), revision = current.revision + 1)
            } else {
                it
            }
        }
        return 1
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

    private fun updateSecretForUpload(
        secretId: String,
        expectedRevision: Long,
        expectedName: String,
        expectedType: String,
        description: String,
        updatedAt: Long,
    ): Int {
        val current = secrets.value.singleOrNull {
            it.id == secretId && it.revision == expectedRevision &&
                it.name == expectedName && it.type == expectedType
        } ?: return 0
        secrets.value = secrets.value.map {
            if (it.id == secretId) {
                current.copy(
                    description = description,
                    updatedAt = maxOf(current.updatedAt, updatedAt),
                    revision = current.revision + 1,
                )
            } else {
                it
            }
        }
        return 1
    }
}
