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
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.TemporaryAccessGrantEntity
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
class ClientRemovalRequestsTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var audit: AuditRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        database.vaultKeyDao().activate(
            VaultKeyEntity(
                id = KEY_ID,
                purpose = VaultKeyPurpose.DEVICE_STATE.storedName,
                active = true,
                createdAt = 1L,
                backing = "SOFTWARE",
            ),
        )
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
        database.requestDao().insertClientPsk(clientPsk("current", byteArrayOf(1)))
        database.requestDao().insertClientPsk(clientPsk("previous", byteArrayOf(2)))
        database.secretDao().insertSecret(
            SecretEntity(
                id = SECRET_ID,
                name = "Deployment",
                description = "",
                type = "environment",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.secretDao().upsertTemporaryAccessGrants(
            listOf(
                TemporaryAccessGrantEntity(
                    secretId = SECRET_ID,
                    clientId = CLIENT_ID,
                    operation = "invocation",
                    expiresAt = 1_000L,
                ),
            ),
        )
        audit = AuditRepository(database.auditDao(), currentTimeMillis = { AUDIT_CLOCK })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun receiveImmediatelyRevokesClientAndRetainsOnlyTheRequestPsk() = runTest {
        assertEquals(RESPONSE, receive())

        val stored = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))
        assertEquals(InboxRequestState.WAITING.storedName, stored.state)
        assertEquals(RESPONSE.toString(), stored.responseJson)
        assertNull(stored.completedAt)
        assertNull(stored.exchangeEndedAt)
        assertFalse(stored.responseOutboxFinished)
        assertNotNull(database.requestDao().getRequestPsk(REQUEST_ID))
        assertNull(database.requestDao().getClientPsk(CLIENT_ID, "current"))
        assertNull(database.requestDao().getClientPsk(CLIENT_ID, "previous"))
        assertEquals(
            RelayClientState.REVOKED.wireName,
            database.requestDao().getClient(CLIENT_ID)?.desiredRelayClientState,
        )
        assertEquals(NOW, database.requestDao().getClient(CLIENT_ID)?.lastSeenAt)
        assertTrue(activeGrants().isEmpty())
        assertTrue(audit.observeEvents().first().isEmpty())
    }

    @Test
    fun matchingCompletionEndsOnceAndTerminalReplayDoesNotReopen() = runTest {
        receive()
        val request = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))
        var openCount = 0

        assertTrue(
            requests(audit).complete(request, COMPLETION) {
                openCount += 1
                CompletionOpenResult.Opened(completionPlaintext())
            },
        )

        val completed = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))
        assertEquals(1, openCount)
        assertEquals(InboxRequestState.COMPLETED.storedName, completed.state)
        assertEquals(COMPLETION.toString(), completed.completionJson)
        assertTrue(completed.responseOutboxFinished)
        assertNull(completed.error)
        assertEquals(NOW, completed.completedAt)
        assertEquals(NOW, completed.exchangeEndedAt)
        assertNull(database.requestDao().getRequestPsk(REQUEST_ID))
        val event = audit.observeEvents().first().single()
        assertEquals(AuditEventType.CLIENT_UNPAIRED_ITSELF, event.type)
        assertEquals(NOW, event.occurredAt)

        assertTrue(
            requests(audit).complete(completed, COMPLETION) {
                error("A terminal removal completion must not be reopened")
            },
        )
        assertEquals(1, audit.observeEvents().first().size)
    }

    @Test
    fun mismatchedClientSoftwareIsAnInvalidCompletion() = runTest {
        receive()
        val request = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))

        assertTrue(
            requests(audit).complete(request, COMPLETION) {
                CompletionOpenResult.Opened(completionPlaintext(version = "9.9.9"))
            },
        )

        val completed = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))
        assertEquals(COMPLETION_ERROR, completed.error)
        assertEquals(COMPLETION.toString(), completed.completionJson)
        assertNull(database.requestDao().getRequestPsk(REQUEST_ID))
        val event = audit.observeEvents().first().single()
        assertEquals(AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED, event.type)
        assertEquals(COMPLETION_ERROR, event.detail)
        assertEquals(NOW, event.occurredAt)
    }

    @Test
    fun retryLaterLeavesTheExchangeAndRequestPskLive() = runTest {
        receive()
        val before = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))

        assertFalse(
            requests(audit).complete(before, COMPLETION) { CompletionOpenResult.RetryLater },
        )

        assertEquals(before, database.requestDao().getRequestById(REQUEST_ID))
        assertNotNull(database.requestDao().getRequestPsk(REQUEST_ID))
        assertTrue(audit.observeEvents().first().isEmpty())
    }

    @Test
    fun irrecoverablyInvalidCompletionEndsWithoutRetainingItsPayload() = runTest {
        receive()
        val request = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))

        assertTrue(
            requests(audit).complete(request, COMPLETION) {
                CompletionOpenResult.IrrecoverablyInvalid
            },
        )

        val completed = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))
        assertEquals(COMPLETION_ERROR, completed.error)
        assertNull(completed.completionJson)
        assertNotNull(completed.exchangeEndedAt)
        assertNull(database.requestDao().getRequestPsk(REQUEST_ID))
        assertEquals(
            AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED,
            audit.observeEvents().first().single().type,
        )
    }

    @Test
    fun invalidCompletionCannotMatchAMissingStoredSoftwareSnapshot() = runTest {
        receive()
        val request = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))
        assertEquals(1, database.requestDao().updateRequest(request.copy(clientSoftwareJson = null)))

        assertTrue(
            requests(audit).complete(request, COMPLETION) {
                CompletionOpenResult.IrrecoverablyInvalid
            },
        )

        val completed = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))
        assertEquals(COMPLETION_ERROR, completed.error)
        assertEquals(
            AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED,
            audit.observeEvents().first().single().type,
        )
    }

    @Test
    fun completionAndAuditRollBackTogetherAndCanBeRetried() = runTest {
        receive()
        val before = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).complete(before, COMPLETION) {
                    CompletionOpenResult.Opened(completionPlaintext())
                }
            }.isFailure,
        )
        assertEquals(before, database.requestDao().getRequestById(REQUEST_ID))
        assertNotNull(database.requestDao().getRequestPsk(REQUEST_ID))
        assertTrue(audit.observeEvents().first().isEmpty())

        assertTrue(
            requests(audit).complete(before, COMPLETION) {
                CompletionOpenResult.Opened(completionPlaintext())
            },
        )
        assertNull(database.requestDao().getRequestPsk(REQUEST_ID))
        assertEquals(1, audit.observeEvents().first().size)
    }

    @Test
    fun invalidCompletionAndAuditRollBackTogether() = runTest {
        receive()
        val before = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).complete(before, COMPLETION) {
                    CompletionOpenResult.IrrecoverablyInvalid
                }
            }.isFailure,
        )

        assertEquals(before, database.requestDao().getRequestById(REQUEST_ID))
        assertNotNull(database.requestDao().getRequestPsk(REQUEST_ID))
        assertTrue(audit.observeEvents().first().isEmpty())
    }

    @Test
    fun expiryAndAuditRollBackAndReplayTogether() = runTest {
        receive()
        val before = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).expire(
                    before,
                    EXPIRY_MESSAGE,
                    EXPIRY_TIME,
                )
            }.isFailure,
        )
        assertEquals(before, database.requestDao().getRequestById(REQUEST_ID))
        assertNotNull(database.requestDao().getRequestPsk(REQUEST_ID))
        assertTrue(audit.observeEvents().first().isEmpty())

        val regular = requests(audit)
        regular.expire(before, EXPIRY_MESSAGE, EXPIRY_TIME)
        val expired = checkNotNull(database.requestDao().getRequestById(REQUEST_ID))
        assertEquals(InboxRequestState.COMPLETED.storedName, expired.state)
        assertEquals(EXPIRY_MESSAGE, expired.error)
        assertEquals(EXPIRY_TIME, expired.completedAt)
        assertEquals(EXPIRY_TIME, expired.exchangeEndedAt)
        assertNull(database.requestDao().getRequestPsk(REQUEST_ID))
        val event = audit.observeEvents().first().single()
        assertEquals(AuditEventType.CLIENT_REMOVAL_UNCONFIRMED, event.type)
        assertEquals(EXPIRY_MESSAGE, event.detail)
        assertEquals(EXPIRY_TIME, event.occurredAt)

        regular.expire(expired, "conflicting replay", EXPIRY_TIME + 1)
        assertEquals(1, audit.observeEvents().first().size)
        assertEquals(EXPIRY_MESSAGE, database.requestDao().getRequestById(REQUEST_ID)?.error)
    }

    private fun requests(auditSink: AuditSink) = ClientRemovalRequests(
        dao = database.requestDao(),
        audit = auditSink,
        writeTransaction = RoomWriteTransaction(database),
        json = Json,
        currentTimeMillis = { NOW },
    )

    private suspend fun receive(): JsonElement? = requests(audit).receive(
        client = client(),
        relayRequestId = REQUEST_ID,
        requestPayload = REQUEST,
        plaintext = requestPlaintext(),
        requestPsk = requestPsk(),
        sealResponse = { RESPONSE },
    )

    private suspend fun activeGrants() = database.secretDao().getActiveTemporaryAccessGrants(
        CLIENT_ID,
        listOf(SECRET_ID),
        "invocation",
        0L,
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

    private fun clientPsk(slot: String, ciphertext: ByteArray) = ClientPskEntity(
        clientId = CLIENT_ID,
        slot = slot,
        encryptedPsk = encryptedValue(ciphertext),
        storedAt = 3L,
    )

    private fun requestPsk() = RequestPskEntity(
        requestId = REQUEST_ID,
        encryptedPsk = encryptedValue(byteArrayOf(3)),
    )

    private fun encryptedValue(ciphertext: ByteArray) = EncryptedValue(
        formatVersion = 1,
        keyId = KEY_ID,
        nonce = ByteArray(12),
        ciphertext = ciphertext,
    )

    private fun requestPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"method":"PairingRemove"}""".encodeToByteArray()

    private fun completionPlaintext(version: String = SOFTWARE_VERSION): ByteArray =
        """{${clientSoftwareFields(version)}}""".encodeToByteArray()

    private fun clientSoftwareFields(version: String = SOFTWARE_VERSION): String =
        """"app_info":{"name":"agentknock-cli","version":"$version"},"lib_info":{"name":"agentknock","version":"$version"}"""

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

    private companion object {
        const val KEY_ID = "key"
        const val DEVICE_IDENTITY_ID = "device-identity"
        const val DEVICE_ID = "01JDEVICE000000000000000000"
        const val CLIENT_ID = "01JCLIENT000000000000000000"
        const val REQUEST_ID = "01JREMOVE000000000000000000"
        const val SECRET_ID = "secret-id"
        const val SOFTWARE_VERSION = "0.3.0"
        const val NOW = 10L
        const val AUDIT_CLOCK = 99L
        const val EXPIRY_TIME = 20L
        const val EXPIRY_MESSAGE = "The relay exchange expired before it completed."
        const val COMPLETION_ERROR = "Client removal completion could not be verified."
        val REQUEST: JsonElement = Json.parseToJsonElement("""{"ciphertext":"request"}""")
        val RESPONSE: JsonElement = Json.parseToJsonElement("""{"ciphertext":"response"}""")
        val COMPLETION: JsonElement = Json.parseToJsonElement("""{"ciphertext":"completion"}""")
    }
}
