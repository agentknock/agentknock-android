package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.EncryptionKeyStore
import dev.agentknock.storage.crypto.GeneratedEncryptionKey
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.storage.secret.SshKeyAlgorithm
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretManagementRequestsTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var audit: AuditRepository
    private lateinit var material: RequestMaterialStore
    private lateinit var secrets: SecretRepository
    private var materialId = 0

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        database.deviceIdentityDao().insertIdentity(
            DeviceIdentityEntity(
                id = DEVICE_IDENTITY_ID,
                role = "active",
                address = "amber-river-maple",
                deviceId = DEVICE_ID,
                createdAt = 1L,
            ),
        )
        database.requestDao().insertClient(client())
        val keyStore = MemoryEncryptionKeyStore()
        var keyId = 0
        val keyManager = VaultKeyManager(
            dao = database.vaultKeyDao(),
            keyStore = keyStore,
            newKeyId = { "test-key-${keyId++}" },
            currentTimeMillis = { NOW },
            keyStoreDispatcher = Dispatchers.Unconfined,
        )
        val encryption = AesGcmEncryption(keyStore)
        audit = AuditRepository(database.auditDao(), currentTimeMillis = { NOW })
        material = RequestMaterialStore(
            dao = database.requestDao(),
            keyManager = keyManager,
            encryption = encryption,
            newId = { "request-material-${materialId++}" },
            currentTimeMillis = { NOW },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
        secrets = SecretRepository(
            dao = database.secretDao(),
            keyManager = keyManager,
            encryption = encryption,
            audit = audit,
            writeTransaction = RoomWriteTransaction(database),
            newId = { SECRET_ID },
            currentTimeMillis = { NOW },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun receivedListAndAuditRollBackTogetherAndCanBeRetried() = runTest {
        val requestId = "list-request"
        val acceptedPsks = acceptedPsks(requestId)
        val failing = requests(InsertThenFailAuditSink(audit))

        assertTrue(
            runCatching {
                failing.receiveSecretList(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = requestPayload(requestId),
                    plaintext = secretListPlaintext(),
                    acceptedPsks = acceptedPsks,
                    sealResponse = { RESPONSE },
                )
            }.isFailure,
        )
        assertNull(database.requestDao().getRequestById(requestId))
        assertNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(3L, database.requestDao().getClient(CLIENT_ID)?.lastSeenAt)
        assertTrue(audit.observeEvents().first().isEmpty())

        assertEquals(
            RESPONSE,
            requests(audit).receiveSecretList(
                client = client(),
                relayRequestId = requestId,
                requestPayload = requestPayload(requestId),
                plaintext = secretListPlaintext(),
                acceptedPsks = acceptedPsks,
                sealResponse = { RESPONSE },
            ),
        )
        assertNotNull(database.requestDao().getRequestById(requestId))
        assertNotNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(NOW, database.requestDao().getClient(CLIENT_ID)?.lastSeenAt)
        assertEquals(
            listOf(AuditEventType.SECRET_LIST_RECEIVED),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun invalidListCompletionUsesSafeFailureCategory() = runTest {
        val requestId = "list-invalid-completion"
        requests(audit).receiveSecretList(
            client = client(),
            relayRequestId = requestId,
            requestPayload = requestPayload(requestId),
            plaintext = secretListPlaintext(),
            acceptedPsks = acceptedPsks(requestId),
            sealResponse = { RESPONSE },
        )
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val malicious = "raw-secret-list-parser-input"

        assertTrue(
            requests(audit).completeSecretList(
                request = request,
                openCompletion = {
                    CompletionOpenResult.Opened("{not-json-$malicious".encodeToByteArray())
                },
            ),
        )

        assertEquals(
            "Secret list completion could not be verified.",
            database.requestDao().getRequestById(requestId)?.error,
        )
        val completionAudit = audit.observeEvents().first()
            .single { it.type == AuditEventType.SECRET_LIST_COMPLETED }
        assertEquals("Secret list completion could not be verified.", completionAudit.detail)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    @Test
    fun pendingSshUploadDerivesCanonicalFormatAndRejectsWrongBindings() = runTest {
        val ed25519 = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "ed25519@example")
        val encrypted = material.encryptSecretUploadSshKey("ssh-upload", CLIENT_ID, ed25519)

        val decrypted = material.decryptSecretUploadSshKey(request("ssh-upload"), encrypted)
        assertTrue(decrypted is DecryptionResult.Plaintext)
        assertTrue((decrypted as DecryptionResult.Plaintext).value.contentEquals(ed25519.privateKey))

        val rsa = secrets.generateSshKey(SshKeyAlgorithm.RSA, "rsa@example")
        assertEquals(
            DecryptionResult.AuthenticationFailed,
            material.decryptSecretUploadSshKey(
                request("ssh-upload"),
                encrypted.copy(
                    algorithm = rsa.algorithm.storedName,
                    publicKey = rsa.publicKey,
                    comment = rsa.comment,
                ),
            ),
        )

        val other = material.encryptSecretUploadSshKey(
            "other-upload",
            CLIENT_ID,
            secrets.generateSshKey(SshKeyAlgorithm.ED25519, "other@example"),
        )
        assertEquals(
            DecryptionResult.AuthenticationFailed,
            material.decryptSecretUploadSshKey(
                request("ssh-upload"),
                encrypted.copy(encryptedPrivateKey = other.encryptedPrivateKey),
            ),
        )
    }

    @Test
    fun receivedUploadRowsRequestKeyClientAndAuditRollBackAndCanBeRetried() = runTest {
        val requestId = "upload-receive"
        val acceptedPsks = acceptedPsks(requestId)
        val failing = requests(InsertThenFailAuditSink(audit))

        assertTrue(
            runCatching {
                failing.receiveSecretUpload(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = requestPayload(requestId),
                    plaintext = environmentUploadPlaintext(),
                    acceptedPsks = acceptedPsks,
                    sealResponse = { RESPONSE },
                )
            }.isFailure,
        )
        assertNull(database.requestDao().getRequestById(requestId))
        assertNull(database.requestDao().getSecretUploadRequest(requestId))
        assertTrue(database.requestDao().getSecretUploadEnvironmentVariables(requestId).isEmpty())
        assertNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(3L, database.requestDao().getClient(CLIENT_ID)?.lastSeenAt)
        assertTrue(audit.observeEvents().first().isEmpty())

        assertEquals(
            RESPONSE,
            requests(audit).receiveSecretUpload(
                client = client(),
                relayRequestId = requestId,
                requestPayload = requestPayload(requestId),
                plaintext = environmentUploadPlaintext(),
                acceptedPsks = acceptedPsks,
                sealResponse = { RESPONSE },
            ),
        )
        assertNotNull(database.requestDao().getRequestById(requestId))
        assertNotNull(database.requestDao().getSecretUploadRequest(requestId))
        assertEquals(
            1,
            database.requestDao().getSecretUploadEnvironmentVariables(requestId).size,
        )
        assertNotNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(NOW, database.requestDao().getClient(CLIENT_ID)?.lastSeenAt)
        assertEquals(
            listOf(AuditEventType.SECRET_UPLOAD_RECEIVED),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun approvedUploadSecretRequestValuesAndAuditCommitOrRollBackTogether() = runTest {
        val requestId = "upload-approval"
        receiveEnvironmentUpload(requestId)
        val eventCount = audit.observeEvents().first().size

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).approveSecretUpload(
                    requestId,
                    "uploaded-secret",
                )
            }.isFailure,
        )
        assertTrue(database.secretDao().getSecrets().isEmpty())
        assertNull(database.requestDao().getSecretUploadRequest(requestId)?.decision)
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
        assertEquals(
            1,
            database.requestDao().getSecretUploadEnvironmentVariables(requestId).size,
        )
        assertEquals(eventCount, audit.observeEvents().first().size)

        assertEquals(
            SecretUploadDecisionResult.Approved(SECRET_ID),
            requests(audit).approveSecretUpload(requestId, "uploaded-secret"),
        )
        assertEquals(listOf("uploaded-secret"), database.secretDao().getSecrets().map { it.name })
        assertEquals(
            SecretUploadRequestState.APPROVED.storedName,
            database.requestDao().getSecretUploadRequest(requestId)?.decision,
        )
        assertTrue(
            database.requestDao().getSecretUploadEnvironmentVariables(requestId).isEmpty(),
        )
        assertEquals(
            listOf(
                AuditEventType.SECRET_UPLOAD_DECIDED,
                AuditEventType.SECRET_UPLOAD_RECEIVED,
            ),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun changedUpdateTargetRejectsAndDiscardsThePendingUpload() = runTest {
        val requestId = "changed-update-target"
        val created = secrets.createEnvironmentSecret("production", "")
        check(created is CreateSecretResult.Created)
        assertEquals(
            RESPONSE,
            requests(audit).receiveSecretUpload(
                client = client(),
                relayRequestId = requestId,
                requestPayload = requestPayload(requestId),
                plaintext = environmentUploadPlaintext("UPDATE", "production"),
                acceptedPsks = acceptedPsks(requestId),
                sealResponse = { RESPONSE },
            ),
        )
        assertEquals(
            SaveSecretResult.SAVED,
            secrets.saveInstructions(created.id, "New instructions"),
        )

        assertEquals(
            SecretUploadDecisionResult.Invalidated(
                "The target secret changed before the upload was approved.",
            ),
            requests(audit).approveSecretUpload(requestId, "production"),
        )
        assertEquals(
            SecretUploadRequestState.REJECTED.storedName,
            database.requestDao().getSecretUploadRequest(requestId)?.decision,
        )
        assertTrue(database.requestDao().getSecretUploadEnvironmentVariables(requestId).isEmpty())
    }

    @Test
    fun pendingUploadSensitivityControlsProtectedValueAccess() = runTest {
        val requestId = "upload-protected-value"
        receiveEnvironmentUpload(requestId)
        val variable = database.requestDao()
            .getSecretUploadEnvironmentVariables(requestId)
            .single()
        val target = requests(audit)

        assertEquals(
            SecretUploadVariableValue.AuthenticationRequired("TOKEN"),
            target.readSecretUploadVariable(
                requestId,
                variable.id,
                sensitiveAccessAuthorized = false,
            ),
        )
        assertEquals(
            SecretUploadSensitivityResult.AuthenticationRequired("TOKEN"),
            target.setSecretUploadVariableSensitivity(
                requestId,
                variable.id,
                sensitive = false,
                sensitivityReductionAuthorized = false,
            ),
        )
        assertTrue(
            database.requestDao().getSecretUploadEnvironmentVariables(requestId).single().sensitive,
        )

        assertEquals(
            SecretUploadSensitivityResult.Changed,
            target.setSecretUploadVariableSensitivity(
                requestId,
                variable.id,
                sensitive = false,
                sensitivityReductionAuthorized = true,
            ),
        )
        assertEquals(
            SecretUploadVariableValue.Available("secret-value"),
            target.readSecretUploadVariable(
                requestId,
                variable.id,
                sensitiveAccessAuthorized = false,
            ),
        )
    }

    @Test
    fun uploadCompletionAndAuditRollBackAndReplayDoesNotReopenOrDuplicate() = runTest {
        val requestId = "upload-completion"
        receiveEnvironmentUpload(requestId)
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val eventCount = audit.observeEvents().first().size

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).completeSecretUpload(
                    request,
                ) { CompletionOpenResult.Opened(uploadCompletionPlaintext()) }
            }.isFailure,
        )
        assertEquals(eventCount, audit.observeEvents().first().size)

        val regular = requests(audit)
        assertTrue(
            regular.completeSecretUpload(request) {
                CompletionOpenResult.Opened(uploadCompletionPlaintext())
            },
        )
        val completedTransport = checkNotNull(database.requestDao().getRequestById(requestId))
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, completedTransport.state)
        assertNull(completedTransport.completedAt)
        assertTrue(
            regular.completeSecretUpload(completedTransport) {
                error("A completed upload must not reopen its transport completion")
            },
        )
        assertEquals(eventCount + 1, audit.observeEvents().first().size)

        assertEquals(SecretUploadDecisionResult.Rejected, regular.rejectSecretUpload(requestId))
        assertEquals(
            InboxRequestState.COMPLETED.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
    }

    @Test
    fun invalidUploadCompletionUsesSafeFailureCategory() = runTest {
        val requestId = "upload-invalid-completion"
        receiveEnvironmentUpload(requestId)
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val malicious = "raw-secret-upload-parser-input"

        assertTrue(
            requests(audit).completeSecretUpload(
                request = request,
                openCompletion = {
                    CompletionOpenResult.Opened("{not-json-$malicious".encodeToByteArray())
                },
            ),
        )

        assertEquals(
            "Secret upload completion could not be verified.",
            database.requestDao().getRequestById(requestId)?.error,
        )
        val completionAudit = audit.observeEvents().first()
            .single { it.type == AuditEventType.SECRET_UPLOAD_COMPLETED }
        assertEquals("Secret upload completion could not be verified.", completionAudit.detail)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    private fun requests(auditSink: AuditSink) = SecretManagementRequests(
        dao = database.requestDao(),
        material = material,
        secrets = secrets,
        audit = auditSink,
        writeTransaction = RoomWriteTransaction(database),
        currentTimeMillis = { NOW },
        cryptographyDispatcher = Dispatchers.Unconfined,
    )

    private suspend fun receiveEnvironmentUpload(requestId: String) {
        assertEquals(
            RESPONSE,
            requests(audit).receiveSecretUpload(
                client = client(),
                relayRequestId = requestId,
                requestPayload = requestPayload(requestId),
                plaintext = environmentUploadPlaintext(),
                acceptedPsks = acceptedPsks(requestId),
                sealResponse = { RESPONSE },
            ),
        )
    }

    private suspend fun acceptedPsks(requestId: String) = AcceptedRequestPsks(
        requestPsk = material.encryptRequestPsk(
            deviceIdentityId = DEVICE_IDENTITY_ID,
            clientId = CLIENT_ID,
            relayRequestId = requestId,
            clientPsk = ByteArray(32) { it.toByte() },
        ),
        currentClientPsk = null,
        previousClientPsk = null,
    )

    private fun client() = ClientEntity(
        clientId = CLIENT_ID,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        name = "Workstation",
        instructions = "",
        desiredRelayClientState = null,
        relayClientState = RelayClientState.ACTIVE.wireName,
        clientSoftwareJson = null,
        platform = "linux",
        architecture = "x86_64",
        hostname = "host",
        machineId = "machine",
        osVersion = "NixOS",
        pairedAt = 2L,
        lastSeenAt = 3L,
    )

    private fun requestPayload(requestId: String): JsonElement =
        Json.parseToJsonElement("""{"request":"$requestId"}""")

    private fun request(id: String) = InboxRequestEntity(
        id = id,
        parentRequestId = null,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        clientId = CLIENT_ID,
        clientNameSnapshot = "Workstation",
        clientSoftwareJson = null,
        kind = "secret_upload",
        state = "pending",
        listed = false,
        requestJson = "{}",
        responseJson = null,
        error = null,
        receivedAt = NOW,
        completedAt = null,
        exchangeEndedAt = null,
        responseOutboxFinished = false,
    )

    private fun secretListPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"method":"SecretList"}""".encodeToByteArray()

    private fun environmentUploadPlaintext(
        mode: String = "CREATE",
        name: String = "uploaded-secret",
    ): ByteArray =
        """{${clientSoftwareFields()},"method":"SecretUpload","mode":"$mode","secret":{"name":"$name","type":"environment","variables":{"TOKEN":{"value":"secret-value"}}}}"""
            .encodeToByteArray()

    private fun uploadCompletionPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"result":"RECEIVED"}""".encodeToByteArray()

    private fun clientSoftwareFields(): String =
        """"app_info":{"name":"agentknock-cli","version":"0.3.0"},"lib_info":{"name":"agentknock","version":"0.3.0"}"""

    private class InsertThenFailAuditSink(
        private val delegate: AuditSink,
    ) : AuditSink {
        override suspend fun record(record: AuditRecord) {
            delegate.record(record)
            error("Injected audit failure")
        }

        override suspend fun append(records: List<AuditRecord>, occurredAt: Long) {
            delegate.append(records, occurredAt)
            error("Injected audit failure")
        }
    }

    private class MemoryEncryptionKeyStore : EncryptionKeyStore {
        private val keys = mutableMapOf<String, SecretKey>()

        override fun get(keyId: String): SecretKey? = keys[keyId]

        override fun generate(keyId: String): GeneratedEncryptionKey {
            check(keyId !in keys)
            keys[keyId] = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
            return GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)
        }

        override fun delete(keyId: String) {
            keys.remove(keyId)
        }
    }

    private companion object {
        const val DEVICE_IDENTITY_ID = "device-identity"
        const val DEVICE_ID = "01JDEVICE000000000000000000"
        const val CLIENT_ID = "01JCLIENT000000000000000000"
        const val SECRET_ID = "secret-id"
        const val NOW = 10L
        val RESPONSE: JsonElement = Json.parseToJsonElement("""{"ciphertext":"response"}""")
    }
}
