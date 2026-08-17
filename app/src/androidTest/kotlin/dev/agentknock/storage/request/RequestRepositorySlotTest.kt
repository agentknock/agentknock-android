package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.relay.RelayDeviceClient
import dev.agentknock.relay.RelayDeviceConnection
import dev.agentknock.relay.RelayDeviceConnectionResult
import dev.agentknock.relay.RelayDeviceEvent
import dev.agentknock.relay.RelayDeviceFrame
import dev.agentknock.relay.RelayExchangeState
import dev.agentknock.relay.RelayMessageKind
import dev.agentknock.relay.RelayMessageState
import dev.agentknock.storage.AgentKnockDatabase
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.EncryptionKeyStore
import dev.agentknock.storage.crypto.GeneratedEncryptionKey
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import dev.agentknock.storage.profile.ProfileRepository
import dev.agentknock.storage.vault.RelayDeviceCredentialSource
import dev.agentknock.storage.vault.RelayDeviceCredentials
import dev.agentknock.storage.vault.RelayDeviceCredentialsResult
import dev.agentknock.storage.vault.VaultIdentityEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestRepositorySlotTest {
    private lateinit var database: AgentKnockDatabase
    private lateinit var relay: QueuedRelayDeviceClient
    private lateinit var repository: RequestRepository
    private lateinit var credentials: RelayDeviceCredentials
    private var now = CLIENT_ID.timestamp()

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentKnockDatabase::class.java,
        ).build()
        val keyStore = MemoryEncryptionKeyStore()
        val keyManager = LocalEncryptionKeyManager(
            dao = database.localEncryptionDao(),
            keyStore = keyStore,
            newKeyId = { "local-key" },
            currentTimeMillis = { now },
            keyStoreDispatcher = Dispatchers.Unconfined,
        )
        val encryption = AesGcmEncryption(keyStore)
        val devicePrivateKey = ByteArray(32) { 0x42 }
        val devicePublicKey = X25519PrivateKeyParameters(devicePrivateKey, 0)
            .generatePublicKey().encoded
        credentials = RelayDeviceCredentials(
            vaultIdentityId = VAULT_ID,
            address = ADDRESS,
            addressId = ADDRESS_ID,
            deviceId = DEVICE_ID,
            devicePublicKey = devicePublicKey,
            devicePrivateKey = devicePrivateKey,
            deviceToken = "token",
        )
        database.vaultDao().insertIdentity(
            VaultIdentityEntity(
                id = VAULT_ID,
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
        repository = RequestRepository(
            dao = database.requestDao(),
            deviceCredentials = StaticCredentialSource(credentials),
            profiles = ProfileRepository(
                dao = database.profileDao(),
                keyManager = keyManager,
                encryption = encryption,
                cryptographyDispatcher = Dispatchers.Unconfined,
            ),
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
        assertNotNull(database.requestDao().getPairingSecret(root.id, "current_client_psk"))

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
        val sender = BASE_HPKE.setupBaseS(
            BASE_HPKE.deserializePublicKey(credentials.devicePublicKey),
            VERSION_INFO + DEVICE_ID.ulidBytes() + CLIENT_ID.ulidBytes(),
        )
        val secretCiphertext = sender.seal(EMPTY, clientSecret)
        val applicationCiphertext = sender.seal(
            EMPTY,
            """{"cli_version":"test","platform":"linux","architecture":"x86_64","hostname":"test"}"""
                .encodeToByteArray(),
        )
        val encapsulation =
            (sender as org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation).encapsulation
        return Json.parseToJsonElement(
            """{"key":"${BASE64.encodeToString(encapsulation)}","secret":"${BASE64.encodeToString(secretCiphertext)}","ciphertext":"${BASE64.encodeToString(applicationCiphertext)}"}""",
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
        const val VAULT_ID = "vault"
        const val ADDRESS = "write-leader-hungry"
        const val ADDRESS_ID = "0123456789abcdef0123456789abcdef"
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
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
    }
}

private class StaticCredentialSource(
    private val credentials: RelayDeviceCredentials,
) : RelayDeviceCredentialSource {
    override suspend fun activeDeviceCredentials() =
        RelayDeviceCredentialsResult.Available(credentials)

    override suspend fun deviceCredentials(vaultIdentityId: String) =
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

    override fun delete(keyId: String) {
        keys.remove(keyId)
    }
}
