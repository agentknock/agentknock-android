package dev.agentknock.storage.device

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.protocol.DeviceProtocol
import dev.agentknock.relay.RelayClaimClient
import dev.agentknock.relay.RelayClaimOutcome
import dev.agentknock.relay.RelayClaimResult
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.audit.NoOpAuditSink
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.InMemoryEncryptionKeyStore
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.request.ClientEntity
import dev.agentknock.storage.request.InboxRequestEntity
import dev.agentknock.storage.request.RequestPskEntity
import dev.agentknock.storage.request.SshAuthenticationRequestEntity
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DeviceIdentityRepositoryTest {
    private val databases = mutableListOf<AgentknockDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach { it.close() }
    }

    @Test
    fun connectionCredentialsAndAuthorizationDoNotDecryptThePrivateKey() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        fixture.sql(
            "UPDATE device_credentials SET ciphertext = zeroblob(48) WHERE kind = 'device_private_key'"
        )

        val active = fixture.repository.activeDeviceCredentials()
        assertTrue(active is DeviceCredentialResult.Available)
        val credentials = (active as DeviceCredentialResult.Available).value
        assertEquals(fixture.relay.claims.single().deviceToken, credentials.deviceToken)
        assertTrue(
            fixture.repository.deviceCredentials(credentials.deviceIdentityId)
                is DeviceCredentialResult.Available
        )
        assertTrue(
            fixture.repository.activeDeviceAuthorization() is DeviceCredentialResult.Available
        )
        var called = false
        val operation = runCatching { credentials.deviceKey.use { called = true } }
        assertTrue(operation.exceptionOrNull() is DeviceKeyAccessException)
        assertFalse(called)
    }

    @Test
    fun aLoadedKeyHandleChecksTheWrappingKeyAgainOnEveryUse() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        val credentials =
            (fixture.repository.activeDeviceCredentials() as DeviceCredentialResult.Available).value
        credentials.deviceKey.use { assertEquals(32, it.privateKey.size) }
        fixture.dao
            .observeCredentials()
            .first()
            .map { it.encryptedValue.keyId }
            .distinct()
            .forEach(fixture.keyStore::delete)
        val operation = runCatching { credentials.deviceKey.use { error("must not run") } }
        assertTrue(operation.exceptionOrNull() is DeviceKeyAccessException)
    }

    @Test
    fun claimsAndPromotesALocallyEncryptedDeviceIdentity() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("yup-its-free"),
        )

        val active = fixture.dao.observeIdentities().first().single()
        assertEquals(DeviceIdentityRole.ACTIVE.storedName, active.role)
        assertEquals("yup-its-free", active.address)
        assertEquals(26, active.deviceId.length)
        assertTrue(active.claimAttemptedAt != null)
        val credentials = fixture.dao.observeCredentials().first()
        assertEquals(
            setOf("device_token", "device_private_key"),
            credentials.map(DeviceCredentialEntity::kind).toSet(),
        )
        assertTrue(credentials.all { it.identityId == active.id })
        assertTrue(credentials.all { it.encryptedValue.ciphertext.size > 32 })
        assertEquals(
            setOf(VaultKeyPurpose.DEVICE_STATE.storedName),
            credentials
                .map { credential ->
                    fixture.encryptionMetadata.getKey(credential.encryptedValue.keyId)?.purpose
                }
                .toSet(),
        )
        assertEquals(1, fixture.relay.claims.size)
        assertEquals(
            "9e6f33bf47382846903dffa0962ea313",
            fixture.relay.claims.single().addressId,
        )
        assertTrue(fixture.relay.claims.single().deviceToken.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(fixture.relay.addressChanges.isEmpty())
    }

    @Test
    fun retriesAnAmbiguousClaimWithThePersistedIdentityAndToken() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.relay.results += RelayEndpointResult.Unavailable(IOException("offline"))
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.CLAIMED)

        assertTrue(
            fixture.repository.stageAndClaim("amber-river-maple")
                is ClaimPairingAddressResult.RelayUnavailable
        )
        val candidateId = fixture.dao.observeIdentities().first().single().id
        val firstClaim = fixture.relay.claims.single()
        assertTrue(fixture.dao.observeIdentities().first().single().claimAttemptedAt != null)
        fixture.advanceTimeBy(5 * 60 * 1_000L)

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )

        assertEquals(candidateId, fixture.dao.observeIdentities().first().single().id)
        assertEquals(
            DeviceIdentityRole.ACTIVE.storedName,
            fixture.dao.observeIdentities().first().single().role,
        )
        assertEquals(2, fixture.relay.claims.size)
        assertEquals(firstClaim, fixture.relay.claims.last())
    }

    @Test
    fun aDifferentAddressReusesTheExistingCandidateMaterial() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE)

        assertEquals(
            ClaimPairingAddressResult.AddressUnavailable,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val candidate = fixture.dao.observeIdentities().first().single()
        val firstClaim = fixture.relay.claims.single()
        val originalCredentials =
            (fixture.repository.deviceCredentials(candidate.id) as DeviceCredentialResult.Available)
                .value
        val publicKey = originalCredentials.deviceKey.use { it.publicKey }
        fixture.advanceTimeBy(5 * 60 * 1_000L)

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("silent-forest-cloud"),
        )

        val active = fixture.dao.observeIdentities().first().single()
        val secondClaim = fixture.relay.claims.last()
        assertEquals(candidate.id, active.id)
        assertEquals(candidate.createdAt, active.createdAt)
        assertEquals(candidate.claimAttemptedAt, active.claimAttemptedAt)
        assertEquals(candidate.deviceId, active.deviceId)
        val activeCredentials =
            (fixture.repository.activeDeviceCredentials() as DeviceCredentialResult.Available).value
        assertArrayEquals(publicKey, activeCredentials.deviceKey.use { it.publicKey })
        assertEquals(firstClaim.deviceId, secondClaim.deviceId)
        assertEquals(firstClaim.deviceToken, secondClaim.deviceToken)
        assertNotEquals(firstClaim.addressId, secondClaim.addressId)
    }

    @Test
    fun selectingTheActiveAddressDiscardsAnUnfinishedCandidate() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val active = fixture.dao.observeIdentities().first().single()
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE)
        assertEquals(
            ClaimPairingAddressResult.AddressUnavailable,
            fixture.repository.stageAndClaim("silent-forest-cloud"),
        )
        assertEquals(2, fixture.dao.observeIdentities().first().size)

        assertEquals(
            ClaimPairingAddressResult.SameAddress,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )

        assertEquals(listOf(active), fixture.dao.observeIdentities().first())
        assertEquals(2, fixture.dao.observeCredentials().first().size)
    }

    @Test
    fun anUndecryptableRestoredCandidateIsReplacedEvenAfterAClaimAttempt() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE)
        assertEquals(
            ClaimPairingAddressResult.AddressUnavailable,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val originalCandidate = fixture.dao.observeIdentities().first().single()
        val originalClaim = fixture.relay.claims.single()
        assertTrue(originalCandidate.claimAttemptedAt != null)

        val restoredKeyStore = InMemoryEncryptionKeyStore()
        val restoredKeyIds = ArrayDeque(listOf("replacement-secret-key", "replacement-device-key"))
        val restoredKeyManager =
            VaultKeyManager(
                dao = fixture.encryptionMetadata,
                keyStore = restoredKeyStore,
                newKeyId = { restoredKeyIds.removeFirst() },
                currentTimeMillis = { 10_000L },
            )
        restoredKeyManager.initialize()
        val restored =
            DeviceIdentityRepository(
                dao = fixture.dao,
                keyManager = restoredKeyManager,
                encryption = AesGcmEncryption(restoredKeyStore),
                relay = fixture.relay,
                audit = NoOpAuditSink,
                writeTransaction = RoomWriteTransaction(fixture.database),
                newId = { "restored-candidate" },
                currentTimeMillis = { 10_001L },
                cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            restored.stageAndClaim("silent-forest-cloud"),
        )

        val replacement = fixture.dao.observeIdentities().first().single()
        val replacementClaim = fixture.relay.claims.last()
        assertEquals("restored-candidate", replacement.id)
        assertNotEquals(originalCandidate.id, replacement.id)
        assertNotEquals(originalClaim.deviceId, replacementClaim.deviceId)
        assertNotEquals(originalClaim.deviceToken, replacementClaim.deviceToken)
        assertEquals("silent-forest-cloud", replacement.address)
    }

    @Test
    fun refreshesAnUnattemptedInitialDeviceIdAfterItBecomesStale() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.relay.results += RelayEndpointResult.Unavailable(IOException("offline"))

        assertTrue(
            fixture.repository.stageAndClaim("amber-river-maple")
                is ClaimPairingAddressResult.RelayUnavailable
        )
        val originalCandidate = fixture.dao.observeIdentities().first().single()
        val originalClaim = fixture.relay.claims.single()
        fixture.sql("UPDATE device_identities SET claim_attempted_at = NULL")
        fixture.advanceTimeBy(5 * 60 * 1_000L)

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.claimCandidate(),
        )

        val refreshed = fixture.dao.observeIdentities().first().single()
        val refreshedClaim = fixture.relay.claims.last()
        assertEquals(DeviceIdentityRole.ACTIVE.storedName, refreshed.role)
        assertNotEquals(originalCandidate.id, refreshed.id)
        assertNotEquals(originalClaim.deviceId, refreshedClaim.deviceId)
        assertNotEquals(originalClaim.deviceToken, refreshedClaim.deviceToken)
        assertEquals(originalClaim.addressId, refreshedClaim.addressId)
    }

    @Test
    fun anUnavailableAddressChangeLeavesTheActiveDeviceIdentityIntact() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val activeId = fixture.dao.observeIdentities().first().single().id
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE)

        assertEquals(
            ClaimPairingAddressResult.AddressUnavailable,
            fixture.repository.stageAndClaim("silent-forest-cloud"),
        )

        val active =
            fixture.dao.observeIdentities().first().single {
                it.role == DeviceIdentityRole.ACTIVE.storedName
            }
        val candidate =
            fixture.dao.observeIdentities().first().single {
                it.role == DeviceIdentityRole.CANDIDATE.storedName
            }
        assertEquals(activeId, active.id)
        assertEquals("amber-river-maple", active.address)
        assertEquals("silent-forest-cloud", candidate.address)
        assertNotEquals(
            dev.agentknock.protocol.DeviceProtocol.addressId(active.address),
            dev.agentknock.protocol.DeviceProtocol.addressId(candidate.address),
        )
        assertEquals(active.deviceId, candidate.deviceId)
        assertEquals(1, fixture.relay.claims.size)
        assertEquals(1, fixture.relay.addressChanges.size)
    }

    @Test
    fun changingAddressPreservesTheDeviceIdentityKeysAndPairingsAnchor() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val before =
            (fixture.repository.activeDeviceCredentials()
                    as DeviceCredentialResult.Available<RelayDeviceCredentials>)
                .value

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("silent-forest-cloud"),
        )
        val after =
            (fixture.repository.activeDeviceCredentials()
                    as DeviceCredentialResult.Available<RelayDeviceCredentials>)
                .value

        assertEquals(before.deviceIdentityId, after.deviceIdentityId)
        assertEquals(before.deviceId, after.deviceId)
        assertEquals("silent-forest-cloud", after.address)
        assertNotEquals(before.addressId, after.addressId)
        assertArrayEquals(
            before.deviceKey.use { it.privateKey.copyOf() },
            after.deviceKey.use { it.privateKey.copyOf() },
        )
        assertArrayEquals(
            before.deviceKey.use { it.publicKey },
            after.deviceKey.use { it.publicKey },
        )
        assertEquals(before.deviceToken, after.deviceToken)
        assertEquals(1, fixture.dao.observeIdentities().first().size)
        assertEquals(1, fixture.relay.claims.size)
        assertEquals(1, fixture.relay.addressChanges.size)
        assertEquals(before.deviceId, fixture.relay.addressChanges.single().deviceId)
        assertEquals(
            DeviceProtocol.addressId("silent-forest-cloud"),
            fixture.relay.addressChanges.single().addressId,
        )
        assertEquals(before.deviceToken, fixture.relay.addressChanges.single().deviceToken)
    }

    @Test
    fun relayAuthorizationDoesNotDependOnTheDevicePrivateKey() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        fixture.sql("DELETE FROM device_credentials WHERE kind = 'device_private_key'")

        val authorization = fixture.repository.activeDeviceAuthorization()

        assertTrue(authorization is DeviceCredentialResult.Available)
        assertEquals(
            DeviceCredentialResult.Corrupted,
            fixture.repository.activeDeviceCredentials(),
        )
    }

    @Test
    fun aRestoredBackupKeepsDeviceMetadataAndReportsUnavailableCredentials() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")

        val replacementKeys = InMemoryEncryptionKeyStore()
        val replacementIds = ArrayDeque(listOf("replacement-secret-key", "replacement-device-key"))
        val replacementManager =
            VaultKeyManager(
                dao = fixture.encryptionMetadata,
                keyStore = replacementKeys,
                newKeyId = { replacementIds.removeFirst() },
                currentTimeMillis = { 999L },
            )
        replacementManager.initialize()
        val restored =
            DeviceIdentityRepository(
                dao = fixture.dao,
                keyManager = replacementManager,
                encryption = AesGcmEncryption(replacementKeys),
                relay = fixture.relay,
                audit = NoOpAuditSink,
                writeTransaction = RoomWriteTransaction(fixture.database),
                cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        val configuration = restored.observeConfiguration().first()

        assertEquals("amber-river-maple", configuration.active?.address)
        assertFalse(configuration.active?.credentialsAvailable ?: true)
        assertEquals(2, fixture.dao.observeCredentials().first().size)
    }

    @Test
    fun aRestoredBackupCanClaimAReplacementDeviceIdentity() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        val original = fixture.dao.observeIdentities().first().single()
        fixture.seedPendingRequest(original.id)

        val replacementKeys = InMemoryEncryptionKeyStore()
        val replacementIds = ArrayDeque(listOf("replacement-secret-key", "replacement-device-key"))
        val replacementManager =
            VaultKeyManager(
                dao = fixture.encryptionMetadata,
                keyStore = replacementKeys,
                newKeyId = { replacementIds.removeFirst() },
                currentTimeMillis = { 999L },
            )
        replacementManager.initialize()
        val restored =
            DeviceIdentityRepository(
                dao = fixture.dao,
                keyManager = replacementManager,
                encryption = AesGcmEncryption(replacementKeys),
                relay = fixture.relay,
                audit = NoOpAuditSink,
                writeTransaction = RoomWriteTransaction(fixture.database),
                currentTimeMillis = { 1_000L },
                cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            restored.stageAndClaim("silent-forest-cloud"),
        )

        val replacement =
            fixture.dao.observeIdentities().first().single {
                it.role == DeviceIdentityRole.ACTIVE.storedName
            }
        val retired =
            fixture.dao.observeIdentities().first().single {
                it.role == DeviceIdentityRole.RETIRED.storedName
            }
        assertEquals(DeviceIdentityRole.ACTIVE.storedName, replacement.role)
        assertEquals("silent-forest-cloud", replacement.address)
        assertNotEquals(original.id, replacement.id)
        assertNotEquals(original.deviceId, replacement.deviceId)
        assertEquals(
            original.copy(
                role = DeviceIdentityRole.RETIRED.storedName,
                address = "",
                deviceId = "",
                claimAttemptedAt = null,
                pairingEnabled = false,
                instructions = "",
            ),
            retired,
        )
        assertEquals(replacement.deviceId, fixture.relay.claims.last().deviceId)
        assertEquals(
            setOf("device_token", "device_private_key"),
            fixture.dao.observeCredentials().first().map { it.kind }.toSet(),
        )
        assertEquals(
            0,
            fixture.dao.observeCredentials().first().count { it.identityId == original.id },
        )
        assertEquals(
            2,
            fixture.dao.observeCredentials().first().count { it.identityId == replacement.id },
        )
        val requests = fixture.database.requestDao()
        assertEquals(null, requests.getRequestPsk("pending"))
        assertEquals(null, requests.getSshAuthenticationRequest("pending")?.message)
        assertEquals(null, requests.getClientById("client"))
        assertTrue(requests.getRequestById("pending")?.exchangeEndedAt != null)
    }

    @Test
    fun aReplacementMailboxStartsWithPairingEnabled() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        val original =
            fixture.dao
                .observeIdentities()
                .first()
                .single()
                .copy(
                    pairingEnabled = false,
                    instructions = "Only approve work requests.",
                )
        fixture.dao.updatePairingEnabled(original.id, false, "active")
        fixture.dao.updateActiveInstructions("active", original.instructions)

        val replacementKeys = InMemoryEncryptionKeyStore()
        val replacementIds = ArrayDeque(listOf("replacement-secret-key", "replacement-device-key"))
        val replacementManager =
            VaultKeyManager(
                dao = fixture.encryptionMetadata,
                keyStore = replacementKeys,
                newKeyId = { replacementIds.removeFirst() },
                currentTimeMillis = { 999L },
            )
        replacementManager.initialize()
        val restored =
            DeviceIdentityRepository(
                dao = fixture.dao,
                keyManager = replacementManager,
                encryption = AesGcmEncryption(replacementKeys),
                relay = fixture.relay,
                audit = NoOpAuditSink,
                writeTransaction = RoomWriteTransaction(fixture.database),
                currentTimeMillis = { 1_000L },
                cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            restored.stageAndClaim("silent-forest-cloud"),
        )

        val replacement =
            fixture.dao.observeIdentities().first().single {
                it.role == DeviceIdentityRole.ACTIVE.storedName
            }
        assertNotEquals(original.deviceId, replacement.deviceId)
        assertTrue(replacement.pairingEnabled)
        assertEquals(original.instructions, replacement.instructions)
    }

    private inner class Fixture(dispatcher: CoroutineDispatcher) {
        val database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AgentknockDatabase::class.java,
                )
                .build()
                .also { databases += it }
        val encryptionMetadata = database.vaultKeyDao()
        val keyStore = InMemoryEncryptionKeyStore()
        val dao = database.deviceIdentityDao()

        suspend fun sql(statement: String) {
            database.useWriterConnection { it.executeSQL(statement) }
        }

        suspend fun seedPendingRequest(identityId: String) {
            val requests = database.requestDao()
            requests.insertClient(
                ClientEntity(
                    clientId = "client",
                    deviceIdentityId = identityId,
                    name = "Original client",
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
            )
            requests.insertRequest(
                InboxRequestEntity(
                    id = "pending",
                    parentRequestId = null,
                    deviceIdentityId = identityId,
                    clientId = "client",
                    clientNameSnapshot = "Original client",
                    clientSoftwareJson = null,
                    kind = "ssh_authenticate",
                    state = "action_required",
                    listed = true,
                    requestJson = "{}",
                    responseJson = null,
                    error = null,
                    receivedAt = 1,
                    completedAt = null,
                    exchangeEndedAt = null,
                    responseOutboxFinished = false,
                )
            )
            requests.insertRequestPsk(
                RequestPskEntity("pending", dao.getCredentials(identityId).first().encryptedValue)
            )
            requests.insertSshAuthenticationRequestRow(
                SshAuthenticationRequestEntity(
                    requestId = "pending",
                    secretName = "ssh",
                    message = byteArrayOf(1, 2, 3),
                    username = "git",
                    method = "publickey",
                    algorithm = "ssh-ed25519",
                    hostKeyAlgorithm = null,
                    hostKeyFingerprint = null,
                    approvalEvaluationJson = null,
                    decision = null,
                    completionResult = null,
                    completionReason = null,
                    completionMessage = null,
                    decidedAt = null,
                )
            )
        }

        val relay = FakeRelayClaimClient()
        private var id = 0
        private var time = 100L
        private val keyIds = ArrayDeque(listOf("secret-storage-key", "device-storage-key"))
        private val keyManager =
            VaultKeyManager(
                dao = encryptionMetadata,
                keyStore = keyStore,
                newKeyId = { keyIds.removeFirst() },
                currentTimeMillis = { ++time },
            )
        val repository =
            DeviceIdentityRepository(
                dao = dao,
                keyManager = keyManager,
                encryption = AesGcmEncryption(keyStore),
                relay = relay,
                audit = NoOpAuditSink,
                writeTransaction = RoomWriteTransaction(database),
                newId = { "id-${++id}" },
                currentTimeMillis = { ++time },
                cryptographyDispatcher = dispatcher,
            )

        fun advanceTimeBy(milliseconds: Long) {
            time += milliseconds
        }
    }
}

private data class RecordedClaim(
    val deviceId: String,
    val addressId: String,
    val deviceToken: String,
)

private class FakeRelayClaimClient : RelayClaimClient {
    val claims = mutableListOf<RecordedClaim>()
    val addressChanges = mutableListOf<RecordedClaim>()
    val results = ArrayDeque<RelayClaimResult>()

    override suspend fun claimAndSetAddress(
        deviceId: String,
        addressId: String,
        deviceToken: String,
    ): RelayClaimResult {
        claims += RecordedClaim(deviceId, addressId, deviceToken)
        return nextResult()
    }

    override suspend fun setAddress(
        deviceId: String,
        addressId: String,
        deviceToken: String,
    ): RelayClaimResult {
        addressChanges += RecordedClaim(deviceId, addressId, deviceToken)
        return nextResult()
    }

    private fun nextResult(): RelayClaimResult {
        return if (results.isEmpty()) {
            RelayEndpointResult.Success(RelayClaimOutcome.CLAIMED)
        } else {
            results.removeFirst()
        }
    }
}
