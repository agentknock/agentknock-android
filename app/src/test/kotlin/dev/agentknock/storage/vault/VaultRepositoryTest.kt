package dev.agentknock.storage.vault

import dev.agentknock.relay.RelayClaimClient
import dev.agentknock.relay.RelayClaimResult
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.FakeEncryptionKeyStore
import dev.agentknock.storage.crypto.FakeLocalEncryptionDao
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultRepositoryTest {
    @Test
    fun `claims and promotes a locally encrypted device identity`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("yup-its-free"),
        )

        val active = fixture.dao.identities.value.single()
        assertEquals("active", active.role)
        assertEquals("yup-its-free", active.address)
        assertEquals("9e6f33bf47382846903dffa0962ea313", active.addressId)
        assertEquals(26, active.deviceId.length)
        assertEquals(32, active.devicePublicKey.size)
        assertTrue(active.claimedAt != null)
        val secrets = fixture.dao.secrets.value
        assertEquals(
            setOf("device_token", "device_private_key"),
            secrets.map(VaultSecretEntity::kind).toSet(),
        )
        assertTrue(secrets.all { it.identityId == active.id })
        assertTrue(secrets.all { it.ciphertext.size > 32 })
        assertEquals(1, fixture.relay.claims.size)
        assertTrue(fixture.relay.claims.single().deviceToken.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test
    fun `retries an ambiguous claim with the persisted identity and token`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.relay.results += RelayClaimResult.Unavailable(IOException("offline"))
        fixture.relay.results += RelayClaimResult.Claimed

        assertTrue(
            fixture.repository.stageAndClaim("amber-river-maple") is
                ClaimPairingAddressResult.RelayUnavailable,
        )
        val candidateId = fixture.dao.identities.value.single().id
        val firstClaim = fixture.relay.claims.single()

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )

        assertEquals(candidateId, fixture.dao.identities.value.single().id)
        assertEquals("active", fixture.dao.identities.value.single().role)
        assertEquals(2, fixture.relay.claims.size)
        assertEquals(firstClaim, fixture.relay.claims.last())
    }

    @Test
    fun `an unavailable replacement leaves the active device identity intact`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val activeId = fixture.dao.identities.value.single().id
        fixture.relay.results += RelayClaimResult.AddressUnavailable

        assertEquals(
            ClaimPairingAddressResult.AddressUnavailable,
            fixture.repository.stageAndClaim("silent-forest-cloud"),
        )

        val active = fixture.dao.identities.value.single { it.role == "active" }
        val candidate = fixture.dao.identities.value.single { it.role == "candidate" }
        assertEquals(activeId, active.id)
        assertEquals("amber-river-maple", active.address)
        assertEquals("silent-forest-cloud", candidate.address)
        assertNull(candidate.claimedAt)
        assertNotEquals(active.addressId, candidate.addressId)
        assertEquals(active.deviceId, candidate.deviceId)
    }

    @Test
    fun `changing address preserves the device identity keys and pairings anchor`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("amber-river-maple"),
        )
        val before = (
            fixture.repository.activeDeviceCredentials() as
                RelayDeviceCredentialsResult.Available
            ).credentials

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            fixture.repository.stageAndClaim("silent-forest-cloud"),
        )
        val after = (
            fixture.repository.activeDeviceCredentials() as
                RelayDeviceCredentialsResult.Available
            ).credentials

        assertEquals(before.deviceIdentityId, after.deviceIdentityId)
        assertEquals(before.deviceId, after.deviceId)
        assertEquals("silent-forest-cloud", after.address)
        assertNotEquals(before.addressId, after.addressId)
        assertArrayEquals(before.devicePrivateKey, after.devicePrivateKey)
        assertArrayEquals(before.devicePublicKey, after.devicePublicKey)
        assertEquals(before.deviceToken, after.deviceToken)
        assertEquals(1, fixture.dao.identities.value.size)
        assertEquals(fixture.relay.claims[0].deviceId, fixture.relay.claims[1].deviceId)
    }

    @Test
    fun `a restored vault keeps device metadata and reports unavailable secrets`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")

        val replacementKeys = FakeEncryptionKeyStore()
        val replacementManager = LocalEncryptionKeyManager(
            dao = fixture.encryptionMetadata,
            keyStore = replacementKeys,
            newKeyId = { "replacement-key" },
            currentTimeMillis = { 999L },
        )
        replacementManager.initialize()
        val restored = VaultRepository(
            dao = fixture.dao,
            keyManager = replacementManager,
            encryption = AesGcmEncryption(replacementKeys),
            relay = fixture.relay,
            cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val configuration = restored.observeConfiguration().first()

        assertEquals("amber-river-maple", configuration.active?.address)
        assertFalse(configuration.active?.credentialsAvailable ?: true)
        assertEquals(2, fixture.dao.secrets.value.size)
    }

    @Test
    fun `a restored vault can claim a replacement device identity`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.repository.stageAndClaim("amber-river-maple")
        val original = fixture.dao.identities.value.single()

        val replacementKeys = FakeEncryptionKeyStore()
        val replacementManager = LocalEncryptionKeyManager(
            dao = fixture.encryptionMetadata,
            keyStore = replacementKeys,
            newKeyId = { "replacement-key" },
            currentTimeMillis = { 999L },
        )
        replacementManager.initialize()
        val restored = VaultRepository(
            dao = fixture.dao,
            keyManager = replacementManager,
            encryption = AesGcmEncryption(replacementKeys),
            relay = fixture.relay,
            currentTimeMillis = { 1_000L },
            cryptographyDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertEquals(
            ClaimPairingAddressResult.Claimed,
            restored.stageAndClaim("silent-forest-cloud"),
        )

        val replacement = fixture.dao.identities.value.single()
        assertEquals("active", replacement.role)
        assertEquals("silent-forest-cloud", replacement.address)
        assertNotEquals(original.id, replacement.id)
        assertNotEquals(original.deviceId, replacement.deviceId)
        assertEquals(
            setOf("device_token", "device_private_key"),
            fixture.dao.secrets.value.map { it.kind }.toSet(),
        )
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val encryptionMetadata = FakeLocalEncryptionDao()
        private val keyStore = FakeEncryptionKeyStore()
        val dao = FakeVaultDao()
        val relay = FakeRelayClaimClient()
        private var id = 0
        private var time = 100L
        private val keyManager = LocalEncryptionKeyManager(
            dao = encryptionMetadata,
            keyStore = keyStore,
            newKeyId = { "storage-key" },
            currentTimeMillis = { ++time },
        )
        val repository = VaultRepository(
            dao = dao,
            keyManager = keyManager,
            encryption = AesGcmEncryption(keyStore),
            relay = relay,
            newId = { "id-${++id}" },
            currentTimeMillis = { ++time },
            cryptographyDispatcher = dispatcher,
        )
    }
}

