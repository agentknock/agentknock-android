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
import dev.agentknock.storage.vault.RelayDeviceCredentialSource
import dev.agentknock.storage.vault.RelayDeviceCredentials
import dev.agentknock.storage.vault.RelayDeviceCredentialsResult
import dev.agentknock.storage.vault.DeviceIdentityEntity
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
        database.vaultDao().insertIdentity(
            DeviceIdentityEntity(
                id = DEVICE_IDENTITY_ID,
                role = "active",
                address = ADDRESS,
                addressId = ADDRESS_ID,
                deviceId = DEVICE_ID,
                devicePublicKey = devicePublicKey,
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
        repository = RequestRepository(
            dao = database.requestDao(),
            deviceCredentials = StaticCredentialSource(credentials),
            secrets = secrets,
            relay = relay,
            keyManager = keyManager,
            encryption = encryption,
            pairingProtocol = PairingProtocol(random = SecureRandom()),
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
        val root = checkNotNull(database.requestDao().getRequestByRelayId(CLIENT_ID))
        assertNull(root.completionJson)
        assertEquals("receiving", database.requestDao().getPairing(root.id)?.state)

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
        assertEquals("receiving", requestAfterFailure.state)
        val pairingAfterFailure = checkNotNull(database.requestDao().getPairing(root.id))
        assertEquals("receiving", pairingAfterFailure.state)
        assertNotNull(pairingAfterFailure.error)
        assertNull(database.requestDao().getPairingSecret(root.id, "current_client_psk"))

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
        val acceptedPairing = checkNotNull(database.requestDao().getPairing(root.id))
        assertEquals(acceptedCompletion.toString(), acceptedRequest.completionJson)
        assertEquals("sas_verification_pending", acceptedPairing.state)
        val pairingSecret = checkNotNull(
            database.requestDao().getPairingSecret(root.id, "current_client_psk"),
        )
        assertEquals(
            VaultKeyPurpose.DEVICE_STATE.storedName,
            database.vaultKeyDao().getKey(pairingSecret.encryptionKeyId)?.purpose,
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
        assertEquals(acceptedPairing.updatedAt, database.requestDao().getPairing(root.id)?.updatedAt)
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
            database.requestDao().getRequestByRelayId(INVOCATION_REQUEST_ID),
        )
        val storedInvocation = checkNotNull(database.requestDao().getSecretUseRequest(stored.id))
        val persistedResponse = Json.parseToJsonElement(checkNotNull(stored.responseJson))
        assertEquals(invocation.toString(), stored.requestJson)
        assertEquals("waiting", stored.state)
        assertNotNull(stored.requestAcknowledgedAt)
        assertNull(stored.responseAcknowledgedAt)
        assertEquals("waiting_for_completion", storedInvocation.state)
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
            database.requestDao().getRequestByRelayId(INVOCATION_REQUEST_ID),
        )
        assertEquals(stored.id, afterReplay.id)
        assertEquals(stored.receivedAt, afterReplay.receivedAt)
        assertEquals(stored.responseJson, afterReplay.responseJson)
        assertNotNull(afterReplay.responseAcknowledgedAt)
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
            database.requestDao().getRequestByRelayId(INVOCATION_REQUEST_ID),
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

        val child = checkNotNull(database.requestDao().getRequestByRelayId(GIT_SIGN_REQUEST_ID))
        val gitSign = checkNotNull(database.requestDao().getGitSignRequest(child.id))
        assertEquals(parent.id, child.parentRequestId)
        assertEquals(signing.toString(), child.requestJson)
        assertNull(child.responseJson)
        assertEquals("action_required", child.state)
        assertEquals("approval_pending", gitSign.state)
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
        assertEquals("waiting_for_completion", deniedSigning.state)
        assertEquals("denied", deniedSigning.decision)
        assertEquals("USER_DENIED", deniedSigning.completionReason)
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
        val root = checkNotNull(database.requestDao().getRequestByRelayId(CLIENT_ID))
        val pairing = checkNotNull(database.requestDao().getPairing(root.id))
        assertEquals(
            PairingDecisionResult.VERIFIED,
            repository.chooseSas(root.id, checkNotNull(pairing.correctSasIndex)),
        )

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
        val finish = pairedRequest(
            requestId = FINISH_REQUEST_ID,
            clientPsk = material.clientPsk,
            plaintext = """{${clientSoftwareFields()},"method":"PairingFinish"}"""
                .encodeToByteArray(),
        )
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
        assertEquals("active", database.requestDao().getPairing(root.id)?.state)
        return material.clientPsk
    }

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
    }

    override val events: ReceiveChannel<RelayDeviceEvent> = channel

    val sentFrames = mutableListOf<RelayDeviceFrame>()

    override fun send(frame: RelayDeviceFrame): Boolean {
        sentFrames += frame
        return true
    }

    override suspend fun close() = Unit
}

private class MemoryEncryptionKeyStore : EncryptionKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()

    override fun contains(keyId: String): Boolean = keyId in keys

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
}
