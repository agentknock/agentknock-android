package dev.agentknock.storage.secret

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.VaultKeyPurpose
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretDaoTransactionTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var dao: SecretDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        dao = database.secretDao()
        kotlinx.coroutines.runBlocking {
            database.vaultKeyDao().activate(
                VaultKeyEntity(
                    id = KEY_ID,
                    purpose = VaultKeyPurpose.SECRET_VALUES.storedName,
                    active = true,
                    createdAt = 1,
                    backing = "SOFTWARE",
                ),
            )
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun subtypeWritesRequireTheOwningSecretType() = runTest {
        dao.insertSecret(secret("ssh", SecretType.SSH))
        val inserted = dao.insertEnvironmentVariableIfCurrent(variable("ssh"), 1, 2)

        assertFalse(inserted)
        assertNull(dao.getEnvironmentVariable(VARIABLE_ID))

        dao.insertSecret(secret("environment", SecretType.ENVIRONMENT))
        val failed = runCatching {
            dao.insertSshSecret(
                secret("another-environment", SecretType.ENVIRONMENT),
                sshKey("another-environment"),
            )
        }
        assertTrue(failed.isFailure)
        assertNull(dao.getSecret("another-environment"))
        assertNull(dao.getSshKey("another-environment"))
    }

    @Test
    fun staleRevisionCannotOverwriteSecretMaterial() = runTest {
        dao.insertSecret(secret("environment", SecretType.ENVIRONMENT))
        assertTrue(dao.insertEnvironmentVariableIfCurrent(variable("environment"), 1, 2))
        val stored = checkNotNull(dao.getEnvironmentVariable(VARIABLE_ID))

        val staleUpdate = dao.updateEnvironmentVariableIfCurrent(
            stored.copy(
                encryptedValue = encrypted(byteArrayOf(9, 9, 9)),
                valueUpdatedAt = 3,
            ),
            expectedSecretRevision = 1,
            secretUpdatedAt = 3,
        )

        assertFalse(staleUpdate)
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            dao.getEnvironmentVariable(VARIABLE_ID)?.encryptedValue?.ciphertext,
        )
        assertEquals(2L, dao.getSecret("environment")?.revision)
    }

    @Test
    fun deletingASecretCascadesTypedContent() = runTest {
        val secret = secret("ssh", SecretType.SSH)
        dao.insertSshSecret(secret, sshKey(secret.id))

        dao.deleteSecret(secret)

        assertNull(dao.getSecret(secret.id))
        assertNull(dao.getSshKey(secret.id))
    }

    @Test
    fun editingSshDescriptionDoesNotRewriteKeyMaterial() = runTest {
        val secret = secret("ssh", SecretType.SSH)
        val key = sshKey(secret.id)
        dao.insertSshSecret(secret, key)

        assertTrue(dao.updateSecretMetadata(secret.id, secret.name, "New description", 5))

        val stored = checkNotNull(dao.getSshKey(secret.id))
        assertArrayEquals(key.publicKey, stored.publicKey)
        assertArrayEquals(key.encryptedPrivateKey.nonce, stored.encryptedPrivateKey.nonce)
        assertArrayEquals(key.encryptedPrivateKey.ciphertext, stored.encryptedPrivateKey.ciphertext)
    }

    private fun secret(id: String, type: SecretType) = SecretEntity(
        id = id,
        name = id,
        description = "",
        type = type.storedName,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun variable(secretId: String) = EnvironmentVariableEntity(
        id = VARIABLE_ID,
        secretId = secretId,
        name = "TOKEN",
        sensitive = true,
        encryptedValue = encrypted(byteArrayOf(1, 2, 3)),
        valueUpdatedAt = 2,
    )

    private fun sshKey(secretId: String) = SshKeyEntity(
        secretId = secretId,
        algorithm = SshKeyAlgorithm.ED25519.storedName,
        publicKey = ByteArray(32) { 4 },
        comment = "test@example",
        encryptedPrivateKey = encrypted(byteArrayOf(5, 6, 7)),
    )

    private fun encrypted(ciphertext: ByteArray) = EncryptedValue(
        formatVersion = 1,
        keyId = KEY_ID,
        nonce = ByteArray(12) { 8 },
        ciphertext = ciphertext,
    )

    private companion object {
        const val KEY_ID = "secret-values-key"
        const val VARIABLE_ID = "variable"
    }
}
