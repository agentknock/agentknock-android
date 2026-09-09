package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.protocol.PairedRequestErrorCode
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.EncryptionKeyStore
import dev.agentknock.storage.crypto.GeneratedEncryptionKey
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.device.RelayDeviceCredentials
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingRequestsTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var material: RequestMaterialStore
    private lateinit var audit: AuditRepository

    @Before
    fun setUp() = runTest {
        database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AgentknockDatabase::class.java,
                )
                .build()
        database
            .deviceIdentityDao()
            .insertIdentity(
                DeviceIdentityEntity(
                    id = DEVICE_IDENTITY_ID,
                    role = "active",
                    address = ADDRESS,
                    deviceId = DEVICE_ID,
                    createdAt = 1L,
                )
            )
        val keyStore = MemoryEncryptionKeyStore()
        var nextKey = 0
        val keyManager =
            VaultKeyManager(
                dao = database.vaultKeyDao(),
                keyStore = keyStore,
                newKeyId = { "pairing-key-${nextKey++}" },
                currentTimeMillis = { NOW },
                keyStoreDispatcher = Dispatchers.Unconfined,
            )
        material =
            RequestMaterialStore(
                dao = database.requestDao(),
                keyManager = keyManager,
                encryption = AesGcmEncryption(keyStore),
                currentTimeMillis = { NOW },
                cryptographyDispatcher = Dispatchers.Unconfined,
            )
        audit = AuditRepository(database.auditDao(), currentTimeMillis = { NOW })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun finishContextRequiresTheActiveIdentityClientAndFinishState() = runTest {
        insertPendingPairing()
        val requests = requests(audit)

        val available = requests.finishContext(credentials(), CLIENT_ID)
        assertNotNull(available)
        assertArrayEquals(PENDING_PSK, available?.clientPsk)
        assertNull(requests.finishContext(credentials(), OTHER_CLIENT_ID))
        assertNull(
            requests.finishContext(
                credentials().copy(deviceIdentityId = "different-identity"),
                CLIENT_ID,
            )
        )

        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(
            1,
            database
                .requestDao()
                .updatePairingAttempt(
                    attempt.copy(state = PairingState.SAS_VERIFICATION_PENDING.storedName)
                ),
        )
        assertNull(requests.finishContext(credentials(), CLIENT_ID))
    }

    @Test
    fun matchingPendingSasReturnsTheCurrentClientIdentityOnlyForTheCorrectChoice() = runTest {
        insertPendingPairing(state = PairingState.SAS_VERIFICATION_PENDING)
        val requests = requests(audit)

        assertEquals(
            MatchingPairingSas("Developer laptop"),
            requests.matchingPendingSas(CLIENT_ID, selectedIndex = 1),
        )
        assertNull(requests.matchingPendingSas(CLIENT_ID, selectedIndex = 0))

        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(
            1,
            database
                .requestDao()
                .updatePairingAttempt(attempt.copy(friendlyName = null, hostname = null)),
        )
        assertEquals(
            MatchingPairingSas("linux"),
            requests.matchingPendingSas(CLIENT_ID, selectedIndex = 1),
        )
    }

    @Test
    fun validFinishPromotesClientAndOwnsAllDurablePairingTransitions() = runTest {
        insertPendingPairing(relayState = RelayClientState.ACTIVE)
        val requests = requests(audit)
        val pairing = checkNotNull(requests.finishContext(credentials(), CLIENT_ID))
        var sealedPlaintext: ByteArray? = null

        assertEquals(
            ProcessedRelayMessage,
            requests.receiveFinish(
                pairing = pairing,
                relayRequestId = FINISH_REQUEST_ID,
                requestPayload = REQUEST,
                plaintext = finishPlaintext(),
                requestPsk = requestPsk(),
                sealResponse = { plaintext ->
                    sealedPlaintext = plaintext
                    RESPONSE
                },
            ),
        )

        assertEquals("{\"result\":\"ACCEPTED\"}", sealedPlaintext?.decodeToString())
        val root = checkNotNull(database.requestDao().getRequestById(CLIENT_ID))
        assertEquals(InboxRequestState.COMPLETED.storedName, root.state)
        assertFalse(root.listed)
        assertEquals(NOW, root.completedAt)
        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(PairingState.COMPLETED.storedName, attempt.state)
        assertNull(attempt.pendingPsk)
        assertNull(attempt.desiredRelayClientState)
        val client = checkNotNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals(RelayClientState.ACTIVE.wireName, client.relayClientState)
        assertArrayEquals(PENDING_PSK, material.decryptClientPsk(client))
        val finish = checkNotNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertEquals(CLIENT_ID, finish.parentRequestId)
        assertEquals(RESPONSE.toString(), finish.responseJson)
        assertNotNull(database.requestDao().getRequestPsk(FINISH_REQUEST_ID))
        val event = audit.observeEvents().first().single()
        assertEquals(AuditEventType.PAIRING_COMPLETED, event.type)
        assertEquals(CLIENT_ID, event.relayRequestId)
    }

    @Test
    fun stalePendingSnapshotCannotPromoteARejectedPairing() = runTest {
        insertPendingPairing()
        val requests = requests(audit)
        val pairing = checkNotNull(requests.finishContext(credentials(), CLIENT_ID))
        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(
            1,
            database
                .requestDao()
                .updatePairingAttempt(
                    attempt.copy(
                        state = PairingState.REJECTED.storedName,
                        desiredRelayClientState = RelayClientState.REVOKED.wireName,
                        pendingPsk = null,
                    )
                ),
        )

        assertNull(
            requests.receiveFinish(
                pairing = pairing,
                relayRequestId = FINISH_REQUEST_ID,
                requestPayload = REQUEST,
                plaintext = finishPlaintext(),
                requestPsk = requestPsk(),
                sealResponse = { RESPONSE },
            )
        )
        assertNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertTrue(audit.observeEvents().first().isEmpty())
    }

    @Test
    fun finishPromotionAndAuditRollBackTogetherThenReplayIsImmutable() = runTest {
        insertPendingPairing(relayState = RelayClientState.ACTIVE)
        val pairing = checkNotNull(requests(audit).finishContext(credentials(), CLIENT_ID))
        val requestPsk = requestPsk()

        assertNull(
            requests(InsertThenFailAuditSink(audit))
                .receiveFinish(
                    pairing = pairing,
                    relayRequestId = FINISH_REQUEST_ID,
                    requestPayload = REQUEST,
                    plaintext = finishPlaintext(),
                    requestPsk = requestPsk,
                    sealResponse = { RESPONSE },
                )
        )
        assertNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals(
            PairingState.WAITING_FOR_FINISH.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )
        assertTrue(audit.observeEvents().first().isEmpty())

        val regular = requests(audit)
        assertEquals(
            ProcessedRelayMessage,
            regular.receiveFinish(
                pairing = pairing,
                relayRequestId = FINISH_REQUEST_ID,
                requestPayload = REQUEST,
                plaintext = finishPlaintext(),
                requestPsk = requestPsk,
                sealResponse = { RESPONSE },
            ),
        )
        val stored = checkNotNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))

        assertNull(
            regular.receiveFinish(
                pairing = pairing,
                relayRequestId = FINISH_REQUEST_ID,
                requestPayload = Json.parseToJsonElement("""{"changed":true}"""),
                plaintext = finishPlaintext(),
                requestPsk = requestPsk,
                sealResponse = { Json.parseToJsonElement("""{"changed":true}""") },
            )
        )
        assertEquals(stored, database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertEquals(1, audit.observeEvents().first().size)
    }

    @Test
    fun pendingPairingRejectionIsFeatureOwnedAndRejectsStaleSnapshots() = runTest {
        insertPendingPairing()
        val requests = requests(audit)
        val pairing = checkNotNull(requests.finishContext(credentials(), CLIENT_ID))
        val requestPsk = requestPsk()

        assertTrue(
            requests.recordRejectedRequest(
                pairing = pairing,
                relayRequestId = FINISH_REQUEST_ID,
                requestPayload = REQUEST,
                responsePayload = RESPONSE,
                requestPsk = requestPsk,
                code = PairedRequestErrorCode.INVALID_STATE,
            )
        )
        val rejected = checkNotNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertEquals(RequestKind.UNKNOWN.storedName, rejected.kind)
        assertEquals(PairedRequestErrorCode.INVALID_STATE.message, rejected.error)
        assertEquals(AuditEventType.REQUEST_REJECTED, audit.observeEvents().first().single().type)
        assertFalse(
            requests.recordRejectedRequest(
                pairing = pairing,
                relayRequestId = FINISH_REQUEST_ID,
                requestPayload = REQUEST,
                responsePayload = RESPONSE,
                requestPsk = requestPsk,
                code = PairedRequestErrorCode.INVALID_REQUEST,
            )
        )
    }

    private fun requests(auditSink: AuditSink) =
        PairingRequests(
            dao = database.requestDao(),
            material = material,
            audit = auditSink,
            writeTransaction = RoomWriteTransaction(database),
            pairingProtocol = PairingProtocol(),
            json = Json,
            currentTimeMillis = { NOW },
        )

    private suspend fun insertPendingPairing(
        state: PairingState = PairingState.WAITING_FOR_FINISH,
        relayState: RelayClientState = RelayClientState.PENDING,
    ) {
        val request =
            InboxRequestEntity(
                id = CLIENT_ID,
                parentRequestId = null,
                deviceIdentityId = DEVICE_IDENTITY_ID,
                clientId = CLIENT_ID,
                clientNameSnapshot = CLIENT_ID,
                clientSoftwareJson = CLIENT_SOFTWARE,
                kind = RequestKind.PAIRING.storedName,
                state = InboxRequestState.WAITING.storedName,
                listed = true,
                requestJson = "{}",
                responseJson = "{}",
                error = null,
                receivedAt = 2L,
                completedAt = null,
                exchangeEndedAt = 3L,
                responseOutboxFinished = true,
            )
        val attempt =
            PairingAttemptEntity(
                requestId = CLIENT_ID,
                pairingAddress = ADDRESS,
                friendlyName = "Developer laptop",
                deviceRandom = ByteArray(32),
                desiredRelayClientState = RelayClientState.ACTIVE.wireName,
                relayClientState = relayState.wireName,
                state = state.storedName,
                sasOption0 = 1L,
                sasOption1 = 2L,
                sasOption2 = 3L,
                correctSasIndex = 1,
                platform = "linux",
                architecture = "x86_64",
                hostname = "survo",
                machineId = "machine-id",
                osVersion = "NixOS",
                pendingPsk = null,
                decidedAt = 4L,
            )
        database
            .requestDao()
            .insertPairingRequest(
                request = request,
                attempt = material.withEncryptedPendingPsk(attempt, request, PENDING_PSK),
            )
    }

    private suspend fun requestPsk() =
        material.encryptRequestPsk(
            deviceIdentityId = DEVICE_IDENTITY_ID,
            clientId = CLIENT_ID,
            relayRequestId = FINISH_REQUEST_ID,
            clientPsk = PENDING_PSK,
        )

    private fun credentials() =
        RelayDeviceCredentials(
            deviceIdentityId = DEVICE_IDENTITY_ID,
            address = ADDRESS,
            addressId = "address-id",
            deviceId = DEVICE_ID,
            devicePublicKey = ByteArray(32),
            devicePrivateKey = ByteArray(32),
            deviceToken = "token",
        )

    private fun finishPlaintext() =
        """{$CLIENT_SOFTWARE_FIELDS,"method":"PairingFinish"}""".encodeToByteArray()

    private class InsertThenFailAuditSink(private val delegate: AuditSink) : AuditSink {
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
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val OTHER_CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3W"
        const val FINISH_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P3Y"
        const val ADDRESS = "quiet-river-maple"
        const val NOW = 10L
        const val CLIENT_SOFTWARE_FIELDS =
            "\"app_info\":{\"name\":\"agentknock-cli\",\"version\":\"0.3.0\"}," +
                "\"lib_info\":{\"name\":\"agentknock\",\"version\":\"0.3.0\"}"
        const val CLIENT_SOFTWARE = "{$CLIENT_SOFTWARE_FIELDS}"
        val PENDING_PSK = ByteArray(32) { (it + 1).toByte() }
        val REQUEST: JsonElement = Json.parseToJsonElement("""{"ciphertext":"request"}""")
        val RESPONSE: JsonElement = Json.parseToJsonElement("""{"ciphertext":"response"}""")
    }
}
