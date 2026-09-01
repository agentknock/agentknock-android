package dev.agentknock.storage.crypto

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.device.DeviceCredentialEntity
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.request.ClientEntity
import dev.agentknock.storage.request.ClientPskEntity
import dev.agentknock.storage.request.InboxRequestEntity
import dev.agentknock.storage.request.PairingAttemptEntity
import dev.agentknock.storage.request.RequestPskEntity
import dev.agentknock.storage.request.SecretUploadEnvironmentVariableEntity
import dev.agentknock.storage.request.SecretUploadRequestEntity
import dev.agentknock.storage.request.SecretUploadSshKeyEntity
import dev.agentknock.storage.secret.EnvironmentVariableEntity
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.SshKeyEntity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultKeyDaoTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var dao: VaultKeyDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        dao = database.vaultKeyDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun everyCiphertextLocationContributesItsReferencedVaultKey() = runTest {
        val expected = setOf(
            ENVIRONMENT_KEY,
            SSH_KEY,
            UPLOAD_ENVIRONMENT_KEY,
            UPLOAD_SSH_KEY,
            DEVICE_CREDENTIAL_KEY,
            CLIENT_PSK_KEY,
            REQUEST_PSK_KEY,
            PENDING_PSK_KEY,
        )
        expected.forEach { keyId ->
            dao.insertKey(
                vaultKey(
                    keyId,
                    if (keyId in SECRET_VALUE_KEYS) {
                        VaultKeyPurpose.SECRET_VALUES
                    } else {
                        VaultKeyPurpose.DEVICE_STATE
                    },
                ),
            )
        }
        insertEveryReference()

        val referenced = dao.observeReferencedKeys().first().mapTo(linkedSetOf(), VaultKeyEntity::id)

        assertEquals(expected, referenced)
    }

    @Test
    fun removingTheLastCiphertextReferenceUpdatesTheObservedKeys() = runTest {
        dao.insertKey(vaultKey(ENVIRONMENT_KEY, VaultKeyPurpose.SECRET_VALUES))
        val secret = secret("environment-secret", "environment")
        val variable = EnvironmentVariableEntity(
            id = "environment-variable",
            secretId = secret.id,
            name = "TOKEN",
            sensitive = true,
            notes = "",
            encryptedValue = encrypted(ENVIRONMENT_KEY),
            valueUpdatedAt = 1,
        )
        database.secretDao().insertSecret(secret)
        database.secretDao().insertEnvironmentVariableRow(variable)
        assertEquals(
            setOf(ENVIRONMENT_KEY),
            dao.observeReferencedKeys().first().mapTo(linkedSetOf(), VaultKeyEntity::id),
        )
        val afterDeletion = async(start = CoroutineStart.UNDISPATCHED) {
            dao.observeReferencedKeys()
                .map { keys -> keys.mapTo(linkedSetOf(), VaultKeyEntity::id) }
                .first { keys -> keys.isEmpty() }
        }

        database.secretDao().deleteEnvironmentVariableRow(variable)

        assertEquals(emptySet<String>(), afterDeletion.await())
    }

    private suspend fun insertEveryReference() {
        val secretDao = database.secretDao()
        val requestDao = database.requestDao()
        val identityDao = database.deviceIdentityDao()
        val identity = DeviceIdentityEntity(
            id = IDENTITY_ID,
            role = "active",
            address = "write-leader-hungry",
            deviceId = "device-id",
            createdAt = 1,
        )
        identityDao.insertIdentity(identity)
        identityDao.insertCredentials(
            listOf(
                DeviceCredentialEntity(
                    identityId = identity.id,
                    kind = "device_token",
                    encryptedValue = encrypted(DEVICE_CREDENTIAL_KEY),
                ),
            ),
        )

        val environmentSecret = secret("environment-secret", "environment")
        secretDao.insertSecret(environmentSecret)
        secretDao.insertEnvironmentVariableRow(
            EnvironmentVariableEntity(
                id = "environment-variable",
                secretId = environmentSecret.id,
                name = "TOKEN",
                sensitive = true,
                notes = "",
                encryptedValue = encrypted(ENVIRONMENT_KEY),
                valueUpdatedAt = 1,
            ),
        )
        val sshSecret = secret("ssh-secret", "ssh")
        secretDao.insertSecret(sshSecret)
        secretDao.insertSshKeyRow(
            SshKeyEntity(
                secretId = sshSecret.id,
                algorithm = "ed25519",
                publicKey = byteArrayOf(1),
                comment = "",
                encryptedPrivateKey = encrypted(SSH_KEY),
            ),
        )

        val client = ClientEntity(
            clientId = CLIENT_ID,
            deviceIdentityId = identity.id,
            name = "Test client",
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
        requestDao.insertClient(client)
        requestDao.insertClientPsk(
            ClientPskEntity(
                clientId = client.clientId,
                slot = "current",
                encryptedPsk = encrypted(CLIENT_PSK_KEY),
                storedAt = 1,
            ),
        )

        requestDao.insertRequest(request(REQUEST_ID))
        requestDao.insertRequestPsk(
            RequestPskEntity(
                requestId = REQUEST_ID,
                encryptedPsk = encrypted(REQUEST_PSK_KEY),
            ),
        )
        requestDao.insertRequest(request(PAIRING_ID, clientId = PAIRING_ID))
        requestDao.insertPairingAttempt(
            PairingAttemptEntity(
                requestId = PAIRING_ID,
                pairingAddress = "write-leader-hungry",
                friendlyName = null,
                deviceRandom = ByteArray(32),
                desiredRelayClientState = null,
                relayClientState = "active",
                state = "waiting_for_finish",
                sasOption0 = null,
                sasOption1 = null,
                sasOption2 = null,
                correctSasIndex = null,
                platform = null,
                architecture = null,
                hostname = null,
                machineId = null,
                osVersion = null,
                pendingPsk = encrypted(PENDING_PSK_KEY),
                decidedAt = null,
            ),
        )

        insertUpload(UPLOAD_ENVIRONMENT_REQUEST_ID, "environment")
        requestDao.insertSecretUploadEnvironmentVariables(
            listOf(
                SecretUploadEnvironmentVariableEntity(
                    id = "uploaded-environment-variable",
                    requestId = UPLOAD_ENVIRONMENT_REQUEST_ID,
                    name = "TOKEN",
                    sensitive = true,
                    encryptedValue = encrypted(UPLOAD_ENVIRONMENT_KEY),
                ),
            ),
        )
        insertUpload(UPLOAD_SSH_REQUEST_ID, "ssh")
        requestDao.insertSecretUploadSshKey(
            SecretUploadSshKeyEntity(
                requestId = UPLOAD_SSH_REQUEST_ID,
                algorithm = "ed25519",
                publicKey = byteArrayOf(2),
                comment = "",
                encryptedPrivateKey = encrypted(UPLOAD_SSH_KEY),
            ),
        )
    }

    private suspend fun insertUpload(requestId: String, type: String) {
        val requestDao = database.requestDao()
        requestDao.insertRequest(request(requestId))
        requestDao.insertSecretUploadRequestRow(
            SecretUploadRequestEntity(
                requestId = requestId,
                decision = null,
                mode = "create",
                uploadedName = requestId,
                approvedName = null,
                descriptionProvided = false,
                description = null,
                secretType = type,
                targetSecretId = null,
                targetSecretRevision = null,
                summaryJson = "{}",
                intakeError = null,
                decidedAt = null,
            ),
        )
    }

    private fun request(id: String, clientId: String = CLIENT_ID) = InboxRequestEntity(
        id = id,
        parentRequestId = null,
        deviceIdentityId = IDENTITY_ID,
        clientId = clientId,
        clientNameSnapshot = "Test client",
        clientSoftwareJson = null,
        kind = "test",
        state = "waiting",
        listed = false,
        requestJson = "{}",
        responseJson = null,
        error = null,
        receivedAt = 1,
        completedAt = null,
        exchangeEndedAt = null,
        responseOutboxFinished = false,
    )

    private fun secret(id: String, type: String) = SecretEntity(
        id = id,
        name = id,
        description = "",
        type = type,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun encrypted(keyId: String) = EncryptedValue(
        formatVersion = 1,
        keyId = keyId,
        nonce = ByteArray(12),
        ciphertext = byteArrayOf(1),
    )

    private fun vaultKey(id: String, purpose: VaultKeyPurpose) = VaultKeyEntity(
        id = id,
        purpose = purpose.storedName,
        active = false,
        createdAt = 1,
        backing = EncryptionKeyBacking.SOFTWARE.storedName,
    )

    private companion object {
        const val IDENTITY_ID = "identity"
        const val CLIENT_ID = "client"
        const val REQUEST_ID = "request"
        const val PAIRING_ID = "pairing"
        const val UPLOAD_ENVIRONMENT_REQUEST_ID = "upload-environment"
        const val UPLOAD_SSH_REQUEST_ID = "upload-ssh"

        const val ENVIRONMENT_KEY = "environment-key"
        const val SSH_KEY = "ssh-key"
        const val UPLOAD_ENVIRONMENT_KEY = "upload-environment-key"
        const val UPLOAD_SSH_KEY = "upload-ssh-key"
        const val DEVICE_CREDENTIAL_KEY = "device-credential-key"
        const val CLIENT_PSK_KEY = "client-psk-key"
        const val REQUEST_PSK_KEY = "request-psk-key"
        const val PENDING_PSK_KEY = "pending-psk-key"

        val SECRET_VALUE_KEYS = setOf(
            ENVIRONMENT_KEY,
            SSH_KEY,
            UPLOAD_ENVIRONMENT_KEY,
            UPLOAD_SSH_KEY,
        )
    }
}
