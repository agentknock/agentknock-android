package dev.agentknock.storage.device

import dev.agentknock.protocol.DeviceProtocol
import dev.agentknock.relay.RelayClaimClient
import dev.agentknock.relay.RelayClaimOutcome
import dev.agentknock.relay.RelayClaimResult
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.storage.ImmediateWriteTransaction
import dev.agentknock.storage.audit.NoOpAuditSink
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.FakeEncryptionKeyStore
import dev.agentknock.storage.crypto.FakeVaultKeyDao
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceIdentityRepositoryTest {
    @Test
    fun `connection credentials and authorization do not decrypt the private key`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        fixture.dao.credentials.value =
            fixture.dao.credentials.value.map { credential ->
                if (credential.kind == DeviceCredentialKind.DEVICE_PRIVATE_KEY.storedName) {
                    credential.copy(
                        encryptedValue = credential.encryptedValue.copy(ciphertext = ByteArray(48))
                    )
                } else credential
            }

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
    fun `a loaded key handle checks the wrapping key again on every use`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        val credentials =
            (fixture.repository.activeDeviceCredentials() as DeviceCredentialResult.Available).value
        credentials.deviceKey.use { assertEquals(32, it.privateKey.size) }
        fixture.keyStore.generatedKeyIds.forEach(fixture.keyStore::delete)
        val operation = runCatching { credentials.deviceKey.use { error("must not run") } }
        assertTrue(operation.exceptionOrNull() is DeviceKeyAccessException)
    }

    @Test
    fun `claims and promotes a locally encrypted device identity`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("yup-its-free"),
        )

        val active = fixture.dao.identities.value.single()
        assertEquals(DeviceIdentityRole.ACTIVE.storedName, active.role)
        assertEquals("yup-its-free", active.address)
        assertEquals(26, active.deviceId.length)
        assertTrue(active.claimAttemptedAt != null)
        val credentials = fixture.dao.credentials.value
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
    fun `retries an ambiguous claim with the persisted identity and token`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.relay.results += RelayEndpointResult.Unavailable(IOException("offline"))
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.CLAIMED)

        assertTrue(
            fixture.repository.stageAndClaim("amber-river-maple")
                is ClaimPairingAddressResult.RelayUnavailable
        )
        val candidateId = fixture.dao.identities.value.single().id
        val firstClaim = fixture.relay.claims.single()
        assertTrue(fixture.dao.identities.value.single().claimAttemptedAt != null)
        fixture.advanceTimeBy(5 * 60 * 1_000L)

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )

        assertEquals(candidateId, fixture.dao.identities.value.single().id)
        assertEquals(
            DeviceIdentityRole.ACTIVE.storedName,
            fixture.dao.identities.value.single().role,
        )
        assertEquals(2, fixture.relay.claims.size)
        assertEquals(firstClaim, fixture.relay.claims.last())
    }

    @Test
    fun `a different address reuses the existing candidate material`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE)

        assertEquals(
            ClaimPairingAddressResult.AddressUnavailable,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val candidate = fixture.dao.identities.value.single()
        val firstClaim = fixture.relay.claims.single()
        val encryptedCredentials = fixture.dao.credentials.value.map { it.encryptedValue }
        fixture.advanceTimeBy(5 * 60 * 1_000L)

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("silent-forest-cloud"),
        )

        val active = fixture.dao.identities.value.single()
        val secondClaim = fixture.relay.claims.last()
        assertEquals(candidate.id, active.id)
        assertEquals(candidate.createdAt, active.createdAt)
        assertEquals(candidate.claimAttemptedAt, active.claimAttemptedAt)
        assertEquals(candidate.deviceId, active.deviceId)
        assertEquals(encryptedCredentials, fixture.dao.credentials.value.map { it.encryptedValue })
        assertEquals(firstClaim.deviceId, secondClaim.deviceId)
        assertEquals(firstClaim.deviceToken, secondClaim.deviceToken)
        assertNotEquals(firstClaim.addressId, secondClaim.addressId)
    }

    @Test
    fun `selecting the active address discards an unfinished candidate`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val active = fixture.dao.identities.value.single()
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE)
        assertEquals(
            ClaimPairingAddressResult.AddressUnavailable,
            fixture.repository.stageAndClaim("silent-forest-cloud"),
        )
        assertEquals(2, fixture.dao.identities.value.size)

        assertEquals(
            ClaimPairingAddressResult.SameAddress,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )

        assertEquals(listOf(active), fixture.dao.identities.value)
        assertEquals(2, fixture.dao.credentials.value.size)
    }

    @Test
    fun `an undecryptable restored candidate is replaced even after a claim attempt`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE)
        assertEquals(
            ClaimPairingAddressResult.AddressUnavailable,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val originalCandidate = fixture.dao.identities.value.single()
        val originalClaim = fixture.relay.claims.single()
        assertTrue(originalCandidate.claimAttemptedAt != null)

        val restoredKeyStore = FakeEncryptionKeyStore()
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
                writeTransaction = ImmediateWriteTransaction,
                newId = { "restored-candidate" },
                currentTimeMillis = { 10_001L },
                cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            restored.stageAndClaim("silent-forest-cloud"),
        )

        val replacement = fixture.dao.identities.value.single()
        val replacementClaim = fixture.relay.claims.last()
        assertEquals("restored-candidate", replacement.id)
        assertNotEquals(originalCandidate.id, replacement.id)
        assertNotEquals(originalClaim.deviceId, replacementClaim.deviceId)
        assertNotEquals(originalClaim.deviceToken, replacementClaim.deviceToken)
        assertEquals("silent-forest-cloud", replacement.address)
    }

    @Test
    fun `refreshes an unattempted initial device id after it becomes stale`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.relay.results += RelayEndpointResult.Unavailable(IOException("offline"))

        assertTrue(
            fixture.repository.stageAndClaim("amber-river-maple")
                is ClaimPairingAddressResult.RelayUnavailable
        )
        val originalCandidate = fixture.dao.identities.value.single()
        val originalClaim = fixture.relay.claims.single()
        fixture.dao.identities.value = listOf(originalCandidate.copy(claimAttemptedAt = null))
        fixture.advanceTimeBy(5 * 60 * 1_000L)

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.claimCandidate(),
        )

        val refreshed = fixture.dao.identities.value.single()
        val refreshedClaim = fixture.relay.claims.last()
        assertEquals(DeviceIdentityRole.ACTIVE.storedName, refreshed.role)
        assertNotEquals(originalCandidate.id, refreshed.id)
        assertNotEquals(originalClaim.deviceId, refreshedClaim.deviceId)
        assertNotEquals(originalClaim.deviceToken, refreshedClaim.deviceToken)
        assertEquals(originalClaim.addressId, refreshedClaim.addressId)
    }

    @Test
    fun `an unavailable address change leaves the active device identity intact`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val activeId = fixture.dao.identities.value.single().id
        fixture.relay.results += RelayEndpointResult.Success(RelayClaimOutcome.ADDRESS_UNAVAILABLE)

        assertEquals(
            ClaimPairingAddressResult.AddressUnavailable,
            fixture.repository.stageAndClaim("silent-forest-cloud"),
        )

        val active =
            fixture.dao.identities.value.single {
                it.role == DeviceIdentityRole.ACTIVE.storedName
            }
        val candidate =
            fixture.dao.identities.value.single {
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
    fun `changing address preserves the device identity keys and pairings anchor`() = runTest {
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
        assertEquals(1, fixture.dao.identities.value.size)
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
    fun `relay authorization does not depend on the device private key`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        fixture.dao.credentials.value =
            fixture.dao.credentials.value.filterNot {
                it.kind == DeviceCredentialKind.DEVICE_PRIVATE_KEY.storedName
            }

        val authorization = fixture.repository.activeDeviceAuthorization()

        assertTrue(authorization is DeviceCredentialResult.Available)
        assertEquals(
            DeviceCredentialResult.Corrupted,
            fixture.repository.activeDeviceCredentials(),
        )
    }

    @Test
    fun `a restored backup keeps device metadata and reports unavailable credentials`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")

        val replacementKeys = FakeEncryptionKeyStore()
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
                writeTransaction = ImmediateWriteTransaction,
                cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        val configuration = restored.observeConfiguration().first()

        assertEquals("amber-river-maple", configuration.active?.address)
        assertFalse(configuration.active?.credentialsAvailable ?: true)
        assertEquals(2, fixture.dao.credentials.value.size)
    }

    @Test
    fun `a restored backup can claim a replacement device identity`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        val original = fixture.dao.identities.value.single()

        val replacementKeys = FakeEncryptionKeyStore()
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
                writeTransaction = ImmediateWriteTransaction,
                currentTimeMillis = { 1_000L },
                cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            restored.stageAndClaim("silent-forest-cloud"),
        )

        val replacement =
            fixture.dao.identities.value.single {
                it.role == DeviceIdentityRole.ACTIVE.storedName
            }
        val retired =
            fixture.dao.identities.value.single {
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
            fixture.dao.credentials.value.map { it.kind }.toSet(),
        )
        assertEquals(0, fixture.dao.credentials.value.count { it.identityId == original.id })
        assertEquals(2, fixture.dao.credentials.value.count { it.identityId == replacement.id })
        assertEquals(listOf(original.id), fixture.dao.requestPskDeletionIdentityIds)
        assertEquals(listOf(original.id), fixture.dao.sshMessageDiscardIdentityIds)
        assertEquals(listOf(original.id), fixture.dao.clientDeletionIdentityIds)
    }

    @Test
    fun `a replacement mailbox starts with pairing enabled`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        val original =
            fixture.dao.identities.value
                .single()
                .copy(
                    pairingEnabled = false,
                    instructions = "Only approve work requests.",
                )
        fixture.dao.identities.value = listOf(original)

        val replacementKeys = FakeEncryptionKeyStore()
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
                writeTransaction = ImmediateWriteTransaction,
                currentTimeMillis = { 1_000L },
                cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            restored.stageAndClaim("silent-forest-cloud"),
        )

        val replacement =
            fixture.dao.identities.value.single {
                it.role == DeviceIdentityRole.ACTIVE.storedName
            }
        assertNotEquals(original.deviceId, replacement.deviceId)
        assertTrue(replacement.pairingEnabled)
        assertEquals(original.instructions, replacement.instructions)
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val encryptionMetadata = FakeVaultKeyDao()
        val keyStore = FakeEncryptionKeyStore()
        val dao = FakeDeviceIdentityDao()
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
                writeTransaction = ImmediateWriteTransaction,
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

