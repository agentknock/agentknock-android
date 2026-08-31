package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.EncryptionKeyStore
import dev.agentknock.storage.crypto.GeneratedEncryptionKey
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.device.RelayDeviceCredentialsResult
import dev.agentknock.storage.device.DeviceIdentityEntity
import java.math.BigInteger
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    private lateinit var secrets: SecretRepository
    private lateinit var credentials: RelayDeviceCredentials
    private lateinit var protocolRandom: SwitchableSecureRandom
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
                claimedAt = now,
            ),
        )
        relay = QueuedRelayDeviceClient()
        secrets = SecretRepository(
            dao = database.secretDao(),
            keyManager = keyManager,
            encryption = encryption,
            newId = { "secret-id" },
            currentTimeMillis = { now },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
        protocolRandom = SwitchableSecureRandom()
        repository = RequestRepository(
            dao = database.requestDao(),
            deviceCredentials = StaticCredentialSource(credentials),
            secrets = secrets,
            relay = relay,
            keyManager = keyManager,
            encryption = encryption,
            pairingProtocol = PairingProtocol(random = protocolRandom),
            currentTimeMillis = { now },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun failedCompletionLeavesSlotEmptyAndAcceptedCompletionIgnoresLaterDeliveries() = runTest {
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
        assertEquals("receiving", database.requestDao().getPairingAttempt(root.id)?.state)

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
        assertEquals("waiting", requestAfterFailure.state)
        val pairingAfterFailure = checkNotNull(database.requestDao().getPairingAttempt(root.id))
        assertEquals("receiving", pairingAfterFailure.state)
        assertNotNull(requestAfterFailure.error)
        assertNull(pairingAfterFailure.pendingPsk)

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
        val acceptedRequest = checkNotNull(database.requestDao().getRequestById(root.id))
        val acceptedPairing = checkNotNull(database.requestDao().getPairingAttempt(root.id))
        assertEquals(acceptedCompletion.toString(), acceptedRequest.completionJson)
        assertEquals("sas_verification_pending", acceptedPairing.state)
        val pendingKeyId = checkNotNull(acceptedPairing.pendingPsk).keyId
        assertEquals(
            VaultKeyPurpose.DEVICE_STATE.storedName,
            database.vaultKeyDao().getKey(pendingKeyId)?.purpose,
        )

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
        assertEquals(
            acceptedCompletion.toString(),
            database.requestDao().getRequestById(root.id)?.completionJson,
        )
        assertNotNull(database.requestDao().getRequestById(root.id)?.completionAcknowledgedAt)
    }

    @Test
    fun authenticatedInitialPairingCompletionInfersResponseReceipt() = runTest {
        val clientSecret = ByteArray(32) { it.toByte() }
        connect(
            RelayDeviceEvent.Message(
                clientId = CLIENT_ID,
                requestId = CLIENT_ID,
                kind = RelayMessageKind.REQUEST,
                payload = pairingRequest(clientSecret),
                addressId = ADDRESS_ID,
            ),
            RelayDeviceEvent.Failed("disconnect before response receipt"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect before response receipt"),
            repository.sync(),
        )
        assertNull(database.requestDao().getRequestById(CLIENT_ID)?.responseAcknowledgedAt)

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
        assertEquals(now, database.requestDao().getRequestById(CLIENT_ID)?.responseAcknowledgedAt)
    }

    @Test
    fun authenticatedUnknownCompletionInfersResponseReceipt() = runTest {
        val clientPsk = establishActivePairing()
        val exchange = pairedExchange(
            requestId = UNSUPPORTED_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext = unsupportedPlaintext(),
            completionPlaintext = "{}".encodeToByteArray(),
        )
        assertResponseReceiptInferredFromCompletion(UNSUPPORTED_REQUEST_ID, exchange)
    }

    @Test
    fun authenticatedPairingRemovalCompletionInfersResponseReceipt() = runTest {
        val clientPsk = establishActivePairing()
        val exchange = pairedExchange(
            requestId = REMOVE_REQUEST_ID,
            clientPsk = clientPsk,
            requestPlaintext =
                """{${clientSoftwareFields()},"method":"PairingRemove"}"""
                    .encodeToByteArray(),
            completionPlaintext = """{${clientSoftwareFields()}}""".encodeToByteArray(),
        )
        assertResponseReceiptInferredFromCompletion(REMOVE_REQUEST_ID, exchange)
    }

    @Test
    fun authenticatedFinishCompletionInfersResponseReceipt() = runTest {
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
        assertResponseReceiptInferredFromCompletion(FINISH_REQUEST_ID, exchange)
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

        val replay = connect(
            requestEvent(FINISH_REQUEST_ID, finish),
            RelayDeviceEvent.Acknowledgement(
                CLIENT_ID,
                FINISH_REQUEST_ID,
                RelayMessageKind.RESPONSE,
            ),
            RelayDeviceEvent.CaughtUp,
        )
        assertEquals(RequestSyncResult.Success, repository.sync())
        assertTrue(
            replay.sentFrames.contains(
                RelayDeviceFrame.Message(CLIENT_ID, FINISH_REQUEST_ID, persistedResponse),
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
        assertNotNull(stored.requestAcknowledgedAt)
        assertNull(stored.responseAcknowledgedAt)
        assertEquals("approved", storedInvocation.decision)
        assertEquals("git-signing", storedInvocation.secretsJson.removeSurrounding("[\"", "\"]"))
        assertEquals(
            listOf(
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    INVOCATION_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
                RelayDeviceFrame.Message(CLIENT_ID, INVOCATION_REQUEST_ID, persistedResponse),
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
                RelayDeviceFrame.Message(CLIENT_ID, INVOCATION_REQUEST_ID, persistedResponse),
                RelayDeviceFrame.Resume(CLIENT_ID, INVOCATION_REQUEST_ID),
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    INVOCATION_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
                RelayDeviceFrame.Message(CLIENT_ID, INVOCATION_REQUEST_ID, persistedResponse),
            ),
            replay.sentFrames,
        )
        val afterReplay = checkNotNull(
            database.requestDao().getRequestById(INVOCATION_REQUEST_ID),
        )
        assertEquals(stored.id, afterReplay.id)
        assertEquals(stored.receivedAt, afterReplay.receivedAt)
        assertEquals(stored.responseJson, afterReplay.responseJson)
        assertNotNull(afterReplay.responseAcknowledgedAt)
    }

    @Test
    fun sameClientAndRequestIdWithDifferentPayloadIsNotTreatedAsReplay() = runTest {
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
        assertTrue(collision.sentFrames.isEmpty())
        assertEquals(
            pairingRequest(ByteArray(32) { it.toByte() }).toString(),
            database.requestDao().getRequestById(CLIENT_ID)?.requestJson,
        )
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
                updatedAt = now,
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
        assertNull(denied.responseAcknowledgedAt)
        assertEquals("denied", deniedSigning.decision)
        assertEquals("USER_DENIED", deniedSigning.completionReason)
        // Pairing belongs to Clients rather than request history. The active invocation and its
        // signing child remain because neither workflow has completed.
        assertEquals(0, repository.clearCompletedHistory())
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
                RelayDeviceFrame.Message(CLIENT_ID, GIT_SIGN_REQUEST_ID, persistedResponse),
                RelayDeviceFrame.Resume(CLIENT_ID, INVOCATION_REQUEST_ID),
                RelayDeviceFrame.Resume(CLIENT_ID, GIT_SIGN_REQUEST_ID),
                RelayDeviceFrame.Acknowledgement(
                    CLIENT_ID,
                    GIT_SIGN_REQUEST_ID,
                    RelayMessageKind.REQUEST,
                ),
                RelayDeviceFrame.Message(CLIENT_ID, GIT_SIGN_REQUEST_ID, persistedResponse),
            ),
            replay.sentFrames,
        )
        val afterReplay = checkNotNull(database.requestDao().getRequestById(child.id))
        assertEquals(denied.responseJson, afterReplay.responseJson)
        assertNotNull(afterReplay.responseAcknowledgedAt)
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

    private suspend fun createSigningSecret() {
        val key = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "characterization@test")
        assertTrue(
            secrets.createSshSecret("git-signing", "Signing key", key) is
                CreateSecretResult.Created,
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

    private suspend fun assertResponseReceiptInferredFromCompletion(
        requestId: String,
        exchange: PairedExchange,
    ) {
        connect(
            requestEvent(requestId, exchange.request),
            RelayDeviceEvent.Failed("disconnect before response receipt"),
        )
        assertEquals(
            RequestSyncResult.RelayUnavailable("disconnect before response receipt"),
            repository.sync(),
        )
        assertNull(database.requestDao().getRequestById(requestId)?.responseAcknowledgedAt)

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
        assertEquals(
            now,
            database.requestDao().getRequestById(requestId)?.responseAcknowledgedAt,
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
        """{${clientSoftwareFields()},"method":"Invocation","secrets":{"git-signing":{}},"operation":{"type":"exec","command":"git","arguments":["commit"],"working_directory":"/tmp/project","executable_path":"/usr/bin/git","executable_mode":"direct","stdin":"TERMINAL","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[],"invocation_token":"${BASE64.encodeToString(token)}"}"""
            .encodeToByteArray()

    private fun gitSignPlaintext(token: ByteArray): ByteArray =
        """{${clientSoftwareFields()},"method":"GitSign","invocation_id":"$INVOCATION_REQUEST_ID","invocation_token":"${BASE64.encodeToString(token)}","secret":"git-signing","message":"${BASE64.encodeToString("commit to sign".encodeToByteArray())}","repository":{"remote":"git@example.test:repo.git","worktree":"/tmp/project"}}"""
            .encodeToByteArray()

    private fun unsupportedPlaintext(): ByteArray =
        """{${clientSoftwareFields()},"method":"FutureMethod"}""".encodeToByteArray()

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

private class StaticCredentialSource(
    private val credentials: RelayDeviceCredentials,
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

private class MemoryEncryptionKeyStore : EncryptionKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()

    override fun get(keyId: String): SecretKey? = keys[keyId]

    override fun generate(keyId: String): GeneratedEncryptionKey {
        check(keyId !in keys)
        keys[keyId] = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
        return GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)
    }

    override fun importKey(keyId: String, keyMaterial: ByteArray): GeneratedEncryptionKey {
        check(keyId !in keys)
        require(keyMaterial.size == 16)
        keys[keyId] = SecretKeySpec(keyMaterial.copyOf(), "AES")
        return GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)
    }

    override fun delete(keyId: String) {
        keys.remove(keyId)
    }

    override fun managedKeyIds(): List<String> = keys.keys.toList()
}
