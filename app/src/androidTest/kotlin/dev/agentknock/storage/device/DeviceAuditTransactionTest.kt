package dev.agentknock.storage.device

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.relay.RelayClaimClient
import dev.agentknock.relay.RelayClaimOutcome
import dev.agentknock.relay.RelayClaimResult
import dev.agentknock.relay.RelayDeviceManagementClient
import dev.agentknock.relay.RelayDeviceManagementResult
import dev.agentknock.relay.RelayEndpointResult
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
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DeviceAuditTransactionTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var audit: AuditRepository
    private lateinit var keys: TestEncryptionKeyStore
    private lateinit var keyManager: VaultKeyManager
    private lateinit var transaction: RoomWriteTransaction
    private var nextKeyId = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        audit = AuditRepository(database.auditDao(), currentTimeMillis = { 1_000L })
        keys = TestEncryptionKeyStore()
        keyManager = VaultKeyManager(
            dao = database.vaultKeyDao(),
            keyStore = keys,
            newKeyId = { "test-key-${++nextKeyId}" },
            currentTimeMillis = { 100L + nextKeyId },
        )
        transaction = RoomWriteTransaction(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun claimingAndAuditingADeviceIdentityRollBackTogether() = runTest {
        val relay = SuccessfulClaimClient()
        val failingRepository = identityRepository(
            relay = relay,
            auditSink = InsertThenFailAuditSink(audit),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val failed = runCatching {
            failingRepository.stageAndClaim("amber-river-maple")
        }

        assertTrue(failed.isFailure)
        assertNull(database.deviceIdentityDao().getIdentity(DeviceIdentityRole.ACTIVE.storedName))
        assertNotNull(
            database.deviceIdentityDao().getIdentity(DeviceIdentityRole.CANDIDATE.storedName),
        )
        assertTrue(audit.observeEvents().first().isEmpty())

        val repository = identityRepository(
            relay = relay,
            auditSink = audit,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(ClaimPairingAddressResult.Claimed, repository.claimCandidate())
        assertNotNull(database.deviceIdentityDao().getIdentity(DeviceIdentityRole.ACTIVE.storedName))
        assertNull(database.deviceIdentityDao().getIdentity(DeviceIdentityRole.CANDIDATE.storedName))
        assertEquals(
            listOf(AuditEventType.PAIRING_ADDRESS_CLAIMED),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun deviceInstructionsAndTheirAuditRollBackTogether() = runTest {
        database.deviceIdentityDao().insertIdentity(activeIdentity(instructions = "Original"))
        val repository = identityRepository(
            relay = SuccessfulClaimClient(),
            auditSink = InsertThenFailAuditSink(audit),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val failed = runCatching { repository.saveInstructions("Changed") }

        assertTrue(failed.isFailure)
        assertEquals(
            "Original",
            database.deviceIdentityDao()
                .getIdentity(DeviceIdentityRole.ACTIVE.storedName)
                ?.instructions,
        )
        assertTrue(audit.observeEvents().first().isEmpty())
    }

    @Test
    fun pairingAdmissionAndItsAuditRollBackTogether() = runTest {
        database.deviceIdentityDao().insertIdentity(activeIdentity(pairingEnabled = true))
        val authorization = RelayDeviceAuthorization(
            deviceIdentityId = IDENTITY_ID,
            deviceId = DEVICE_ID,
            deviceToken = "device-token",
        )
        val relay = SuccessfulManagementClient()
        val repository = DeviceManagementRepository(
            deviceIdentityDao = database.deviceIdentityDao(),
            deviceAuthorization = RelayDeviceAuthorizationSource {
                RelayDeviceAuthorizationResult.Available(authorization)
            },
            relay = relay,
            audit = InsertThenFailAuditSink(audit),
            writeTransaction = transaction,
        )

        val failed = runCatching { repository.setPairingEnabled(false) }

        assertTrue(failed.isFailure)
        assertEquals(listOf(false), relay.pairingChanges)
        assertTrue(
            checkNotNull(
                database.deviceIdentityDao()
                    .getIdentity(DeviceIdentityRole.ACTIVE.storedName),
            ).pairingEnabled,
        )
        assertTrue(audit.observeEvents().first().isEmpty())

        val successfulRepository = DeviceManagementRepository(
            deviceIdentityDao = database.deviceIdentityDao(),
            deviceAuthorization = RelayDeviceAuthorizationSource {
                RelayDeviceAuthorizationResult.Available(authorization)
            },
            relay = relay,
            audit = audit,
            writeTransaction = transaction,
        )
        assertEquals(
            DeviceManagementResult.Changed,
            successfulRepository.setPairingEnabled(false),
        )
        assertFalse(
            checkNotNull(
                database.deviceIdentityDao()
                    .getIdentity(DeviceIdentityRole.ACTIVE.storedName),
            ).pairingEnabled,
        )
        assertEquals(
            listOf(AuditEventType.NEW_PAIRINGS_PAUSED),
            audit.observeEvents().first().map { it.type },
        )
    }

    private fun identityRepository(
        relay: RelayClaimClient,
        auditSink: AuditSink,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = DeviceIdentityRepository(
        dao = database.deviceIdentityDao(),
        keyManager = keyManager,
        encryption = AesGcmEncryption(keys),
        relay = relay,
        audit = auditSink,
        writeTransaction = transaction,
        newId = { "identity" },
        currentTimeMillis = { 200L },
        cryptographyDispatcher = dispatcher,
    )

    private fun activeIdentity(
        pairingEnabled: Boolean = true,
        instructions: String = "",
    ) = DeviceIdentityEntity(
        id = IDENTITY_ID,
        role = DeviceIdentityRole.ACTIVE.storedName,
        address = "amber-river-maple",
        deviceId = DEVICE_ID,
        createdAt = 1L,
        pairingEnabled = pairingEnabled,
        instructions = instructions,
    )

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

    private class SuccessfulClaimClient : RelayClaimClient {
        override suspend fun claim(
            deviceId: String,
            addressId: String,
            deviceToken: String,
            provideAttestation: Boolean,
        ): RelayClaimResult = RelayEndpointResult.Success(RelayClaimOutcome.CLAIMED)
    }

    private class SuccessfulManagementClient : RelayDeviceManagementClient {
        val pairingChanges = mutableListOf<Boolean>()

        override suspend fun setPairingEnabled(
            deviceId: String,
            deviceToken: String,
            enabled: Boolean,
        ): RelayDeviceManagementResult {
            pairingChanges += enabled
            return RelayEndpointResult.Success(Unit)
        }

        override suspend fun deleteDevice(
            deviceId: String,
            deviceToken: String,
        ): RelayDeviceManagementResult = RelayEndpointResult.Success(Unit)
    }

    private class TestEncryptionKeyStore : EncryptionKeyStore {
        private val keys = mutableMapOf<String, SecretKey>()

        override fun get(keyId: String): SecretKey? = keys[keyId]

        override fun generate(keyId: String): GeneratedEncryptionKey {
            check(keyId !in keys)
            keys[keyId] = KeyGenerator.getInstance("AES").run {
                init(128)
                generateKey()
            }
            return GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)
        }

        override fun delete(keyId: String) {
            keys.remove(keyId)
        }
    }

    private companion object {
        const val IDENTITY_ID = "identity"
        const val DEVICE_ID = "01JDEVICE000000000000000000"
    }
}
