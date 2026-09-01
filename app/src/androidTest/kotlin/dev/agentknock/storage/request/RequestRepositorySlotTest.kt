package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.protocol.PairedRequestProtocol
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.relay.RelayClientState
import dev.agentknock.relay.RelayDeviceClient
import dev.agentknock.relay.RelayDeviceConnection
import dev.agentknock.relay.RelayDeviceConnectionResult
import dev.agentknock.relay.RelayDeviceEvent
import dev.agentknock.relay.RelayDeviceFrame
import dev.agentknock.relay.RelayExchangeState
import dev.agentknock.relay.RelayMessageKind
import dev.agentknock.relay.RelayMessageState
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.relay.RelayApprovalReview
import dev.agentknock.relay.RelayApprovalReviewClient
import dev.agentknock.relay.RelayApprovalReviewDecision
import dev.agentknock.relay.RelayApprovalReviewResult
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.relay.ApprovalReviewRequest
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.EncryptionKeyStore
import dev.agentknock.storage.crypto.GeneratedEncryptionKey
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.device.RelayDeviceCredentialsResult
import dev.agentknock.storage.device.DeviceIdentityEntity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.decodeFromString
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
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
class RequestRepositorySlotTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var relay: QueuedRelayDeviceClient
    private lateinit var repository: RequestRepository
    private lateinit var inbox: RequestInbox
    private lateinit var secrets: SecretRepository
    private lateinit var credentials: RelayDeviceCredentials
    private lateinit var credentialSource: StaticCredentialSource
    private lateinit var protocolRandom: SwitchableSecureRandom
    private lateinit var reviewScope: CoroutineScope
    private lateinit var approvalReviewer: ControllableApprovalReviewer
    private var synchronizationRequests = 0
    private var now = CLIENT_ID.timestamp()

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        val keyStore = MemoryEncryptionKeyStore()
        var nextKeyId = 0
        val keyManager = VaultKeyManager(
            dao = database.vaultKeyDao(),
            keyStore = keyStore,
            newKeyId = { "slot-key-${nextKeyId++}" },
            currentTimeMillis = { now },
            keyStoreDispatcher = Dispatchers.Unconfined,
        )
        val encryption = AesGcmEncryption(keyStore)
        val devicePrivateKey = ByteArray(32) { 0x42 }
        val devicePublicKey = X25519PrivateKeyParameters(devicePrivateKey, 0)
            .generatePublicKey().encoded
        credentials = RelayDeviceCredentials(
            deviceIdentityId = DEVICE_IDENTITY_ID,
            address = ADDRESS,
            addressId = ADDRESS_ID,
            deviceId = DEVICE_ID,
            devicePublicKey = devicePublicKey,
            devicePrivateKey = devicePrivateKey,
            deviceToken = "token",
        )
        database.deviceIdentityDao().insertIdentity(
            DeviceIdentityEntity(
                id = DEVICE_IDENTITY_ID,
                role = "active",
                address = ADDRESS,
                deviceId = DEVICE_ID,
                createdAt = now,
            ),
        )
        relay = QueuedRelayDeviceClient()
        val audit = AuditRepository(database.auditDao(), currentTimeMillis = { now })
        var nextSecretId = 0
        secrets = SecretRepository(
            dao = database.secretDao(),
            keyManager = keyManager,
            encryption = encryption,
            audit = audit,
            writeTransaction = RoomWriteTransaction(database),
            newId = { "secret-id-${nextSecretId++}" },
            currentTimeMillis = { now },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
        protocolRandom = SwitchableSecureRandom()
        reviewScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        approvalReviewer = ControllableApprovalReviewer()
        credentialSource = StaticCredentialSource(credentials)
        synchronizationRequests = 0
        inbox = RequestInbox(database.requestDao())
        val clients = ClientRepository(
            dao = database.requestDao(),
            temporaryAccessGrants = secrets.observeTemporaryAccessGrants(),
            audit = audit,
            writeTransaction = RoomWriteTransaction(database),
        )
        val requestMaterial = RequestMaterialStore(
            dao = database.requestDao(),
            keyManager = keyManager,
            encryption = encryption,
            newId = { "request-material-id" },
            currentTimeMillis = { now },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
        val secretManagement = SecretManagementRequests(
            dao = database.requestDao(),
            material = requestMaterial,
            secrets = secrets,
            audit = audit,
            writeTransaction = RoomWriteTransaction(database),
            currentTimeMillis = { now },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
        val invocationRequests = InvocationRequests(
            dao = database.requestDao(),
            secrets = secrets,
            audit = audit,
            writeTransaction = RoomWriteTransaction(database),
            currentTimeMillis = { now },
        )
        val gitSigningRequests = GitSigningRequests(
            dao = database.requestDao(),
            secrets = secrets,
            audit = audit,
            writeTransaction = RoomWriteTransaction(database),
            currentTimeMillis = { now },
        )
        val sshAuthenticationRequests = SshAuthenticationRequests(
            dao = database.requestDao(),
            secrets = secrets,
            audit = audit,
            writeTransaction = RoomWriteTransaction(database),
            currentTimeMillis = { now },
        )
        val pairingProtocol = PairingProtocol(random = protocolRandom)
        val pairingRequests = PairingRequests(
            dao = database.requestDao(),
            material = requestMaterial,
            audit = audit,
            writeTransaction = RoomWriteTransaction(database),
            pairingProtocol = pairingProtocol,
            json = Json,
            currentTimeMillis = { now },
        )
        repository = RequestRepository(
            dao = database.requestDao(),
            material = requestMaterial,
            deviceCredentials = credentialSource,
            secrets = secrets,
            clients = clients,
            secretManagement = secretManagement,
            invocationRequests = invocationRequests,
            gitSigningRequests = gitSigningRequests,
            sshAuthenticationRequests = sshAuthenticationRequests,
            pairingRequests = pairingRequests,
            approvalReviewer = approvalReviewer,
            relay = relay,
            aiReviews = AiReviewCoordinator(reviewScope),
            scheduleSynchronization = { synchronizationRequests += 1 },
            audit = audit,
            requestPushRegistration = {},
            pairedRequestProtocol = PairedRequestProtocol(random = protocolRandom),
            currentTimeMillis = { now },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        reviewScope.cancel()
        database.close()
    }

    @Test
    fun firstInvalidPairingCompletionEndsExchangeAndLaterCompletionIsIgnored() = runTest {
        val clientSecret = ByteArray(32) { it.toByte() }
        val pairingRequest = pairingRequest(clientSecret)
        val acceptedCompletion = pairingCompletion(clientSecret)

        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest,
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.Acknowledgement(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.RESPONSE,
            ),
        )
        val root = checkNotNull(database.requestDao().getRequestById(CLIENT_ID))
        assertNull(root.completionJson)
        assertEquals("exchange_pending", database.requestDao().getPairingAttempt(root.id)?.state)

        now += 1
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = Json.parseToJsonElement(
                    """{"key":"bad","secret":"bad","ciphertext":"bad"}""",
                ),
                addressId = ADDRESS_ID,
            ),
        )
        val requestAfterFailure = checkNotNull(database.requestDao().getRequestById(root.id))
        assertNull(requestAfterFailure.completionJson)
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, requestAfterFailure.state)
        val pairingAfterFailure = checkNotNull(database.requestDao().getPairingAttempt(root.id))
        assertEquals(PairingState.EXCHANGE_FAILED.storedName, pairingAfterFailure.state)
        assertEquals("The pairing message could not be verified.", requestAfterFailure.error)
        assertNotNull(requestAfterFailure.exchangeEndedAt)
        assertNull(pairingAfterFailure.pendingPsk)
        assertEquals(
            listOf(CLIENT_ID),
            inbox.observeRequests().first().map(InboxRequestSummary::id),
        )

        now += 1
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = acceptedCompletion,
                addressId = ADDRESS_ID,
            ),
        )
        val afterReplay = checkNotNull(database.requestDao().getRequestById(root.id))
        val pairingAfterReplay = checkNotNull(database.requestDao().getPairingAttempt(root.id))
        assertNull(afterReplay.completionJson)
        assertEquals(PairingState.EXCHANGE_FAILED.storedName, pairingAfterReplay.state)
        assertNull(pairingAfterReplay.pendingPsk)

        now += 1
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = Json.parseToJsonElement("""{"later":"ignored"}"""),
                addressId = ADDRESS_ID,
            ),
        )
        assertNull(database.requestDao().getRequestById(root.id)?.completionJson)
        assertNotNull(checkNotNull(database.requestDao().getRequestById(root.id)).exchangeEndedAt)
    }

    @Test
    fun failedInitialExchangeBlocksAnotherPairingUntilExplicitRejection() = runTest {
        val clientSecret = ByteArray(32) { it.toByte() }
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(clientSecret),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = Json.parseToJsonElement(
                    """{"key":"bad","secret":"bad","ciphertext":"bad"}""",
                ),
                addressId = ADDRESS_ID,
            ),
        )
        assertEquals(
            PairingState.EXCHANGE_FAILED.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )

        val blocked = connect(
            RelayDeviceEvent.Message(
                clientId = COLLISION_CLIENT_ID,
                requestId = COLLISION_CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(ByteArray(32) { (it + 1).toByte() }),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNull(database.requestDao().getRequestById(COLLISION_CLIENT_ID))
        assertFalse(blocked.sentFrames.any {
            it is RelayDeviceFrame.Acknowledgement && it.requestId == COLLISION_CLIENT_ID
        })

        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))
        val accepted = connect(
            RelayDeviceEvent.Message(
                clientId = COLLISION_CLIENT_ID,
                requestId = COLLISION_CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(ByteArray(32) { (it + 1).toByte() }),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.Acknowledgement(
                clientId = COLLISION_CLIENT_ID,
                requestId = COLLISION_CLIENT_ID,
                kind = RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.State(
                clientId = COLLISION_CLIENT_ID,
                requestId = COLLISION_CLIENT_ID,
                exchange = RelayExchangeState.OPEN,
                request = RelayMessageState.DELIVERED,
                response = RelayMessageState.DELIVERED,
                completion = RelayMessageState.ABSENT,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNotNull(database.requestDao().getRequestById(COLLISION_CLIENT_ID))
        assertTrue(accepted.sentFrames.any {
            it is RelayDeviceFrame.Acknowledgement && it.requestId == COLLISION_CLIENT_ID
        })
    }

    @Test
    fun internalInitialCompletionFailureStaysLiveAndUnacknowledged() = runTest {
        val material = pairingMaterial(ByteArray(32) { it.toByte() })
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(ByteArray(32) { it.toByte() }),
                addressId = ADDRESS_ID,
            ),
        )
        protocolRandom.fail = true
        val connection = connect(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = material.completion,
                addressId = ADDRESS_ID,
            ),
            relayState(CLIENT_ID, completion = RelayMessageState.DELIVERED),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val request = checkNotNull(database.requestDao().getRequestById(CLIENT_ID))
        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertNull(request.exchangeEndedAt)
        assertNull(request.error)
        assertEquals(PairingState.EXCHANGE_PENDING.storedName, attempt.state)
        assertNull(attempt.pendingPsk)
        assertFalse(connection.sentFrames.any {
            it is RelayDeviceFrame.Acknowledgement &&
                it.requestId == CLIENT_ID &&
                it.kind == RelayMessageKind.COMPLETION
        })
    }

    @Test
    fun authenticatedInitialPairingCompletionEndsExchangeWithoutResponseStatus() = runTest {
        val clientSecret = ByteArray(32) { it.toByte() }
        connect(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(clientSecret),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.Failed("disconnect before response status"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect before response status"),
            repository.sync(),
        )
        assertFalse(
            checkNotNull(database.requestDao().getRequestById(CLIENT_ID)).responseOutboxFinished,
        )

        now += 1
        connect(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = pairingCompletion(clientSecret),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.Failed("disconnect after completion"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect after completion"),
            repository.sync(),
        )
        assertTrue(
            checkNotNull(database.requestDao().getRequestById(CLIENT_ID)).responseOutboxFinished,
        )
    }

    @Test
    fun retryableRelayErrorPreservesServerRetryDelay() = runTest {
        connect(
            RelayDeviceEvent.Error(
                code = "RATE_LIMITED",
                message = "retry later",
                retryable = true,
                clientId = null,
                requestId = null,
                kind = null,
                retryAfterMillis = 60_000,
            ),
        )

        assertEquals(
            RequestSyncResult.RelayUnavailable("retry later", 60_000),
            repository.sync(),
        )
    }

    @Test
    fun openAndClosingStatesDoNotEndExchangeButClosingFinishesResponseOutbox() = runTest {
        insertOpenUnknownRequest()
        connect(
            relayState(UNSUPPORTED_REQUEST_ID).copy(
                exchange = RelayExchangeState.CLOSING,
                response = RelayMessageState.ABSENT,
            ),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        val closing = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertTrue(closing.responseOutboxFinished)
        assertNull(closing.exchangeEndedAt)

        now += 1
        connect(
            relayState(UNSUPPORTED_REQUEST_ID).copy(exchange = RelayExchangeState.SETTLED),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val settled = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertEquals(now, settled.exchangeEndedAt)
        assertEquals(now, settled.completedAt)
    }

    @Test
    fun responseInactiveOnlyFinishesOutboxAndLeavesExchangeOpen() = runTest {
        insertOpenUnknownRequest()
        connect(
            RelayDeviceEvent.Inactive(
                CLIENT_ID,
                UNSUPPORTED_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertTrue(stored.responseOutboxFinished)
        assertNull(stored.exchangeEndedAt)
    }

    @Test
    fun bareInactiveEndsExchange() = runTest {
        insertOpenUnknownRequest()
        connect(
            RelayDeviceEvent.Inactive(CLIENT_ID, UNSUPPORTED_REQUEST_ID, null),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertEquals(now, stored.exchangeEndedAt)
        assertEquals(now, stored.completedAt)
        assertTrue(stored.responseOutboxFinished)
    }

    @Test
    fun requestAndCompletionInactiveEventsLeaveExchangeOpen() = runTest {
        insertOpenUnknownRequest()
        connect(
            RelayDeviceEvent.Inactive(
                CLIENT_ID,
                UNSUPPORTED_REQUEST_ID,
                RelayMessageKind.REQUEST,
            ),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                UNSUPPORTED_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNull(
            database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID)?.exchangeEndedAt,
        )

        connect(
            RelayDeviceEvent.Inactive(
                CLIENT_ID,
                UNSUPPORTED_REQUEST_ID,
                RelayMessageKind.COMPLETION,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNull(
            database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID)?.exchangeEndedAt,
        )
    }

    @Test
    fun expiredStateEndsExchange() = runTest {
        insertOpenUnknownRequest()
        connect(
            RelayDeviceEvent.State(
                clientId = CLIENT_ID,
                requestId = UNSUPPORTED_REQUEST_ID,
                exchange = RelayExchangeState.EXPIRED,
                request = RelayMessageState.DELIVERED,
                response = RelayMessageState.ABSENT,
                completion = RelayMessageState.ABSENT,
            ),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertNotNull(stored.exchangeEndedAt)
        assertTrue(checkNotNull(stored.error).contains("expired"))
    }

    @Test
    fun acceptedAndDiscardedResponseStatesFinishOnlyTheOutbox() = runTest {
        insertOpenUnknownRequest()
        for (responseState in listOf(RelayMessageState.ACCEPTED, RelayMessageState.DISCARDED)) {
            val current = checkNotNull(
                database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID),
            )
            database.requestDao().updateRequest(current.copy(responseOutboxFinished = false))
            connect(
                RelayDeviceEvent.State(
                    clientId = CLIENT_ID,
                    requestId = UNSUPPORTED_REQUEST_ID,
                    exchange = RelayExchangeState.OPEN,
                    request = RelayMessageState.DELIVERED,
                    response = responseState,
                    completion = RelayMessageState.ABSENT,
                ),
                RelayDeviceEvent.CaughtUp,
            )

            assertEquals(RequestSyncResult.Success, repository.sync())
            val stored = checkNotNull(
                database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID),
            )
            assertTrue(stored.responseOutboxFinished)
            assertNull(stored.exchangeEndedAt)
        }
    }

    @Test
    fun authenticatedUnknownCompletionEndsExchangeWithoutResponseStatus() = runTest {
        val clientPsk = establishActivePairing()
        val exchange = pairedExchange(
            requestId = UNSUPPORTED_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext = unsupportedPlaintext(),
            completionPlaintext = "{}".encodeToByteArray(),
        )
        assertCompletionEndsExchangeWithoutResponseStatus(UNSUPPORTED_REQUEST_ID, exchange)
    }

    @Test
    fun unsupportedStoredRequestEncryptionFormatEndsCompletionAsInvalid() = runTest {
        val clientPsk = establishActivePairing()
        val exchange = pairedExchange(
            requestId = UNSUPPORTED_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext = unsupportedPlaintext(),
            completionPlaintext = "{}".encodeToByteArray(),
        )
        connect(
            requestEvent(UNSUPPORTED_REQUEST_ID, exchange.request),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                UNSUPPORTED_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.Failed("disconnect before completion"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect before completion"),
            repository.sync(),
        )
        database.useWriterConnection { connection ->
            connection.executeSQL(
                "UPDATE request_psks SET encryption_format = 999 " +
                    "WHERE request_id = '$UNSUPPORTED_REQUEST_ID'",
            )
        }

        connect(
            completionEvent(UNSUPPORTED_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val ended = checkNotNull(
            database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID),
        )
        assertNotNull(ended.exchangeEndedAt)
        assertNull(ended.completionJson)
        assertEquals("The completion could not be verified.", ended.error)
        assertNull(database.requestDao().getRequestPsk(UNSUPPORTED_REQUEST_ID))
    }

    @Test
    fun authenticatedPairingRemovalCompletionEndsExchangeWithoutResponseStatus() = runTest {
        val clientPsk = establishActivePairing()
        val exchange = pairedExchange(
            requestId = REMOVE_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext =
                """{${clientSoftwareFields()},"method":"PairingRemove"}"""
                    .encodeToByteArray(),
            completionPlaintext = """{${clientSoftwareFields()}}""".encodeToByteArray(),
        )
        assertCompletionEndsExchangeWithoutResponseStatus(REMOVE_REQUEST_ID, exchange)
    }

    @Test
    fun responseDeliveryDoesNotCompletePairingRemoval() = runTest {
        val clientPsk = establishActivePairing()
        val exchange = pairedExchange(
            requestId = REMOVE_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext =
                """{${clientSoftwareFields()},"method":"PairingRemove"}"""
                    .encodeToByteArray(),
            completionPlaintext = """{${clientSoftwareFields()}}""".encodeToByteArray(),
        )
        connect(
            requestEvent(REMOVE_REQUEST_ID, exchange.request),
            RelayDeviceEvent.State(
                clientId = CLIENT_ID,
                requestId = REMOVE_REQUEST_ID,
                exchange = RelayExchangeState.OPEN,
                request = RelayMessageState.DELIVERED,
                response = RelayMessageState.DELIVERED,
                completion = RelayMessageState.ABSENT,
            ),
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
            RelayDeviceEvent.CaughtUp,
            RelayDeviceEvent.State(
                clientId = CLIENT_ID,
                requestId = REMOVE_REQUEST_ID,
                exchange = RelayExchangeState.OPEN,
                request = RelayMessageState.DELIVERED,
                response = RelayMessageState.DELIVERED,
                completion = RelayMessageState.ABSENT,
            ),
        )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val request = checkNotNull(database.requestDao().getRequestById(REMOVE_REQUEST_ID))
        assertTrue(request.responseOutboxFinished)
        assertNull(request.completedAt)
        assertNull(request.exchangeEndedAt)
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertFalse(
            AuditRepository(database.auditDao()).observeEvents().first().any {
                it.type == AuditEventType.CLIENT_UNPAIRED_ITSELF &&
                    it.relayRequestId == REMOVE_REQUEST_ID
            },
        )
    }

    @Test
    fun invalidPairingRemovalCompletionUsesSafeFailureCategory() = runTest {
        val clientPsk = establishActivePairing()
        val malicious = "raw-client-removal-parser-input"
        val exchange = pairedExchange(
            requestId = REMOVE_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext =
                """{${clientSoftwareFields()},"method":"PairingRemove"}"""
                    .encodeToByteArray(),
            completionPlaintext = "{not-json-$malicious".encodeToByteArray(),
        )
        connect(
            requestEvent(REMOVE_REQUEST_ID, exchange.request),
            RelayDeviceEvent.Acknowledgement(
                clientId = CLIENT_ID,
                requestId = REMOVE_REQUEST_ID,
                kind = RelayMessageKind.RESPONSE,
            ),
            completionEvent(REMOVE_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val request = checkNotNull(database.requestDao().getRequestById(REMOVE_REQUEST_ID))
        assertEquals("Client removal completion could not be verified.", request.error)
        val completionAudit = AuditRepository(database.auditDao()).observeEvents().first()
            .single { it.type == AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED }
        assertEquals(
            "Client removal completion could not be verified.",
            completionAudit.detail,
        )
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    @Test
    fun authenticatedFinishCompletionEndsExchangeWithoutResponseStatus() = runTest {
        val material = verifyPairingSas()
        connect(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            relayState(CLIENT_ID, completion = RelayMessageState.DELIVERED),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val exchange = pairedExchange(
            requestId = FINISH_REQUEST_ID,
            clientPsk = material.clientPsk,
            requestPlaintext =
                """{${clientSoftwareFields()},"method":"PairingFinish"}"""
                    .encodeToByteArray(),
            completionPlaintext =
                """{${clientSoftwareFields()},"result":"ACCEPTED"}"""
                    .encodeToByteArray(),
        )
        assertCompletionEndsExchangeWithoutResponseStatus(FINISH_REQUEST_ID, exchange)
    }

    @Test
    fun pairingWithACompletionErrorRemainsUserRejectable() = runTest {
        val clientSecret = ByteArray(32) { it.toByte() }
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(clientSecret),
                addressId = ADDRESS_ID,
            ),
        )
        now += 1
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = Json.parseToJsonElement(
                    """{"key":"bad","secret":"bad","ciphertext":"bad"}""",
                ),
                addressId = ADDRESS_ID,
            ),
        )

        assertNotNull(database.requestDao().getRequestById(CLIENT_ID)?.error)
        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))
        val rejected = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals("rejected", rejected.state)
        assertNull(rejected.pendingPsk)
    }

    @Test
    fun verifiedPairingRemainsRejectableWhileWaitingForFinish() = runTest {
        verifyPairingSas()

        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))

        val rejected = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(PairingState.REJECTED.storedName, rejected.state)
        assertNull(rejected.pendingPsk)
        assertNotNull(rejected.decidedAt)
    }

    @Test
    fun wrongSasRejectsWithoutRecordingACodeAcceptedTimestamp() = runTest {
        receivePairingUntilSas()
        val pairing = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        val wrongIndex = (checkNotNull(pairing.correctSasIndex) + 1) % 3

        assertEquals(
            PairingDecisionResult.REJECTED,
            repository.chooseSas(CLIENT_ID, wrongIndex),
        )

        val rejected = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(PairingState.REJECTED.storedName, rejected.state)
        assertNull(rejected.decidedAt)
    }

    @Test
    fun terminalRootEventsAfterInitialCompletionPreserveSasAndPendingPsk() = runTest {
        receivePairingUntilSas()
        val before = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertNotNull(before.pendingPsk)

        connect(
            relayState(CLIENT_ID).copy(exchange = RelayExchangeState.EXPIRED),
            RelayDeviceEvent.Inactive(CLIENT_ID, CLIENT_ID, null),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val after = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(PairingState.SAS_VERIFICATION_PENDING.storedName, after.state)
        assertEquals(before.sasOption0, after.sasOption0)
        assertEquals(before.sasOption1, after.sasOption1)
        assertEquals(before.sasOption2, after.sasOption2)
        assertEquals(before.correctSasIndex, after.correctSasIndex)
        assertNotNull(after.pendingPsk)
    }

    @Test
    fun completionAfterPairingWasRejectedEndsTheRelayExchange() = runTest {
        val clientSecret = ByteArray(32) { it.toByte() }
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(clientSecret),
                addressId = ADDRESS_ID,
            ),
        )
        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))
        assertNull(database.requestDao().getRequestById(CLIENT_ID)?.exchangeEndedAt)

        val connection = connect(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = pairingCompletion(clientSecret),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertNotNull(database.requestDao().getRequestById(CLIENT_ID)?.exchangeEndedAt)
        assertEquals(
            PairingState.REJECTED.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )
        assertTrue(
            connection.sentFrames.contains(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    CLIENT_ID,
                    RelayMessageKind.COMPLETION,
                ),
            ),
        )
    }

    @Test
    fun rejectedPendingPairingRevokesAClientThatActivatesLateOnTheSameConnection() = runTest {
        receivePairingUntilSas()
        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))

        val connection = connect(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            RelayDeviceEvent.CaughtUp,
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertTrue(
            connection.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.REVOKED),
            ),
        )
        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals("rejected", attempt.state)
        assertEquals("revoked", attempt.relayClientState)
        assertNull(attempt.desiredRelayClientState)
        assertNull(database.requestDao().getClient(CLIENT_ID))
    }

    @Test
    fun finishBeforeActivationPromotesPendingClientThenAppliesActiveState() = runTest {
        val material = verifyPairingSas()
        val finish = finishRequest(material)

        val connection = connect(
            requestEvent(FINISH_REQUEST_ID, finish),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                FINISH_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            relayState(CLIENT_ID, completion = RelayMessageState.DELIVERED),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertTrue(
            connection.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.ACTIVE),
            ),
        )
        val client = checkNotNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals("active", client.relayClientState)
        assertNull(client.desiredRelayClientState)
        assertNotNull(database.requestDao().getClientPsk(CLIENT_ID, "current"))
        val finishRequest = checkNotNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertEquals(InboxRequestState.COMPLETED.storedName, finishRequest.state)
        assertNull(finishRequest.exchangeEndedAt)
        assertNotNull(database.requestDao().getRequestPsk(FINISH_REQUEST_ID))
    }

    @Test
    fun suspendedBeforeFinishPromotesSuspendedClientWithoutInventingActiveState() = runTest {
        assertSuspendedFinishOutcome(stateBeforeFinish = true)
    }

    @Test
    fun suspendedAfterFinishProducesTheSameDurableClientState() = runTest {
        assertSuspendedFinishOutcome(stateBeforeFinish = false)
    }

    @Test
    fun revokedBeforeFinishRecordsReplayableResponseWithoutDurableClient() = runTest {
        val material = verifyPairingSas()
        val stateConnection = connect(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
            RelayDeviceEvent.Failed("disconnect after revocation"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect after revocation"),
            repository.sync(),
        )
        assertTrue(
            stateConnection.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.ACTIVE),
            ),
        )

        val finish = finishRequest(material)
        val finishConnection = connect(
            requestEvent(FINISH_REQUEST_ID, finish),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                FINISH_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.Failed("disconnect after finish"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect after finish"),
            repository.sync(),
        )

        val stored = checkNotNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        val persistedResponse = Json.parseToJsonElement(checkNotNull(stored.responseJson))
        assertEquals("completed", stored.state)
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertNull(database.requestDao().getClientPsk(CLIENT_ID, "current"))
        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals("completed", attempt.state)
        assertEquals("revoked", attempt.relayClientState)
        assertNull(attempt.desiredRelayClientState)
        assertFalse(
            AuditRepository(database.auditDao()).observeEvents().first().any {
                it.type == AuditEventType.PAIRING_COMPLETED && it.relayRequestId == CLIENT_ID
            },
        )

        val replay = connect(
            requestEvent(FINISH_REQUEST_ID, finish),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                FINISH_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            relayState(FINISH_REQUEST_ID, completion = RelayMessageState.DELIVERED).copy(
                exchange = RelayExchangeState.SETTLED,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(
            replay.sentFrames.contains(
                RelayDeviceFrame.Response(CLIENT_ID, FINISH_REQUEST_ID, persistedResponse),
            ),
        )
    }

    @Test
    fun revokedAfterFinishProducesNoDurableClient() = runTest {
        val material = verifyPairingSas()
        val finish = finishRequest(material)
        connect(
            requestEvent(FINISH_REQUEST_ID, finish),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                FINISH_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
            relayState(CLIENT_ID, completion = RelayMessageState.DELIVERED),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertNull(database.requestDao().getClientPsk(CLIENT_ID, "current"))
        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals("completed", attempt.state)
        assertEquals("revoked", attempt.relayClientState)
        assertNull(attempt.desiredRelayClientState)
    }

    @Test
    fun authenticatedInvocationReplayKeepsOneRowAndResendsThePersistedResponse() = runTest {
        val clientPsk = establishActivePairing()
        createSigningSecret()
        val token = ByteArray(32) { (0x60 + it).toByte() }
        val invocation = pairedRequest(
            requestId = INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = invocationPlaintext(token),
        )

        val interrupted = connect(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = INVOCATION_REQUEST_ID,
                kind = RelayMessageKind.REQUEST,
                payload = invocation,
                addressId = null,
            ),
            RelayDeviceEvent.Failed("connection lost after response"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("connection lost after response"),
            repository.sync(),
        )

        val stored = checkNotNull(
            database.requestDao().getRequestById(INVOCATION_REQUEST_ID),
        )
        val storedInvocation = checkNotNull(database.requestDao().getSecretUseRequest(stored.id))
        val persistedResponse = Json.parseToJsonElement(checkNotNull(stored.responseJson))
        assertEquals(invocation.toString(), stored.requestJson)
        assertEquals("waiting", stored.state)
        assertFalse(stored.responseOutboxFinished)
        assertEquals("approved", storedInvocation.decision)
        assertEquals("git-signing", storedInvocation.secretsJson.removeSurrounding("[\"", "\"]"))
        assertEquals(
            listOf(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    INVOCATION_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
                RelayDeviceFrame.Response(CLIENT_ID, INVOCATION_REQUEST_ID, persistedResponse),
            ),
            interrupted.sentFrames,
        )

        // Replay is owned by the immutable request snapshot and its request-scoped PSK. It must
        // still work after the durable client has been removed and its live PSKs have cascaded.
        assertEquals(1, database.requestDao().deleteClient(CLIENT_ID))
        assertNull(database.requestDao().getClient(CLIENT_ID))

        now += 1
        val replay = connect(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = INVOCATION_REQUEST_ID,
                kind = RelayMessageKind.REQUEST,
                payload = invocation,
                addressId = null,
            ),
            RelayDeviceEvent.Acknowledgement(
                clientId = CLIENT_ID,
                requestId = INVOCATION_REQUEST_ID,
                kind = RelayMessageKind.RESPONSE,
            ),
            relayState(INVOCATION_REQUEST_ID),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertEquals(
            listOf(
                RelayDeviceFrame.Response(CLIENT_ID, INVOCATION_REQUEST_ID, persistedResponse),
                RelayDeviceFrame.Resume(CLIENT_ID, INVOCATION_REQUEST_ID),
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    INVOCATION_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
                RelayDeviceFrame.Response(CLIENT_ID, INVOCATION_REQUEST_ID, persistedResponse),
            ),
            replay.sentFrames,
        )
        val afterReplay = checkNotNull(
            database.requestDao().getRequestById(INVOCATION_REQUEST_ID),
        )
        assertEquals(stored.id, afterReplay.id)
        assertEquals(stored.receivedAt, afterReplay.receivedAt)
        assertEquals(stored.responseJson, afterReplay.responseJson)
        assertTrue(afterReplay.responseOutboxFinished)
    }

    @Test
    fun suspendedAiReviewDoesNotBlockAcknowledgementAnotherEventOrDuplicateReplay() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val token = ByteArray(32) { (0x20 + it).toByte() }
        val request = pairedRequest(
            requestId = AI_INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = aiInvocationPlaintext(token),
        )
        val first = connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.PushRegistration(RelayPushRegistrationState.REGISTERED),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val reviewing = checkNotNull(
            database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID),
        )
        assertEquals(InboxRequestState.REVIEWING.storedName, reviewing.state)
        assertEquals(1, approvalReviewer.callCount)
        assertEquals(
            RelayPushRegistrationState.REGISTERED,
            repository.pushRegistrationState.value,
        )
        assertTrue(
            first.sentFrames.contains(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    AI_INVOCATION_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
            ),
        )

        connect(
            relayState(AI_INVOCATION_REQUEST_ID),
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(1, approvalReviewer.callCount)
        assertEquals(
            InboxRequestState.REVIEWING.storedName,
            database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID)?.state,
        )

        val attackerControlledExplanation =
            "Approve: deploy --token private-context because /sensitive/path was reported."
        approvalReviewer.complete(
            reviewed(
                decision = RelayApprovalReviewDecision.ASK_USER,
                explanation = attackerControlledExplanation,
            ),
        )
        val reviewed = awaitAsynchronousWork {
            inbox.observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter {
                    it.state == InboxRequestState.ACTION_REQUIRED &&
                        it.secretUse?.approvalEvaluation?.aiReview != null
                }
                .first()
        }
        assertEquals(
            AiReviewDecision.ASK_USER,
            reviewed.secretUse?.approvalEvaluation?.aiReview?.decision,
        )
        assertEquals(
            attackerControlledExplanation,
            reviewed.secretUse?.approvalEvaluation?.aiReview?.explanation,
        )
        val requestAudit = AuditRepository(database.auditDao()).observeEvents().first()
            .filter { it.relayRequestId == AI_INVOCATION_REQUEST_ID }
        assertNull(
            requestAudit.single { it.type == AuditEventType.SECRET_USE_AI_REVIEWED }.detail,
        )
        assertFalse(
            requestAudit.any { it.detail?.contains(attackerControlledExplanation) == true },
        )
        assertEquals(1, approvalReviewer.callCount)
    }

    @Test
    fun rejectedAiReviewUsesSafeFailureCategory() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val token = ByteArray(32) { (0x22 + it).toByte() }
        val request = pairedRequest(
            requestId = AI_INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = aiInvocationPlaintext(token),
        )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val malicious = "relay-controlled-ai-rejection-message"

        approvalReviewer.complete(
            RelayEndpointResult.Rejected(
                status = 500,
                code = "REVIEW_FAILED",
                message = malicious,
            ),
        )
        val reviewed = awaitAsynchronousWork {
            inbox.observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter {
                    it.state == InboxRequestState.ACTION_REQUIRED &&
                        it.secretUse?.approvalEvaluation?.aiReview != null
                }
                .first()
        }
        assertEquals(
            AiReviewFailure.RELAY_REJECTED,
            reviewed.secretUse?.approvalEvaluation?.aiReview?.failure,
        )
        val requestAudit = AuditRepository(database.auditDao()).observeEvents().first()
            .filter { it.relayRequestId == AI_INVOCATION_REQUEST_ID }
        assertEquals(
            "The relay rejected AI review.",
            requestAudit.single { it.type == AuditEventType.SECRET_USE_AI_REVIEWED }.detail,
        )
        assertFalse(requestAudit.any { it.detail?.contains(malicious) == true })
    }

    @Test
    fun aiReviewCompletionRevalidatesChangedApprovalSettings() = runTest {
        val clientPsk = establishActivePairing()
        val secretId = createAiEnvironmentSecret()
        val token = ByteArray(32) { (0x21 + it).toByte() }
        val request = pairedRequest(
            requestId = AI_INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = aiInvocationPlaintext(token),
        )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(SaveSecretResult.SAVED, secrets.saveApprovalMode(secretId, SecretApprovalMode.ASK_ME))

        approvalReviewer.complete(
            reviewed(
                decision = RelayApprovalReviewDecision.APPROVE,
                explanation = "The original settings allow this request.",
            ),
        )
        val reviewed = awaitAsynchronousWork {
            inbox.observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter {
                    it.state == InboxRequestState.ACTION_REQUIRED &&
                        it.secretUse?.approvalEvaluation?.aiReview != null
                }
                .first()
        }
        assertNull(database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID)?.responseJson)
        assertEquals(
            AiReviewDecision.ASK_USER,
            reviewed.secretUse?.approvalEvaluation?.aiReview?.decision,
        )
        assertTrue(
            reviewed.secretUse?.approvalEvaluation?.aiReview?.explanation
                ?.contains("changed during AI review") == true,
        )
    }

    @Test
    fun aiApprovalSchedulesAndDeliversItsDurableResponse() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request = pairedRequest(
            requestId = AI_INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = aiInvocationPlaintext(ByteArray(32) { (0x22 + it).toByte() }),
        )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val synchronizationsBeforeReview = synchronizationRequests

        approvalReviewer.complete(
            reviewed(
                decision = RelayApprovalReviewDecision.APPROVE,
                explanation = "The request follows the supplied instructions.",
            ),
        )
        val stored = awaitAsynchronousWork {
            database.requestDao().observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter { it.responseJson != null }
                .first()
        }
        val persistedResponse = Json.parseToJsonElement(checkNotNull(stored.responseJson))
        awaitAsynchronousWork {
            while (synchronizationRequests <= synchronizationsBeforeReview) delay(1)
        }
        assertTrue(synchronizationRequests > synchronizationsBeforeReview)
        assertTrue(repository.hasPendingRelayWork())

        val delivery = connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                AI_INVOCATION_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            relayState(AI_INVOCATION_REQUEST_ID),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(
            delivery.sentFrames.contains(
                RelayDeviceFrame.Response(
                    CLIENT_ID,
                    AI_INVOCATION_REQUEST_ID,
                    persistedResponse,
                ),
            ),
        )
        assertTrue(
            checkNotNull(database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID))
                .responseOutboxFinished,
        )
    }

    @Test
    fun aiReviewEscalatesWhenClientInstructionsChange() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request = pairedRequest(
            requestId = AI_INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = aiInvocationPlaintext(ByteArray(32) { (0x23 + it).toByte() }),
        )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            ClientChangeResult.CHANGED,
            repository.saveClientInstructions(
                CLIENT_ID,
                "Never deploy to production from this client.",
            ),
        )

        approvalReviewer.complete(
            reviewed(
                decision = RelayApprovalReviewDecision.APPROVE,
                explanation = "The original instructions allow the request.",
            ),
        )
        val reviewed = awaitAsynchronousWork {
            inbox.observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter {
                    it.state == InboxRequestState.ACTION_REQUIRED &&
                        it.secretUse?.approvalEvaluation?.aiReview != null
                }
                .first()
        }
        assertNull(database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID)?.responseJson)
        assertEquals(
            AiReviewDecision.ASK_USER,
            reviewed.secretUse?.approvalEvaluation?.aiReview?.decision,
        )
        assertTrue(
            reviewed.secretUse?.approvalEvaluation?.aiReview?.explanation
                ?.contains("instructions") == true,
        )
    }

    @Test
    fun aiApprovalEscalatesWhenDeviceInstructionsChange() = runTest {
        assertAiReviewEscalatesWhenDeviceInstructionsChange(
            RelayApprovalReviewDecision.APPROVE,
        )
    }

    @Test
    fun aiDenialEscalatesWhenDeviceInstructionsChange() = runTest {
        assertAiReviewEscalatesWhenDeviceInstructionsChange(
            RelayApprovalReviewDecision.DENY,
        )
    }

    @Test
    fun aiApprovalEscalatesWhenClientNameChanges() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request = pairedRequest(
            requestId = AI_INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = aiInvocationPlaintext(ByteArray(32) { (0x26 + it).toByte() }),
        )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            ClientChangeResult.CHANGED,
            repository.renameClient(CLIENT_ID, "Renamed during review"),
        )

        approvalReviewer.complete(
            reviewed(
                decision = RelayApprovalReviewDecision.APPROVE,
                explanation = "The original client name influenced this verdict.",
            ),
        )
        val reviewed = awaitAsynchronousWork {
            inbox.observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter {
                    it.state == InboxRequestState.ACTION_REQUIRED &&
                        it.secretUse?.approvalEvaluation?.aiReview != null
                }
                .first()
        }

        assertNull(database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID)?.responseJson)
        assertEquals(
            AiReviewDecision.ASK_USER,
            reviewed.secretUse?.approvalEvaluation?.aiReview?.decision,
        )
    }

    private suspend fun assertAiReviewEscalatesWhenDeviceInstructionsChange(
        decision: RelayApprovalReviewDecision,
    ) {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request = pairedRequest(
            requestId = AI_INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = aiInvocationPlaintext(ByteArray(32) { (0x25 + it).toByte() }),
        )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val changedInstructions = "Never use production credentials for experiments."
        credentialSource.credentials = credentials.copy(instructions = changedInstructions)
        assertEquals(
            1,
            database.deviceIdentityDao().updateActiveInstructions(
                activeRole = "active",
                instructions = changedInstructions,
            ),
        )

        approvalReviewer.complete(
            reviewed(
                decision = decision,
                explanation = "The original device instructions determine this verdict.",
            ),
        )
        val reviewed = awaitAsynchronousWork {
            inbox.observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter {
                    it.state == InboxRequestState.ACTION_REQUIRED &&
                        it.secretUse?.approvalEvaluation?.aiReview != null
                }
                .first()
        }

        assertNull(database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID)?.responseJson)
        assertEquals(
            AiReviewDecision.ASK_USER,
            reviewed.secretUse?.approvalEvaluation?.aiReview?.decision,
        )
        assertTrue(
            reviewed.secretUse?.approvalEvaluation?.aiReview?.explanation
                ?.contains("instructions") == true,
        )
    }

    @Test
    fun aiReviewDefersIfClientWasRevokedDuringReview() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request = pairedRequest(
            requestId = AI_INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = aiInvocationPlaintext(ByteArray(32) { (0x24 + it).toByte() }),
        )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            ClientChangeResult.CHANGED,
            repository.setClientState(CLIENT_ID, RelayClientState.REVOKED),
        )
        val synchronizationsBeforeReview = synchronizationRequests

        approvalReviewer.complete(
            reviewed(
                decision = RelayApprovalReviewDecision.APPROVE,
                explanation = "The original client state allows the request.",
            ),
        )
        val stored = awaitAsynchronousWork {
            database.requestDao().observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter { it.state == InboxRequestState.ACTION_REQUIRED.storedName }
                .first()
        }
        val secretUse = checkNotNull(
            database.requestDao().getSecretUseRequest(AI_INVOCATION_REQUEST_ID),
        )
        assertNull(secretUse.decision)
        assertNull(
            Json.decodeFromString<ApprovalEvaluation>(
                checkNotNull(secretUse.approvalEvaluationJson),
            ).aiReview,
        )
        assertNull(stored.responseJson)
        awaitAsynchronousWork {
            while (synchronizationRequests <= synchronizationsBeforeReview) delay(1)
        }
        assertTrue(synchronizationRequests > synchronizationsBeforeReview)
    }

    @Test
    fun clientStateMutationRetriesWhenRelayReportsAContraryState() = runTest {
        establishActivePairing()
        assertEquals(
            ClientChangeResult.CHANGED,
            repository.setClientState(CLIENT_ID, RelayClientState.SUSPENDED),
        )

        val connection = connect(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            RelayDeviceEvent.CaughtUp,
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.SUSPENDED),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertEquals(
            2,
            connection.sentFrames.count {
                it == RelayDeviceFrame.SetClientState(
                    CLIENT_ID,
                    RelayClientState.SUSPENDED,
                )
            },
        )
        val client = checkNotNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals(RelayClientState.SUSPENDED.wireName, client.relayClientState)
        assertNull(client.desiredRelayClientState)
    }

    @Test
    fun pendingUnrestrictedInvocationReopensItsExactDeliverySelection() = runTest {
        assertPendingInvocationSelectionAfterVariableAdded(
            delivery = "{}",
            initialVariableNames = listOf("ORIGINAL"),
            expectedVariableNames = listOf("ADDED", "ORIGINAL"),
        )
    }

    @Test
    fun pendingInvocationPreservesOmittedVariablesWhenASecretChanges() = runTest {
        assertPendingInvocationSelectionAfterVariableAdded(
            delivery = """{"environment":{"omit":["OMITTED"]}}""",
            initialVariableNames = listOf("ORIGINAL", "OMITTED"),
            expectedVariableNames = listOf("ADDED", "ORIGINAL"),
        )
    }

    @Test
    fun pendingInvocationPreservesExplicitOnlyWhenASecretChanges() = runTest {
        assertPendingInvocationSelectionAfterVariableAdded(
            delivery = """{"environment":{"only":["ORIGINAL"]}}""",
            initialVariableNames = listOf("ORIGINAL"),
            expectedVariableNames = listOf("ORIGINAL"),
        )
    }

    @Test
    fun startupRecoveryEscalatesInterruptedReviewWithoutCallingReviewer() = runTest {
        database.requestDao().insertRequest(
            InboxRequestEntity(
                id = INTERRUPTED_REVIEW_REQUEST_ID,
                parentRequestId = null,
                deviceIdentityId = DEVICE_IDENTITY_ID,
                clientId = CLIENT_ID,
                clientNameSnapshot = "Interrupted client",
                clientSoftwareJson = null,
                kind = "secret_use",
                state = InboxRequestState.REVIEWING.storedName,
                listed = true,
                requestJson = "{}",
                responseJson = null,
                completionJson = null,
                error = null,
                receivedAt = now,
                completedAt = null,
                exchangeEndedAt = null,
                responseOutboxFinished = false,
            ),
        )

        now += 1
        assertEquals(1, repository.recoverInterruptedAiReviews())
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(INTERRUPTED_REVIEW_REQUEST_ID)?.state,
        )
        assertEquals(0, approvalReviewer.callCount)
    }

    @Test
    fun secretUploadApprovalCommitsSecretDecisionAuditAndStagingDeletionTogether() = runTest {
        receiveEnvironmentSecretUpload()

        val result = repository.approveSecretUpload(UPLOAD_REQUEST_ID, "uploaded-secret")

        assertTrue(result is SecretUploadDecisionResult.Approved)
        assertEquals(
            "approved",
            database.requestDao().getSecretUploadRequest(UPLOAD_REQUEST_ID)?.decision,
        )
        assertTrue(
            database.requestDao()
                .getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID)
                .isEmpty(),
        )
        assertEquals(
            1,
            database.secretDao().getSecretsByName(listOf("uploaded-secret")).size,
        )
        val decisionAudit = database.auditDao().observeEvents().first().single {
            it.eventType == "secret_upload_decided" && it.relayRequestId == UPLOAD_REQUEST_ID
        }
        assertEquals("approved", decisionAudit.outcome)
        assertEquals(now, decisionAudit.occurredAt)
    }

    @Test
    fun auditFailureRollsBackSecretUploadApprovalCompletely() = runTest {
        receiveEnvironmentSecretUpload()
        val requestBefore = database.requestDao().getRequestById(UPLOAD_REQUEST_ID)
        val uploadBefore = database.requestDao().getSecretUploadRequest(UPLOAD_REQUEST_ID)
        val auditBefore = database.auditDao().observeEvents().first()
        database.useWriterConnection { connection ->
            connection.executeSQL(
                """
                CREATE TRIGGER fail_secret_upload_decision_audit
                BEFORE INSERT ON audit_events
                WHEN NEW.event_type = 'secret_upload_decided'
                BEGIN
                    SELECT RAISE(ABORT, 'forced audit failure');
                END
                """.trimIndent(),
            )
        }

        val failure = runCatching {
            repository.approveSecretUpload(UPLOAD_REQUEST_ID, "uploaded-secret")
        }

        assertTrue(failure.isFailure)
        assertEquals(requestBefore, database.requestDao().getRequestById(UPLOAD_REQUEST_ID))
        assertEquals(uploadBefore, database.requestDao().getSecretUploadRequest(UPLOAD_REQUEST_ID))
        assertEquals(
            1,
            database.requestDao()
                .getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID)
                .size,
        )
        assertTrue(database.secretDao().getSecretsByName(listOf("uploaded-secret")).isEmpty())
        assertEquals(auditBefore, database.auditDao().observeEvents().first())
    }

    @Test
    fun terminalRequestIdCollisionIsAcknowledgedWithoutReplayingResponse() = runTest {
        establishActivePairing()
        val collision = connect(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = Json.parseToJsonElement(
                    """{"version":"agentknock-v1","commitment":"different"}""",
                ),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            listOf(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    CLIENT_ID,
                    RelayMessageKind.REQUEST,
                ),
            ),
            collision.sentFrames,
        )
        assertEquals(
            pairingRequest(ByteArray(32) { it.toByte() }).toString(),
            database.requestDao().getRequestById(CLIENT_ID)?.requestJson,
        )
    }

    @Test
    fun sameClientRequestAndPayloadFromAnotherIdentityIsNotTreatedAsReplay() = runTest {
        database.deviceIdentityDao().insertIdentity(
            DeviceIdentityEntity(
                id = RETIRED_DEVICE_IDENTITY_ID,
                role = "retired",
                address = "retired-address",
                deviceId = "01K2ENXDTW1P3XAR4J7V7C9D0J",
                createdAt = now - 1,
            ),
        )
        val payload = Json.parseToJsonElement("""{"same":"payload"}""")
        val stored = InboxRequestEntity(
            id = UNSUPPORTED_REQUEST_ID,
            parentRequestId = null,
            deviceIdentityId = RETIRED_DEVICE_IDENTITY_ID,
            clientId = CLIENT_ID,
            clientNameSnapshot = "Retired identity client",
            clientSoftwareJson = null,
            kind = "unsupported",
            state = InboxRequestState.WAITING.storedName,
            listed = false,
            requestJson = payload.toString(),
            responseJson = """{"old":"response"}""",
            completionJson = null,
            error = null,
            receivedAt = now - 1,
            completedAt = null,
            exchangeEndedAt = null,
            responseOutboxFinished = false,
        )
        database.requestDao().insertRequest(stored)
        val collision = connect(
            requestEvent(UNSUPPORTED_REQUEST_ID, payload),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(collision.sentFrames.isEmpty())
        assertEquals(stored, database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
    }

    @Test
    fun durableClientRejectionIsNotAcknowledgedWhenResponseSealingFails() = runTest {
        val clientPsk = establishActivePairing()
        protocolRandom.fail = true
        val request = pairedRequest(
            requestId = UNSUPPORTED_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = unsupportedPlaintext(),
        )
        val connection = connect(
            requestEvent(UNSUPPORTED_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertFalse(connection.sentFrames.any {
            it is RelayDeviceFrame.Acknowledgement && it.requestId == UNSUPPORTED_REQUEST_ID
        })
    }

    @Test
    fun pendingPairingRejectionIsNotAcknowledgedWhenResponseSealingFails() = runTest {
        val material = verifyPairingSas()
        connect(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            relayState(CLIENT_ID, completion = RelayMessageState.DELIVERED),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            "waiting_for_finish",
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )

        protocolRandom.fail = true
        val request = pairedRequest(
            requestId = UNSUPPORTED_REQUEST_ID,
            clientPsk = material.clientPsk,
            plaintext = unsupportedPlaintext(),
        )
        val connection = connect(
            requestEvent(UNSUPPORTED_REQUEST_ID, request),
            relayState(CLIENT_ID, completion = RelayMessageState.DELIVERED),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertFalse(connection.sentFrames.any {
            it is RelayDeviceFrame.Acknowledgement && it.requestId == UNSUPPORTED_REQUEST_ID
        })
    }

    @Test
    fun pairingStartRejectsClientIdAlreadyOwnedByDurableClient() = runTest {
        database.requestDao().insertClient(
            ClientEntity(
                clientId = COLLISION_CLIENT_ID,
                deviceIdentityId = DEVICE_IDENTITY_ID,
                name = "Existing client",
                instructions = "",
                desiredRelayClientState = null,
                relayClientState = "active",
                clientSoftwareJson = null,
                platform = null,
                architecture = null,
                hostname = null,
                machineId = null,
                osVersion = null,
                pairedAt = now,
                lastSeenAt = now,
            ),
        )
        val collision = connect(
            RelayDeviceEvent.Message(
                clientId = COLLISION_CLIENT_ID,
                requestId = COLLISION_CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(ByteArray(32) { it.toByte() }),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(collision.sentFrames.isEmpty())
        assertNull(database.requestDao().getRequestById(COLLISION_CLIENT_ID))
        assertNotNull(database.requestDao().getClient(COLLISION_CLIENT_ID))
    }

    @Test
    fun correlatedGitSigningDenialPersistsParentLinkAndReplaysExactResponse() = runTest {
        val clientPsk = establishActivePairing()
        createSigningSecret()
        val token = ByteArray(32) { (0x30 + it).toByte() }
        val invocation = pairedRequest(
            requestId = INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = invocationPlaintext(token),
        )
        val invocationConnection = connect(
            requestEvent(INVOCATION_REQUEST_ID, invocation),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                INVOCATION_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertFalse(invocationConnection.sentFrames.isEmpty())
        val parent = checkNotNull(
            database.requestDao().getRequestById(INVOCATION_REQUEST_ID),
        )

        now += 1
        val signing = pairedRequest(
            requestId = GIT_SIGN_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = gitSignPlaintext(token),
        )
        val received = connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(GIT_SIGN_REQUEST_ID, signing),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val child = checkNotNull(database.requestDao().getRequestById(GIT_SIGN_REQUEST_ID))
        val gitSign = checkNotNull(database.requestDao().getGitSignRequest(child.id))
        assertEquals(parent.id, child.parentRequestId)
        assertEquals(signing.toString(), child.requestJson)
        assertNull(child.responseJson)
        assertEquals("action_required", child.state)
        assertEquals("git-signing", gitSign.secretName)
        assertEquals("commit to sign", gitSign.message.decodeToString())
        assertEquals(
            listOf(
                RelayDeviceFrame.Resume(CLIENT_ID, INVOCATION_REQUEST_ID),
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    GIT_SIGN_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
            ),
            received.sentFrames,
        )

        now += 1
        assertEquals(GitSignDecisionResult.Decided, repository.denyGitSignRequest(child.id))
        val denied = checkNotNull(database.requestDao().getRequestById(child.id))
        val deniedSigning = checkNotNull(database.requestDao().getGitSignRequest(child.id))
        val persistedResponse = Json.parseToJsonElement(checkNotNull(denied.responseJson))
        assertEquals("waiting", denied.state)
        assertFalse(denied.responseOutboxFinished)
        assertEquals("denied", deniedSigning.decision)
        assertEquals("USER_DENIED", deniedSigning.completionReason)
        // Pairing belongs to Clients rather than request history. The active invocation and its
        // signing child remain because neither workflow has completed.
        assertNotNull(database.requestDao().getRequestById(parent.id))
        assertNotNull(database.requestDao().getRequestById(child.id))

        val replay = connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(GIT_SIGN_REQUEST_ID, signing),
            RelayDeviceEvent.Receipt(
                CLIENT_ID,
                GIT_SIGN_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            relayState(GIT_SIGN_REQUEST_ID),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                GIT_SIGN_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            listOf(
                RelayDeviceFrame.Response(CLIENT_ID, GIT_SIGN_REQUEST_ID, persistedResponse),
                RelayDeviceFrame.Resume(CLIENT_ID, INVOCATION_REQUEST_ID),
                RelayDeviceFrame.Resume(CLIENT_ID, GIT_SIGN_REQUEST_ID),
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    GIT_SIGN_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
                RelayDeviceFrame.Response(CLIENT_ID, GIT_SIGN_REQUEST_ID, persistedResponse),
            ),
            replay.sentFrames,
        )
        val afterReplay = checkNotNull(database.requestDao().getRequestById(child.id))
        assertEquals(denied.responseJson, afterReplay.responseJson)
        assertTrue(afterReplay.responseOutboxFinished)
    }

    @Test
    fun approvedGitSigningCompletesAndDiscardsItsRequestKey() = runTest {
        val invocation = establishSigningInvocation()
        val exchange = pairedExchange(
            requestId = GIT_SIGN_REQUEST_ID,
            clientPsk = invocation.clientPsk,
            requestPlaintext = gitSignPlaintext(invocation.token),
            completionPlaintext = approvedCompletionPlaintext(),
        )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(GIT_SIGN_REQUEST_ID, exchange.request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(GIT_SIGN_REQUEST_ID)?.state,
        )

        now += 1
        assertEquals(
            GitSignDecisionResult.Decided,
            repository.approveGitSignRequest(GIT_SIGN_REQUEST_ID),
        )
        val decided = checkNotNull(database.requestDao().getRequestById(GIT_SIGN_REQUEST_ID))
        assertEquals(InboxRequestState.WAITING.storedName, decided.state)
        assertNotNull(decided.responseJson)
        assertEquals(
            ApprovalDecision.APPROVED.storedName,
            database.requestDao().getGitSignRequest(GIT_SIGN_REQUEST_ID)?.decision,
        )

        now += 1
        connect(
            relayState(INVOCATION_REQUEST_ID),
            relayState(GIT_SIGN_REQUEST_ID),
            completionEvent(GIT_SIGN_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val completed = checkNotNull(database.requestDao().getRequestById(GIT_SIGN_REQUEST_ID))
        val completedSigning = checkNotNull(
            database.requestDao().getGitSignRequest(GIT_SIGN_REQUEST_ID),
        )
        assertEquals(InboxRequestState.COMPLETED.storedName, completed.state)
        assertEquals(ApprovalCompletionResult.APPROVED.storedName, completedSigning.completionResult)
        assertTrue(completed.responseOutboxFinished)
        assertNotNull(completed.exchangeEndedAt)
        assertNull(database.requestDao().getRequestPsk(GIT_SIGN_REQUEST_ID))
    }

    @Test
    fun gitAbortMessageStaysOutOfAudit() = runTest {
        val invocation = establishSigningInvocation()
        val malicious = "raw-git-abort-client-message"
        val exchange = pairedExchange(
            requestId = GIT_SIGN_REQUEST_ID,
            clientPsk = invocation.clientPsk,
            requestPlaintext = gitSignPlaintext(invocation.token),
            completionPlaintext = abortedCompletionPlaintext(malicious),
        )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(GIT_SIGN_REQUEST_ID, exchange.request),
            completionEvent(GIT_SIGN_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val completionAudit = AuditRepository(database.auditDao()).observeEvents().first()
            .single { it.type == AuditEventType.GIT_SIGN_COMPLETED }
        assertEquals("Git signing was aborted by the client.", completionAudit.detail)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    @Test
    fun invalidGitCompletionDoesNotRetainDecodedClientFields() = runTest {
        val invocation = establishSigningInvocation()
        val malicious = "raw-invalid-git-client-message"
        val exchange = pairedExchange(
            requestId = GIT_SIGN_REQUEST_ID,
            clientPsk = invocation.clientPsk,
            requestPlaintext = gitSignPlaintext(invocation.token),
            completionPlaintext = wrongSoftwareAbortedCompletionPlaintext(malicious),
        )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(GIT_SIGN_REQUEST_ID, exchange.request),
            completionEvent(GIT_SIGN_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val request = checkNotNull(database.requestDao().getRequestById(GIT_SIGN_REQUEST_ID))
        assertEquals("Git signing completion could not be verified.", request.error)
        val signing = checkNotNull(database.requestDao().getGitSignRequest(GIT_SIGN_REQUEST_ID))
        assertNull(signing.completionResult)
        assertNull(signing.completionReason)
        assertNull(signing.completionMessage)
        val completionAudit = AuditRepository(database.auditDao()).observeEvents().first()
            .single { it.type == AuditEventType.GIT_SIGN_COMPLETED }
        assertEquals("Git signing completion could not be verified.", completionAudit.detail)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    @Test
    fun deniedSshAuthenticationReplaysExactlyAndAcceptsMatchingCompletion() = runTest {
        val invocation = establishSigningInvocation()
        val exchange = pairedExchange(
            requestId = SSH_AUTHENTICATION_REQUEST_ID,
            clientPsk = invocation.clientPsk,
            requestPlaintext = sshAuthenticationPlaintext(invocation.token, invocation.key),
            completionPlaintext = deniedSshAuthenticationCompletionPlaintext(),
        )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val received = checkNotNull(
            database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID),
        )
        val authentication = checkNotNull(
            database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID),
        )
        assertEquals(INVOCATION_REQUEST_ID, received.parentRequestId)
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, received.state)
        assertEquals("git-signing", authentication.secretName)
        assertEquals("deploy", authentication.username)
        assertEquals("publickey", authentication.method)
        assertEquals("ssh-ed25519", authentication.algorithm)

        now += 1
        assertEquals(
            SshAuthenticationDecisionResult.Decided,
            repository.denySshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID),
        )
        val denied = checkNotNull(
            database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID),
        )
        val persistedResponse = Json.parseToJsonElement(checkNotNull(denied.responseJson))

        now += 1
        val replay = connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.request),
            relayState(SSH_AUTHENTICATION_REQUEST_ID),
            completionEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertEquals(
            listOf(persistedResponse, persistedResponse),
            replay.sentFrames.filterIsInstance<RelayDeviceFrame.Response>().map { it.payload },
        )
        val completed = checkNotNull(
            database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID),
        )
        val completedAuthentication = checkNotNull(
            database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID),
        )
        assertEquals(InboxRequestState.COMPLETED.storedName, completed.state)
        assertEquals(ApprovalDecision.DENIED.storedName, completedAuthentication.decision)
        assertNull(completedAuthentication.message)
        assertEquals(
            ApprovalCompletionResult.DENIED.storedName,
            completedAuthentication.completionResult,
        )
        assertEquals("USER_DENIED", completedAuthentication.completionReason)
        assertNull(database.requestDao().getRequestPsk(SSH_AUTHENTICATION_REQUEST_ID))
    }

    @Test
    fun approvedSshAuthenticationCompletesWithTheSameSecretAndParent() = runTest {
        val invocation = establishSigningInvocation()
        val exchange = pairedExchange(
            requestId = SSH_AUTHENTICATION_REQUEST_ID,
            clientPsk = invocation.clientPsk,
            requestPlaintext = sshAuthenticationPlaintext(invocation.token, invocation.key),
            completionPlaintext = approvedCompletionPlaintext(),
        )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        now += 1
        assertEquals(
            SshAuthenticationDecisionResult.Decided,
            repository.approveSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID),
        )
        val decided = checkNotNull(
            database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID),
        )
        assertEquals(ApprovalDecision.APPROVED.storedName, decided.decision)
        assertNull(decided.message)

        now += 1
        connect(
            relayState(INVOCATION_REQUEST_ID),
            relayState(SSH_AUTHENTICATION_REQUEST_ID),
            completionEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val completed = checkNotNull(
            database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID),
        )
        val completedAuthentication = checkNotNull(
            database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID),
        )
        assertEquals(INVOCATION_REQUEST_ID, completed.parentRequestId)
        assertEquals(InboxRequestState.COMPLETED.storedName, completed.state)
        assertEquals(
            ApprovalCompletionResult.APPROVED.storedName,
            completedAuthentication.completionResult,
        )
        assertNull(database.requestDao().getRequestPsk(SSH_AUTHENTICATION_REQUEST_ID))
    }

    @Test
    fun sshAuthenticationAbortMessageStaysOutOfAudit() = runTest {
        val invocation = establishSigningInvocation()
        val malicious = "raw-ssh-abort-client-message"
        val exchange = pairedExchange(
            requestId = SSH_AUTHENTICATION_REQUEST_ID,
            clientPsk = invocation.clientPsk,
            requestPlaintext = sshAuthenticationPlaintext(invocation.token, invocation.key),
            completionPlaintext = abortedCompletionPlaintext(malicious),
        )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.request),
            completionEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val completionAudit = AuditRepository(database.auditDao()).observeEvents().first()
            .single { it.type == AuditEventType.SSH_AUTHENTICATION_COMPLETED }
        assertEquals("SSH authentication was aborted by the client.", completionAudit.detail)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    @Test
    fun invalidSshAuthenticationCompletionDoesNotRetainDecodedClientFields() = runTest {
        val invocation = establishSigningInvocation()
        val malicious = "raw-invalid-ssh-client-message"
        val exchange = pairedExchange(
            requestId = SSH_AUTHENTICATION_REQUEST_ID,
            clientPsk = invocation.clientPsk,
            requestPlaintext = sshAuthenticationPlaintext(invocation.token, invocation.key),
            completionPlaintext = wrongSoftwareAbortedCompletionPlaintext(malicious),
        )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.request),
            completionEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val request = checkNotNull(
            database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID),
        )
        assertEquals("SSH authentication completion could not be verified.", request.error)
        val authentication = checkNotNull(
            database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID),
        )
        assertNull(authentication.completionResult)
        assertNull(authentication.completionReason)
        assertNull(authentication.completionMessage)
        assertNull(authentication.message)
        val completionAudit = AuditRepository(database.auditDao()).observeEvents().first()
            .single { it.type == AuditEventType.SSH_AUTHENTICATION_COMPLETED }
        assertEquals(
            "SSH authentication completion could not be verified.",
            completionAudit.detail,
        )
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    @Test
    fun secretListReplayUsesPersistedResponseAndAuthenticatedCompletion() = runTest {
        val clientPsk = establishActivePairing()
        createSigningSecret()
        val exchange = pairedExchange(
            requestId = SECRET_LIST_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext = secretListPlaintext(),
            completionPlaintext = clientSoftwarePlaintext(),
        )
        val interrupted = connect(
            requestEvent(SECRET_LIST_REQUEST_ID, exchange.request),
            RelayDeviceEvent.Failed("connection lost after secret list response"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("connection lost after secret list response"),
            repository.sync(),
        )
        val stored = checkNotNull(database.requestDao().getRequestById(SECRET_LIST_REQUEST_ID))
        val persistedResponse = Json.parseToJsonElement(checkNotNull(stored.responseJson))
        assertEquals(RequestKind.SECRET_LIST.storedName, stored.kind)
        assertEquals(
            listOf(persistedResponse),
            interrupted.sentFrames.filterIsInstance<RelayDeviceFrame.Response>().map { it.payload },
        )

        now += 1
        val replay = connect(
            relayState(SECRET_LIST_REQUEST_ID),
            requestEvent(SECRET_LIST_REQUEST_ID, exchange.request),
            RelayDeviceEvent.Receipt(
                CLIENT_ID,
                SECRET_LIST_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            completionEvent(SECRET_LIST_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertEquals(
            listOf(persistedResponse, persistedResponse),
            replay.sentFrames.filterIsInstance<RelayDeviceFrame.Response>().map { it.payload },
        )
        val completed = checkNotNull(database.requestDao().getRequestById(SECRET_LIST_REQUEST_ID))
        assertEquals(stored.receivedAt, completed.receivedAt)
        assertEquals(InboxRequestState.COMPLETED.storedName, completed.state)
        assertEquals(exchange.completion.toString(), completed.completionJson)
        assertNull(database.requestDao().getRequestPsk(SECRET_LIST_REQUEST_ID))
    }

    @Test
    fun completedSecretUploadRemainsPendingUntilTheUserRejectsIt() = runTest {
        val clientPsk = establishActivePairing()
        val exchange = pairedExchange(
            requestId = UPLOAD_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext = environmentUploadPlaintext(),
            completionPlaintext = receivedUploadCompletionPlaintext(),
        )
        connect(
            requestEvent(UPLOAD_REQUEST_ID, exchange.request),
            RelayDeviceEvent.Receipt(CLIENT_ID, UPLOAD_REQUEST_ID, RelayMessageKind.RESPONSE),
            completionEvent(UPLOAD_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val awaitingDecision = checkNotNull(database.requestDao().getRequestById(UPLOAD_REQUEST_ID))
        val pendingUpload = checkNotNull(
            database.requestDao().getSecretUploadRequest(UPLOAD_REQUEST_ID),
        )
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, awaitingDecision.state)
        assertNotNull(awaitingDecision.completionJson)
        assertNull(awaitingDecision.completedAt)
        assertNull(pendingUpload.decision)
        assertEquals(
            1,
            database.requestDao().getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID).size,
        )
        assertNull(database.requestDao().getRequestPsk(UPLOAD_REQUEST_ID))

        now += 1
        assertEquals(
            SecretUploadDecisionResult.Rejected,
            repository.rejectSecretUpload(UPLOAD_REQUEST_ID),
        )

        val rejected = checkNotNull(database.requestDao().getRequestById(UPLOAD_REQUEST_ID))
        val rejectedUpload = checkNotNull(
            database.requestDao().getSecretUploadRequest(UPLOAD_REQUEST_ID),
        )
        assertEquals(InboxRequestState.COMPLETED.storedName, rejected.state)
        assertEquals("rejected", rejectedUpload.decision)
        assertNotNull(rejected.completedAt)
        assertTrue(
            database.requestDao().getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID).isEmpty(),
        )
        assertTrue(database.secretDao().getSecretsByName(listOf("uploaded-secret")).isEmpty())
    }

    @Test
    fun rejectedSecretUploadWaitsForAuthenticatedClientCompletion() = runTest {
        val clientPsk = establishActivePairing()
        val exchange = pairedExchange(
            requestId = UPLOAD_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext = environmentUploadPlaintext(),
            completionPlaintext = receivedUploadCompletionPlaintext(),
        )
        connect(
            requestEvent(UPLOAD_REQUEST_ID, exchange.request),
            RelayDeviceEvent.Receipt(CLIENT_ID, UPLOAD_REQUEST_ID, RelayMessageKind.RESPONSE),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        now += 1
        assertEquals(
            SecretUploadDecisionResult.Rejected,
            repository.rejectSecretUpload(UPLOAD_REQUEST_ID),
        )
        val awaitingCompletion = checkNotNull(
            database.requestDao().getRequestById(UPLOAD_REQUEST_ID),
        )
        assertEquals(InboxRequestState.WAITING.storedName, awaitingCompletion.state)
        assertNull(awaitingCompletion.completionJson)
        assertNull(awaitingCompletion.completedAt)
        assertNotNull(database.requestDao().getRequestPsk(UPLOAD_REQUEST_ID))
        assertTrue(
            database.requestDao().getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID).isEmpty(),
        )

        now += 1
        connect(
            relayState(UPLOAD_REQUEST_ID),
            completionEvent(UPLOAD_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val completed = checkNotNull(database.requestDao().getRequestById(UPLOAD_REQUEST_ID))
        assertEquals(InboxRequestState.COMPLETED.storedName, completed.state)
        assertEquals(exchange.completion.toString(), completed.completionJson)
        assertNotNull(completed.completedAt)
        assertNull(database.requestDao().getRequestPsk(UPLOAD_REQUEST_ID))
        assertTrue(database.secretDao().getSecretsByName(listOf("uploaded-secret")).isEmpty())
    }

    private suspend fun establishActivePairing(): ByteArray {
        val material = verifyPairingSas()

        val activation = connect(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            relayState(CLIENT_ID, completion = RelayMessageState.DELIVERED),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(
            activation.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.ACTIVE),
            ),
        )

        now += 1
        val finish = finishRequest(material)
        connect(
            relayState(CLIENT_ID, completion = RelayMessageState.DELIVERED),
            requestEvent(FINISH_REQUEST_ID, finish),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                FINISH_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            relayState(FINISH_REQUEST_ID, completion = RelayMessageState.DELIVERED).copy(
                exchange = RelayExchangeState.SETTLED,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val completedAttempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals("completed", completedAttempt.state)
        assertNull(completedAttempt.pendingPsk)
        val client = checkNotNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals("active", client.relayClientState)
        assertNotNull(database.requestDao().getClientPsk(CLIENT_ID, "current"))
        return material.clientPsk
    }

    private suspend fun establishSigningInvocation(): SigningInvocation {
        val clientPsk = establishActivePairing()
        val key = createSigningSecret()
        val token = ByteArray(32) { (0x70 + it).toByte() }
        val request = pairedRequest(
            requestId = INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = invocationPlaintext(token),
        )
        connect(
            requestEvent(INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                INVOCATION_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            ApprovalDecision.APPROVED.storedName,
            database.requestDao().getSecretUseRequest(INVOCATION_REQUEST_ID)?.decision,
        )
        return SigningInvocation(clientPsk, token, key)
    }

    private suspend fun receiveEnvironmentSecretUpload() {
        val clientPsk = establishActivePairing()
        val upload = pairedRequest(
            requestId = UPLOAD_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = environmentUploadPlaintext(),
        )
        connect(
            requestEvent(UPLOAD_REQUEST_ID, upload),
            RelayDeviceEvent.CaughtUp,
            relayState(UPLOAD_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            "action_required",
            database.requestDao().getRequestById(UPLOAD_REQUEST_ID)?.state,
        )
        assertNull(database.requestDao().getSecretUploadRequest(UPLOAD_REQUEST_ID)?.decision)
        assertEquals(
            1,
            database.requestDao()
                .getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID)
                .size,
        )
    }

    private suspend fun receivePairingUntilSas(): PairingMaterial {
        val clientSecret = ByteArray(32) { it.toByte() }
        val material = pairingMaterial(clientSecret)
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(clientSecret),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.Acknowledgement(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = material.completion,
                addressId = ADDRESS_ID,
            ),
        )
        val pairing = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals("sas_verification_pending", pairing.state)
        return material
    }

    private suspend fun verifyPairingSas(): PairingMaterial {
        val material = receivePairingUntilSas()
        val pairing = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(
            PairingDecisionResult.VERIFIED,
            repository.chooseSas(CLIENT_ID, checkNotNull(pairing.correctSasIndex)),
        )
        val verified = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(PairingState.WAITING_FOR_FINISH.storedName, verified.state)
        assertEquals(RelayClientState.ACTIVE.wireName, verified.desiredRelayClientState)
        assertNotNull(verified.decidedAt)
        return material
    }

    private suspend fun assertSuspendedFinishOutcome(stateBeforeFinish: Boolean) {
        val material = verifyPairingSas()
        val finish = finishRequest(material)
        val events = if (stateBeforeFinish) {
            listOf(
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.SUSPENDED),
                requestEvent(FINISH_REQUEST_ID, finish),
            )
        } else {
            listOf(
                requestEvent(FINISH_REQUEST_ID, finish),
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.SUSPENDED),
            )
        }
        connect(
            *events.toTypedArray(),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                FINISH_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.Failed("disconnect with suspended client"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect with suspended client"),
            repository.sync(),
        )

        val client = checkNotNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals("suspended", client.relayClientState)
        assertEquals("active", client.desiredRelayClientState)
        assertNotNull(database.requestDao().getClientPsk(CLIENT_ID, "current"))
        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals("completed", attempt.state)
        assertEquals("suspended", attempt.relayClientState)
        assertNull(attempt.desiredRelayClientState)
    }

    private fun finishRequest(material: PairingMaterial): JsonElement = pairedRequest(
            requestId = FINISH_REQUEST_ID,
            clientPsk = material.clientPsk,
            plaintext = """{${clientSoftwareFields()},"method":"PairingFinish"}"""
                .encodeToByteArray(),
        )

    private suspend fun createSigningSecret(): SshPrivateKey {
        val key = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "characterization@test")
        assertTrue(
            secrets.createSshSecret("git-signing", "Signing key", key) is
                CreateSecretResult.Created,
        )
        return key
    }

    private suspend fun createAiEnvironmentSecret(): String {
        val created = secrets.createEnvironmentSecret("deployment", "Deployment credentials")
        val secretId = (created as CreateSecretResult.Created).id
        assertTrue(
            secrets.createEnvironmentVariable(
                secretId = secretId,
                name = "DEPLOY_TOKEN",
                value = "secret-token",
                sensitive = true,
                notes = "",
            ) is CreateEnvironmentVariableResult.Created,
        )
        assertEquals(
            SaveSecretResult.SAVED,
            secrets.saveApprovalMode(secretId, SecretApprovalMode.ASK_AI),
        )
        return secretId
    }

    private suspend fun assertPendingInvocationSelectionAfterVariableAdded(
        delivery: String,
        initialVariableNames: List<String>,
        expectedVariableNames: List<String>,
    ) {
        val clientPsk = establishActivePairing()
        val created = secrets.createEnvironmentSecret(
            name = "selection",
            description = "Selection regression",
        )
        val secretId = (created as CreateSecretResult.Created).id
        initialVariableNames.forEach { name ->
            assertTrue(
                secrets.createEnvironmentVariable(
                    secretId = secretId,
                    name = name,
                    value = "value-$name",
                    sensitive = true,
                    notes = "",
                ) is CreateEnvironmentVariableResult.Created,
            )
        }
        val token = ByteArray(32) { (0x45 + it).toByte() }
        val request = pairedRequest(
            requestId = AI_INVOCATION_REQUEST_ID,
            clientPsk = clientPsk,
            plaintext = environmentSelectionInvocationPlaintext(token, delivery),
        )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID)?.state,
        )

        now += 1
        assertTrue(
            secrets.createEnvironmentVariable(
                secretId = secretId,
                name = "ADDED",
                value = "new-sensitive-value",
                sensitive = true,
                notes = "",
            ) is CreateEnvironmentVariableResult.Created,
        )

        assertEquals(
            InvocationDecisionResult.SecretsChanged,
            repository.approveSecretUseRequest(AI_INVOCATION_REQUEST_ID),
        )
        val stored = checkNotNull(
            database.requestDao().getSecretUseRequest(AI_INVOCATION_REQUEST_ID),
        )
        val metadata = Json.decodeFromString<List<SecretMetadata>>(stored.secretDetailsJson)
            .single()
        assertEquals(expectedVariableNames, metadata.environmentVariableNames.sorted())
        assertNull(database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID)?.responseJson)
    }

    private suspend fun <T> awaitAsynchronousWork(block: suspend () -> T): T = withContext(
        Dispatchers.Default.limitedParallelism(1),
    ) {
        withTimeout(5_000) { block() }
    }

    private suspend fun insertOpenUnknownRequest() {
        database.requestDao().insertRequest(
            InboxRequestEntity(
                id = UNSUPPORTED_REQUEST_ID,
                parentRequestId = null,
                deviceIdentityId = DEVICE_IDENTITY_ID,
                clientId = CLIENT_ID,
                clientNameSnapshot = "Test client",
                clientSoftwareJson = null,
                kind = RequestKind.UNKNOWN.storedName,
                state = InboxRequestState.WAITING.storedName,
                listed = false,
                requestJson = "{}",
                responseJson = "{}",
                completionJson = null,
                error = null,
                receivedAt = now,
                completedAt = null,
                exchangeEndedAt = null,
                responseOutboxFinished = false,
            ),
        )
    }

    private fun connect(vararg events: RelayDeviceEvent): TestRelayDeviceConnection =
        TestRelayDeviceConnection(events.toList()).also(relay::enqueue)

    private fun requestEvent(requestId: String, payload: JsonElement) = RelayDeviceEvent.Message(
        clientId = CLIENT_ID,
        requestId = requestId,
        kind = RelayMessageKind.REQUEST,
        payload = payload,
        addressId = null,
    )

    private fun completionEvent(
        requestId: String,
        payload: JsonElement,
    ) = RelayDeviceEvent.Message(
        clientId = CLIENT_ID,
        requestId = requestId,
        kind = RelayMessageKind.COMPLETION,
        payload = payload,
        addressId = null,
    )

    private suspend fun assertCompletionEndsExchangeWithoutResponseStatus(
        requestId: String,
        exchange: PairedExchange,
    ) {
        connect(
            requestEvent(requestId, exchange.request),
            RelayDeviceEvent.Failed("disconnect before response status"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect before response status"),
            repository.sync(),
        )
        assertFalse(
            checkNotNull(database.requestDao().getRequestById(requestId)).responseOutboxFinished,
        )

        now += 1
        connect(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = requestId,
                kind = RelayMessageKind.COMPLETION,
                payload = exchange.completion,
                addressId = null,
            ),
            RelayDeviceEvent.Failed("disconnect after completion"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect after completion"),
            repository.sync(),
        )
        assertTrue(
            checkNotNull(database.requestDao().getRequestById(requestId)).responseOutboxFinished,
        )
    }

    private fun relayState(
        requestId: String,
        completion: RelayMessageState = RelayMessageState.ABSENT,
    ) = RelayDeviceEvent.State(
        clientId = CLIENT_ID,
        requestId = requestId,
        exchange = RelayExchangeState.OPEN,
        request = RelayMessageState.DELIVERED,
        response = RelayMessageState.DELIVERED,
        completion = completion,
    )

    private fun invocationPlaintext(token: ByteArray): ByteArray =
        """{${clientSoftwareFields()},"method":"Invocation","secrets":{"git-signing":{}},"operation":{"type":"exec","command":"git","arguments":["commit"],"working_directory":"/tmp/project","executable_path":"/usr/bin/git","executable_mode":"BINARY","stdin":"TERMINAL","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[],"invocation_token":"${BASE64.encodeToString(token)}"}"""
            .encodeToByteArray()

    private fun aiInvocationPlaintext(token: ByteArray): ByteArray =
        """{${clientSoftwareFields()},"method":"Invocation","secrets":{"deployment":{}},"operation":{"type":"exec","command":"deploy","arguments":["production"],"working_directory":"/tmp/project","executable_path":"/usr/bin/deploy","executable_mode":"BINARY","stdin":"TERMINAL","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[],"invocation_token":"${BASE64.encodeToString(token)}"}"""
            .encodeToByteArray()

    private fun environmentSelectionInvocationPlaintext(
        token: ByteArray,
        delivery: String,
    ): ByteArray =
        """{${clientSoftwareFields()},"method":"Invocation","secrets":{"selection":$delivery},"operation":{"type":"exec","command":"deploy","arguments":[],"working_directory":"/tmp/project","executable_path":"/usr/bin/deploy","executable_mode":"BINARY","stdin":"TERMINAL","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[],"invocation_token":"${BASE64.encodeToString(token)}"}"""
            .encodeToByteArray()

    private fun gitSignPlaintext(token: ByteArray): ByteArray =
        """{${clientSoftwareFields()},"method":"GitSign","invocation_id":"$INVOCATION_REQUEST_ID","invocation_token":"${BASE64.encodeToString(token)}","secret":"git-signing","message":"${BASE64.encodeToString("commit to sign".encodeToByteArray())}","repository":{"remote":"git@example.test:repo.git","worktree":"/tmp/project"}}"""
            .encodeToByteArray()

    private fun sshAuthenticationPlaintext(
        token: ByteArray,
        key: SshPrivateKey,
    ): ByteArray {
        val publicKeyBlob = sshStrings(
            key.algorithm.publicName.encodeToByteArray(),
            key.publicKey,
        )
        val message = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeSshString("session identifier".encodeToByteArray())
                output.writeByte(50)
                output.writeSshString("deploy".encodeToByteArray())
                output.writeSshString("ssh-connection".encodeToByteArray())
                output.writeSshString("publickey".encodeToByteArray())
                output.writeByte(1)
                output.writeSshString(key.algorithm.publicName.encodeToByteArray())
                output.writeSshString(publicKeyBlob)
            }
            bytes.toByteArray()
        }
        return """{${clientSoftwareFields()},"method":"SshAuthenticate","invocation_id":"$INVOCATION_REQUEST_ID","invocation_token":"${BASE64.encodeToString(token)}","secret":"git-signing","message":"${BASE64.encodeToString(message)}"}"""
            .encodeToByteArray()
    }

    private fun secretListPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"method":"SecretList"}""".encodeToByteArray()

    private fun clientSoftwarePlaintext(): ByteArray =
        """{${clientSoftwareFields()}}""".encodeToByteArray()

    private fun approvedCompletionPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"result":"APPROVED"}""".encodeToByteArray()

    private fun abortedCompletionPlaintext(message: String): ByteArray =
        """{${clientSoftwareFields()},"result":"ABORTED","reason":"CANCELLED","message":"$message"}"""
            .encodeToByteArray()

    private fun wrongSoftwareAbortedCompletionPlaintext(message: String): ByteArray =
        """{"app_info":{"name":"attacker","version":"1"},"lib_info":{"name":"attacker","version":"1"},"result":"ABORTED","reason":"CANCELLED","message":"$message"}"""
            .encodeToByteArray()

    private fun deniedSshAuthenticationCompletionPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"result":"DENIED","reason":"USER_DENIED","message":"SSH authentication denied on device."}"""
            .encodeToByteArray()

    private fun receivedUploadCompletionPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"result":"RECEIVED"}""".encodeToByteArray()

    private fun sshStrings(vararg values: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                values.forEach { value -> output.writeSshString(value) }
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeSshString(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun unsupportedPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"method":"FutureMethod"}""".encodeToByteArray()

    private fun environmentUploadPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"method":"SecretUpload","mode":"CREATE","secret":{"name":"uploaded-secret","type":"environment","variables":{"TOKEN":{"value":"secret-value"}}}}"""
            .encodeToByteArray()

    private fun clientSoftwareFields(): String =
        """"app_info":{"name":"agentknock-cli","version":"test"},"lib_info":{"name":"agentknock","version":"test"}"""

    private fun pairedRequest(
        requestId: String,
        clientPsk: ByteArray,
        plaintext: ByteArray,
    ): JsonElement {
        val sender = PSK_HPKE.SetupPSKS(
            PSK_HPKE.deserializePublicKey(credentials.devicePublicKey),
            VERSION_INFO + DEVICE_ID.ulidBytes() + requestId.ulidBytes(),
            clientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val encapsulation =
            (sender as org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation).encapsulation
        return Json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, plaintext))}"}""",
        )
    }

    private fun pairedExchange(
        requestId: String,
        clientPsk: ByteArray,
        requestPlaintext: ByteArray,
        completionPlaintext: ByteArray,
    ): PairedExchange {
        val sender = PSK_HPKE.SetupPSKS(
            PSK_HPKE.deserializePublicKey(credentials.devicePublicKey),
            VERSION_INFO + DEVICE_ID.ulidBytes() + requestId.ulidBytes(),
            clientPsk,
            CLIENT_ID.ulidBytes(),
        ) as org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation
        val request = Json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(sender.encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, requestPlaintext))}"}""",
        )
        val completion = Json.parseToJsonElement(
            """{"ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, completionPlaintext))}"}""",
        )
        return PairedExchange(request, completion)
    }

    private suspend fun synchronize(vararg events: RelayDeviceEvent) {
        val state = RelayDeviceEvent.State(
            clientId = CLIENT_ID,
            requestId = CLIENT_ID,
            exchange = RelayExchangeState.OPEN,
            request = RelayMessageState.DELIVERED,
            response = RelayMessageState.DELIVERED,
            completion = RelayMessageState.ABSENT,
        )
        val connection = TestRelayDeviceConnection(
            events.toList() + state + RelayDeviceEvent.CaughtUp,
        )
        relay.enqueue(connection)
        assertEquals(RequestSyncResult.Success, repository.sync())
    }

    private fun pairingRequest(clientSecret: ByteArray): JsonElement {
        val commitment = derive(
            input = clientSecret,
            salt = "agentknock-v1".encodeToByteArray(),
            info = "agentknock-v1 commitment".encodeToByteArray(),
            length = 32,
        )
        return Json.parseToJsonElement(
            """{"version":"agentknock-v1","commitment":"${BASE64.encodeToString(commitment)}"}""",
        )
    }

    private fun pairingCompletion(clientSecret: ByteArray): JsonElement {
        return pairingMaterial(clientSecret).completion
    }

    private fun pairingMaterial(clientSecret: ByteArray): PairingMaterial {
        val sender = BASE_HPKE.setupBaseS(
            BASE_HPKE.deserializePublicKey(credentials.devicePublicKey),
            VERSION_INFO + DEVICE_ID.ulidBytes() + CLIENT_ID.ulidBytes(),
        )
        val secretCiphertext = sender.seal(EMPTY, clientSecret)
        val applicationCiphertext = sender.seal(
            EMPTY,
            """{${clientSoftwareFields()},"platform":"linux","architecture":"x86_64","hostname":"test"}"""
                .encodeToByteArray(),
        )
        val encapsulation =
            (sender as org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation).encapsulation
        return PairingMaterial(
            completion = Json.parseToJsonElement(
                """{"key":"${BASE64.encodeToString(encapsulation)}","secret":"${BASE64.encodeToString(secretCiphertext)}","ciphertext":"${BASE64.encodeToString(applicationCiphertext)}"}""",
            ),
            clientPsk = sender.export("agentknock-v1 psk".encodeToByteArray(), 32),
        )
    }

    private fun derive(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        HKDFBytesGenerator(SHA256Digest()).run {
            init(HKDFParameters(input, salt, info))
            generateBytes(output, 0, output.size)
        }
        return output
    }

    private fun String.ulidBytes(): ByteArray {
        var value = BigInteger.ZERO
        for (character in this) {
            value = value.shiftLeft(5).or(
                BigInteger.valueOf(ULID_ALPHABET.indexOf(character).toLong()),
            )
        }
        val encoded = value.toByteArray()
        return ByteArray(16).also { output ->
            val sourceOffset = (encoded.size - output.size).coerceAtLeast(0)
            val length = encoded.size - sourceOffset
            encoded.copyInto(output, output.size - length, sourceOffset)
        }
    }

    private fun String.timestamp(): Long {
        val bytes = ulidBytes()
        var timestamp = 0L
        repeat(6) { timestamp = (timestamp shl 8) or (bytes[it].toLong() and 0xff) }
        return timestamp
    }

    private companion object {
        const val DEVICE_IDENTITY_ID = "device-identity"
        const val RETIRED_DEVICE_IDENTITY_ID = "retired-device-identity"
        const val ADDRESS = "write-leader-hungry"
        const val ADDRESS_ID = "0123456789abcdef0123456789abcdef"
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val FINISH_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P3Y"
        const val INVOCATION_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P3Z"
        const val GIT_SIGN_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P40"
        const val COLLISION_CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P41"
        const val UNSUPPORTED_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P42"
        const val REMOVE_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P43"
        const val UPLOAD_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P44"
        const val AI_INVOCATION_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P45"
        const val INTERRUPTED_REVIEW_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P46"
        const val SSH_AUTHENTICATION_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P47"
        const val SECRET_LIST_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P48"
        const val ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val EMPTY = ByteArray(0)
        val VERSION_INFO = "agentknock-v1".encodeToByteArray() + ByteArray(3)
        val BASE64: Base64.Encoder = Base64.getEncoder()
        val BASE_HPKE = HPKE(
            HPKE.mode_base,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_CHACHA20_POLY1305,
        )
        val PSK_HPKE = HPKE(
            HPKE.mode_psk,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_CHACHA20_POLY1305,
        )
    }
}