private class FakeDeviceIdentityDao : DeviceIdentityDao {
    val identities = MutableStateFlow<List<DeviceIdentityEntity>>(emptyList())
    val credentials = MutableStateFlow<List<DeviceCredentialEntity>>(emptyList())
    val requestPskDeletionIdentityIds = mutableListOf<String>()
    val sshMessageDiscardIdentityIds = mutableListOf<String>()
    val clientDeletionIdentityIds = mutableListOf<String>()

    override fun observeIdentities(): Flow<List<DeviceIdentityEntity>> = identities

    override fun observeCredentials(): Flow<List<DeviceCredentialEntity>> = credentials

    override suspend fun getIdentityRows(role: String): List<DeviceIdentityEntity> =
        identities.value.filter { it.role == role }.sortedBy { it.id }

    override suspend fun getIdentityById(id: String): DeviceIdentityEntity? =
        identities.value.singleOrNull { it.id == id }

    override suspend fun getCredentials(identityId: String): List<DeviceCredentialEntity> =
        credentials.value.filter { it.identityId == identityId }

    override suspend fun getCredential(
        identityId: String,
        kind: String,
    ): DeviceCredentialEntity? =
        credentials.value.singleOrNull {
            it.identityId == identityId && it.kind == kind
        }

    override suspend fun deleteIdentity(role: String): Int {
        val removed = identities.value.filter { it.role == role }
        identities.value = identities.value.filterNot { it.role == role }
        credentials.value =
            credentials.value.filterNot { credential ->
                removed.any { identity -> identity.id == credential.identityId }
            }
        return removed.size
    }