private data class RecordedClaim(
    val deviceId: String,
    val addressId: String,
    val deviceToken: String,
)

private class FakeRelayClaimClient : RelayClaimClient {
    val claims = mutableListOf<RecordedClaim>()
    val results = ArrayDeque<RelayClaimResult>()

    override suspend fun claim(
        deviceId: String,
        addressId: String,
        deviceToken: String,
    ): RelayClaimResult {
        claims += RecordedClaim(deviceId, addressId, deviceToken)
        return if (results.isEmpty()) RelayClaimResult.Claimed else results.removeFirst()
    }
}

private class FakeVaultDao : VaultDao {
    val identities = MutableStateFlow<List<DeviceIdentityEntity>>(emptyList())
    val secrets = MutableStateFlow<List<VaultSecretEntity>>(emptyList())

    override fun observeIdentities(): Flow<List<DeviceIdentityEntity>> = identities

    override fun observeSecrets(): Flow<List<VaultSecretEntity>> = secrets

    override suspend fun getIdentity(role: String): DeviceIdentityEntity? =
        identities.value.singleOrNull { it.role == role }

    override suspend fun getIdentityById(id: String): DeviceIdentityEntity? =
        identities.value.singleOrNull { it.id == id }

    override suspend fun getSecrets(identityId: String): List<VaultSecretEntity> =
        secrets.value.filter { it.identityId == identityId }

    override suspend fun deleteIdentity(role: String): Int {
        val removed = identities.value.filter { it.role == role }
        identities.value = identities.value.filterNot { it.role == role }
        secrets.value = secrets.value.filterNot { secret ->
            removed.any { identity -> identity.id == secret.identityId }
        }
        return removed.size
    }

    override suspend fun identityExists(id: String, role: String): Boolean =
        identities.value.any { it.id == id && it.role == role }

    override suspend fun insertIdentity(identity: DeviceIdentityEntity) {
        check(identities.value.none { it.id == identity.id || it.role == identity.role })
        identities.value += identity
    }

    override suspend fun insertSecrets(secrets: List<VaultSecretEntity>) {
        check(secrets.none { inserted -> this.secrets.value.any { it.id == inserted.id } })
        this.secrets.value += secrets
    }

    override suspend fun markCandidateActive(
        candidateId: String,
        claimedAt: Long,
        activeRole: String,
        candidateRole: String,
    ): Int {
        if (identities.value.none { it.id == candidateId && it.role == candidateRole }) return 0
        identities.value = identities.value.map { identity ->
            if (identity.id == candidateId && identity.role == candidateRole) {
                identity.copy(role = activeRole, claimedAt = claimedAt)
            } else {
                identity
            }
        }
        return 1
    }

    override suspend fun updateActiveAddress(
        activeId: String,
        address: String,
        addressId: String,
        claimedAt: Long,
        activeRole: String,
    ): Int {
        if (identities.value.none { it.id == activeId && it.role == activeRole }) return 0
        identities.value = identities.value.map { identity ->
            if (identity.id == activeId && identity.role == activeRole) {
                identity.copy(address = address, addressId = addressId, claimedAt = claimedAt)
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
        identities.value = identities.value.map { identity ->
            if (identity.id == identityId && identity.role == activeRole) {
                identity.copy(pairingEnabled = enabled)
            } else {
                identity
            }
        }
        return 1
    }
}
