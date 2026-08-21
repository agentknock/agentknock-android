package dev.agentknock.storage.secret

import dev.agentknock.protocol.SecretUploadMode
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.FakeEncryptionKeyStore
import dev.agentknock.storage.crypto.FakeLocalEncryptionDao
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRepositoryTest {
    @Test
    fun `stores one encrypted row for each secret environment variable`() = runTest {
        val fixture = Fixture()
        val secretId = fixture.createSecret("aws-read-only")

        fixture.createVariable(secretId, "AWS_ACCESS_KEY_ID", "AKIAEXAMPLE", sensitive = true)
        fixture.createVariable(secretId, "AWS_REGION", "eu-west-1", sensitive = false)

        val rows = fixture.dao.variables.value
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.secretId == secretId })
        assertFalse(rows[0].ciphertext.contentEquals("AKIAEXAMPLE".encodeToByteArray()))
        assertFalse(rows[1].ciphertext.contentEquals("eu-west-1".encodeToByteArray()))

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
        val replacementManager = LocalEncryptionKeyManager(
            dao = original.encryptionMetadata,
            keyStore = replacementKeyStore,
            newKeyId = { "replacement-key" },
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
        assertEquals("replacement-key", replacementRow.encryptionKeyId)
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
        assertEquals(
            listOf("FIRST_REGION", "SHARED_TOKEN"),
            description.secrets.first().environmentVariableNames,
        )

        val result = fixture.repository.requestedSecrets(listOf("first", "second"))
        check(result is RequestedSecretsResult.Available)
        assertEquals(
            mapOf(
                "first" to SecretValues(
                    "",
                    mapOf("FIRST_REGION" to "eu-west-1", "SHARED_TOKEN" to "same-value"),
                ),
                "second" to SecretValues("", mapOf("SHARED_TOKEN" to "same-value")),
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
        val encryptionMetadata = FakeLocalEncryptionDao()
        val keyStore = FakeEncryptionKeyStore()
        val dao = FakeSecretDao()
        private var id = 0
        private var time = 100L
        private val keyManager = LocalEncryptionKeyManager(
            dao = encryptionMetadata,
            keyStore = keyStore,
            newKeyId = { keyId },
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
            val result = repository.createSecret(name, "")
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

    override suspend fun getSecrets(): List<SecretEntity> = secrets.value
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SecretEntity::name))

    override suspend fun getEnvironmentVariables(): List<EnvironmentVariableEntity> =
        variables.value.sortedWith(
            compareBy<EnvironmentVariableEntity> { it.secretId }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

    override suspend fun getSecretsByName(names: List<String>): List<SecretEntity> =
        secrets.value.filter { it.name in names }

    override suspend fun getEnvironmentVariablesForSecrets(
        secretIds: List<String>,
    ): List<EnvironmentVariableEntity> = variables.value.filter { it.secretId in secretIds }

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

    override suspend fun deleteSecret(secret: SecretEntity) {
        secrets.value = secrets.value.filterNot { it.id == secret.id }
        variables.value = variables.value.filterNot { it.secretId == secret.id }
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

    override suspend fun deleteEnvironmentVariableRow(variable: EnvironmentVariableEntity) {
        variables.value = variables.value.filterNot { it.id == variable.id }
    }

    override suspend fun touchSecret(secretId: String, updatedAt: Long) {
        secrets.value = secrets.value.map {
            if (it.id == secretId) it.copy(updatedAt = updatedAt) else it
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
}
