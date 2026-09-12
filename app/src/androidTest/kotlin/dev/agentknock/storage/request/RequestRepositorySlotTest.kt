package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.AgentknockActions
import dev.agentknock.ProtectedActionResult
import dev.agentknock.SecretValueAction
import dev.agentknock.protocol.PairedRequestProtocol
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.relay.RelayApprovalReview
import dev.agentknock.relay.RelayApprovalReviewClient
import dev.agentknock.relay.RelayApprovalReviewDecision
import dev.agentknock.relay.RelayApprovalReviewResult
import dev.agentknock.relay.RelayClientState
import dev.agentknock.relay.RelayDeviceClient
import dev.agentknock.relay.RelayDeviceConnection
import dev.agentknock.relay.RelayDeviceConnectionResult
import dev.agentknock.relay.RelayDeviceErrorScope
import dev.agentknock.relay.RelayDeviceEvent
import dev.agentknock.relay.RelayDeviceFrame
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.relay.RelayExchangeState
import dev.agentknock.relay.RelayFrameSendResult
import dev.agentknock.relay.RelayMessageKind
import dev.agentknock.relay.RelayMessageState
import dev.agentknock.relay.RelayPushRegistrationState
import dev.agentknock.review.ApprovalReviewRequest
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.EncryptionKeyStore
import dev.agentknock.storage.crypto.GeneratedEncryptionKey
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.device.DeviceCredentialResult
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.device.DeviceKeyAccess
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.EnvironmentVariableValue
import dev.agentknock.storage.secret.SaveEnvironmentVariableResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretMetadata
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.ui.auth.DeviceAuthenticationResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val subscription = FakeSubscription(DEVICE_ID)
    private lateinit var database: AgentknockDatabase
    private lateinit var relay: QueuedRelayDeviceClient
    private lateinit var repository: RequestRepository
    private lateinit var inbox: RequestInbox
    private lateinit var secrets: SecretRepository
    private lateinit var devicePublicKey: ByteArray
    private lateinit var credentials: RelayDeviceCredentials
    private lateinit var credentialSource: StaticCredentialSource
    private lateinit var protocolRandom: SwitchableSecureRandom
    private lateinit var reviewScope: CoroutineScope
    private lateinit var approvalReviewer: ControllableApprovalReviewer
    private var synchronizationRequests = 0
    private var pushRegistrationState: RelayPushRegistrationState? = null
    private var deviceKeyFailure: DecryptionResult? = null
    private val decryptedDeviceKeys = mutableListOf<ByteArray>()
    private var now = CLIENT_ID.timestamp()

    @Before
    fun setUp() = runTest {
        database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AgentknockDatabase::class.java,
                )
                .build()
        val keyStore = MemoryEncryptionKeyStore()
        var nextKeyId = 0
        val keyManager =
            VaultKeyManager(
                dao = database.vaultKeyDao(),
                keyStore = keyStore,
                newKeyId = { "slot-key-${nextKeyId++}" },
                currentTimeMillis = { now },
                keyStoreDispatcher = Dispatchers.Unconfined,
            )
        val encryption = AesGcmEncryption(keyStore)
        val devicePrivateKey = ByteArray(32) { 0x42 }
        devicePublicKey =
            X25519PrivateKeyParameters(devicePrivateKey, 0).generatePublicKey().encoded
        credentials =
            RelayDeviceCredentials(
                deviceIdentityId = DEVICE_IDENTITY_ID,
                address = ADDRESS,
                addressId = ADDRESS_ID,
                deviceId = DEVICE_ID,
                deviceKey =
                    DeviceKeyAccess(Dispatchers.Unconfined) {
                        deviceKeyFailure
                            ?: DecryptionResult.Plaintext(
                                devicePrivateKey.copyOf().also(decryptedDeviceKeys::add)
                            )
                    },
                deviceToken = "token",
            )
        database
            .deviceIdentityDao()
            .insertIdentity(
                DeviceIdentityEntity(
                    id = DEVICE_IDENTITY_ID,
                    role = "active",
                    address = ADDRESS,
                    deviceId = DEVICE_ID,
                    createdAt = now,
                )
            )
        relay = QueuedRelayDeviceClient()
        val audit = AuditRepository(database.auditDao(), currentTimeMillis = { now })
        var nextSecretId = 0
        secrets =
            SecretRepository(
                dao = database.secretDao(),
                keyManager = keyManager,
                encryption = encryption,
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
                newId = { "secret-id-${nextSecretId++}" },
                currentTimeMillis = { now },
            )
        protocolRandom = SwitchableSecureRandom()
        reviewScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        approvalReviewer = ControllableApprovalReviewer()
        credentialSource = StaticCredentialSource(credentials)
        synchronizationRequests = 0
        pushRegistrationState = null
        inbox = RequestInbox(database.requestDao())
        val clients =
            ClientRepository(
                dao = database.requestDao(),
                temporaryAccessGrants = secrets.observeTemporaryAccessGrants(),
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
            )
        val requestMaterial =
            RequestMaterialStore(
                dao = database.requestDao(),
                keyManager = keyManager,
                encryption = encryption,
                newId = { "request-material-id" },
                currentTimeMillis = { now },
            )
        val secretManagement =
            SecretManagementRequests(
                dao = database.requestDao(),
                material = requestMaterial,
                secrets = secrets,
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
                currentTimeMillis = { now },
            )
        val invocationRequests =
            InvocationRequests(
                dao = database.requestDao(),
                secrets = secrets,
                deviceCredentials = credentialSource,
                approvalReviewer = approvalReviewer,
                subscription = subscription.repository,
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
                currentTimeMillis = { now },
            )
        val gitSigningRequests =
            GitSigningRequests(
                dao = database.requestDao(),
                secrets = secrets,
                deviceCredentials = credentialSource,
                approvalReviewer = approvalReviewer,
                subscription = subscription.repository,
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
                currentTimeMillis = { now },
            )
        val sshAuthenticationRequests =
            SshAuthenticationRequests(
                dao = database.requestDao(),
                secrets = secrets,
                deviceCredentials = credentialSource,
                approvalReviewer = approvalReviewer,
                subscription = subscription.repository,
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
                currentTimeMillis = { now },
            )
        val pairingProtocol = PairingProtocol(random = protocolRandom)
        val pairingRequests =
            PairingRequests(
                dao = database.requestDao(),
                material = requestMaterial,
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
                pairingProtocol = pairingProtocol,
                json = Json,
                currentTimeMillis = { now },
            )
        val clientRemovalRequests =
            ClientRemovalRequests(
                dao = database.requestDao(),
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
                json = Json,
                currentTimeMillis = { now },
            )
        repository =
            RequestRepository(
                dao = database.requestDao(),
                material = requestMaterial,
                deviceCredentials = credentialSource,
                clients = clients,
                secretManagement = secretManagement,
                invocationRequests = invocationRequests,
                gitSigningRequests = gitSigningRequests,
                sshAuthenticationRequests = sshAuthenticationRequests,
                pairingRequests = pairingRequests,
                clientRemovalRequests = clientRemovalRequests,
                relay = relay,
                aiReviews = AiReviewCoordinator(reviewScope),
                scheduleSynchronization = { synchronizationRequests += 1 },
                audit = audit,
                writeTransaction = RoomWriteTransaction(database),
                updatePushRegistrationState = { pushRegistrationState = it },
                pairedRequestProtocol = PairedRequestProtocol(random = protocolRandom),
                currentTimeMillis = { now },
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
        assertEquals("exchange_pending", database.requestDao().getPairingAttempt(root.id)?.state)

        now += 1
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload =
                    Json.parseToJsonElement("""{"key":"bad","secret":"bad","ciphertext":"bad"}"""),
                addressId = ADDRESS_ID,
            )
        )
        val requestAfterFailure = checkNotNull(database.requestDao().getRequestById(root.id))
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
            )
        )
        val afterReplay = checkNotNull(database.requestDao().getRequestById(root.id))
        val pairingAfterReplay = checkNotNull(database.requestDao().getPairingAttempt(root.id))
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
            )
        )
        assertNotNull(checkNotNull(database.requestDao().getRequestById(root.id)).exchangeEndedAt)
    }

    @Test
    fun failedInitialExchangeRejectsAnotherPairingUntilExplicitRejection() = runTest {
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
                payload =
                    Json.parseToJsonElement("""{"key":"bad","secret":"bad","ciphertext":"bad"}"""),
                addressId = ADDRESS_ID,
            ),
        )
        assertEquals(
            PairingState.EXCHANGE_FAILED.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )

        val blocked =
            connect(
                RelayDeviceEvent.Message(
                    clientId = COLLISION_CLIENT_ID,
                    requestId = COLLISION_CLIENT_ID,
                    kind = RelayMessageKind.REQUEST,
                    payload = pairingRequest(ByteArray(32) { (it + 1).toByte() }),
                    addressId = ADDRESS_ID,
                ),
                RelayDeviceEvent.Message(
                    clientId = COLLISION_CLIENT_ID,
                    requestId = COLLISION_CLIENT_ID,
                    kind = RelayMessageKind.COMPLETION,
                    payload = Json.parseToJsonElement("{}"),
                    addressId = ADDRESS_ID,
                ),
                RelayDeviceEvent.Acknowledgement(
                    clientId = COLLISION_CLIENT_ID,
                    requestId = COLLISION_CLIENT_ID,
                    kind = RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val rejected = checkNotNull(database.requestDao().getRequestById(COLLISION_CLIENT_ID))
        assertFalse(rejected.listed)
        assertEquals(InboxRequestState.COMPLETED.storedName, rejected.state)
        assertEquals(
            PairingState.REJECTED.storedName,
            database.requestDao().getPairingAttempt(COLLISION_CLIENT_ID)?.state,
        )
        assertTrue(
            blocked.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == COLLISION_CLIENT_ID
            }
        )
        assertEquals(
            "INVALID_STATE",
            blocked.sentFrames
                .filterIsInstance<RelayDeviceFrame.Response>()
                .single { it.requestId == COLLISION_CLIENT_ID }
                .payload
                .jsonObject
                .getValue("error")
                .jsonPrimitive
                .content,
        )

        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))
        val accepted =
            connect(
                RelayDeviceEvent.Message(
                    clientId = UNSUPPORTED_REQUEST_ID,
                    requestId = UNSUPPORTED_REQUEST_ID,
                    kind = RelayMessageKind.REQUEST,
                    payload = pairingRequest(ByteArray(32) { (it + 1).toByte() }),
                    addressId = ADDRESS_ID,
                ),
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
                RelayDeviceEvent.Acknowledgement(
                    clientId = UNSUPPORTED_REQUEST_ID,
                    requestId = UNSUPPORTED_REQUEST_ID,
                    kind = RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.State(
                    clientId = UNSUPPORTED_REQUEST_ID,
                    requestId = UNSUPPORTED_REQUEST_ID,
                    exchange = RelayExchangeState.OPEN,
                    response = RelayMessageState.DELIVERED,
                ),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertTrue(
            accepted.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == UNSUPPORTED_REQUEST_ID
            }
        )
    }

    @Test
    fun malformedPairingIsRejectedWithoutBlockingTheNextRequest() = runTest {
        val malformedId = COLLISION_CLIENT_ID
        val acceptedId = UNSUPPORTED_REQUEST_ID
        val connection =
            connect(
                RelayDeviceEvent.Message(
                    clientId = malformedId,
                    requestId = malformedId,
                    kind = RelayMessageKind.REQUEST,
                    payload =
                        Json.parseToJsonElement(
                            """{"version":"agentknock-v1","commitment":"bad"}"""
                        ),
                    addressId = ADDRESS_ID,
                ),
                RelayDeviceEvent.Acknowledgement(
                    clientId = malformedId,
                    requestId = malformedId,
                    kind = RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.State(
                    clientId = malformedId,
                    requestId = malformedId,
                    exchange = RelayExchangeState.SETTLED,
                    response = RelayMessageState.DELIVERED,
                ),
                RelayDeviceEvent.Message(
                    clientId = acceptedId,
                    requestId = acceptedId,
                    kind = RelayMessageKind.REQUEST,
                    payload = pairingRequest(ByteArray(32) { (it + 3).toByte() }),
                    addressId = ADDRESS_ID,
                ),
                RelayDeviceEvent.Acknowledgement(
                    clientId = acceptedId,
                    requestId = acceptedId,
                    kind = RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.State(
                    clientId = acceptedId,
                    requestId = acceptedId,
                    exchange = RelayExchangeState.OPEN,
                    response = RelayMessageState.DELIVERED,
                ),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())

        assertEquals(
            "INVALID_REQUEST",
            connection.sentFrames
                .filterIsInstance<RelayDeviceFrame.Response>()
                .single { it.requestId == malformedId }
                .payload
                .jsonObject
                .getValue("error")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            PairingState.REJECTED.storedName,
            database.requestDao().getPairingAttempt(malformedId)?.state,
        )
        assertEquals(
            PairingState.EXCHANGE_PENDING.storedName,
            database.requestDao().getPairingAttempt(acceptedId)?.state,
        )
    }

    @Test
    fun rejectedPairingResponseReplaysAfterDisconnect() = runTest {
        val requestId = COLLISION_CLIENT_ID
        val first =
            connect(
                RelayDeviceEvent.Message(
                    clientId = requestId,
                    requestId = requestId,
                    kind = RelayMessageKind.REQUEST,
                    payload = Json.parseToJsonElement("{}"),
                    addressId = ADDRESS_ID,
                ),
                RelayDeviceEvent.Failed("disconnect"),
            )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect"),
            repository.sync(),
        )
        assertTrue(
            first.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == requestId
            }
        )

        val replay =
            connect(
                RelayDeviceEvent.Acknowledgement(
                    clientId = requestId,
                    requestId = requestId,
                    kind = RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.State(
                    clientId = requestId,
                    requestId = requestId,
                    exchange = RelayExchangeState.SETTLED,
                    response = RelayMessageState.DELIVERED,
                ),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            "INVALID_REQUEST",
            replay.sentFrames
                .filterIsInstance<RelayDeviceFrame.Response>()
                .single { it.requestId == requestId }
                .payload
                .jsonObject
                .getValue("error")
                .jsonPrimitive
                .content,
        )
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
            )
        )
        protocolRandom.fail = true
        val connection =
            connect(
                RelayDeviceEvent.Message(
                    clientId = CLIENT_ID,
                    requestId = CLIENT_ID,
                    kind = RelayMessageKind.COMPLETION,
                    payload = material.completion,
                    addressId = ADDRESS_ID,
                )
            )

        assertEquals(
            unprocessedRelayMessage(RelayMessageKind.COMPLETION),
            repository.sync(),
        )
        assertTrue(connection.closed)

        val request = checkNotNull(database.requestDao().getRequestById(CLIENT_ID))
        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertNull(request.exchangeEndedAt)
        assertNull(request.error)
        assertEquals(PairingState.EXCHANGE_PENDING.storedName, attempt.state)
        assertNull(attempt.pendingPsk)
        assertFalse(
            connection.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement &&
                    it.requestId == CLIENT_ID &&
                    it.kind == RelayMessageKind.COMPLETION
            }
        )
    }

    @Test
    fun initialCompletionReportsKeyFailuresAndRemainsReplayable() = runTest {
        val clientSecret = ByteArray(32) { it.toByte() }
        synchronize(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(clientSecret),
                addressId = ADDRESS_ID,
            )
        )
        val completion =
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.COMPLETION,
                payload = pairingCompletion(clientSecret),
                addressId = ADDRESS_ID,
            )

        assertCompletionKeyFailures(completion)
        assertEquals(
            PairingState.EXCHANGE_PENDING.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )
        val retried = connect(completion, RelayDeviceEvent.CaughtUp)
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNotNull(database.requestDao().getRequestById(CLIENT_ID)?.exchangeEndedAt)
        assertEquals(
            PairingState.SAS_VERIFICATION_PENDING.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )
        assertTrue(
            RelayDeviceFrame.Acknowledgement(CLIENT_ID, CLIENT_ID, RelayMessageKind.COMPLETION) in
                retried.sentFrames
        )
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
            checkNotNull(database.requestDao().getRequestById(CLIENT_ID)).responseOutboxFinished
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
            checkNotNull(database.requestDao().getRequestById(CLIENT_ID)).responseOutboxFinished
        )
    }

    @Test
    fun retryableRelayErrorPreservesServerRetryDelay() = runTest {
        connect(
            RelayDeviceEvent.Error(
                code = "RATE_LIMITED",
                message = "retry later",
                retryable = true,
                scope = RelayDeviceErrorScope.Unscoped,
                retryAfterMillis = 60_000,
            )
        )

        assertEquals(
            RequestSyncResult.RelayUnavailable("retry later", 60_000),
            repository.sync(),
        )
    }

    @Test
    fun oversizedImmediateResponseIsReplacedAndProcessesTheNextRequest() = runTest {
        val clientPsk = establishActivePairing()
        val oversizedRequest =
            pairedRequest(
                requestId = SECRET_LIST_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = secretListPlaintext(),
            )
        val followingRequest =
            pairedRequest(
                requestId = UNSUPPORTED_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = unsupportedPlaintext(),
            )
        var oversizedResponseAttempts = 0
        val connection =
            connectWithSendResult(
                sendResult = { frame ->
                    if (
                        frame is RelayDeviceFrame.Response &&
                            frame.requestId == SECRET_LIST_REQUEST_ID &&
                            oversizedResponseAttempts++ == 0
                    ) {
                        RelayFrameSendResult.FrameTooLarge
                    } else {
                        RelayFrameSendResult.Sent
                    }
                },
                requestEvent(SECRET_LIST_REQUEST_ID, oversizedRequest),
                requestEvent(UNSUPPORTED_REQUEST_ID, followingRequest),
                RelayDeviceEvent.Acknowledgement(
                    CLIENT_ID,
                    SECRET_LIST_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.Acknowledgement(
                    CLIENT_ID,
                    UNSUPPORTED_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val oversized = checkNotNull(database.requestDao().getRequestById(SECRET_LIST_REQUEST_ID))
        assertNull(oversized.exchangeEndedAt)
        assertTrue(oversized.responseOutboxFinished)
        assertEquals("The response was too large to deliver.", oversized.error)
        assertNotNull(database.requestDao().getRequestPsk(SECRET_LIST_REQUEST_ID))
        val oversizedAttempts =
            connection.attemptedFrames.filterIsInstance<RelayDeviceFrame.Response>().filter {
                it.requestId == SECRET_LIST_REQUEST_ID
            }
        assertEquals(2, oversizedAttempts.size)
        assertEquals(oversizedAttempts.last().payload.toString(), oversized.responseJson)
        assertEquals(
            1,
            connection.sentFrames.count {
                it is RelayDeviceFrame.Response && it.requestId == SECRET_LIST_REQUEST_ID
            },
        )
        assertTrue(
            connection.sentFrames.any {
                it is RelayDeviceFrame.Response && it.requestId == UNSUPPORTED_REQUEST_ID
            }
        )
        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID)?.exchangeEndedAt)
    }

    @Test
    fun oversizedResponseRemainsFailedAfterAuthenticatedCompletion() = runTest {
        val clientPsk = establishActivePairing()
        val exchange =
            pairedExchange(
                requestId = SECRET_LIST_REQUEST_ID,
                clientPsk = clientPsk,
                requestPlaintext = secretListPlaintext(),
                completionPlaintext = abortedCompletionPlaintext("response rejected"),
            )
        var responseAttempts = 0
        connectWithSendResult(
            sendResult = { frame ->
                if (
                    frame is RelayDeviceFrame.Response &&
                        frame.requestId == SECRET_LIST_REQUEST_ID &&
                        responseAttempts++ == 0
                ) {
                    RelayFrameSendResult.FrameTooLarge
                } else {
                    RelayFrameSendResult.Sent
                }
            },
            requestEvent(SECRET_LIST_REQUEST_ID, exchange.request),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                SECRET_LIST_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            completionEvent(SECRET_LIST_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val completed = checkNotNull(database.requestDao().getRequestById(SECRET_LIST_REQUEST_ID))
        assertNotNull(completed.exchangeEndedAt)
        assertEquals("The response was too large to deliver.", completed.error)
        val audit =
            AuditRepository(database.auditDao()).observeEvents().first().single {
                it.type == AuditEventType.SECRET_LIST_COMPLETED
            }
        assertEquals(AuditOutcome.FAILED, audit.outcome)
        assertEquals("The response was too large to deliver.", audit.detail)
    }

    @Test
    fun oversizedReplayedResponseIsReplacedWithoutBlockingTheFollowingOutbox() = runTest {
        val clientPsk = establishActivePairing()
        val request =
            pairedRequest(
                requestId = UNSUPPORTED_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = unsupportedPlaintext(),
            )
        connectWithSendResult(
            sendResult = { frame ->
                if (frame is RelayDeviceFrame.Response) {
                    RelayFrameSendResult.Unavailable
                } else {
                    RelayFrameSendResult.Sent
                }
            },
            requestEvent(UNSUPPORTED_REQUEST_ID, request),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("Could not send relay response."),
            repository.sync(),
        )

        now += 1
        insertOpenUnknownRequest(INVOCATION_REQUEST_ID)
        var oversizedResponseAttempts = 0
        val connection =
            connectWithSendResult(
                sendResult = { frame ->
                    if (
                        frame is RelayDeviceFrame.Response &&
                            frame.requestId == UNSUPPORTED_REQUEST_ID &&
                            oversizedResponseAttempts++ == 0
                    ) {
                        RelayFrameSendResult.FrameTooLarge
                    } else {
                        RelayFrameSendResult.Sent
                    }
                },
                relayState(UNSUPPORTED_REQUEST_ID).copy(response = RelayMessageState.DELIVERED),
                relayState(INVOCATION_REQUEST_ID).copy(response = RelayMessageState.DELIVERED),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val oversized = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertNull(oversized.exchangeEndedAt)
        assertTrue(oversized.responseOutboxFinished)
        assertEquals("The response was too large to deliver.", oversized.error)
        assertNotNull(database.requestDao().getRequestPsk(UNSUPPORTED_REQUEST_ID))
        assertEquals(
            2,
            connection.attemptedFrames.count {
                it is RelayDeviceFrame.Response && it.requestId == UNSUPPORTED_REQUEST_ID
            },
        )
        assertEquals(
            1,
            connection.sentFrames.count {
                it is RelayDeviceFrame.Response && it.requestId == UNSUPPORTED_REQUEST_ID
            },
        )
        assertTrue(
            connection.sentFrames.any {
                it is RelayDeviceFrame.Response && it.requestId == INVOCATION_REQUEST_ID
            }
        )
        assertNull(database.requestDao().getRequestById(INVOCATION_REQUEST_ID)?.exchangeEndedAt)
    }

    @Test
    fun oversizedFallbackRemainsDurableAcrossAWriteFailure() = runTest {
        val clientPsk = establishActivePairing()
        val request =
            pairedRequest(
                requestId = SECRET_LIST_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = secretListPlaintext(),
            )
        var responseAttempts = 0
        connectWithSendResult(
            sendResult = { frame ->
                if (frame is RelayDeviceFrame.Response) {
                    if (responseAttempts++ == 0) {
                        RelayFrameSendResult.FrameTooLarge
                    } else {
                        RelayFrameSendResult.Unavailable
                    }
                } else {
                    RelayFrameSendResult.Sent
                }
            },
            requestEvent(SECRET_LIST_REQUEST_ID, request),
        )

        assertEquals(
            RequestSyncResult.RelayUnavailable("Could not send relay response."),
            repository.sync(),
        )
        val fallback = checkNotNull(database.requestDao().getRequestById(SECRET_LIST_REQUEST_ID))
        assertNull(fallback.exchangeEndedAt)
        assertFalse(fallback.responseOutboxFinished)
        assertEquals("The response was too large to deliver.", fallback.error)

        val retry =
            connect(
                relayState(SECRET_LIST_REQUEST_ID).copy(response = RelayMessageState.DELIVERED),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val delivered =
            retry.sentFrames.filterIsInstance<RelayDeviceFrame.Response>().single {
                it.requestId == SECRET_LIST_REQUEST_ID
            }
        assertEquals(fallback.responseJson, delivered.payload.toString())
        assertTrue(
            checkNotNull(database.requestDao().getRequestById(SECRET_LIST_REQUEST_ID))
                .responseOutboxFinished
        )
    }

    @Test
    fun scopedRetryableErrorPreservesDurableResponseAndServerRetryDelay() = runTest {
        insertOpenUnknownRequest()
        val connection =
            connect(
                RelayDeviceEvent.Error(
                    code = "CAPACITY_EXCEEDED",
                    message = "retry response later",
                    retryable = true,
                    scope =
                        RelayDeviceErrorScope.Exchange(
                            clientId = CLIENT_ID,
                            requestId = UNSUPPORTED_REQUEST_ID,
                            kind = RelayMessageKind.RESPONSE,
                        ),
                    retryAfterMillis = 30_000,
                )
            )

        assertEquals(
            RequestSyncResult.RelayUnavailable("retry response later", 30_000),
            repository.sync(),
        )
        val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertFalse(stored.responseOutboxFinished)
        assertNull(stored.exchangeEndedAt)
        assertTrue(
            connection.sentFrames.contains(
                RelayDeviceFrame.Response(
                    CLIENT_ID,
                    UNSUPPORTED_REQUEST_ID,
                    Json.parseToJsonElement("{}"),
                )
            )
        )
    }

    @Test
    fun nonretryableResponseErrorFinishesOnlyItsOutboxAndKeepsSocketHealthy() = runTest {
        insertOpenUnknownRequest()
        connect(
            RelayDeviceEvent.Error(
                code = "REQUEST_ID_CONFLICT",
                message = "response rejected",
                retryable = false,
                scope =
                    RelayDeviceErrorScope.Exchange(
                        clientId = CLIENT_ID,
                        requestId = UNSUPPORTED_REQUEST_ID,
                        kind = RelayMessageKind.RESPONSE,
                    ),
            ),
            RelayDeviceEvent.PushRegistration(RelayPushRegistrationState.REGISTERED),
            relayState(UNSUPPORTED_REQUEST_ID).copy(response = RelayMessageState.ABSENT),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertTrue(stored.responseOutboxFinished)
        assertNull(stored.exchangeEndedAt)
        assertEquals(RelayPushRegistrationState.REGISTERED, pushRegistrationState)
    }

    @Test
    fun nonretryableExchangeErrorEndsOnlyItsMatchingExchange() = runTest {
        insertOpenUnknownRequest(responseJson = null)
        now += 1
        insertOpenUnknownRequest(INVOCATION_REQUEST_ID, responseJson = null)
        connect(
            RelayDeviceEvent.Error(
                code = "INVALID_REQUEST_ID",
                message = "exchange rejected",
                retryable = false,
                scope =
                    RelayDeviceErrorScope.Exchange(
                        clientId = CLIENT_ID,
                        requestId = UNSUPPORTED_REQUEST_ID,
                        kind = null,
                    ),
            ),
            relayState(INVOCATION_REQUEST_ID),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        val rejected = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        val healthy = checkNotNull(database.requestDao().getRequestById(INVOCATION_REQUEST_ID))
        assertEquals(now, rejected.exchangeEndedAt)
        assertEquals("exchange rejected", rejected.error)
        assertNull(healthy.exchangeEndedAt)
    }

    @Test
    fun mismatchedScopedErrorRejectsSessionWithoutMutatingDurableWork() = runTest {
        insertOpenUnknownRequest()
        connect(
            RelayDeviceEvent.Error(
                code = "REQUEST_ID_CONFLICT",
                message = "mismatched error",
                retryable = false,
                scope =
                    RelayDeviceErrorScope.Exchange(
                        clientId = COLLISION_CLIENT_ID,
                        requestId = UNSUPPORTED_REQUEST_ID,
                        kind = RelayMessageKind.RESPONSE,
                    ),
            )
        )

        assertEquals(
            RequestSyncResult.RelayRejected(0, "mismatched error"),
            repository.sync(),
        )
        val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertFalse(stored.responseOutboxFinished)
        assertNull(stored.exchangeEndedAt)
    }

    @Test
    fun requestAndCompletionScopedErrorsAreInvalidForDeviceOperations() = runTest {
        insertOpenUnknownRequest()
        for (kind in listOf(RelayMessageKind.REQUEST, RelayMessageKind.COMPLETION)) {
            connect(
                RelayDeviceEvent.Error(
                    code = "INVALID_REQUEST_ID",
                    message = "unsupported device error scope",
                    retryable = false,
                    scope =
                        RelayDeviceErrorScope.Exchange(
                            clientId = CLIENT_ID,
                            requestId = UNSUPPORTED_REQUEST_ID,
                            kind = kind,
                        ),
                )
            )

            assertEquals(
                RequestSyncResult.RelayRejected(0, "unsupported device error scope"),
                repository.sync(),
            )
        }
        val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertFalse(stored.responseOutboxFinished)
        assertNull(stored.exchangeEndedAt)
    }

    @Test
    fun invalidClientStateSkipsItsMutationAndAdvancesRemainingDurableIntent() = runTest {
        insertDesiredClient(CLIENT_ID, pairedAt = now)
        insertDesiredClient(COLLISION_CLIENT_ID, pairedAt = now + 1)
        val connection =
            connect(
                RelayDeviceEvent.Error(
                    code = "INVALID_CLIENT_STATE",
                    message = "invalid transition",
                    retryable = false,
                    scope = RelayDeviceErrorScope.Unscoped,
                ),
                RelayDeviceEvent.ClientState(
                    COLLISION_CLIENT_ID,
                    RelayClientState.SUSPENDED,
                ),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            listOf(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.SUSPENDED),
                RelayDeviceFrame.SetClientState(
                    COLLISION_CLIENT_ID,
                    RelayClientState.SUSPENDED,
                ),
            ),
            connection.sentFrames,
        )
        assertEquals(
            RelayClientState.SUSPENDED.wireName,
            database.requestDao().getClient(CLIENT_ID)?.desiredRelayClientState,
        )
        assertEquals(
            null,
            database.requestDao().getClient(COLLISION_CLIENT_ID)?.desiredRelayClientState,
        )
    }

    @Test
    fun mixedDurableRelayWorkAdvancesOneOperationAtATime() = runTest {
        insertDesiredClient(COLLISION_CLIENT_ID, pairedAt = now)
        insertOpenUnknownRequest(UNSUPPORTED_REQUEST_ID)
        insertOpenUnknownRequest(INVOCATION_REQUEST_ID)
        insertOpenUnknownRequest(GIT_SIGN_REQUEST_ID)
        insertOpenUnknownRequest(REMOVE_REQUEST_ID, responseJson = null)
        val connection = connectInteractively()

        val synchronization = async { repository.sync() }
        val sent = buildList {
            repeat(5) {
                val frame = awaitAsynchronousWork { connection.nextSentFrame() }
                add(frame)
                assertEquals(1, connection.outstandingDurableOperations)
                connection.resolve(frame)
            }
        }
        connection.emit(RelayDeviceEvent.CaughtUp)

        assertEquals(
            RequestSyncResult.Success,
            awaitAsynchronousWork { synchronization.await() },
        )
        assertEquals(1, connection.maximumOutstandingDurableOperations)
        assertEquals(1, sent.count { it is RelayDeviceFrame.SetClientState })
        assertEquals(3, sent.count { it is RelayDeviceFrame.Response })
        assertEquals(1, sent.count { it is RelayDeviceFrame.Resume })
        assertTrue(connection.closed)
    }

    @Test
    fun matchingAcknowledgementThenReceiptAreAcceptedForRetainedRequest() = runTest {
        // Requests outlive revoked clients, so lifecycle frames are authenticated by the
        // request's durable ownership rather than by the presence of a current client row.
        assertNull(database.requestDao().getClient(CLIENT_ID))
        val original = insertOpenUnknownRequest()
        connect(
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                UNSUPPORTED_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.Receipt(
                CLIENT_ID,
                UNSUPPORTED_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            relayState(UNSUPPORTED_REQUEST_ID).copy(response = RelayMessageState.ABSENT),
            RelayDeviceEvent.CaughtUp,
        )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            original.copy(responseOutboxFinished = true),
            database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID),
        )
    }

    @Test
    fun lifecycleEventsFromForeignClientDoNotMutateRequest() = runTest {
        val original = insertOpenUnknownRequest()

        assertLifecycleEventsRejected(relayLifecycleEvents(COLLISION_CLIENT_ID), original)
    }

    @Test
    fun lifecycleEventsFromForeignIdentityDoNotMutateRequest() = runTest {
        database
            .deviceIdentityDao()
            .insertIdentity(
                DeviceIdentityEntity(
                    id = RETIRED_DEVICE_IDENTITY_ID,
                    role = "retired",
                    address = "retired-address",
                    deviceId = "01K2ENXDTW1P3XAR4J7V7C9D0J",
                    createdAt = now - 1,
                )
            )
        val original = insertOpenUnknownRequest(deviceIdentityId = RETIRED_DEVICE_IDENTITY_ID)

        assertLifecycleEventsRejected(relayLifecycleEvents(CLIENT_ID), original)
    }

    @Test
    fun lifecycleEventsWithoutDurableRequestAreRejected() = runTest {
        assertLifecycleEventsRejected(relayLifecycleEvents(CLIENT_ID), expectedRequest = null)
    }

    @Test
    fun openAndClosingStatesDoNotEndExchangeButClosingFinishesResponseOutbox() = runTest {
        insertOpenUnknownRequest()
        connect(
            relayState(UNSUPPORTED_REQUEST_ID)
                .copy(
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
    fun caughtUpOnlySyncPrunesExpiredSettledHiddenRequest() = runTest {
        val endedAt = now - 26 * 60 * 60 * 1_000L
        val request = insertOpenUnknownRequest()
        assertEquals(
            1,
            database
                .requestDao()
                .updateRequest(
                    request.copy(
                        state = InboxRequestState.COMPLETED.storedName,
                        completedAt = endedAt,
                        exchangeEndedAt = endedAt,
                        responseOutboxFinished = true,
                    )
                ),
        )
        connect(RelayDeviceEvent.CaughtUp)

        assertEquals(RequestSyncResult.Success, repository.sync())

        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
    }

    @Test
    fun responseInactiveOnlyFinishesOutboxAndLeavesExchangeOpen() = runTest {
        insertOpenUnknownRequest()
        val connection =
            connect(
                RelayDeviceEvent.Inactive(
                    CLIENT_ID,
                    UNSUPPORTED_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.CaughtUp,
                relayState(UNSUPPORTED_REQUEST_ID).copy(response = RelayMessageState.ABSENT),
            )

        assertEquals(RequestSyncResult.Success, repository.sync())
        val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertTrue(stored.responseOutboxFinished)
        assertNull(stored.exchangeEndedAt)
        assertTrue(
            RelayDeviceFrame.Resume(CLIENT_ID, UNSUPPORTED_REQUEST_ID) in connection.sentFrames
        )
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
        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID)?.exchangeEndedAt)

        connect(
            RelayDeviceEvent.Inactive(
                CLIENT_ID,
                UNSUPPORTED_REQUEST_ID,
                RelayMessageKind.COMPLETION,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID)?.exchangeEndedAt)
    }

    @Test
    fun expiredStateEndsExchange() = runTest {
        insertOpenUnknownRequest()
        connect(
            RelayDeviceEvent.State(
                clientId = CLIENT_ID,
                requestId = UNSUPPORTED_REQUEST_ID,
                exchange = RelayExchangeState.EXPIRED,
                response = RelayMessageState.ABSENT,
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
            val current = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
            database.requestDao().updateRequest(current.copy(responseOutboxFinished = false))
            connect(
                RelayDeviceEvent.State(
                    clientId = CLIENT_ID,
                    requestId = UNSUPPORTED_REQUEST_ID,
                    exchange = RelayExchangeState.OPEN,
                    response = responseState,
                ),
                RelayDeviceEvent.CaughtUp,
            )

            assertEquals(RequestSyncResult.Success, repository.sync())
            val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
            assertTrue(stored.responseOutboxFinished)
            assertNull(stored.exchangeEndedAt)
        }
    }

    @Test
    fun authenticatedUnknownCompletionEndsExchangeWithoutResponseStatus() = runTest {
        val clientPsk = establishActivePairing()
        val exchange =
            pairedExchange(
                requestId = UNSUPPORTED_REQUEST_ID,
                clientPsk = clientPsk,
                requestPlaintext = unsupportedPlaintext(),
                completionPlaintext = "{}".encodeToByteArray(),
            )
        assertCompletionEndsExchangeWithoutResponseStatus(UNSUPPORTED_REQUEST_ID, exchange)
        assertEquals(
            "The requested operation is not supported.",
            database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID)?.error,
        )
    }

    @Test
    fun pairedCompletionReportsKeyFailuresAndRemainsReplayable() = runTest {
        val clientPsk = establishActivePairing()
        val exchange =
            pairedExchange(
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
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNotNull(database.requestDao().getRequestPsk(UNSUPPORTED_REQUEST_ID))
        val completion = completionEvent(UNSUPPORTED_REQUEST_ID, exchange.completion)

        assertCompletionKeyFailures(completion)
        val retried = connect(completion, RelayDeviceEvent.CaughtUp)
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID)?.exchangeEndedAt)
        assertNull(database.requestDao().getRequestPsk(UNSUPPORTED_REQUEST_ID))
        assertTrue(
            RelayDeviceFrame.Acknowledgement(
                CLIENT_ID,
                UNSUPPORTED_REQUEST_ID,
                RelayMessageKind.COMPLETION,
            ) in retried.sentFrames
        )
    }

    @Test
    fun unsupportedStoredRequestEncryptionFormatEndsCompletionAsInvalid() = runTest {
        val clientPsk = establishActivePairing()
        val exchange =
            pairedExchange(
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
        val rejectionError = database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID)?.error
        assertNotNull(rejectionError)
        database.useWriterConnection { connection ->
            connection.executeSQL(
                "UPDATE request_psks SET encryption_format = 999 " +
                    "WHERE request_id = '$UNSUPPORTED_REQUEST_ID'"
            )
        }

        connect(
            completionEvent(UNSUPPORTED_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val ended = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertNotNull(ended.exchangeEndedAt)
        assertEquals(rejectionError, ended.error)
        assertNull(database.requestDao().getRequestPsk(UNSUPPORTED_REQUEST_ID))
    }

    @Test
    fun lowOrderStoredRequestKeyEndsCompletionAsInvalid() = runTest {
        val clientPsk = establishActivePairing()
        val exchange =
            pairedExchange(
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

        val stored = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        val requestObject = Json.parseToJsonElement(stored.requestJson) as JsonObject
        val lowOrderKey = Base64.getEncoder().encodeToString(ByteArray(32))
        assertEquals(
            1,
            database
                .requestDao()
                .updateRequest(
                    stored.copy(
                        requestJson =
                            JsonObject(requestObject + ("key" to JsonPrimitive(lowOrderKey)))
                                .toString()
                    )
                ),
        )

        connect(
            completionEvent(UNSUPPORTED_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val ended = checkNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertNotNull(ended.exchangeEndedAt)
        assertEquals(stored.error, ended.error)
        assertNull(database.requestDao().getRequestPsk(UNSUPPORTED_REQUEST_ID))
    }

    @Test
    fun authenticatedPairingRemovalCompletionEndsExchangeWithoutResponseStatus() = runTest {
        val clientPsk = establishActivePairing()
        val exchange =
            pairedExchange(
                requestId = REMOVE_REQUEST_ID,
                clientPsk = clientPsk,
                requestPlaintext =
                    """{${clientSoftwareFields()},"method":"PairingRemove"}""".encodeToByteArray(),
                completionPlaintext = """{${clientSoftwareFields()}}""".encodeToByteArray(),
            )
        assertCompletionEndsExchangeWithoutResponseStatus(REMOVE_REQUEST_ID, exchange)
    }

    @Test
    fun responseDeliveryDoesNotCompletePairingRemoval() = runTest {
        val clientPsk = establishActivePairing()
        val exchange =
            pairedExchange(
                requestId = REMOVE_REQUEST_ID,
                clientPsk = clientPsk,
                requestPlaintext =
                    """{${clientSoftwareFields()},"method":"PairingRemove"}""".encodeToByteArray(),
                completionPlaintext = """{${clientSoftwareFields()}}""".encodeToByteArray(),
            )
        val connection =
            connect(
                requestEvent(REMOVE_REQUEST_ID, exchange.request),
                RelayDeviceEvent.State(
                    clientId = CLIENT_ID,
                    requestId = REMOVE_REQUEST_ID,
                    exchange = RelayExchangeState.OPEN,
                    response = RelayMessageState.DELIVERED,
                ),
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
                RelayDeviceEvent.CaughtUp,
                RelayDeviceEvent.State(
                    clientId = CLIENT_ID,
                    requestId = REMOVE_REQUEST_ID,
                    exchange = RelayExchangeState.OPEN,
                    response = RelayMessageState.DELIVERED,
                ),
            )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val responseIndex =
            connection.sentFrames.indexOfFirst {
                it is RelayDeviceFrame.Response && it.requestId == REMOVE_REQUEST_ID
            }
        val revocationIndex =
            connection.sentFrames.indexOfFirst {
                it == RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.REVOKED)
            }
        assertTrue(responseIndex >= 0)
        assertTrue(revocationIndex > responseIndex)

        val request = checkNotNull(database.requestDao().getRequestById(REMOVE_REQUEST_ID))
        assertTrue(request.responseOutboxFinished)
        assertNull(request.completedAt)
        assertNull(request.exchangeEndedAt)
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertFalse(
            AuditRepository(database.auditDao()).observeEvents().first().any {
                it.type == AuditEventType.CLIENT_UNPAIRED_ITSELF &&
                    it.relayRequestId == REMOVE_REQUEST_ID
            }
        )
    }

    @Test
    fun invalidPairingRemovalCompletionUsesSafeFailureCategory() = runTest {
        val clientPsk = establishActivePairing()
        val malicious = "raw-client-removal-parser-input"
        val exchange =
            pairedExchange(
                requestId = REMOVE_REQUEST_ID,
                clientPsk = clientPsk,
                requestPlaintext =
                    """{${clientSoftwareFields()},"method":"PairingRemove"}""".encodeToByteArray(),
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
        val completionAudit =
            AuditRepository(database.auditDao()).observeEvents().first().single {
                it.type == AuditEventType.CLIENT_REMOVAL_CONFIRMATION_FAILED
            }
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
            relayState(CLIENT_ID),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val exchange =
            pairedExchange(
                requestId = FINISH_REQUEST_ID,
                clientPsk = material.clientPsk,
                requestPlaintext =
                    """{${clientSoftwareFields()},"method":"PairingFinish"}""".encodeToByteArray(),
                completionPlaintext =
                    """{${clientSoftwareFields()},"result":"ACCEPTED"}""".encodeToByteArray(),
            )
        assertCompletionEndsExchangeWithoutResponseStatus(FINISH_REQUEST_ID, exchange)
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
    fun correctSasRequiresSuccessfulAuthentication() = runTest {
        receivePairingUntilSas()
        val pairing = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        val correctIndex = checkNotNull(pairing.correctSasIndex)
        var authentication: DeviceAuthenticationResult =
            DeviceAuthenticationResult.Error("cancelled")
        var authenticationCalls = 0
        val actions = actions {
            authenticationCalls += 1
            authentication
        }

        assertEquals(
            ProtectedActionResult.AuthenticationFailed("cancelled"),
            actions.choosePairingCode(CLIENT_ID, correctIndex),
        )
        assertEquals(1, authenticationCalls)
        assertEquals(
            PairingState.SAS_VERIFICATION_PENDING.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )

        authentication = DeviceAuthenticationResult.Success
        assertEquals(
            ProtectedActionResult.Completed(PairingDecisionResult.VERIFIED),
            actions.choosePairingCode(CLIENT_ID, correctIndex),
        )
        assertEquals(2, authenticationCalls)
        assertEquals(
            PairingState.WAITING_FOR_FINISH.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )
    }

    @Test
    fun wrongSasRejectsWithoutRecordingACodeAcceptedTimestamp() = runTest {
        receivePairingUntilSas()
        val pairing = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        val wrongIndex = (checkNotNull(pairing.correctSasIndex) + 1) % 3

        assertEquals(
            ProtectedActionResult.Completed(PairingDecisionResult.REJECTED),
            actions { error("Wrong SAS must not request authentication") }
                .choosePairingCode(CLIENT_ID, wrongIndex),
        )

        val rejected = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(PairingState.REJECTED.storedName, rejected.state)
        assertNull(rejected.decidedAt)
    }

    @Test
    fun noneOfTheSasChoicesRejectsWithoutAuthentication() = runTest {
        receivePairingUntilSas()

        assertEquals(
            ProtectedActionResult.Completed(PairingDecisionResult.REJECTED),
            actions { error("None of the above must not request authentication") }
                .choosePairingCode(CLIENT_ID, selectedIndex = null),
        )
        assertEquals(
            PairingState.REJECTED.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )
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
            )
        )
        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))
        assertNull(database.requestDao().getRequestById(CLIENT_ID)?.exchangeEndedAt)

        val connection =
            connect(
                RelayDeviceEvent.Message(
                    clientId = CLIENT_ID,
                    requestId = CLIENT_ID,
                    kind = RelayMessageKind.COMPLETION,
                    payload = pairingCompletion(clientSecret),
                    addressId = ADDRESS_ID,
                ),
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
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
                )
            )
        )
    }

    @Test
    fun rejectedPendingPairingRevokesAClientThatActivatesLateOnTheSameConnection() = runTest {
        receivePairingUntilSas()
        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))

        val connection =
            connect(
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
                RelayDeviceEvent.CaughtUp,
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
            )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertTrue(
            connection.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.REVOKED)
            )
        )
        val attempt = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals("rejected", attempt.state)
        assertEquals("revoked", attempt.relayClientState)
        assertNull(attempt.desiredRelayClientState)
        assertNull(database.requestDao().getClient(CLIENT_ID))
    }

    @Test
    fun rejectedPairingRetriesRevocationAfterLosingTheActivationState() = runTest {
        receivePairingUntilSas()
        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))

        val stillPending =
            connect(
                RelayDeviceEvent.Error(
                    code = "INVALID_CLIENT_STATE",
                    message = "client is still pending",
                    retryable = false,
                    scope = RelayDeviceErrorScope.Unscoped,
                ),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(
            stillPending.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.REVOKED)
            )
        )
        assertEquals(
            RelayClientState.REVOKED.wireName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.desiredRelayClientState,
        )

        val activatedWithoutReply =
            connect(
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(
            activatedWithoutReply.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.REVOKED)
            )
        )
        val revoked = checkNotNull(database.requestDao().getPairingAttempt(CLIENT_ID))
        assertEquals(RelayClientState.REVOKED.wireName, revoked.relayClientState)
        assertNull(revoked.desiredRelayClientState)
    }

    @Test
    fun finishBeforeActivationPromotesPendingClientThenAppliesActiveState() = runTest {
        val material = verifyPairingSas()
        val finish = finishRequest(material)

        val connection =
            connect(
                requestEvent(FINISH_REQUEST_ID, finish),
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
                RelayDeviceEvent.Acknowledgement(
                    CLIENT_ID,
                    FINISH_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                relayState(CLIENT_ID),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertTrue(
            connection.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.ACTIVE)
            )
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
    fun finishFromAnotherClientIsDiscardedWithoutPersisting() = runTest {
        val material = verifyPairingSas()
        connect(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            relayState(CLIENT_ID),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val finish = finishRequest(material)
        val connection =
            connect(
                RelayDeviceEvent.Message(
                    clientId = COLLISION_CLIENT_ID,
                    requestId = FINISH_REQUEST_ID,
                    kind = RelayMessageKind.REQUEST,
                    payload = finish,
                    addressId = null,
                ),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())

        assertNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertEquals(
            PairingState.WAITING_FOR_FINISH.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )
        assertTrue(
            connection.sentFrames.contains(
                RelayDeviceFrame.Acknowledgement(
                    COLLISION_CLIENT_ID,
                    FINISH_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                )
            )
        )
    }

    @Test
    fun finishAfterPairingRejectionIsDiscardedWithoutPersisting() = runTest {
        val material = verifyPairingSas()
        assertEquals(PairingDecisionResult.REJECTED, repository.rejectPairing(CLIENT_ID))
        val connection =
            connect(
                requestEvent(FINISH_REQUEST_ID, finishRequest(material)),
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())

        assertNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertEquals(
            PairingState.REJECTED.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )
        assertTrue(
            connection.sentFrames.contains(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    FINISH_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                )
            )
        )
    }

    @Test
    fun keyAccessFailureLeavesRequestsUnacknowledgedForReplay() = runTest {
        val clientPsk = establishActivePairing()
        assertTrue(decryptedDeviceKeys.isNotEmpty())
        assertTrue(decryptedDeviceKeys.all { bytes -> bytes.all { it == 0.toByte() } })
        val request =
            pairedRequest(
                requestId = UNSUPPORTED_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = unsupportedPlaintext(),
            )
        for ((failure, expected) in
            listOf(
                DecryptionResult.KeyUnavailable to RequestSyncResult.DeviceCredentialsUnavailable,
                DecryptionResult.AuthenticationFailed to
                    RequestSyncResult.DeviceCredentialsCorrupted,
                DecryptionResult.UnsupportedFormat to
                    RequestSyncResult.UnsupportedDeviceCredentialEncryption,
            )) {
            deviceKeyFailure = failure
            val connection =
                connect(requestEvent(UNSUPPORTED_REQUEST_ID, request), RelayDeviceEvent.CaughtUp)
            assertEquals(expected, repository.sync())
            assertTrue(connection.closed)
            assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
            assertFalse(
                connection.sentFrames.any {
                    it is RelayDeviceFrame.Acknowledgement && it.requestId == UNSUPPORTED_REQUEST_ID
                }
            )
        }
        deviceKeyFailure = null
        val retried =
            connect(
                requestEvent(UNSUPPORTED_REQUEST_ID, request),
                RelayDeviceEvent.Acknowledgement(
                    CLIENT_ID,
                    UNSUPPORTED_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertTrue(
            retried.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == UNSUPPORTED_REQUEST_ID
            }
        )
        assertTrue(decryptedDeviceKeys.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun idleConnectionDoesNotOpenTheDeviceKey() = runTest {
        deviceKeyFailure = DecryptionResult.KeyUnavailable
        connect(RelayDeviceEvent.CaughtUp)
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(decryptedDeviceKeys.isEmpty())
    }

    @Test
    fun requestReplayedAfterClientSuspensionIsDiscardedAndAcknowledged() = runTest {
        val clientPsk = establishActivePairing()
        val client = checkNotNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals(
            1,
            database
                .requestDao()
                .updateClient(client.copy(relayClientState = RelayClientState.SUSPENDED.wireName)),
        )
        val request =
            pairedRequest(
                requestId = UNSUPPORTED_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = unsupportedPlaintext(),
            )
        val connection =
            connect(
                requestEvent(UNSUPPORTED_REQUEST_ID, request),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertTrue(
            connection.sentFrames.contains(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    UNSUPPORTED_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                )
            )
        )
    }

    @Test
    fun failedFinishPromotionRollsBackAndCanBeRetried() = runTest {
        val material = verifyPairingSas()
        connect(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            relayState(CLIENT_ID),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val finish = finishRequest(material)
        database.useWriterConnection { connection ->
            connection.executeSQL(
                """
                CREATE TRIGGER fail_pairing_completed_audit
                BEFORE INSERT ON audit_events
                WHEN NEW.event_type = 'pairing_completed'
                BEGIN
                    SELECT RAISE(ABORT, 'forced audit failure');
                END
                """
                    .trimIndent()
            )
        }

        val failed =
            connect(
                requestEvent(FINISH_REQUEST_ID, finish),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(unprocessedRelayMessage(RelayMessageKind.REQUEST), repository.sync())
        assertTrue(failed.closed)
        assertNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals(
            PairingState.WAITING_FOR_FINISH.storedName,
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )
        assertFalse(
            failed.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == FINISH_REQUEST_ID
            }
        )

        database.useWriterConnection { connection ->
            connection.executeSQL("DROP TRIGGER fail_pairing_completed_audit")
        }
        val retried =
            connect(
                requestEvent(FINISH_REQUEST_ID, finish),
                RelayDeviceEvent.Acknowledgement(
                    CLIENT_ID,
                    FINISH_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertNotNull(database.requestDao().getRequestById(FINISH_REQUEST_ID))
        assertNotNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals(
            1,
            AuditRepository(database.auditDao()).observeEvents().first().count {
                it.type == AuditEventType.PAIRING_COMPLETED
            },
        )
        assertTrue(
            retried.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == FINISH_REQUEST_ID
            }
        )
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
        val stateConnection =
            connect(
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
                RelayDeviceEvent.Failed("disconnect after revocation"),
            )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect after revocation"),
            repository.sync(),
        )
        assertTrue(
            stateConnection.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.ACTIVE)
            )
        )

        val finish = finishRequest(material)
        val finishConnection =
            connect(
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
            }
        )

        val replay =
            connect(
                requestEvent(FINISH_REQUEST_ID, finish),
                relayState(FINISH_REQUEST_ID).copy(response = RelayMessageState.ABSENT),
                RelayDeviceEvent.Acknowledgement(
                    CLIENT_ID,
                    FINISH_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                relayState(FINISH_REQUEST_ID).copy(exchange = RelayExchangeState.SETTLED),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(
            replay.sentFrames.contains(
                RelayDeviceFrame.Response(CLIENT_ID, FINISH_REQUEST_ID, persistedResponse)
            )
        )
    }

    @Test
    fun revokedAfterFinishProducesNoDurableClient() = runTest {
        val material = verifyPairingSas()
        val finish = finishRequest(material)
        connect(
            requestEvent(FINISH_REQUEST_ID, finish),
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.REVOKED),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                FINISH_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            relayState(FINISH_REQUEST_ID),
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
        val invocation =
            pairedRequest(
                requestId = INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = invocationPlaintext(token),
            )

        val interrupted =
            connect(
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

        val stored = checkNotNull(database.requestDao().getRequestById(INVOCATION_REQUEST_ID))
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
        val replay =
            connect(
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
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    INVOCATION_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
            ),
            replay.sentFrames,
        )
        val afterReplay = checkNotNull(database.requestDao().getRequestById(INVOCATION_REQUEST_ID))
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
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(token),
            )
        val first =
            connect(
                requestEvent(AI_INVOCATION_REQUEST_ID, request),
                RelayDeviceEvent.PushRegistration(RelayPushRegistrationState.REGISTERED),
                RelayDeviceEvent.CaughtUp,
                relayState(AI_INVOCATION_REQUEST_ID),
            )

        assertEquals(RequestSyncResult.Success, repository.sync())

        val reviewing = checkNotNull(database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID))
        assertEquals(InboxRequestState.REVIEWING.storedName, reviewing.state)
        assertEquals(1, approvalReviewer.callCount)
        assertEquals(
            RelayPushRegistrationState.REGISTERED,
            pushRegistrationState,
        )
        assertTrue(
            first.sentFrames.contains(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    AI_INVOCATION_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                )
            )
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
            )
        )
        val reviewed = awaitAsynchronousWork {
            inbox
                .observeRequest(AI_INVOCATION_REQUEST_ID)
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
        val requestAudit =
            AuditRepository(database.auditDao()).observeEvents().first().filter {
                it.relayRequestId == AI_INVOCATION_REQUEST_ID
            }
        assertNull(requestAudit.single { it.type == AuditEventType.SECRET_USE_AI_REVIEWED }.detail)
        assertFalse(requestAudit.any { it.detail?.contains(attackerControlledExplanation) == true })
        assertEquals(1, approvalReviewer.callCount)
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(AI_INVOCATION_REQUEST_ID, RequestDecision.APPROVE),
        )
    }

    @Test
    fun inactiveAiRequestsStayManualAfterRenewalWhileNewRequestsResumeAi() = runTest {
        val clientPsk = establishActivePairing()
        val secretId = createAiEnvironmentSecret()
        subscription.active = false
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(ByteArray(32) { (0x22 + it).toByte() }),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(0, approvalReviewer.callCount)
        val manual = checkNotNull(inbox.observeRequest(AI_INVOCATION_REQUEST_ID).first())
        assertEquals(InboxRequestState.ACTION_REQUIRED, manual.state)
        assertEquals(
            AiReviewFailure.SUBSCRIPTION_REQUIRED,
            manual.secretUse?.approvalEvaluation?.aiReview?.failure,
        )
        assertEquals(
            SecretApprovalMode.ASK_AI,
            secrets.observeSecret(secretId).first()?.approvalMode,
        )
        assertEquals(
            AuditOutcome.DEFERRED,
            AuditRepository(database.auditDao())
                .observeEvents()
                .first()
                .single {
                    it.relayRequestId == AI_INVOCATION_REQUEST_ID &&
                        it.type == AuditEventType.SECRET_USE_AI_REVIEWED
                }
                .outcome,
        )

        subscription.active = true
        val nextRequest =
            pairedRequest(
                requestId = INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(ByteArray(32) { (0x33 + it).toByte() }),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            requestEvent(INVOCATION_REQUEST_ID, nextRequest),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
            relayState(INVOCATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(1, approvalReviewer.callCount)
        assertEquals(2, subscription.statusCalls)
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID)?.state,
        )
        assertEquals(
            InboxRequestState.REVIEWING.storedName,
            database.requestDao().getRequestById(INVOCATION_REQUEST_ID)?.state,
        )
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(AI_INVOCATION_REQUEST_ID, RequestDecision.APPROVE),
        )
    }

    @Test
    fun expiryDuringReviewUpdatesSharedAccessAndLeavesTheRequestManual() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(ByteArray(32) { (0x22 + it).toByte() }),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        approvalReviewer.complete(
            RelayEndpointResult.Rejected(402, "SUBSCRIPTION_REQUIRED", "Subscription expired")
        )
        val manual = awaitAsynchronousWork {
            inbox
                .observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter {
                    it.state == InboxRequestState.ACTION_REQUIRED &&
                        it.secretUse?.approvalEvaluation?.aiReview != null
                }
                .first()
        }
        assertEquals(
            AiReviewFailure.SUBSCRIPTION_REQUIRED,
            manual.secretUse?.approvalEvaluation?.aiReview?.failure,
        )
        assertEquals(
            dev.agentknock.subscription.AiReviewAccess.INACTIVE,
            subscription.repository.access.value,
        )
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(AI_INVOCATION_REQUEST_ID, RequestDecision.DENY),
        )
    }

    @Test
    fun exhaustedAiReviewRetriesUseSafeFailureCategory() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val token = ByteArray(32) { (0x22 + it).toByte() }
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(token),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val malicious = "relay-controlled-ai-rejection-message"

        repeat(4) {
            approvalReviewer.complete(
                RelayEndpointResult.Rejected(
                    status = 500,
                    code = "REVIEW_FAILED",
                    message = malicious,
                    retryAfterMillis = 0,
                )
            )
        }
        val reviewed = awaitAsynchronousWork {
            inbox
                .observeRequest(AI_INVOCATION_REQUEST_ID)
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
        assertEquals(4, approvalReviewer.callCount)
        val requestAudit =
            AuditRepository(database.auditDao()).observeEvents().first().filter {
                it.relayRequestId == AI_INVOCATION_REQUEST_ID
            }
        assertEquals(
            "The relay rejected AI review.",
            requestAudit.single { it.type == AuditEventType.SECRET_USE_AI_REVIEWED }.detail,
        )
        assertFalse(requestAudit.any { it.detail?.contains(malicious) == true })
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(AI_INVOCATION_REQUEST_ID, RequestDecision.DENY),
        )
    }

    @Test
    fun aiReviewCompletionRevalidatesChangedApprovalSettings() = runTest {
        val clientPsk = establishActivePairing()
        val secretId = createAiEnvironmentSecret()
        val token = ByteArray(32) { (0x21 + it).toByte() }
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(token),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            SaveSecretResult.SAVED,
            secrets.saveApprovalMode(secretId, SecretApprovalMode.ASK_ME),
        )

        approvalReviewer.complete(
            reviewed(
                decision = RelayApprovalReviewDecision.APPROVE,
                explanation = "The original settings allow this request.",
            )
        )
        val reviewed = awaitAsynchronousWork {
            inbox
                .observeRequest(AI_INVOCATION_REQUEST_ID)
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
            reviewed.secretUse
                ?.approvalEvaluation
                ?.aiReview
                ?.explanation
                ?.contains("changed during AI review") == true
        )
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(
                AI_INVOCATION_REQUEST_ID,
                RequestDecision.ALLOW_TEMPORARILY,
            ),
        )
    }

    @Test
    fun aiApprovalSchedulesAndDeliversItsDurableResponse() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(ByteArray(32) { (0x22 + it).toByte() }),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val synchronizationsBeforeReview = synchronizationRequests

        approvalReviewer.complete(
            reviewed(
                decision = RelayApprovalReviewDecision.APPROVE,
                explanation = "The request follows the supplied instructions.",
            )
        )
        val stored = awaitAsynchronousWork {
            database
                .requestDao()
                .observeRequest(AI_INVOCATION_REQUEST_ID)
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

        val delivery =
            connect(
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
                )
            )
        )
        assertTrue(
            checkNotNull(database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID))
                .responseOutboxFinished
        )
    }

    @Test
    fun aiReviewEscalatesWhenClientInstructionsChange() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(ByteArray(32) { (0x23 + it).toByte() }),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
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
            )
        )
        val reviewed = awaitAsynchronousWork {
            inbox
                .observeRequest(AI_INVOCATION_REQUEST_ID)
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
            reviewed.secretUse
                ?.approvalEvaluation
                ?.aiReview
                ?.explanation
                ?.contains("instructions") == true
        )
    }

    @Test
    fun aiApprovalEscalatesWhenDeviceInstructionsChange() = runTest {
        assertAiReviewEscalatesWhenDeviceInstructionsChange(RelayApprovalReviewDecision.APPROVE)
    }

    @Test
    fun aiDenialEscalatesWhenDeviceInstructionsChange() = runTest {
        assertAiReviewEscalatesWhenDeviceInstructionsChange(RelayApprovalReviewDecision.DENY)
    }

    @Test
    fun aiApprovalEscalatesWhenClientNameChanges() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(ByteArray(32) { (0x26 + it).toByte() }),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
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
            )
        )
        val reviewed = awaitAsynchronousWork {
            inbox
                .observeRequest(AI_INVOCATION_REQUEST_ID)
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
        decision: RelayApprovalReviewDecision
    ) {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(ByteArray(32) { (0x25 + it).toByte() }),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        val changedInstructions = "Never use production credentials for experiments."
        credentialSource.credentials =
            RelayDeviceCredentials(
                deviceIdentityId = credentials.deviceIdentityId,
                address = credentials.address,
                addressId = credentials.addressId,
                deviceId = credentials.deviceId,
                deviceKey = credentials.deviceKey,
                deviceToken = credentials.deviceToken,
                instructions = changedInstructions,
            )
        assertEquals(
            1,
            database
                .deviceIdentityDao()
                .updateActiveInstructions(
                    activeRole = "active",
                    instructions = changedInstructions,
                ),
        )

        approvalReviewer.complete(
            reviewed(
                decision = decision,
                explanation = "The original device instructions determine this verdict.",
            )
        )
        val reviewed = awaitAsynchronousWork {
            inbox
                .observeRequest(AI_INVOCATION_REQUEST_ID)
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
            reviewed.secretUse
                ?.approvalEvaluation
                ?.aiReview
                ?.explanation
                ?.contains("instructions") == true
        )
    }

    @Test
    fun aiReviewDefersIfClientWasRevokedDuringReview() = runTest {
        val clientPsk = establishActivePairing()
        createAiEnvironmentSecret()
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = aiInvocationPlaintext(ByteArray(32) { (0x24 + it).toByte() }),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
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
            )
        )
        val stored = awaitAsynchronousWork {
            database
                .requestDao()
                .observeRequest(AI_INVOCATION_REQUEST_ID)
                .filterNotNull()
                .filter { it.state == InboxRequestState.ACTION_REQUIRED.storedName }
                .first()
        }
        val secretUse =
            checkNotNull(database.requestDao().getSecretUseRequest(AI_INVOCATION_REQUEST_ID))
        assertNull(secretUse.decision)
        assertNull(
            Json.decodeFromString<ApprovalEvaluation>(
                    checkNotNull(secretUse.approvalEvaluationJson)
                )
                .aiReview
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

        val connection =
            connect(
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
                RelayDeviceEvent.CaughtUp,
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.SUSPENDED),
            )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertEquals(
            2,
            connection.sentFrames.count {
                it ==
                    RelayDeviceFrame.SetClientState(
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
        database
            .requestDao()
            .insertRequest(
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
                    error = null,
                    receivedAt = now,
                    completedAt = null,
                    exchangeEndedAt = null,
                    responseOutboxFinished = false,
                )
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
    fun storedValueActionsAuthenticateFromCurrentSensitivity() = runTest {
        val secretId =
            (secrets.createEnvironmentSecret("protected", "") as CreateSecretResult.Created).id
        val variableId =
            (secrets.createEnvironmentVariable(
                    secretId = secretId,
                    name = "TOKEN",
                    value = "secret-value",
                    sensitive = true,
                    nonSensitiveCreationAuthorized = false,
                ) as CreateEnvironmentVariableResult.Created)
                .id
        var authentication: DeviceAuthenticationResult =
            DeviceAuthenticationResult.Error("cancelled")
        var authenticationCalls = 0
        val actions = actions {
            authenticationCalls += 1
            authentication
        }

        assertEquals(
            ProtectedActionResult.AuthenticationFailed("cancelled"),
            actions.readEnvironmentVariable(variableId, SecretValueAction.REVEAL),
        )
        assertEquals(
            EnvironmentVariableValue.AuthenticationRequired("TOKEN"),
            secrets.readEnvironmentVariableValue(
                variableId,
                sensitiveAccessAuthorized = false,
            ),
        )
        assertEquals(
            ProtectedActionResult.AuthenticationFailed("cancelled"),
            actions.saveEnvironmentVariable(
                id = variableId,
                name = "TOKEN",
                sensitive = false,
                replacementValue = null,
            ),
        )
        assertTrue(
            checkNotNull(secrets.observeSecret(secretId).first())
                .environmentVariables
                .single()
                .sensitive
        )

        authentication = DeviceAuthenticationResult.Success
        assertEquals(
            ProtectedActionResult.Completed(SaveEnvironmentVariableResult.SAVED),
            actions.saveEnvironmentVariable(
                id = variableId,
                name = "TOKEN",
                sensitive = false,
                replacementValue = null,
            ),
        )
        assertFalse(
            checkNotNull(secrets.observeSecret(secretId).first())
                .environmentVariables
                .single()
                .sensitive
        )

        authentication = DeviceAuthenticationResult.Error("must not be requested")
        val callsBeforeNonSensitiveRead = authenticationCalls
        assertEquals(
            ProtectedActionResult.Completed(
                EnvironmentVariableValue.Available("TOKEN", "secret-value", sensitive = false)
            ),
            actions.readEnvironmentVariable(variableId, SecretValueAction.REVEAL),
        )
        assertEquals(callsBeforeNonSensitiveRead, authenticationCalls)
    }

    @Test
    fun nonSensitiveVariableCreationRequiresSuccessfulAuthentication() = runTest {
        val secretId =
            (secrets.createEnvironmentSecret("public", "") as CreateSecretResult.Created).id
        var authentication: DeviceAuthenticationResult =
            DeviceAuthenticationResult.Error("cancelled")
        val actions = actions { authentication }

        assertEquals(
            ProtectedActionResult.AuthenticationFailed("cancelled"),
            actions.createEnvironmentVariable(
                secretId = secretId,
                name = "REGION",
                value = "eu-north-1",
                sensitive = false,
            ),
        )
        assertTrue(
            checkNotNull(secrets.observeSecret(secretId).first()).environmentVariables.isEmpty()
        )

        authentication = DeviceAuthenticationResult.Success
        val created =
            actions.createEnvironmentVariable(
                secretId = secretId,
                name = "REGION",
                value = "eu-north-1",
                sensitive = false,
            )
        assertTrue(
            created is ProtectedActionResult.Completed &&
                created.value is CreateEnvironmentVariableResult.Created
        )
        assertFalse(
            checkNotNull(secrets.observeSecret(secretId).first())
                .environmentVariables
                .single()
                .sensitive
        )
    }

    @Test
    fun pendingUploadActionsFollowCurrentSensitivity() = runTest {
        receiveEnvironmentSecretUpload()
        val variable =
            database.requestDao().getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID).single()
        var authentication: DeviceAuthenticationResult =
            DeviceAuthenticationResult.Error("cancelled")
        var authenticationCalls = 0
        val actions = actions {
            authenticationCalls += 1
            authentication
        }

        assertEquals(
            ProtectedActionResult.AuthenticationFailed("cancelled"),
            actions.readSecretUploadVariable(UPLOAD_REQUEST_ID, variable.id),
        )
        assertEquals(
            ProtectedActionResult.AuthenticationFailed("cancelled"),
            actions.setSecretUploadVariableSensitivity(
                UPLOAD_REQUEST_ID,
                variable.id,
                sensitive = false,
            ),
        )
        assertTrue(
            database
                .requestDao()
                .getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID)
                .single()
                .sensitive
        )

        authentication = DeviceAuthenticationResult.Success
        assertEquals(
            ProtectedActionResult.Completed(SecretUploadSensitivityResult.Changed),
            actions.setSecretUploadVariableSensitivity(
                UPLOAD_REQUEST_ID,
                variable.id,
                sensitive = false,
            ),
        )
        authentication = DeviceAuthenticationResult.Error("must not be requested")
        val callsBeforeNonSensitiveRead = authenticationCalls
        assertEquals(
            ProtectedActionResult.Completed(SecretUploadVariableValue.Available("secret-value")),
            actions.readSecretUploadVariable(UPLOAD_REQUEST_ID, variable.id),
        )
        assertEquals(callsBeforeNonSensitiveRead, authenticationCalls)
    }

    @Test
    fun terminalRequestIdCollisionIsAcknowledgedWithoutReplayingResponse() = runTest {
        establishActivePairing()
        val collision =
            connect(
                RelayDeviceEvent.Message(
                    clientId = CLIENT_ID,
                    requestId = CLIENT_ID,
                    kind = RelayMessageKind.REQUEST,
                    payload =
                        Json.parseToJsonElement(
                            """{"version":"agentknock-v1","commitment":"different"}"""
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
                )
            ),
            collision.sentFrames,
        )
        assertEquals(
            pairingRequest(ByteArray(32) { it.toByte() }).toString(),
            database.requestDao().getRequestById(CLIENT_ID)?.requestJson,
        )
    }

    @Test
    fun requestIdCollisionFromAnotherIdentityIsDiscarded() = runTest {
        database
            .deviceIdentityDao()
            .insertIdentity(
                DeviceIdentityEntity(
                    id = RETIRED_DEVICE_IDENTITY_ID,
                    role = "retired",
                    address = "retired-address",
                    deviceId = "01K2ENXDTW1P3XAR4J7V7C9D0J",
                    createdAt = now - 1,
                )
            )
        val payload = Json.parseToJsonElement("""{"same":"payload"}""")
        val stored =
            InboxRequestEntity(
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
                error = null,
                receivedAt = now - 1,
                completedAt = null,
                exchangeEndedAt = null,
                responseOutboxFinished = false,
            )
        database.requestDao().insertRequest(stored)
        val collision =
            connect(
                requestEvent(UNSUPPORTED_REQUEST_ID, payload),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            listOf(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    UNSUPPORTED_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                )
            ),
            collision.sentFrames,
        )
        assertEquals(stored, database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
    }

    @Test
    fun completionFromAnotherIdentityIsDiscardedWithoutBeingApplied() = runTest {
        database
            .deviceIdentityDao()
            .insertIdentity(
                DeviceIdentityEntity(
                    id = RETIRED_DEVICE_IDENTITY_ID,
                    role = "retired",
                    address = "retired-address",
                    deviceId = "01K2ENXDTW1P3XAR4J7V7C9D0J",
                    createdAt = now - 1,
                )
            )
        val stored =
            InboxRequestEntity(
                id = UNSUPPORTED_REQUEST_ID,
                parentRequestId = null,
                deviceIdentityId = RETIRED_DEVICE_IDENTITY_ID,
                clientId = CLIENT_ID,
                clientNameSnapshot = "Retired identity client",
                clientSoftwareJson = null,
                kind = RequestKind.UNKNOWN.storedName,
                state = InboxRequestState.COMPLETED.storedName,
                listed = false,
                requestJson = "{}",
                responseJson = "{}",
                error = null,
                receivedAt = now - 1,
                completedAt = now - 1,
                exchangeEndedAt = now - 1,
                responseOutboxFinished = true,
            )
        database.requestDao().insertRequest(stored)
        val collision =
            connect(
                completionEvent(
                    UNSUPPORTED_REQUEST_ID,
                    Json.parseToJsonElement("""{"ciphertext":"collision"}"""),
                ),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            listOf(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    UNSUPPORTED_REQUEST_ID,
                    RelayMessageKind.COMPLETION,
                )
            ),
            collision.sentFrames,
        )
        assertEquals(stored, database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
    }

    @Test
    fun unacknowledgedRequestClosesWithoutWaitingForCaughtUp() = runTest {
        val clientPsk = establishActivePairing()
        protocolRandom.fail = true
        val request =
            pairedRequest(
                requestId = UNSUPPORTED_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = unsupportedPlaintext(),
            )
        val connection = connectInteractively()

        val synchronization = async { repository.sync() }
        connection.emit(requestEvent(UNSUPPORTED_REQUEST_ID, request))

        assertEquals(
            unprocessedRelayMessage(RelayMessageKind.REQUEST),
            awaitAsynchronousWork { synchronization.await() },
        )
        assertTrue(connection.closed)
        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertFalse(
            connection.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == UNSUPPORTED_REQUEST_ID
            }
        )
    }

    @Test
    fun rejectedRequestAndAuditRollBackTogetherAndCanBeRetried() = runTest {
        val clientPsk = establishActivePairing()
        val beforeEvents = AuditRepository(database.auditDao()).observeEvents().first()
        val request =
            pairedRequest(
                requestId = UNSUPPORTED_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = unsupportedPlaintext(),
            )

        database.useWriterConnection { connection ->
            connection.executeSQL(
                """
                CREATE TRIGGER fail_request_rejected_audit
                BEFORE INSERT ON audit_events
                WHEN NEW.event_type = 'request_rejected'
                BEGIN
                    SELECT RAISE(ABORT, 'forced audit failure');
                END
                """
                    .trimIndent()
            )
        }
        val failed =
            connect(
                requestEvent(UNSUPPORTED_REQUEST_ID, request),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(unprocessedRelayMessage(RelayMessageKind.REQUEST), repository.sync())
        assertTrue(failed.closed)
        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertEquals(beforeEvents, AuditRepository(database.auditDao()).observeEvents().first())
        assertFalse(
            failed.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == UNSUPPORTED_REQUEST_ID
            }
        )

        database.useWriterConnection { connection ->
            connection.executeSQL("DROP TRIGGER fail_request_rejected_audit")
        }
        val retried =
            connect(
                requestEvent(UNSUPPORTED_REQUEST_ID, request),
                RelayDeviceEvent.Acknowledgement(
                    CLIENT_ID,
                    UNSUPPORTED_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertNotNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertTrue(
            retried.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == UNSUPPORTED_REQUEST_ID
            }
        )
        val rejection =
            AuditRepository(database.auditDao()).observeEvents().first().single {
                it.type == AuditEventType.REQUEST_REJECTED
            }
        assertEquals(UNSUPPORTED_REQUEST_ID, rejection.relayRequestId)
    }

    @Test
    fun pendingPairingRejectionClosesForReplayWhenResponseSealingFails() = runTest {
        val material = verifyPairingSas()
        connect(
            RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
            relayState(CLIENT_ID),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            "waiting_for_finish",
            database.requestDao().getPairingAttempt(CLIENT_ID)?.state,
        )

        protocolRandom.fail = true
        val request =
            pairedRequest(
                requestId = UNSUPPORTED_REQUEST_ID,
                clientPsk = material.clientPsk,
                plaintext = unsupportedPlaintext(),
            )
        val connection =
            connect(
                requestEvent(UNSUPPORTED_REQUEST_ID, request),
                relayState(CLIENT_ID),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(unprocessedRelayMessage(RelayMessageKind.REQUEST), repository.sync())
        assertTrue(connection.closed)
        assertNull(database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID))
        assertFalse(
            connection.sentFrames.any {
                it is RelayDeviceFrame.Acknowledgement && it.requestId == UNSUPPORTED_REQUEST_ID
            }
        )
    }

    @Test
    fun pairingStartRejectsClientIdAlreadyOwnedByDurableClient() = runTest {
        database
            .requestDao()
            .insertClient(
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
                )
            )
        val collision =
            connect(
                RelayDeviceEvent.Message(
                    clientId = COLLISION_CLIENT_ID,
                    requestId = COLLISION_CLIENT_ID,
                    kind = RelayMessageKind.REQUEST,
                    payload = pairingRequest(ByteArray(32) { it.toByte() }),
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
                    exchange = RelayExchangeState.SETTLED,
                    response = RelayMessageState.DELIVERED,
                ),
                RelayDeviceEvent.CaughtUp,
            )

        assertEquals(RequestSyncResult.Success, repository.sync())
        val rejected = checkNotNull(database.requestDao().getRequestById(COLLISION_CLIENT_ID))
        assertFalse(rejected.listed)
        assertEquals(InboxRequestState.COMPLETED.storedName, rejected.state)
        assertEquals(
            PairingState.REJECTED.storedName,
            database.requestDao().getPairingAttempt(COLLISION_CLIENT_ID)?.state,
        )
        assertTrue(
            collision.sentFrames.contains(
                RelayDeviceFrame.Acknowledgement(
                    COLLISION_CLIENT_ID,
                    COLLISION_CLIENT_ID,
                    RelayMessageKind.REQUEST,
                )
            )
        )
        assertEquals(
            "INVALID_STATE",
            collision.sentFrames
                .filterIsInstance<RelayDeviceFrame.Response>()
                .single()
                .payload
                .jsonObject
                .getValue("error")
                .jsonPrimitive
                .content,
        )
        assertNotNull(database.requestDao().getClient(COLLISION_CLIENT_ID))
    }

    @Test
    fun correlatedGitSigningDenialPersistsParentLinkAndReplaysExactResponse() = runTest {
        val clientPsk = establishActivePairing()
        createSigningSecret()
        val token = ByteArray(32) { (0x30 + it).toByte() }
        val invocation =
            pairedRequest(
                requestId = INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = invocationPlaintext(token),
            )
        val invocationConnection =
            connect(
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
        val parent = checkNotNull(database.requestDao().getRequestById(INVOCATION_REQUEST_ID))

        now += 1
        val signing =
            pairedRequest(
                requestId = GIT_SIGN_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = gitSignPlaintext(token),
            )
        val received =
            connect(
                relayState(INVOCATION_REQUEST_ID),
                requestEvent(GIT_SIGN_REQUEST_ID, signing),
                RelayDeviceEvent.CaughtUp,
                relayState(GIT_SIGN_REQUEST_ID),
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
                RelayDeviceFrame.Resume(CLIENT_ID, GIT_SIGN_REQUEST_ID),
            ),
            received.sentFrames,
        )

        now += 1
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(child.id, RequestDecision.DENY),
        )
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

        val replay =
            connect(
                RelayDeviceEvent.Receipt(
                    CLIENT_ID,
                    GIT_SIGN_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                relayState(INVOCATION_REQUEST_ID),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            listOf(
                RelayDeviceFrame.Response(CLIENT_ID, GIT_SIGN_REQUEST_ID, persistedResponse),
                RelayDeviceFrame.Resume(CLIENT_ID, INVOCATION_REQUEST_ID),
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
        val exchange =
            pairedExchange(
                requestId = GIT_SIGN_REQUEST_ID,
                clientPsk = invocation.clientPsk,
                requestPlaintext = gitSignPlaintext(invocation.token),
                completionPlaintext = approvedCompletionPlaintext(),
            )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(GIT_SIGN_REQUEST_ID, exchange.request),
            RelayDeviceEvent.CaughtUp,
            relayState(GIT_SIGN_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(GIT_SIGN_REQUEST_ID)?.state,
        )

        now += 1
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(GIT_SIGN_REQUEST_ID, RequestDecision.APPROVE),
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
            relayState(GIT_SIGN_REQUEST_ID),
            relayState(INVOCATION_REQUEST_ID),
            completionEvent(GIT_SIGN_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val completed = checkNotNull(database.requestDao().getRequestById(GIT_SIGN_REQUEST_ID))
        val completedSigning =
            checkNotNull(database.requestDao().getGitSignRequest(GIT_SIGN_REQUEST_ID))
        assertEquals(InboxRequestState.COMPLETED.storedName, completed.state)
        assertEquals(
            ApprovalCompletionResult.APPROVED.storedName,
            completedSigning.completionResult,
        )
        assertTrue(completed.responseOutboxFinished)
        assertNotNull(completed.exchangeEndedAt)
        assertNull(database.requestDao().getRequestPsk(GIT_SIGN_REQUEST_ID))
    }

    @Test
    fun invalidGitCompletionDoesNotRetainDecodedClientFields() = runTest {
        val invocation = establishSigningInvocation()
        val malicious = "raw-invalid-git-client-message"
        val exchange =
            pairedExchange(
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
        val completionAudit =
            AuditRepository(database.auditDao()).observeEvents().first().single {
                it.type == AuditEventType.GIT_SIGN_COMPLETED
            }
        assertEquals("Git signing completion could not be verified.", completionAudit.detail)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    @Test
    fun deniedSshAuthenticationReplaysExactlyAndAcceptsMatchingCompletion() = runTest {
        val invocation = establishSigningInvocation()
        val exchange =
            pairedExchange(
                requestId = SSH_AUTHENTICATION_REQUEST_ID,
                clientPsk = invocation.clientPsk,
                requestPlaintext = sshAuthenticationPlaintext(invocation.token, invocation.key),
                completionPlaintext = deniedSshAuthenticationCompletionPlaintext(),
            )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.request),
            RelayDeviceEvent.CaughtUp,
            relayState(SSH_AUTHENTICATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val received =
            checkNotNull(database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID))
        val authentication =
            checkNotNull(
                database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID)
            )
        assertEquals(INVOCATION_REQUEST_ID, received.parentRequestId)
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, received.state)
        assertEquals("git-signing", authentication.secretName)
        assertEquals("deploy", authentication.username)
        assertEquals("publickey", authentication.method)
        assertEquals("ssh-ed25519", authentication.algorithm)

        now += 1
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(SSH_AUTHENTICATION_REQUEST_ID, RequestDecision.DENY),
        )
        val denied =
            checkNotNull(database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID))
        val persistedResponse = Json.parseToJsonElement(checkNotNull(denied.responseJson))

        now += 1
        val replay =
            connect(
                RelayDeviceEvent.Acknowledgement(
                    CLIENT_ID,
                    SSH_AUTHENTICATION_REQUEST_ID,
                    RelayMessageKind.RESPONSE,
                ),
                relayState(INVOCATION_REQUEST_ID),
                completionEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.completion),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())

        assertEquals(
            listOf(persistedResponse),
            replay.sentFrames.filterIsInstance<RelayDeviceFrame.Response>().map { it.payload },
        )
        val completed =
            checkNotNull(database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID))
        val completedAuthentication =
            checkNotNull(
                database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID)
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
        val exchange =
            pairedExchange(
                requestId = SSH_AUTHENTICATION_REQUEST_ID,
                clientPsk = invocation.clientPsk,
                requestPlaintext = sshAuthenticationPlaintext(invocation.token, invocation.key),
                completionPlaintext = approvedCompletionPlaintext(),
            )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.request),
            RelayDeviceEvent.CaughtUp,
            relayState(SSH_AUTHENTICATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        now += 1
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(SSH_AUTHENTICATION_REQUEST_ID, RequestDecision.APPROVE),
        )
        val decided =
            checkNotNull(
                database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID)
            )
        assertEquals(ApprovalDecision.APPROVED.storedName, decided.decision)
        assertNull(decided.message)

        now += 1
        connect(
            relayState(SSH_AUTHENTICATION_REQUEST_ID),
            relayState(INVOCATION_REQUEST_ID),
            completionEvent(SSH_AUTHENTICATION_REQUEST_ID, exchange.completion),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())

        val completed =
            checkNotNull(database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID))
        val completedAuthentication =
            checkNotNull(
                database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID)
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
    fun sharedDispatcherAllowsTemporaryGitAndSshUse() = runTest {
        val invocation = establishSigningInvocation(SecretApprovalMode.ASK_ME)
        val gitRequest =
            pairedRequest(
                requestId = GIT_SIGN_REQUEST_ID,
                clientPsk = invocation.clientPsk,
                plaintext = gitSignPlaintext(invocation.token),
            )
        connect(
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(GIT_SIGN_REQUEST_ID, gitRequest),
            RelayDeviceEvent.CaughtUp,
            relayState(GIT_SIGN_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(GIT_SIGN_REQUEST_ID)?.state,
        )
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(
                GIT_SIGN_REQUEST_ID,
                RequestDecision.ALLOW_TEMPORARILY,
            ),
        )
        assertEquals(
            setOf(TemporaryAccessOperation.GIT_SIGN),
            secrets.observeTemporaryAccessGrants().first().map { it.operation }.toSet(),
        )

        now += 1
        val sshRequest =
            pairedRequest(
                requestId = SSH_AUTHENTICATION_REQUEST_ID,
                clientPsk = invocation.clientPsk,
                plaintext = sshAuthenticationPlaintext(invocation.token, invocation.key),
            )
        connect(
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                GIT_SIGN_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            relayState(INVOCATION_REQUEST_ID),
            requestEvent(SSH_AUTHENTICATION_REQUEST_ID, sshRequest),
            RelayDeviceEvent.CaughtUp,
            relayState(SSH_AUTHENTICATION_REQUEST_ID),
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID)?.state,
        )
        assertEquals(
            RequestDecisionResult.Decided,
            repository.decideRequest(
                SSH_AUTHENTICATION_REQUEST_ID,
                RequestDecision.ALLOW_TEMPORARILY,
            ),
        )
        assertEquals(
            setOf(
                TemporaryAccessOperation.GIT_SIGN,
                TemporaryAccessOperation.SSH_AUTHENTICATE,
            ),
            secrets.observeTemporaryAccessGrants().first().map { it.operation }.toSet(),
        )
    }

    @Test
    fun invalidSshAuthenticationCompletionDoesNotRetainDecodedClientFields() = runTest {
        val invocation = establishSigningInvocation()
        val malicious = "raw-invalid-ssh-client-message"
        val exchange =
            pairedExchange(
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

        val request =
            checkNotNull(database.requestDao().getRequestById(SSH_AUTHENTICATION_REQUEST_ID))
        assertEquals("SSH authentication completion could not be verified.", request.error)
        val authentication =
            checkNotNull(
                database.requestDao().getSshAuthenticationRequest(SSH_AUTHENTICATION_REQUEST_ID)
            )
        assertNull(authentication.completionResult)
        assertNull(authentication.completionReason)
        assertNull(authentication.completionMessage)
        assertNull(authentication.message)
        val completionAudit =
            AuditRepository(database.auditDao()).observeEvents().first().single {
                it.type == AuditEventType.SSH_AUTHENTICATION_COMPLETED
            }
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
        val exchange =
            pairedExchange(
                requestId = SECRET_LIST_REQUEST_ID,
                clientPsk = clientPsk,
                requestPlaintext = secretListPlaintext(),
                completionPlaintext = clientSoftwarePlaintext(),
            )
        val interrupted =
            connect(
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
        val replay =
            connect(
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
            listOf(persistedResponse),
            replay.sentFrames.filterIsInstance<RelayDeviceFrame.Response>().map { it.payload },
        )
        val completed = checkNotNull(database.requestDao().getRequestById(SECRET_LIST_REQUEST_ID))
        assertEquals(stored.receivedAt, completed.receivedAt)
        assertEquals(InboxRequestState.COMPLETED.storedName, completed.state)
        assertNull(database.requestDao().getRequestPsk(SECRET_LIST_REQUEST_ID))
    }

    @Test
    fun completedSecretUploadRemainsPendingUntilTheUserRejectsIt() = runTest {
        val clientPsk = establishActivePairing()
        val exchange =
            pairedExchange(
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
        val pendingUpload =
            checkNotNull(database.requestDao().getSecretUploadRequest(UPLOAD_REQUEST_ID))
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, awaitingDecision.state)
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
        val rejectedUpload =
            checkNotNull(database.requestDao().getSecretUploadRequest(UPLOAD_REQUEST_ID))
        assertEquals(InboxRequestState.COMPLETED.storedName, rejected.state)
        assertEquals("rejected", rejectedUpload.decision)
        assertNotNull(rejected.completedAt)
        assertTrue(
            database.requestDao().getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID).isEmpty()
        )
        assertTrue(database.secretDao().getSecretsByName(listOf("uploaded-secret")).isEmpty())
    }

    @Test
    fun rejectedSecretUploadWaitsForAuthenticatedClientCompletion() = runTest {
        val clientPsk = establishActivePairing()
        val exchange =
            pairedExchange(
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
        val awaitingCompletion =
            checkNotNull(database.requestDao().getRequestById(UPLOAD_REQUEST_ID))
        assertEquals(InboxRequestState.WAITING.storedName, awaitingCompletion.state)
        assertNull(awaitingCompletion.completedAt)
        assertNotNull(database.requestDao().getRequestPsk(UPLOAD_REQUEST_ID))
        assertTrue(
            database.requestDao().getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID).isEmpty()
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
        assertNotNull(completed.completedAt)
        assertNull(database.requestDao().getRequestPsk(UPLOAD_REQUEST_ID))
        assertTrue(database.secretDao().getSecretsByName(listOf("uploaded-secret")).isEmpty())
    }

    private suspend fun establishActivePairing(): ByteArray {
        val material = verifyPairingSas()

        val activation =
            connect(
                RelayDeviceEvent.ClientState(CLIENT_ID, RelayClientState.ACTIVE),
                relayState(CLIENT_ID),
                RelayDeviceEvent.CaughtUp,
            )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(
            activation.sentFrames.contains(
                RelayDeviceFrame.SetClientState(CLIENT_ID, RelayClientState.ACTIVE)
            )
        )

        now += 1
        val finish = finishRequest(material)
        connect(
            relayState(CLIENT_ID),
            requestEvent(FINISH_REQUEST_ID, finish),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                FINISH_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            relayState(FINISH_REQUEST_ID).copy(exchange = RelayExchangeState.SETTLED),
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

    private suspend fun establishSigningInvocation(
        approvalMode: SecretApprovalMode? = null
    ): SigningInvocation {
        val clientPsk = establishActivePairing()
        val key = createSigningSecret(approvalMode)
        val token = ByteArray(32) { (0x70 + it).toByte() }
        val request =
            pairedRequest(
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
        val upload =
            pairedRequest(
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
            database.requestDao().getSecretUploadEnvironmentVariables(UPLOAD_REQUEST_ID).size,
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
        val events =
            if (stateBeforeFinish) {
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

    private fun finishRequest(material: PairingMaterial): JsonElement =
        pairedRequest(
            requestId = FINISH_REQUEST_ID,
            clientPsk = material.clientPsk,
            plaintext =
                """{${clientSoftwareFields()},"method":"PairingFinish"}""".encodeToByteArray(),
        )

    private fun actions(authorize: suspend (String) -> DeviceAuthenticationResult) =
        AgentknockActions(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            authorize = authorize,
            requests = repository,
            secrets = secrets,
            awaitStorageReady = {},
        )

    private suspend fun createSigningSecret(
        approvalMode: SecretApprovalMode? = null
    ): SshPrivateKey {
        val key = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "characterization@test")
        val created =
            secrets.createSshSecret("git-signing", "Signing key", key) as CreateSecretResult.Created
        approvalMode?.let { mode ->
            assertEquals(SaveSecretResult.SAVED, secrets.saveApprovalMode(created.id, mode))
        }
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
                nonSensitiveCreationAuthorized = true,
            ) is CreateEnvironmentVariableResult.Created
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
        val created =
            secrets.createEnvironmentSecret(
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
                    nonSensitiveCreationAuthorized = true,
                ) is CreateEnvironmentVariableResult.Created
            )
        }
        val token = ByteArray(32) { (0x45 + it).toByte() }
        val request =
            pairedRequest(
                requestId = AI_INVOCATION_REQUEST_ID,
                clientPsk = clientPsk,
                plaintext = environmentSelectionInvocationPlaintext(token, delivery),
            )
        connect(
            requestEvent(AI_INVOCATION_REQUEST_ID, request),
            RelayDeviceEvent.CaughtUp,
            relayState(AI_INVOCATION_REQUEST_ID),
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
                nonSensitiveCreationAuthorized = true,
            ) is CreateEnvironmentVariableResult.Created
        )

        assertEquals(
            RequestDecisionResult.SecretChanged,
            repository.decideRequest(AI_INVOCATION_REQUEST_ID, RequestDecision.APPROVE),
        )
        val stored =
            checkNotNull(database.requestDao().getSecretUseRequest(AI_INVOCATION_REQUEST_ID))
        val metadata =
            Json.decodeFromString<List<SecretMetadata>>(stored.secretDetailsJson).single()
        assertEquals(expectedVariableNames, metadata.environmentVariableNames.sorted())
        assertNull(database.requestDao().getRequestById(AI_INVOCATION_REQUEST_ID)?.responseJson)
    }

    private suspend fun <T> awaitAsynchronousWork(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { block() }
        }

    private suspend fun insertOpenUnknownRequest(
        requestId: String = UNSUPPORTED_REQUEST_ID,
        deviceIdentityId: String = DEVICE_IDENTITY_ID,
        responseJson: String? = "{}",
    ): InboxRequestEntity {
        val request =
            InboxRequestEntity(
                id = requestId,
                parentRequestId = null,
                deviceIdentityId = deviceIdentityId,
                clientId = CLIENT_ID,
                clientNameSnapshot = "Test client",
                clientSoftwareJson = null,
                kind = RequestKind.UNKNOWN.storedName,
                state = InboxRequestState.WAITING.storedName,
                listed = false,
                requestJson = "{}",
                responseJson = responseJson,
                error = null,
                receivedAt = now,
                completedAt = null,
                exchangeEndedAt = null,
                responseOutboxFinished = false,
            )
        database.requestDao().insertRequest(request)
        return request
    }

    private suspend fun insertDesiredClient(clientId: String, pairedAt: Long) {
        database
            .requestDao()
            .insertClient(
                ClientEntity(
                    clientId = clientId,
                    deviceIdentityId = DEVICE_IDENTITY_ID,
                    name = "Test client $clientId",
                    instructions = "",
                    desiredRelayClientState = RelayClientState.SUSPENDED.wireName,
                    relayClientState = RelayClientState.ACTIVE.wireName,
                    clientSoftwareJson = null,
                    platform = null,
                    architecture = null,
                    hostname = null,
                    machineId = null,
                    osVersion = null,
                    pairedAt = pairedAt,
                    lastSeenAt = pairedAt,
                )
            )
    }

    private fun connect(vararg events: RelayDeviceEvent): TestRelayDeviceConnection =
        TestRelayDeviceConnection(events.toList()).also(relay::enqueue)

    private fun connectWithSendResult(
        sendResult: (RelayDeviceFrame) -> RelayFrameSendResult,
        vararg events: RelayDeviceEvent,
    ): TestRelayDeviceConnection =
        TestRelayDeviceConnection(
                events = events.toList(),
                sendResult = sendResult,
            )
            .also(relay::enqueue)

    private fun connectInteractively(): InteractiveRelayDeviceConnection =
        InteractiveRelayDeviceConnection().also(relay::enqueue)

    private suspend fun assertCompletionKeyFailures(completion: RelayDeviceEvent.Message) {
        val priorError = database.requestDao().getRequestById(completion.requestId)?.error
        val hadRequestPsk = database.requestDao().getRequestPsk(completion.requestId) != null
        for ((failure, expected) in
            listOf(
                DecryptionResult.KeyUnavailable to RequestSyncResult.DeviceCredentialsUnavailable,
                DecryptionResult.AuthenticationFailed to
                    RequestSyncResult.DeviceCredentialsCorrupted,
                DecryptionResult.UnsupportedFormat to
                    RequestSyncResult.UnsupportedDeviceCredentialEncryption,
            )) {
            deviceKeyFailure = failure
            val connection = connect(completion, RelayDeviceEvent.CaughtUp)
            assertEquals(expected, repository.sync())
            assertTrue(connection.closed)
            val request = checkNotNull(database.requestDao().getRequestById(completion.requestId))
            assertNull(request.exchangeEndedAt)
            assertEquals(priorError, request.error)
            assertEquals(
                hadRequestPsk,
                database.requestDao().getRequestPsk(completion.requestId) != null,
            )
            assertFalse(
                connection.sentFrames.any {
                    it is RelayDeviceFrame.Acknowledgement &&
                        it.requestId == completion.requestId &&
                        it.kind == RelayMessageKind.COMPLETION
                }
            )
        }
        deviceKeyFailure = null
    }

    private fun unprocessedRelayMessage(kind: RelayMessageKind) =
        RequestSyncResult.RelayUnavailable(
            "Could not durably process relay ${kind.wireName}; reconnecting for replay."
        )

    private fun requestEvent(requestId: String, payload: JsonElement) =
        RelayDeviceEvent.Message(
            clientId = CLIENT_ID,
            requestId = requestId,
            kind = RelayMessageKind.REQUEST,
            payload = payload,
            addressId = null,
        )

    private fun completionEvent(
        requestId: String,
        payload: JsonElement,
    ) =
        RelayDeviceEvent.Message(
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
            checkNotNull(database.requestDao().getRequestById(requestId)).responseOutboxFinished
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
            checkNotNull(database.requestDao().getRequestById(requestId)).responseOutboxFinished
        )
    }

    private fun relayState(requestId: String) =
        RelayDeviceEvent.State(
            clientId = CLIENT_ID,
            requestId = requestId,
            exchange = RelayExchangeState.OPEN,
            response = RelayMessageState.DELIVERED,
        )

    private fun relayLifecycleEvents(clientId: String): List<Pair<RelayDeviceEvent, String>> =
        buildList {
            RelayMessageKind.entries.forEach { kind ->
                add(
                    RelayDeviceEvent.Acknowledgement(
                        clientId,
                        UNSUPPORTED_REQUEST_ID,
                        kind,
                    ) to "acknowledgement"
                )
                add(
                    RelayDeviceEvent.Receipt(
                        clientId,
                        UNSUPPORTED_REQUEST_ID,
                        kind,
                    ) to "receipt"
                )
                add(
                    RelayDeviceEvent.Inactive(
                        clientId,
                        UNSUPPORTED_REQUEST_ID,
                        kind,
                    ) to "inactive event"
                )
            }
            add(
                RelayDeviceEvent.Inactive(
                    clientId,
                    UNSUPPORTED_REQUEST_ID,
                    null,
                ) to "inactive event"
            )
            RelayExchangeState.entries.forEach { exchange ->
                RelayMessageState.entries.forEach { response ->
                    add(
                        RelayDeviceEvent.State(
                            clientId,
                            UNSUPPORTED_REQUEST_ID,
                            exchange,
                            response,
                        ) to "state"
                    )
                }
            }
        }

    private suspend fun assertLifecycleEventsRejected(
        events: List<Pair<RelayDeviceEvent, String>>,
        expectedRequest: InboxRequestEntity?,
    ) {
        for ((event, eventName) in events) {
            connect(event)
            assertEquals(
                event.toString(),
                RequestSyncResult.RelayRejected(
                    0,
                    "Relay protocol mismatch: $eventName does not match this device's " +
                        "stored request.",
                ),
                repository.sync(),
            )
            assertEquals(
                event.toString(),
                expectedRequest,
                database.requestDao().getRequestById(UNSUPPORTED_REQUEST_ID),
            )
        }
    }

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
        val publicKeyBlob =
            sshStrings(
                key.algorithm.publicName.encodeToByteArray(),
                key.publicKey,
            )
        val message =
            ByteArrayOutputStream().use { bytes ->
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
        val sender =
            PSK_HPKE.SetupPSKS(
                PSK_HPKE.deserializePublicKey(devicePublicKey),
                VERSION_INFO + DEVICE_ID.ulidBytes() + requestId.ulidBytes(),
                clientPsk,
                CLIENT_ID.ulidBytes(),
            )
        val encapsulation =
            (sender as org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation).encapsulation
        return Json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, plaintext))}"}"""
        )
    }

    private fun pairedExchange(
        requestId: String,
        clientPsk: ByteArray,
        requestPlaintext: ByteArray,
        completionPlaintext: ByteArray,
    ): PairedExchange {
        val sender =
            PSK_HPKE.SetupPSKS(
                PSK_HPKE.deserializePublicKey(devicePublicKey),
                VERSION_INFO + DEVICE_ID.ulidBytes() + requestId.ulidBytes(),
                clientPsk,
                CLIENT_ID.ulidBytes(),
            ) as org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation
        val request =
            Json.parseToJsonElement(
                """{"version":"agentknock-v1","key":"${BASE64.encodeToString(sender.encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, requestPlaintext))}"}"""
            )
        val completion =
            Json.parseToJsonElement(
                """{"ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, completionPlaintext))}"}"""
            )
        return PairedExchange(request, completion)
    }

    private suspend fun synchronize(vararg events: RelayDeviceEvent) {
        val state =
            RelayDeviceEvent.State(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                exchange = RelayExchangeState.OPEN,
                response = RelayMessageState.DELIVERED,
            )
        val connection =
            TestRelayDeviceConnection(events.toList() + state + RelayDeviceEvent.CaughtUp)
        relay.enqueue(connection)
        assertEquals(RequestSyncResult.Success, repository.sync())
    }

    private fun pairingRequest(clientSecret: ByteArray): JsonElement {
        val commitment =
            derive(
                input = clientSecret,
                salt = "agentknock-v1".encodeToByteArray(),
                info = "agentknock-v1 commitment".encodeToByteArray(),
                length = 32,
            )
        return Json.parseToJsonElement(
            """{"version":"agentknock-v1","commitment":"${BASE64.encodeToString(commitment)}"}"""
        )
    }

    private fun pairingCompletion(clientSecret: ByteArray): JsonElement {
        return pairingMaterial(clientSecret).completion
    }

    private fun pairingMaterial(clientSecret: ByteArray): PairingMaterial {
        val sender =
            BASE_HPKE.setupBaseS(
                BASE_HPKE.deserializePublicKey(devicePublicKey),
                VERSION_INFO + DEVICE_ID.ulidBytes() + CLIENT_ID.ulidBytes(),
            )
        val secretCiphertext = sender.seal(EMPTY, clientSecret)
        val applicationCiphertext =
            sender.seal(
                EMPTY,
                """{${clientSoftwareFields()},"platform":"linux","architecture":"x86_64","hostname":"test"}"""
                    .encodeToByteArray(),
            )
        val encapsulation =
            (sender as org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation).encapsulation
        return PairingMaterial(
            completion =
                Json.parseToJsonElement(
                    """{"key":"${BASE64.encodeToString(encapsulation)}","secret":"${BASE64.encodeToString(secretCiphertext)}","ciphertext":"${BASE64.encodeToString(applicationCiphertext)}"}"""
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
            value =
                value.shiftLeft(5).or(BigInteger.valueOf(ULID_ALPHABET.indexOf(character).toLong()))
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
        val BASE_HPKE =
            HPKE(
                HPKE.mode_base,
                HPKE.kem_X25519_SHA256,
                HPKE.kdf_HKDF_SHA256,
                HPKE.aead_CHACHA20_POLY1305,
            )
        val PSK_HPKE =
            HPKE(
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

private class StaticCredentialSource(var credentials: RelayDeviceCredentials) :
    RelayDeviceCredentialSource {
    override suspend fun activeDeviceCredentials() = DeviceCredentialResult.Available(credentials)

    override suspend fun deviceCredentials(deviceIdentityId: String) =
        DeviceCredentialResult.Available(credentials)
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
): RelayApprovalReviewResult =
    RelayEndpointResult.Success(RelayApprovalReview(decision, explanation))

private class TestRelayDeviceConnection(
    events: List<RelayDeviceEvent>,
    private val sendResult: (RelayDeviceFrame) -> RelayFrameSendResult = {
        RelayFrameSendResult.Sent
    },
) : RelayDeviceConnection {
    private val channel =
        Channel<RelayDeviceEvent>(Channel.UNLIMITED).apply {
            events.forEach { trySend(it).getOrThrow() }
            close()
        }

    override val events: ReceiveChannel<RelayDeviceEvent> = channel

    val attemptedFrames = mutableListOf<RelayDeviceFrame>()
    val sentFrames = mutableListOf<RelayDeviceFrame>()
    var closed = false
        private set

    override fun send(frame: RelayDeviceFrame): RelayFrameSendResult {
        attemptedFrames += frame
        return sendResult(frame).also { result ->
            if (result == RelayFrameSendResult.Sent) sentFrames += frame
        }
    }

    override suspend fun close() {
        closed = true
    }
}

private class InteractiveRelayDeviceConnection : RelayDeviceConnection {
    private val eventChannel = Channel<RelayDeviceEvent>(Channel.UNLIMITED)
    private val sentFrameChannel = Channel<RelayDeviceFrame>(Channel.UNLIMITED)

    override val events: ReceiveChannel<RelayDeviceEvent> = eventChannel

    val sentFrames = mutableListOf<RelayDeviceFrame>()
    var outstandingDurableOperations = 0
        private set

    var maximumOutstandingDurableOperations = 0
        private set

    var closed = false
        private set

    override fun send(frame: RelayDeviceFrame): RelayFrameSendResult {
        sentFrames += frame
        if (frame.isDurableOperation()) {
            outstandingDurableOperations += 1
            maximumOutstandingDurableOperations =
                maxOf(
                    maximumOutstandingDurableOperations,
                    outstandingDurableOperations,
                )
        }
        sentFrameChannel.trySend(frame).getOrThrow()
        return RelayFrameSendResult.Sent
    }

    suspend fun nextSentFrame(): RelayDeviceFrame = sentFrameChannel.receive()

    fun resolve(frame: RelayDeviceFrame) {
        check(frame.isDurableOperation())
        check(outstandingDurableOperations > 0)
        outstandingDurableOperations -= 1
        emit(
            when (frame) {
                is RelayDeviceFrame.SetClientState ->
                    RelayDeviceEvent.ClientState(
                        clientId = frame.clientId,
                        state = frame.state,
                    )
                is RelayDeviceFrame.Response ->
                    RelayDeviceEvent.Acknowledgement(
                        clientId = frame.clientId,
                        requestId = frame.requestId,
                        kind = RelayMessageKind.RESPONSE,
                    )
                is RelayDeviceFrame.Resume ->
                    RelayDeviceEvent.State(
                        clientId = frame.clientId,
                        requestId = frame.requestId,
                        exchange = RelayExchangeState.OPEN,
                        response = RelayMessageState.ABSENT,
                    )
                is RelayDeviceFrame.Acknowledgement -> error("Acknowledgements are not durable")
            }
        )
    }

    fun emit(event: RelayDeviceEvent) {
        eventChannel.trySend(event).getOrThrow()
    }

    override suspend fun close() {
        closed = true
        eventChannel.close()
        sentFrameChannel.close()
    }

    private fun RelayDeviceFrame.isDurableOperation(): Boolean =
        this !is RelayDeviceFrame.Acknowledgement
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