    override suspend fun identityExists(id: String, role: String): Boolean =
        identities.value.any { it.id == id && it.role == role }

    override suspend fun insertIdentity(identity: DeviceIdentityEntity) {
        check(identities.value.none { it.id == identity.id })
        check(
            identity.role == DeviceIdentityRole.RETIRED.storedName ||
                identities.value.none { it.role == identity.role }
        )
        identities.value += identity
    }

    override suspend fun insertCredentials(credentials: List<DeviceCredentialEntity>) {
        check(
            credentials.none { inserted ->
                this.credentials.value.any {
                    it.identityId == inserted.identityId && it.kind == inserted.kind
                }
            }
        )
        this.credentials.value += credentials
    }

    override suspend fun markCandidateActive(
        candidateId: String,
        activeRole: String,
        candidateRole: String,
    ): Int {
        if (identities.value.none { it.id == candidateId && it.role == candidateRole }) return 0
        identities.value =
            identities.value.map { identity ->
                if (identity.id == candidateId && identity.role == candidateRole) {
                    identity.copy(role = activeRole)
                } else {
                    identity
                }
            }
        return 1
    }

    override suspend fun retireActiveIdentity(
        activeId: String,
        activeRole: String,
        retiredRole: String,
    ): Int {
        if (identities.value.none { it.id == activeId && it.role == activeRole }) return 0
        identities.value =
            identities.value.map { identity ->
                if (identity.id == activeId && identity.role == activeRole) {
                    identity.copy(
                        role = retiredRole,
                        address = "",
                        deviceId = "",
                        claimAttemptedAt = null,
                        pairingEnabled = false,
                        instructions = "",
                    )
                } else {
                    identity
                }
            }
        return 1
    }