private data class PairingMaterial(
    val completion: JsonElement,
    val clientPsk: ByteArray,
)

private data class PairedExchange(
    val request: JsonElement,
    val completion: JsonElement,
)

private data class SigningInvocation(
    val clientPsk: ByteArray,
    val token: ByteArray,
    val key: SshPrivateKey,
)

private class StaticCredentialSource(
    var credentials: RelayDeviceCredentials,
) : RelayDeviceCredentialSource {
    override suspend fun activeDeviceCredentials() =
        RelayDeviceCredentialsResult.Available(credentials)

    override suspend fun deviceCredentials(deviceIdentityId: String) =
        RelayDeviceCredentialsResult.Available(credentials)
}

private class QueuedRelayDeviceClient : RelayDeviceClient {
    private val connections = ArrayDeque<RelayDeviceConnection>()

    fun enqueue(connection: RelayDeviceConnection) {
        connections += connection
    }

    override suspend fun connect(deviceId: String, deviceToken: String) =
        RelayDeviceConnectionResult.Connected(connections.removeFirst())
}

private class ControllableApprovalReviewer : RelayApprovalReviewClient {
    private val results = Channel<RelayApprovalReviewResult>(Channel.UNLIMITED)

    var callCount: Int = 0
        private set

    override suspend fun review(
        deviceId: String,
        deviceToken: String,
        request: ApprovalReviewRequest,
    ): RelayApprovalReviewResult {
        callCount += 1
        return results.receive()
    }

