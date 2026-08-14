package dev.agentknock.storage.profile

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

class ProfileRepositoryTest {
    @Test
    fun `stores one encrypted row for each profile environment variable`() = runTest {
        val fixture = Fixture()
        val profileId = fixture.createProfile("aws-read-only")

        fixture.createVariable(profileId, "AWS_ACCESS_KEY_ID", "AKIAEXAMPLE", sensitive = true)
        fixture.createVariable(profileId, "AWS_REGION", "eu-west-1", sensitive = false)

        val rows = fixture.dao.variables.value
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.profileId == profileId })
        assertFalse(rows[0].ciphertext.contentEquals("AKIAEXAMPLE".encodeToByteArray()))
        assertFalse(rows[1].ciphertext.contentEquals("eu-west-1".encodeToByteArray()))

        val profile = fixture.repository.observeProfile(profileId).first()
        checkNotNull(profile)
        assertEquals(2, profile.environmentVariables.size)
        assertTrue(profile.environmentVariables.all { it.valueAvailable })
    }

    @Test
    fun `binds ciphertext to its environment variable metadata`() = runTest {
        val fixture = Fixture()
        val profileId = fixture.createProfile("aws-read-only")
        val variableId = fixture.createVariable(
            profileId,
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
        val profileId = fixture.createProfile("aws-read-only")
        val variableId = fixture.createVariable(profileId, "AWS_REGION", "eu-west-1", false)
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
    fun `renaming a profile does not re-encrypt its values`() = runTest {
        val fixture = Fixture()
        val profileId = fixture.createProfile("aws-read-only")
        fixture.createVariable(profileId, "AWS_REGION", "eu-west-1", false)
        val before = fixture.dao.variables.value.single()

        assertEquals(
            SaveProfileResult.SAVED,
            fixture.repository.saveProfile(profileId, "aws-reader", "Renamed profile"),
        )

        val after = fixture.dao.variables.value.single()
        assertTrue(before.nonce.contentEquals(after.nonce))
        assertTrue(before.ciphertext.contentEquals(after.ciphertext))
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.valueUpdatedAt, after.valueUpdatedAt)
    }

    @Test
    fun `restored rows stay in their profiles until their values are re-entered`() = runTest {
        val original = Fixture(keyId = "original-key")
        val profileId = original.createProfile("cloudflare-read-only")
        val variableId = original.createVariable(profileId, "CF_TOKEN", "old-token", true)
        val originalRow = original.dao.variables.value.single()

        val replacementKeyStore = FakeEncryptionKeyStore()
        val replacementManager = LocalEncryptionKeyManager(
            dao = original.encryptionMetadata,
            keyStore = replacementKeyStore,
            newKeyId = { "replacement-key" },
            currentTimeMillis = { 500L },
        )
        val restoredRepository = ProfileRepository(
            dao = original.dao,
            keyManager = replacementManager,
            encryption = AesGcmEncryption(replacementKeyStore),
            newId = { error("no new records expected") },
            currentTimeMillis = { 600L },
        )

        replacementManager.initialize()
        val restoredProfile = restoredRepository.observeProfile(profileId).first()
        checkNotNull(restoredProfile)
        assertEquals(1, restoredProfile.environmentVariables.size)
        assertFalse(restoredProfile.environmentVariables.single().valueAvailable)
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

    private class Fixture(keyId: String = "storage-key") {
        val encryptionMetadata = FakeLocalEncryptionDao()
        val keyStore = FakeEncryptionKeyStore()
        val dao = FakeProfileDao()
        private var id = 0
        private var time = 100L
        private val keyManager = LocalEncryptionKeyManager(
            dao = encryptionMetadata,
            keyStore = keyStore,
            newKeyId = { keyId },
            currentTimeMillis = { nextTime() },
        )
        val repository = ProfileRepository(
            dao = dao,
            keyManager = keyManager,
            encryption = AesGcmEncryption(keyStore),
            newId = { "id-${++id}" },
            currentTimeMillis = { nextTime() },
        )

        suspend fun createProfile(name: String): String {
            val result = repository.createProfile(name, "")
            check(result is CreateProfileResult.Created)
            return result.id
        }

        suspend fun createVariable(
            profileId: String,
            name: String,
            value: String,
            sensitive: Boolean,
        ): String {
            val result = repository.createEnvironmentVariable(
                profileId = profileId,
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

private class FakeProfileDao : ProfileDao {
    val profiles = MutableStateFlow<List<ProfileEntity>>(emptyList())
    val variables = MutableStateFlow<List<EnvironmentVariableEntity>>(emptyList())

    override fun observeProfiles(): Flow<List<ProfileSummaryRow>> = combine(
        profiles,
        variables,
    ) { currentProfiles, currentVariables ->
        currentProfiles
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ProfileEntity::name))
            .map { profile ->
                ProfileSummaryRow(
                    id = profile.id,
                    name = profile.name,
                    description = profile.description,
                    createdAt = profile.createdAt,
                    updatedAt = profile.updatedAt,
                    environmentVariableCount = currentVariables.count {
                        it.profileId == profile.id
                    },
                )
            }
    }

    override fun observeProfile(id: String): Flow<ProfileEntity?> =
        profiles.map { all -> all.find { it.id == id } }

    override fun observeEnvironmentVariables(
        profileId: String,
    ): Flow<List<EnvironmentVariableMetadataRow>> = variables.map { all ->
        all.filter { it.profileId == profileId }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EnvironmentVariableEntity::name))
            .map { variable ->
                EnvironmentVariableMetadataRow(
                    id = variable.id,
                    profileId = variable.profileId,
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

    override suspend fun getProfile(id: String): ProfileEntity? = profiles.value.find { it.id == id }

    override suspend fun getEnvironmentVariable(id: String): EnvironmentVariableEntity? =
        variables.value.find { it.id == id }

    override suspend fun profileNameInUse(name: String, excludingId: String): Boolean =
        profiles.value.any { it.name == name && it.id != excludingId }

    override suspend fun environmentVariableNameInUse(
        profileId: String,
        name: String,
        excludingId: String,
    ): Boolean = variables.value.any {
        it.profileId == profileId && it.name == name && it.id != excludingId
    }

    override suspend fun insertProfile(profile: ProfileEntity) {
        check(profiles.value.none { it.id == profile.id || it.name == profile.name })
        profiles.value += profile
    }

    override suspend fun updateProfile(profile: ProfileEntity): Int {
        if (profiles.value.none { it.id == profile.id }) return 0
        profiles.value = profiles.value.map { if (it.id == profile.id) profile else it }
        return 1
    }

    override suspend fun deleteProfile(profile: ProfileEntity) {
        profiles.value = profiles.value.filterNot { it.id == profile.id }
        variables.value = variables.value.filterNot { it.profileId == profile.id }
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

    override suspend fun touchProfile(profileId: String, updatedAt: Long) {
        profiles.value = profiles.value.map {
            if (it.id == profileId) it.copy(updatedAt = updatedAt) else it
        }
    }

    fun directlyReplaceVariable(variable: EnvironmentVariableEntity) {
        variables.value = variables.value.map { if (it.id == variable.id) variable else it }
    }
}