    override suspend fun abandonRequests(identityId: String, now: Long, error: String): Int = 0

    override suspend fun abandonPairingAttempts(identityId: String, now: Long): Int = 0

    override suspend fun deleteOrphanedRetiredIdentities(retiredRole: String): Int = 0

    override suspend fun discardSshAuthenticationMessages(identityId: String): Int {
        sshMessageDiscardIdentityIds += identityId
        return 0
    }

    override suspend fun deleteUploadEnvironmentValues(identityId: String): Int = 0

    override suspend fun deleteUploadSshKeys(identityId: String): Int = 0

    override suspend fun rejectPendingUploads(identityId: String, now: Long): Int = 0

    override suspend fun deleteCredentials(identityId: String): Int {
        val previousSize = credentials.value.size
        credentials.value = credentials.value.filterNot { it.identityId == identityId }
        return previousSize - credentials.value.size
    }

    override suspend fun deleteRequestPsks(identityId: String): Int {
        requestPskDeletionIdentityIds += identityId
        return 0
    }

    override suspend fun deleteClients(identityId: String): Int {
        clientDeletionIdentityIds += identityId
        return 0
    }

    override suspend fun updateActiveAddress(
        activeId: String,
        address: String,
        activeRole: String,
    ): Int {
        if (identities.value.none { it.id == activeId && it.role == activeRole }) return 0
        identities.value =
            identities.value.map { identity ->
                if (identity.id == activeId && identity.role == activeRole) {
                    identity.copy(address = address)
                } else {
                    identity
                }
            }
        return 1
    }

    override suspend fun updateCandidateAddress(
        candidateId: String,
        address: String,
        candidateRole: String,
    ): Int {
        if (identities.value.none { it.id == candidateId && it.role == candidateRole }) return 0
        identities.value =
            identities.value.map { identity ->
                if (identity.id == candidateId && identity.role == candidateRole) {
                    identity.copy(address = address)
                } else {
                    identity
                }
            }
        return 1
    }

    override suspend fun prepareReplacementCandidate(
        candidateId: String,
        instructions: String,
        candidateRole: String,
    ): Int {
        if (identities.value.none { it.id == candidateId && it.role == candidateRole }) return 0
        identities.value =
            identities.value.map { identity ->
                if (identity.id == candidateId && identity.role == candidateRole) {
                    identity.copy(
                        pairingEnabled = true,
                        instructions = instructions,
                    )
                } else {
                    identity
                }
            }
        return 1
    }

    override suspend fun updateActiveInstructions(activeRole: String, instructions: String): Int {
        if (identities.value.none { it.role == activeRole }) return 0
        identities.value =
            identities.value.map { identity ->
                if (identity.role == activeRole) {
                    identity.copy(instructions = instructions)
                } else {
                    identity
                }
            }
        return 1
    }

    override suspend fun updatePairingEnabled(
        identityId: String,
        enabled: Boolean,
        activeRole: String,
    ): Int {
        if (identities.value.none { it.id == identityId && it.role == activeRole }) return 0
        identities.value =
            identities.value.map { identity ->
                if (identity.id == identityId && identity.role == activeRole) {
                    identity.copy(pairingEnabled = enabled)
                } else {
                    identity
                }
            }
        return 1
    }

    override suspend fun markCandidateClaimAttempted(
        candidateId: String,
        attemptedAt: Long,
        candidateRole: String,
    ): Int {
        if (
            identities.value.none {
                it.id == candidateId && it.role == candidateRole && it.claimAttemptedAt == null
            }
        ) {
            return 0
        }
        identities.value =
            identities.value.map { identity ->
                if (identity.id == candidateId && identity.role == candidateRole) {
                    identity.copy(claimAttemptedAt = attemptedAt)
                } else {
                    identity
                }
            }
        return 1
    }
}
