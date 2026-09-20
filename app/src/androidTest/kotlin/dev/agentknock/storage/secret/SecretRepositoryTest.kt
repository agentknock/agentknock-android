package dev.agentknock.storage.secret

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.NoOpAuditSink
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.InMemoryEncryptionKeyStore
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.request.ClientEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretRepositoryTest {
    private val databases = mutableListOf<AgentknockDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach { it.close() }
    }

    @Test
    fun environmentSecretAuditRecordsMetadataButNeverValues() = runTest {
        val audit = RecordingAuditSink()
        val fixture = fixture(audit = audit)

        val result =
            fixture.repository.createEnvironmentSecret(
                name = "production-database",
                description = "Production database access",
                variables =
                    listOf(
                        EnvironmentVariableInput("PGUSER", "analytics", sensitive = false),
                        EnvironmentVariableInput("PGPASSWORD", "do-not-log", sensitive = true),
                    ),
            )
        check(result is CreateSecretResult.Created)

        val record = audit.records.single()
        assertEquals(AuditEventType.SECRET_CREATED, record.type)
        assertEquals(JsonPrimitive(result.id), record.data["secret_id"])
        assertEquals(JsonPrimitive("production-database"), record.data["secret_name"])
        assertEquals(JsonPrimitive("environment"), record.data["secret_type"])
        val variables = record.data.getValue("environment_variables").toString()
        assertTrue(variables.contains("PGUSER"))
        assertTrue(variables.contains("PGPASSWORD"))
        assertTrue(variables.contains("sensitive"))
        assertFalse(record.data.toString().contains("analytics"))
        assertFalse(record.data.toString().contains("do-not-log"))
    }

    @Test
    fun storesAnSSHPrivateKeyEncryptedAndReturnsOnlyItsPublicKey() = runTest {
        val fixture = fixture()
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "test@example")
        val created =
            fixture.repository.createSshSecret(
                name = "production-ssh",
                description = "Production host access",
                privateKey = key,
            )
        check(created is CreateSecretResult.Created)

        val stored = fixture.dao.getSshKeys().single()
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

        val requested =
            fixture.resolver
                .resolve(
                    names = listOf("production-ssh"),
                    includeValues = true,
                )
                .values
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
    fun storesGeneratedRSAMaterialWithItsCanonicalAlgorithm() = runTest {
        val fixture = fixture()
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.RSA, "rsa@example")
        val created = fixture.repository.createSshSecret("legacy-host", "", key)
        check(created is CreateSecretResult.Created)

        val stored = fixture.dao.getSshKeys().single()
        assertEquals("rsa", stored.algorithm)
        assertEquals(RSA_PRIVATE_KEY_FORMAT, SshKeyAlgorithm.RSA.canonicalPrivateKeyFormat())
        assertFalse(stored.encryptedPrivateKey.ciphertext.contentEquals(key.privateKey))
        val details = checkNotNull(fixture.repository.observeSecret(created.id).first())
        assertEquals(SshKeyAlgorithm.RSA, details.sshKey?.algorithm)
        assertTrue(details.sshKey?.publicKey?.startsWith("ssh-rsa ") == true)
    }

    @Test
    fun omitsAPersistedSecretWithAnUnknownTypeFromUIModels() = runTest {
        val fixture = fixture()
        fixture.dao.insertSecret(
            SecretEntity(
                id = "corrupt",
                name = "corrupt",
                description = "",
                type = "unknown",
                createdAt = 1,
                updatedAt = 1,
            )
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
    fun omitsSSHMetadataWithAnUnknownPersistedAlgorithm() = runTest {
        val fixture = fixture()
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "test@example")
        val created = fixture.repository.createSshSecret("production-ssh", "", key)
        check(created is CreateSecretResult.Created)
        val stored = fixture.dao.getSshKeys().single()
        fixture.replaceSshKey(stored.copy(algorithm = "unknown"))

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
    fun rejectsSSHCiphertextWithTheWrongAlgorithmOrRecordBinding() = runTest {
        val algorithmFixture = fixture()
        val ed25519 =
            algorithmFixture.repository.generateSshKey(
                SshKeyAlgorithm.ED25519,
                "ed25519@example",
            )
        algorithmFixture.repository.createSshSecret("algorithm-bound", "", ed25519)
        val rsa = algorithmFixture.repository.generateSshKey(SshKeyAlgorithm.RSA, "rsa@example")
        val stored = algorithmFixture.dao.getSshKeys().single()
        algorithmFixture.replaceSshKey(
            stored.copy(
                algorithm = rsa.algorithm.storedName,
                publicKey = rsa.publicKey,
                comment = rsa.comment,
            )
        )

        assertEquals(
            SignatureResult.SecretCorrupted,
            algorithmFixture.repository.signGitMessage(
                "algorithm-bound",
                rsa.publicKeyLine,
                "commit".encodeToByteArray(),
            ),
        )

        val recordFixture = fixture()
        val first =
            recordFixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "first@example")
        val firstSecret = recordFixture.repository.createSshSecret("first", "", first)
        check(firstSecret is CreateSecretResult.Created)
        val second =
            recordFixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "second@example")
        val secondSecret = recordFixture.repository.createSshSecret("second", "", second)
        check(secondSecret is CreateSecretResult.Created)
        val rows = recordFixture.dao.getSshKeys().associateBy(SshKeyEntity::secretId)
        val firstRow = rows.getValue(firstSecret.id)
        recordFixture.replaceSshKey(
            firstRow.copy(encryptedPrivateKey = rows.getValue(secondSecret.id).encryptedPrivateKey)
        )

        assertEquals(
            SignatureResult.SecretCorrupted,
            recordFixture.repository.signGitMessage(
                "first",
                first.publicKeyLine,
                "commit".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun savingAnSSHCommentCannotRestoreStaleKeyMaterial() = runTest {
        val fixture = fixture()
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "first@example")
        val created = fixture.repository.createSshSecret("git-signing", "", key)
        check(created is CreateSecretResult.Created)
        val original = fixture.dao.getSshKeys().single()
        val replacement =
            original.copy(
                publicKey = byteArrayOf(9, 8, 7),
                encryptedPrivateKey =
                    original.encryptedPrivateKey.copy(
                        keyId = "replacement-key",
                        nonce = byteArrayOf(6, 5, 4),
                        ciphertext = byteArrayOf(3, 2, 1),
                    ),
            )
        fixture.beforeSshCommentUpdate = {
            fixture.replaceSshKey(replacement)
        }

        assertEquals(
            SaveSshSecretResult.Saved(created.id),
            fixture.repository.saveSshComment(created.id, "new@example"),
        )

        val stored = fixture.dao.getSshKeys().single()
        assertEquals(replacement.publicKey.toList(), stored.publicKey.toList())
        assertEquals(
            replacement.encryptedPrivateKey.ciphertext.toList(),
            stored.encryptedPrivateKey.ciphertext.toList(),
        )
        assertEquals("new@example", stored.comment)
    }

    @Test
    fun SSHUploadsCreateAndReplaceOneStableSecretIdentity() = runTest {
        val fixture = fixture()
        val firstKey = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "first@example")
        val create =
            SshSecretUpload(
                mode = SecretUploadMode.CREATE,
                name = "client-suggested-name",
                descriptionProvided = true,
                description = "Production host access",
                privateKey = firstKey,
            )
        check(fixture.repository.describeSshSecretUpload(create) is SshSecretUploadResult.Valid)
        val createPreparation =
            fixture.uploads.prepareSshSecretUpload(
                create,
                approvedName = "production-ssh",
                target = null,
            )
        check(createPreparation is SshSecretUploadPreparation.Ready)
        val created = fixture.uploads.applyPreparedSshSecretUpload(createPreparation.upload)
        check(created is ApplySecretUploadResult.Applied)
        assertEquals(
            ApplySecretUploadResult.Invalid("A secret named production-ssh already exists."),
            fixture.uploads.applyPreparedSshSecretUpload(createPreparation.upload),
        )

        val before = fixture.dao.getSshKeys().single()
        val secondKey = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "second@example")
        val replaceUpload =
            SshSecretUpload(
                mode = SecretUploadMode.REPLACE,
                name = "production-ssh",
                descriptionProvided = false,
                description = null,
                privateKey = secondKey,
            )
        val replacePlan = fixture.repository.describeSshSecretUpload(replaceUpload)
        check(replacePlan is SshSecretUploadResult.Valid)
        val replacePreparation =
            fixture.uploads.prepareSshSecretUpload(
                replaceUpload,
                approvedName = "ignored-for-existing-secret",
                target = replacePlan.summary.target,
            )
        check(replacePreparation is SshSecretUploadPreparation.Ready)
        val replace = fixture.uploads.applyPreparedSshSecretUpload(replacePreparation.upload)
        check(replace is ApplySecretUploadResult.Applied)
        assertEquals(
            ApplySecretUploadResult.Invalid(
                "The target secret changed before the upload was approved."
            ),
            fixture.uploads.applyPreparedSshSecretUpload(replacePreparation.upload),
        )

        assertEquals(created.secretId, replace.secretId)
        assertEquals(created.secretId, fixture.dao.getSecrets().single().id)
        assertFalse(before.publicKey.contentEquals(fixture.dao.getSshKeys().single().publicKey))
        assertEquals(
            secondKey.publicKeyLine,
            fixture.repository.listSecretsForClient().single().sshPublicKey,
        )
    }

    @Test
    fun aRequestMayCombineEnvironmentSecretsWithOneSSHKeyButNotTwo() = runTest {
        val fixture = fixture()
        fixture.createSecret("environment")
        repeat(2) { index ->
            val key =
                fixture.repository.generateSshKey(
                    SshKeyAlgorithm.ED25519,
                    "key-$index@example",
                )
            fixture.repository.createSshSecret("ssh-$index", "", key)
        }

        assertTrue(
            fixture.resolver
                .resolve(
                    listOf("environment", "ssh-0"),
                    includeValues = true,
                )
                .values is RequestedSecretsResult.Available
        )
        assertEquals(
            RequestedSecretsResult.MultipleSshKeys,
            fixture.resolver
                .resolve(
                    listOf("ssh-0", "ssh-1"),
                    includeValues = true,
                )
                .values,
        )
    }

    @Test
    fun storesOneEncryptedRowForEachSecretEnvironmentVariable() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("aws-read-only")

        fixture.createVariable(secretId, "AWS_ACCESS_KEY_ID", "AKIAEXAMPLE", sensitive = true)
        fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", sensitive = false)

        val rows = fixture.dao.getEnvironmentVariables()
        assertEquals(2, rows.size)
        assertEquals(
            setOf(VaultKeyPurpose.SECRET_VALUES.storedName),
            rows
                .map { variable ->
                    fixture.encryptionMetadata.getKey(variable.encryptedValue.keyId)?.purpose
                }
                .toSet(),
        )
        assertTrue(rows.all { it.secretId == secretId })
        assertFalse(
            rows[0].encryptedValue.ciphertext.contentEquals("AKIAEXAMPLE".encodeToByteArray())
        )
        assertFalse(
            rows[1].encryptedValue.ciphertext.contentEquals("eu-west-1".encodeToByteArray())
        )

        val secret = fixture.repository.observeSecret(secretId).first()
        checkNotNull(secret)
        assertEquals(2, secret.environmentVariables.size)
        assertTrue(secret.environmentVariables.all { it.valueAvailable })
    }

    @Test
    fun bindsCiphertextToItsEnvironmentVariableMetadata() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("aws-read-only")
        val variableId =
            fixture.createVariable(
                secretId,
                "AWS_SECRET_ACCESS_KEY",
                "secret-value",
                sensitive = true,
            )

        fixture.replaceVariable(
            fixture.dao.getEnvironmentVariables().single().copy(name = "AWS_ACCESS_KEY_ID")
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
    fun sensitiveEnvironmentValuesRequireExplicitProtectedAccess() = runTest {
        val fixture = fixture()
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
    fun creatingANonsensitiveEnvironmentValueRequiresExplicitAuthorization() = runTest {
        val fixture = fixture()
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
        assertTrue(fixture.dao.getEnvironmentVariables().isEmpty())

        assertTrue(
            fixture.repository.createEnvironmentVariable(
                secretId = secretId,
                name = "AWS_REGION",
                value = "eu-west-1",
                sensitive = false,
                nonSensitiveCreationAuthorized = true,
            ) is CreateEnvironmentVariableResult.Created
        )
    }

    @Test
    fun reducingEnvironmentValueSensitivityRequiresExplicitAuthorization() = runTest {
        val fixture = fixture()
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
        assertTrue(fixture.dao.getEnvironmentVariables().single().sensitive)

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
        assertFalse(fixture.dao.getEnvironmentVariables().single().sensitive)
    }

    @Test
    fun legitimateMetadataChangesReencryptWithoutChangingTheValueTimestamp() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("aws-read-only")
        val variableId = fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", false)
        val before = fixture.dao.getEnvironmentVariables().single()
        val secretUpdatedAt = fixture.dao.getSecrets().single().updatedAt

        val result =
            fixture.repository.saveEnvironmentVariable(
                id = variableId,
                name = "AWS_DEFAULT_REGION",
                sensitive = true,
                replacementValue = null,
                sensitivityReductionAuthorized = true,
            )

        assertEquals(SaveEnvironmentVariableResult.SAVED, result)
        val after = fixture.dao.getEnvironmentVariables().single()
        assertEquals(before.valueUpdatedAt, after.valueUpdatedAt)
        assertNotEquals(secretUpdatedAt, fixture.dao.getSecrets().single().updatedAt)
        assertFalse(before.encryptedValue.ciphertext.contentEquals(after.encryptedValue.ciphertext))
        val value =
            fixture.repository.readEnvironmentVariableValue(
                variableId,
                sensitiveAccessAuthorized = true,
            )
        assertTrue(value is EnvironmentVariableValue.Available)
        assertEquals("eu-west-1", (value as EnvironmentVariableValue.Available).value)
    }

    @Test
    fun restoredRowsStayInTheirSecretsUntilTheirValuesAreReentered() = runTest {
        val original = fixture(keyId = "original-key")
        val secretId = original.createSecret("cloudflare-read-only")
        val variableId = original.createVariable(secretId, "CF_TOKEN", "old-token", true)
        val originalRow = original.dao.getEnvironmentVariables().single()

        val replacementKeyStore = InMemoryEncryptionKeyStore()
        val replacementIds = ArrayDeque(listOf("replacement-secret-key", "replacement-device-key"))
        val replacementManager =
            VaultKeyManager(
                dao = original.encryptionMetadata,
                keyStore = replacementKeyStore,
                newKeyId = { replacementIds.removeFirst() },
                currentTimeMillis = { 500L },
            )
        val restoredRepository =
            SecretRepository(
                dao = original.dao,
                keyManager = replacementManager,
                encryption = AesGcmEncryption(replacementKeyStore),
                audit = NoOpAuditSink,
                writeTransaction = RoomWriteTransaction(original.database),
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

        val replacementRow = original.dao.getEnvironmentVariables().single()
        assertEquals(originalRow.id, replacementRow.id)
        assertEquals("replacement-secret-key", replacementRow.encryptedValue.keyId)
        val replacementValue =
            restoredRepository.readEnvironmentVariableValue(
                variableId,
                sensitiveAccessAuthorized = true,
            )
        assertTrue(replacementValue is EnvironmentVariableValue.Available)
        assertEquals("new-token", (replacementValue as EnvironmentVariableValue.Available).value)
    }

    @Test
    fun requestedSecretsMergeEqualBindingsWithoutExposingMetadataValues() = runTest {
        val fixture = fixture()
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

        val result =
            fixture.resolver
                .resolve(
                    listOf("first", "second"),
                    includeValues = true,
                )
                .values
        check(result is RequestedSecretsResult.Available)
        assertEquals(
            mapOf(
                "first" to
                    SecretValues.Environment(
                        "",
                        mapOf("FIRST_REGION" to "eu-west-1", "SHARED_TOKEN" to "same-value"),
                    ),
                "second" to
                    SecretValues.Environment(
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
    fun requestedSecretsRejectConflictingBindingsAtomically() = runTest {
        val fixture = fixture()
        val first = fixture.createSecret("first")
        val second = fixture.createSecret("second")
        fixture.createVariable(first, "TOKEN", "first-value", true)
        fixture.createVariable(second, "TOKEN", "second-value", true)

        assertEquals(
            RequestedSecretsResult.ConflictingVariable("TOKEN"),
            fixture.resolver
                .resolve(
                    listOf("first", "second"),
                    includeValues = true,
                )
                .values,
        )
        assertEquals(
            RequestedSecretsResult.MissingSecrets(listOf("missing")),
            fixture.resolver.resolve(listOf("missing"), includeValues = true).values,
        )
    }

    @Test
    fun environmentSelectionLimitsValuesMetadataAndSensitivity() = runTest {
        val fixture = fixture()
        val secret = fixture.createSecret("github")
        fixture.createVariable(secret, "GH_HOST", "github.com", false)
        fixture.createVariable(secret, "GH_TOKEN", "secret", true)
        fixture.createVariable(secret, "UNRELATED", "hidden", true)

        val onlyHost = mapOf("github" to EnvironmentVariableSelection(only = setOf("GH_HOST")))
        val description =
            fixture.repository.describeRequestedSecrets(
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
            description.reviewMetadata
                .single()
                .environmentVariables
                .single { it.name == "GH_HOST" }
                .destination,
        )
        assertEquals(
            EnvironmentVariableReviewDestination.Omitted,
            description.reviewMetadata
                .single()
                .environmentVariables
                .single { it.name == "GH_TOKEN" }
                .destination,
        )
        assertFalse(description.containsSensitiveMaterial)

        val requested =
            fixture.resolver
                .resolve(
                    listOf("github"),
                    onlyHost,
                    includeValues = true,
                )
                .values
        check(requested is RequestedSecretsResult.Available)
        assertEquals(
            mapOf("GH_HOST" to "github.com"),
            (requested.secrets.getValue("github") as SecretValues.Environment).environment,
        )

        val omitted =
            fixture.resolver
                .resolve(
                    listOf("github"),
                    mapOf(
                        "github" to EnvironmentVariableSelection(omit = setOf("GH_TOKEN", "ABSENT"))
                    ),
                    includeValues = true,
                )
                .values
        check(omitted is RequestedSecretsResult.Available)
        assertEquals(
            mapOf("GH_HOST" to "github.com", "UNRELATED" to "hidden"),
            (omitted.secrets.getValue("github") as SecretValues.Environment).environment,
        )

        // An explicit empty selection must release nothing, rather than behave like no selector.
        val none =
            fixture.resolver
                .resolve(
                    listOf("github"),
                    mapOf("github" to EnvironmentVariableSelection(only = emptySet())),
                    includeValues = true,
                )
                .values
        check(none is RequestedSecretsResult.Available)
        assertTrue(
            (none.secrets.getValue("github") as SecretValues.Environment).environment.isEmpty()
        )
    }

    @Test
    fun reviewContextIncludesOmittedNonsensitiveValuesWithoutMakingThemReleasable() = runTest {
        val fixture = fixture()
        val secret = fixture.createSecret("database")
        fixture.createVariable(secret, "PGPASSWORD", "secret", true)
        val hostId =
            fixture.createVariable(
                secret,
                "PGHOST",
                "production-db.example.com",
                false,
            )
        val selection =
            mapOf("database" to EnvironmentVariableSelection(only = setOf("PGPASSWORD")))

        val resolved =
            fixture.resolver.resolve(
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
            resolved.description.reviewMetadata
                .single()
                .environmentVariables
                .single { it.name == "PGHOST" }
                .destination,
        )

        val host = fixture.dao.getEnvironmentVariables().single { it.id == hostId }
        fixture.replaceVariable(
            host.copy(encryptedValue = host.encryptedValue.copy(ciphertext = byteArrayOf(1)))
        )
        val withoutOptionalContext =
            fixture.resolver.resolve(
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
    fun environmentSelectionRejectsMissingExactVariablesAndSSHOptions() = runTest {
        val fixture = fixture()
        val environment = fixture.createSecret("environment")
        fixture.createVariable(environment, "TOKEN", "secret", true)
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "git@example")
        fixture.repository.createSshSecret("git-signing", "", key)

        assertEquals(
            RequestedSecretsResult.MissingEnvironmentVariables(
                secretName = "environment",
                names = listOf("MISSING"),
            ),
            fixture.resolver
                .resolve(
                    listOf("environment"),
                    mapOf(
                        "environment" to
                            EnvironmentVariableSelection(only = setOf("TOKEN", "MISSING"))
                    ),
                    includeValues = true,
                )
                .values,
        )
        assertEquals(
            RequestedSecretsResult.EnvironmentOptionsForSshSecret("git-signing"),
            fixture.resolver
                .resolve(
                    listOf("git-signing"),
                    mapOf("git-signing" to EnvironmentVariableSelection(omit = setOf("TOKEN"))),
                    includeValues = true,
                )
                .values,
        )
    }

    @Test
    fun environmentRenamingIsRecordedAndDetectsDeliverednameConflicts() = runTest {
        val fixture = fixture()
        val first = fixture.createSecret("first")
        val second = fixture.createSecret("second")
        fixture.createVariable(first, "FIRST_TOKEN", "one", true)
        fixture.createVariable(second, "TOKEN", "two", true)
        val delivery =
            mapOf("first" to EnvironmentVariableSelection(rename = mapOf("FIRST_TOKEN" to "TOKEN")))

        val description = fixture.repository.describeRequestedSecrets(listOf("first"), delivery)
        assertEquals(
            mapOf("FIRST_TOKEN" to "TOKEN"),
            description.secrets.single().environmentVariableRename,
        )
        assertEquals(
            RequestedSecretsResult.ConflictingVariable("TOKEN"),
            fixture.resolver
                .resolve(
                    listOf("first", "second"),
                    delivery,
                    includeValues = true,
                )
                .values,
        )
        assertEquals(
            RequestedSecretsResult.MissingEnvironmentVariables("first", listOf("ABSENT")),
            fixture.resolver
                .resolve(
                    listOf("first"),
                    mapOf(
                        "first" to EnvironmentVariableSelection(rename = mapOf("ABSENT" to "TOKEN"))
                    ),
                    includeValues = true,
                )
                .values,
        )
    }

    @Test
    fun standardInputDeliveryIsRecordedAndExcludedFromEnvironmentConflicts() = runTest {
        val fixture = fixture()
        val input = fixture.createSecret("input")
        val environment = fixture.createSecret("environment")
        fixture.createVariable(input, "TOKEN", "stdin-value", true)
        fixture.createVariable(environment, "TOKEN", "environment-value", true)
        val delivery = mapOf("input" to EnvironmentVariableSelection(stdin = "TOKEN"))

        val description = fixture.repository.describeRequestedSecrets(listOf("input"), delivery)
        assertEquals("TOKEN", description.secrets.single().environmentVariableStdin)
        assertEquals(
            EnvironmentVariableReviewDestination.StandardInput,
            description.reviewMetadata
                .single()
                .environmentVariables
                .single { it.name == "TOKEN" }
                .destination,
        )
        val requested =
            fixture.resolver
                .resolve(
                    listOf("input", "environment"),
                    delivery,
                    includeValues = true,
                )
                .values
        check(requested is RequestedSecretsResult.Available)
        assertEquals(
            mapOf("TOKEN" to "stdin-value"),
            (requested.secrets.getValue("input") as SecretValues.Environment).environment,
        )
        assertEquals(
            RequestedSecretsResult.MissingEnvironmentVariables("input", listOf("MISSING")),
            fixture.resolver
                .resolve(
                    listOf("input"),
                    mapOf("input" to EnvironmentVariableSelection(stdin = "MISSING")),
                    includeValues = true,
                )
                .values,
        )
    }

    @Test
    fun onlySensitiveEnvironmentValuesRequireApproval() = runTest {
        val fixture = fixture()
        val public = fixture.createSecret("public-context")
        fixture.createVariable(public, "AWS_REGION", "eu-north-1", false)
        val sensitive = fixture.createSecret("credentials")
        fixture.createVariable(sensitive, "AWS_TOKEN", "secret", true)
        val key = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "git@example")
        fixture.repository.createSshSecret("git-signing", "", key)

        assertFalse(
            fixture.repository
                .describeRequestedSecrets(listOf("public-context"))
                .containsSensitiveMaterial
        )
        assertFalse(
            fixture.repository
                .describeRequestedSecrets(listOf("git-signing"))
                .containsSensitiveMaterial
        )
        assertTrue(
            fixture.repository
                .describeRequestedSecrets(listOf("public-context", "credentials"))
                .containsSensitiveMaterial
        )
    }

    @Test
    fun SSHSigningRequiresTheSamePublicKeyApprovedForTheInvocation() = runTest {
        val fixture = fixture()
        val first = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "first@example")
        val created = fixture.repository.createSshSecret("git-signing", "", first)
        check(created is CreateSecretResult.Created)

        val signed =
            fixture.repository.signGitMessage(
                secretName = "git-signing",
                expectedPublicKey = first.publicKeyLine,
                message = "commit object".encodeToByteArray(),
            )
        assertTrue(signed is SignatureResult.Signed)

        val second = fixture.repository.generateSshKey(SshKeyAlgorithm.ED25519, "second@example")
        fixture.repository.replaceSshKey(created.id, second)
        assertEquals(
            SignatureResult.KeyChanged,
            fixture.repository.signGitMessage(
                secretName = "git-signing",
                expectedPublicKey = first.publicKeyLine,
                message = "commit object".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun uploadedSecretsMakeNewEnvironmentVariablesSensitiveByDefault() = runTest {
        val fixture = fixture()

        val preparation =
            fixture.uploads.prepareEnvironmentSecretUpload(
                EnvironmentSecretUpload(
                    mode = SecretUploadMode.CREATE,
                    name = "client-suggested-name",
                    descriptionProvided = true,
                    description = "Cloudflare production account",
                    variables = mapOf("CF_ACCOUNT_ID" to "account", "CF_TOKEN" to "token"),
                ),
                approvedName = "cloudflare-read-only",
                target = null,
            )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        check(result is ApplySecretUploadResult.Applied)
        assertEquals(
            ApplySecretUploadResult.Invalid("A secret named cloudflare-read-only already exists."),
            fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload),
        )
        val secret = fixture.repository.observeSecret(result.secretId).first()
        checkNotNull(secret)
        assertEquals("cloudflare-read-only", secret.name)
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
    fun replaceUploadsPreserveEnvironmentVariableSensitivity() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("aws-read-only")
        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.ASK_AI)
        fixture.repository.saveInstructions(secretId, "Permit read-only AWS operations.")
        val region =
            fixture.repository.createEnvironmentVariable(
                secretId = secretId,
                name = "AWS_REGION",
                value = "eu-west-1",
                sensitive = false,
                nonSensitiveCreationAuthorized = true,
            )
        check(region is CreateEnvironmentVariableResult.Created)
        fixture.createVariable(secretId, "OLD_VARIABLE", "old", true)

        val preparation =
            fixture.uploads.prepareEnvironmentSecretUpload(
                EnvironmentSecretUpload(
                    mode = SecretUploadMode.REPLACE,
                    name = "aws-read-only",
                    descriptionProvided = false,
                    description = null,
                    variables =
                        mapOf(
                            "AWS_REGION" to "eu-north-1",
                            "AWS_ACCESS_KEY_ID" to "new-key",
                        ),
                ),
                approvedName = "ignored-for-existing-secret",
                target =
                    SecretUploadTarget(
                        secretId,
                        checkNotNull(fixture.dao.getSecret(secretId)).revision,
                    ),
            )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertTrue(result is ApplySecretUploadResult.Applied)
        val secret = fixture.repository.observeSecret(secretId).first()
        checkNotNull(secret)
        assertEquals(
            listOf("AWS_ACCESS_KEY_ID", "AWS_REGION"),
            secret.environmentVariables.map { it.name },
        )
        val updatedRegion = secret.environmentVariables.single { it.name == "AWS_REGION" }
        assertFalse(updatedRegion.sensitive)
        assertTrue(secret.environmentVariables.single { it.name == "AWS_ACCESS_KEY_ID" }.sensitive)
        assertEquals(SecretApprovalMode.ASK_AI, secret.approvalMode)
        assertEquals("Permit read-only AWS operations.", secret.instructions)
    }

    @Test
    fun clientApprovalOverrideChangesTheEffectiveModeWithoutReplacingTheDefault() = runTest {
        val fixture = fixture()
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

        val overridden =
            fixture.repository
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
        val inherited =
            fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
        assertEquals(SecretApprovalMode.ASK_AI, inherited.mode)
    }

    @Test
    fun reselectingAnExplicitClientApprovalOverrideChangesNothing() = runTest {
        val audit = RecordingAuditSink()
        val fixture = fixture(audit = audit)
        val secretId = fixture.createSecret("github")
        fixture.repository.setClientApprovalOverride(
            secretId,
            "workstation",
            SecretApprovalMode.ASK_AI,
        )
        fixture.repository.allowTemporaryAccess(
            policies =
                fixture.repository.approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                ),
            clientId = "workstation",
            operation = TemporaryAccessOperation.INVOCATION,
            expiresAt = 10_000,
        )
        val overrideRows = fixture.overrides().toList()
        val revision = checkNotNull(fixture.dao.getSecret(secretId)).revision
        val grants = fixture.grants().toList()
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

        assertEquals(overrideRows, fixture.overrides())
        assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertEquals(grants, fixture.grants())
        assertEquals(auditRecords, audit.records)
    }

    @Test
    fun reselectingInheritedClientApprovalChangesNothing() = runTest {
        val audit = RecordingAuditSink()
        val fixture = fixture(audit = audit)
        val secretId = fixture.createSecret("github")
        fixture.repository.allowTemporaryAccess(
            policies =
                fixture.repository.approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                ),
            clientId = "workstation",
            operation = TemporaryAccessOperation.INVOCATION,
            expiresAt = 10_000,
        )
        val overrideRows = fixture.overrides().toList()
        val revision = checkNotNull(fixture.dao.getSecret(secretId)).revision
        val grants = fixture.grants().toList()
        val auditRecords = audit.records.toList()
        assertTrue(overrideRows.isEmpty())
        assertEquals(1, grants.size)

        assertEquals(
            SaveSecretResult.SAVED,
            fixture.repository.setClientApprovalOverride(secretId, "workstation", null),
        )

        assertEquals(overrideRows, fixture.overrides())
        assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertEquals(grants, fixture.grants())
        assertEquals(auditRecords, audit.records)
    }

    @Test
    fun explicitAndInheritedFormsOfTheSameClientModePreserveTemporaryAccess() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("github")
        fixture.repository.allowTemporaryAccess(
            policies =
                fixture.repository.approvalPoliciesForNames(
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

        assertEquals(1, fixture.grants().size)
        assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)

        fixture.repository.setClientApprovalOverride(secretId, "workstation", null)

        assertEquals(1, fixture.grants().size)
        assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
    }

    @Test
    fun changingTheDefaultModeEndsOnlyAccessInheritedFromThatDefault() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("github")
        fixture.repository.setClientApprovalOverride(
            secretId,
            "pinned-client",
            SecretApprovalMode.ASK_ME,
        )
        listOf("inheriting-client", "pinned-client").forEach { clientId ->
            fixture.repository.allowTemporaryAccess(
                policies =
                    fixture.repository.approvalPoliciesForNames(
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
            fixture.grants().map { it.clientId },
        )
        assertEquals(revision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertEquals(
            SecretApprovalMode.ASK_AI,
            fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "inheriting-client",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
                .mode,
        )
        assertEquals(
            SecretApprovalMode.ASK_ME,
            fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "pinned-client",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
                .mode,
        )
    }

    @Test
    fun clientApprovalOverrideChangesTouchTheSecretAndEndScopedTemporaryAccess() = runTest {
        val audit = RecordingAuditSink()
        val fixture = fixture(audit = audit)
        val secretId = fixture.createSecret("github")
        fixture.repository.allowTemporaryAccess(
            policies =
                fixture.repository.approvalPoliciesForNames(
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
        assertEquals(1, fixture.grants().size)

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
                )
            ),
            fixture.overrides(),
        )
        assertEquals(initialRevision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertTrue(fixture.grants().isEmpty())
        assertEquals(initialAuditCount + 1, audit.records.size)
        assertEquals(
            AuditEventType.CLIENT_APPROVAL_OVERRIDE_CHANGED,
            audit.records.last().type,
        )

        fixture.repository.allowTemporaryAccess(
            policies =
                fixture.repository.approvalPoliciesForNames(
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
        assertEquals(1, fixture.grants().size)

        fixture.repository.setClientApprovalOverride(secretId, "workstation", null)

        assertTrue(fixture.overrides().isEmpty())
        assertEquals(overriddenRevision, checkNotNull(fixture.dao.getSecret(secretId)).revision)
        assertTrue(fixture.grants().isEmpty())
        assertEquals(overriddenAuditCount + 1, audit.records.size)
        assertEquals(
            AuditEventType.CLIENT_APPROVAL_OVERRIDE_CHANGED,
            audit.records.last().type,
        )
    }

    @Test
    fun temporaryAccessIsScopedToClientSecretAndOperation() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("github")
        val otherSecretId = fixture.createSecret("production")
        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.ASK_ME)

        fixture.repository.allowTemporaryAccess(
            policies =
                fixture.repository.approvalPoliciesForNames(
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
            fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
                .temporaryAccessExpiresAt,
        )
        assertNull(
            fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.GIT_SIGN,
                )
                .single()
                .temporaryAccessExpiresAt
        )
        assertNull(
            fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "other-client",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
                .temporaryAccessExpiresAt
        )
        assertNull(
            fixture.repository
                .approvalPoliciesForNames(
                    listOf("production"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single { it.secretId == otherSecretId }
                .temporaryAccessExpiresAt
        )
    }

    @Test
    fun temporaryAccessAuditCancellationPropagates() = runTest {
        val cancellation = CancellationException("cancelled")
        var cancelAudit = false
        val fixture =
            fixture(
                audit =
                    object : AuditSink {
                        override suspend fun record(record: AuditRecord) {
                            if (cancelAudit) throw cancellation
                        }

                        override suspend fun append(records: List<AuditRecord>, occurredAt: Long) {
                            if (cancelAudit) throw cancellation
                        }
                    }
            )
        val secretId = fixture.createSecret("github")
        cancelAudit = true
        val policies =
            fixture.repository.approvalPoliciesForNames(
                listOf("github"),
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            )

        val thrown =
            try {
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
    fun temporaryAccessCannotBeExtendedAndCanBeEndedExplicitly() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("github")
        val initialPolicy =
            fixture.repository.approvalPoliciesForNames(
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
            )
        )

        val activePolicy =
            fixture.repository.approvalPoliciesForNames(
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
            )
        )
        assertEquals(10_000L, fixture.grants().single().expiresAt)

        assertTrue(
            fixture.repository.endTemporaryAccess(
                secretId,
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            )
        )
        assertTrue(fixture.grants().isEmpty())
        assertFalse(
            fixture.repository.endTemporaryAccess(
                secretId,
                "workstation",
                TemporaryAccessOperation.INVOCATION,
            )
        )
    }

    @Test
    fun temporaryAccessIsNotInsertedAfterSecretMaterialChanges() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("github")
        val variableId = fixture.createVariable(secretId, "GITHUB_TOKEN", "old-token", true)
        val stalePolicy =
            fixture.repository.approvalPoliciesForNames(
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
        val inserted =
            fixture.repository.allowTemporaryAccess(
                policies = stalePolicy,
                clientId = "workstation",
                operation = TemporaryAccessOperation.INVOCATION,
                expiresAt = 10_000,
            )

        assertFalse(inserted)
        assertTrue(fixture.grants().isEmpty())
    }

    @Test
    fun renamingASecretEndsItsTemporaryAccess() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("staging")
        fixture.repository.allowTemporaryAccess(
            policies =
                fixture.repository.approvalPoliciesForNames(
                    listOf("staging"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                ),
            clientId = "workstation",
            operation = TemporaryAccessOperation.INVOCATION,
            expiresAt = 10_000,
        )

        fixture.repository.saveSecret(secretId, "production", "")

        assertTrue(fixture.grants().isEmpty())
    }

    @Test
    fun approvalAndSecretChangesEndTemporaryAccess() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("github")
        val variableId = fixture.createVariable(secretId, "GITHUB_TOKEN", "token", true)
        fixture.repository.saveApprovalMode(secretId, SecretApprovalMode.ASK_ME)
        fixture.repository.allowTemporaryAccess(
            policies =
                fixture.repository.approvalPoliciesForNames(
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
            fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
                .temporaryAccessExpiresAt
        )

        fixture.repository.allowTemporaryAccess(
            policies =
                fixture.repository.approvalPoliciesForNames(
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
            fixture.repository
                .approvalPoliciesForNames(
                    listOf("github"),
                    "workstation",
                    TemporaryAccessOperation.INVOCATION,
                )
                .single()
                .temporaryAccessExpiresAt
        )
    }

    @Test
    fun updateUploadsLeaveOmittedEnvironmentVariablesUntouched() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("aws-read-only")
        fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", false)
        val tokenId = fixture.createVariable(secretId, "AWS_TOKEN", "old-token", true)

        val upload =
            EnvironmentSecretUpload(
                mode = SecretUploadMode.UPDATE,
                name = "aws-read-only",
                descriptionProvided = false,
                description = null,
                variables = mapOf("AWS_TOKEN" to "new-token"),
            )
        val description = fixture.repository.describeEnvironmentSecretUpload(upload)
        check(description is EnvironmentSecretUploadResult.Valid)
        assertEquals(listOf("AWS_REGION"), description.summary.unchangedVariables)

        val preparation =
            fixture.uploads.prepareEnvironmentSecretUpload(
                upload,
                approvedName = "ignored-for-existing-secret",
                target = description.summary.target,
            )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertTrue(result is ApplySecretUploadResult.Applied)
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
    fun updateUploadPreservesADescriptionEditedAfterReview() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("production")
        fixture.createVariable(secretId, "TOKEN", "old", true)
        val upload =
            EnvironmentSecretUpload(
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

        val preparation =
            fixture.uploads.prepareEnvironmentSecretUpload(
                upload,
                approvedName = "ignored",
                target = plan.summary.target,
            )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertTrue(result is ApplySecretUploadResult.Applied)
        assertEquals(
            "Edited on the phone",
            fixture.repository.observeSecret(secretId).first()?.description,
        )
    }

    @Test
    fun anUploadCannotRetargetAfterItsSecretIsRenamed() = runTest {
        val fixture = fixture()
        val originalId = fixture.createSecret("production")
        val originalVariable = fixture.createVariable(originalId, "TOKEN", "old", true)
        val upload =
            EnvironmentSecretUpload(
                mode = SecretUploadMode.UPDATE,
                name = "production",
                descriptionProvided = false,
                description = null,
                variables = mapOf("TOKEN" to "uploaded"),
            )
        val plan = fixture.repository.describeEnvironmentSecretUpload(upload)
        check(plan is EnvironmentSecretUploadResult.Valid)
        val preparation =
            fixture.uploads.prepareEnvironmentSecretUpload(
                upload,
                approvedName = "ignored",
                target = plan.summary.target,
            )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)

        assertEquals(
            SaveSecretResult.SAVED,
            fixture.repository.saveSecret(originalId, "archive", ""),
        )
        val replacementId = fixture.createSecret("production")
        val replacementVariable =
            fixture.createVariable(replacementId, "TOKEN", "replacement", true)

        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertEquals(
            ApplySecretUploadResult.Invalid(
                "The target secret changed before the upload was approved."
            ),
            result,
        )
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
    fun anAuthorizationRevisionInvalidatesAPendingUpload() = runTest {
        val fixture = fixture()
        val secretId = fixture.createSecret("production")
        fixture.createVariable(secretId, "TOKEN", "old", true)
        val upload =
            EnvironmentSecretUpload(
                mode = SecretUploadMode.UPDATE,
                name = "production",
                descriptionProvided = false,
                description = null,
                variables = mapOf("TOKEN" to "uploaded"),
            )
        val plan = fixture.repository.describeEnvironmentSecretUpload(upload)
        check(plan is EnvironmentSecretUploadResult.Valid)
        val preparation =
            fixture.uploads.prepareEnvironmentSecretUpload(
                upload,
                approvedName = "ignored",
                target = plan.summary.target,
            )
        check(preparation is EnvironmentSecretUploadPreparation.Ready)
        fixture.repository.saveInstructions(secretId, "Only for production deploys")

        val result = fixture.uploads.applyPreparedEnvironmentSecretUpload(preparation.upload)

        assertEquals(
            ApplySecretUploadResult.Invalid(
                "The target secret changed before the upload was approved."
            ),
            result,
        )
    }

    private suspend fun fixture(
        keyId: String = "storage-key",
        audit: AuditSink = NoOpAuditSink,
    ): Fixture = Fixture(keyId, audit).also { it.seedClients() }

    private inner class Fixture(
        keyId: String = "storage-key",
        audit: AuditSink = NoOpAuditSink,
    ) {
        val database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AgentknockDatabase::class.java,
                )
                .build()
                .also { databases += it }
        val encryptionMetadata = database.vaultKeyDao()
        val keyStore = InMemoryEncryptionKeyStore()
        var beforeSshCommentUpdate: (suspend () -> Unit)? = null
        val dao =
            object : SecretDao by database.secretDao() {
                override suspend fun updateSshKeyComment(
                    secretId: String,
                    comment: String,
                    secretUpdatedAt: Long,
                ): Boolean {
                    beforeSshCommentUpdate?.also {
                        beforeSshCommentUpdate = null
                        it()
                    }
                    return database
                        .secretDao()
                        .updateSshKeyComment(secretId, comment, secretUpdatedAt)
                }
            }

        suspend fun seedClients() {
            database
                .deviceIdentityDao()
                .insertIdentity(
                    DeviceIdentityEntity(
                        id = "device",
                        role = "active",
                        address = "amber-river-maple",
                        deviceId = "device-id",
                        createdAt = 1,
                    )
                )
            for (id in
                listOf("workstation", "other-client", "inheriting-client", "pinned-client")) {
                database
                    .requestDao()
                    .insertClient(
                        ClientEntity(
                            clientId = id,
                            deviceIdentityId = "device",
                            name = id,
                            instructions = "",
                            desiredRelayClientState = null,
                            relayClientState = "active",
                            clientSoftwareJson = null,
                            platform = null,
                            architecture = null,
                            hostname = null,
                            machineId = null,
                            osVersion = null,
                            pairedAt = 1,
                            lastSeenAt = null,
                        )
                    )
            }
        }

        suspend fun overrides() =
            dao.getSecrets().flatMap { dao.observeClientApprovalOverrides(it.id).first() }

        suspend fun grants() = dao.observeTemporaryAccessGrants().first()

        // Deliberately bypass repository validation to exercise persisted corruption and concurrent
        // edits.
        suspend fun replaceVariable(row: EnvironmentVariableEntity) {
            dao.updateEnvironmentVariableMaterialRow(
                row.id,
                row.secretId,
                row.name,
                row.sensitive,
                row.encryptedValue.formatVersion,
                row.encryptedValue.keyId,
                row.encryptedValue.nonce,
                row.encryptedValue.ciphertext,
                row.valueUpdatedAt,
            )
        }

        suspend fun replaceSshKey(row: SshKeyEntity) {
            val encrypted = row.encryptedPrivateKey
            if (encryptionMetadata.getKey(encrypted.keyId) == null) {
                val existing =
                    checkNotNull(
                        encryptionMetadata
                            .getActiveKeys(VaultKeyPurpose.SECRET_VALUES.storedName)
                            .firstOrNull()
                    )
                encryptionMetadata.insertKey(existing.copy(id = encrypted.keyId, active = false))
            }
            dao.updateSshKeyMaterialRow(
                row.secretId,
                row.algorithm,
                row.publicKey,
                row.comment,
                encrypted.formatVersion,
                encrypted.keyId,
                encrypted.nonce,
                encrypted.ciphertext,
            )
        }

        private var id = 0
        private var time = 100L
        private val keyIds = ArrayDeque(listOf("$keyId-secret", "$keyId-device"))
        private val keyManager =
            VaultKeyManager(
                dao = encryptionMetadata,
                keyStore = keyStore,
                newKeyId = { keyIds.removeFirst() },
                currentTimeMillis = { nextTime() },
            )
        private val encryption = AesGcmEncryption(keyStore)
        private val sshKeys = SshKeyCodec()
        private val material =
            SecretMaterialStore(
                keyManager = keyManager,
                encryption = encryption,
                sshKeys = sshKeys,
                cryptographyDispatcher = Dispatchers.IO,
            )
        val repository =
            SecretRepository(
                dao = dao,
                keyManager = keyManager,
                encryption = encryption,
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
                sshKeys = sshKeys,
                newId = { nextId() },
                currentTimeMillis = { nextTime() },
            )
        val uploads =
            SecretUploads(
                dao = dao,
                material = material,
                newId = { nextId() },
                currentTimeMillis = { nextTime() },
            )
        val resolver =
            SecretResolver(
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
            val result =
                repository.createEnvironmentVariable(
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