    fun complete(result: RelayApprovalReviewResult) {
        results.trySend(result).getOrThrow()
    }
}

private fun reviewed(
    decision: RelayApprovalReviewDecision,
    explanation: String,
): RelayApprovalReviewResult = RelayEndpointResult.Success(
    RelayApprovalReview(decision, explanation),
)

private class TestRelayDeviceConnection(events: List<RelayDeviceEvent>) : RelayDeviceConnection {
    private val channel = Channel<RelayDeviceEvent>(Channel.UNLIMITED).apply {
        events.forEach { trySend(it).getOrThrow() }
        close()
    }

    override val events: ReceiveChannel<RelayDeviceEvent> = channel

    val sentFrames = mutableListOf<RelayDeviceFrame>()

    override fun send(frame: RelayDeviceFrame): Boolean {
        sentFrames += frame
        return true
    }

    override suspend fun close() = Unit
}

private class SwitchableSecureRandom : SecureRandom() {
    var fail: Boolean = false

    override fun nextBytes(bytes: ByteArray) {
        check(!fail) { "Injected response-random failure" }
        super.nextBytes(bytes)
    }
}

private val InboxRequestDetails.secretUse: SecretUseRequestDetails?
    get() = (content as? InboxRequestContent.SecretUse)?.details

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
