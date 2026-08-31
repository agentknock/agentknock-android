package dev.agentknock.storage.device

import dev.agentknock.protocol.DeviceProtocol
import dev.agentknock.relay.RelayClaimClient
import dev.agentknock.relay.RelayClaimOutcome
import dev.agentknock.relay.RelayClaimResult
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.NoOpAuditSink
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

internal data class DeviceConfiguration(
    val active: DeviceIdentity?,
    val candidate: DeviceIdentity?,
)

internal data class DeviceIdentity(
    val id: String,
    val address: String,
    val deviceId: String,
    val credentialsAvailable: Boolean,
    val pairingEnabled: Boolean,
    val createdAt: Long,
    val instructions: String,
)

internal data class RelayDeviceCredentials(
    val deviceIdentityId: String,
    val address: String,
    val addressId: String,
    val deviceId: String,
    val devicePublicKey: ByteArray,
    val devicePrivateKey: ByteArray,
    val deviceToken: String,
    val instructions: String = "",
)

internal data class RelayDeviceAuthorization(
    val deviceIdentityId: String,
    val deviceId: String,
    val deviceToken: String,
)

internal sealed interface RelayDeviceAuthorizationResult {
    data class Available(val authorization: RelayDeviceAuthorization) :
        RelayDeviceAuthorizationResult

    data object Missing : RelayDeviceAuthorizationResult

    data object Unavailable : RelayDeviceAuthorizationResult

    data object Corrupted : RelayDeviceAuthorizationResult

    data object UnsupportedEncryption : RelayDeviceAuthorizationResult
}

internal sealed interface RelayDeviceCredentialsResult {
    data class Available(val credentials: RelayDeviceCredentials) : RelayDeviceCredentialsResult

    data object Missing : RelayDeviceCredentialsResult

    data object CredentialsUnavailable : RelayDeviceCredentialsResult

    data object CredentialsCorrupted : RelayDeviceCredentialsResult

    data object UnsupportedEncryption : RelayDeviceCredentialsResult
}

internal sealed interface ClaimPairingAddressResult {
    data object Claimed : ClaimPairingAddressResult

    data object AddressUnavailable : ClaimPairingAddressResult

    data object SameAddress : ClaimPairingAddressResult

    data object NoCandidate : ClaimPairingAddressResult

    data object CredentialsCorrupted : ClaimPairingAddressResult

    data object UnsupportedEncryption : ClaimPairingAddressResult

    data class RelayRejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : ClaimPairingAddressResult

    data class RelayUnavailable(val message: String?) : ClaimPairingAddressResult

    data object InvalidRelayResponse : ClaimPairingAddressResult
}

internal interface RelayDeviceCredentialSource {
    suspend fun activeDeviceCredentials(): RelayDeviceCredentialsResult

    suspend fun deviceCredentials(deviceIdentityId: String): RelayDeviceCredentialsResult
}

internal fun interface RelayDeviceAuthorizationSource {
    suspend fun activeDeviceAuthorization(): RelayDeviceAuthorizationResult
}

internal class DeviceIdentityRepository(
    private val dao: DeviceIdentityDao,
    private val keyManager: VaultKeyManager,
    private val encryption: AesGcmEncryption,
    private val relay: RelayClaimClient,
    private val audit: AuditSink = NoOpAuditSink,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelayDeviceCredentialSource, RelayDeviceAuthorizationSource {
    fun observeConfiguration(): Flow<DeviceConfiguration> = combine(
        dao.observeIdentities(),
        dao.observeCredentials(),
    ) { identities, credentials ->
        val availability = credentials
            .map { it.encryptedValue.keyId }
            .distinct()
            .associateWith { keyId -> keyManager.keyAvailable(keyId) }
        val configuredIdentities = identities
            .filter { identity ->
                identity.role == DeviceIdentityRole.ACTIVE.storedName ||
                    identity.role == DeviceIdentityRole.CANDIDATE.storedName
            }
            .groupBy(DeviceIdentityEntity::role)
        configuredIdentities.forEach { (role, matches) ->
            check(matches.size == 1) { "Multiple $role device identities exist" }
        }
        val identityModels = configuredIdentities.mapValues { (_, matches) ->
            val identity = matches.single()
            val identityCredentials = credentials.filter { it.identityId == identity.id }
            identity.toModel(
                credentialsAvailable = DeviceCredentialKind.entries.all { kind ->
                    identityCredentials.singleOrNull { it.kind == kind.storedName }
                        ?.let { availability.getValue(it.encryptedValue.keyId) } == true
                },
            )
        }
        DeviceConfiguration(
            active = identityModels[DeviceIdentityRole.ACTIVE.storedName],
            candidate = identityModels[DeviceIdentityRole.CANDIDATE.storedName],
        )
    }

    suspend fun stageAndClaim(address: String): ClaimPairingAddressResult {
        require(DeviceProtocol.validPairingAddress(address)) { "Invalid pairing address" }
        val active = dao.getIdentity(DeviceIdentityRole.ACTIVE.storedName)
        if (active?.address == address) {
            dao.deleteIdentity(DeviceIdentityRole.CANDIDATE.storedName)
            return ClaimPairingAddressResult.SameAddress
        }
        val candidate = dao.getIdentity(DeviceIdentityRole.CANDIDATE.storedName)
        if (candidate != null) {
            if (candidate.address != address) {
                check(
                    dao.updateCandidateAddress(
                        candidateId = candidate.id,
                        address = address,
                        candidateRole = DeviceIdentityRole.CANDIDATE.storedName,
                    ) == 1,
                )
            }
            return claimCandidate()
        }

        val device = if (active == null) {
            newDeviceMaterial()
        } else {
            when (val result = deviceMaterial(active)) {
                is DeviceMaterialResult.Available -> result.value
                DeviceMaterialResult.Unavailable -> newDeviceMaterial()
                DeviceMaterialResult.Corrupted -> return ClaimPairingAddressResult.CredentialsCorrupted
                DeviceMaterialResult.Unsupported -> {
                    return ClaimPairingAddressResult.UnsupportedEncryption
                }
            }
        }
        stageCandidate(
            address = address,
            device = device,
            settings = active,
        )
        return claimCandidate()
    }

    private suspend fun stageCandidate(
        address: String,
        device: DeviceMaterial,
        settings: DeviceIdentityEntity?,
    ): DeviceIdentityEntity {
        val encryptionKey = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val now = currentTimeMillis()
        val identity = DeviceIdentityEntity(
            id = newId(),
            role = DeviceIdentityRole.CANDIDATE.storedName,
            address = address,
            deviceId = device.deviceId,
            createdAt = now,
            claimAttemptedAt = null,
            pairingEnabled = settings?.pairingEnabled ?: true,
            instructions = settings?.instructions.orEmpty(),
        )
        val credentials = listOf(
            newCredential(
                identity = identity,
                kind = DeviceCredentialKind.DEVICE_PRIVATE_KEY,
                value = device.keyPair.privateKey,
                encryptionKeyId = encryptionKey.id,
            ),
            newCredential(
                identity = identity,
                kind = DeviceCredentialKind.DEVICE_TOKEN,
                value = device.deviceToken,
                encryptionKeyId = encryptionKey.id,
            ),
        )
        dao.replaceCandidate(
            identity = identity,
            credentials = credentials,
            candidateRole = DeviceIdentityRole.CANDIDATE.storedName,
        )
        return identity
    }

    suspend fun claimCandidate(): ClaimPairingAddressResult {
        var candidate = dao.getIdentity(DeviceIdentityRole.CANDIDATE.storedName)
            ?: return ClaimPairingAddressResult.NoCandidate
        val previous = dao.getIdentity(DeviceIdentityRole.ACTIVE.storedName)
        val now = currentTimeMillis()
        if (
            previous == null &&
            candidate.claimAttemptedAt == null &&
            candidate.createdAt < now - DEVICE_ID_REFRESH_AGE_MILLIS
        ) {
            candidate = stageCandidate(
                address = candidate.address,
                device = newDeviceMaterial(),
                settings = candidate,
            )
        }
        val material = when (val result = deviceMaterial(candidate)) {
            is DeviceMaterialResult.Available -> result.value
            DeviceMaterialResult.Corrupted -> {
                return ClaimPairingAddressResult.CredentialsCorrupted
            }
            DeviceMaterialResult.Unsupported -> {
                return ClaimPairingAddressResult.UnsupportedEncryption
            }
            DeviceMaterialResult.Unavailable -> {
                val replacement = if (previous == null) {
                    newDeviceMaterial()
                } else {
                    when (val activeMaterial = deviceMaterial(previous)) {
                        is DeviceMaterialResult.Available -> activeMaterial.value
                        DeviceMaterialResult.Unavailable -> newDeviceMaterial()
                        DeviceMaterialResult.Corrupted -> {
                            return ClaimPairingAddressResult.CredentialsCorrupted
                        }
                        DeviceMaterialResult.Unsupported -> {
                            return ClaimPairingAddressResult.UnsupportedEncryption
                        }
                    }
                }
                candidate = stageCandidate(
                    address = candidate.address,
                    device = replacement,
                    settings = candidate,
                )
                replacement
            }
        }
        if (candidate.claimAttemptedAt == null) {
            val attemptedAt = currentTimeMillis()
            if (
                dao.markCandidateClaimAttempted(
                    candidateId = candidate.id,
                    attemptedAt = attemptedAt,
                    candidateRole = DeviceIdentityRole.CANDIDATE.storedName,
                ) != 1
            ) {
                return ClaimPairingAddressResult.NoCandidate
            }
            candidate = candidate.copy(claimAttemptedAt = attemptedAt)
        }

        return when (
            val result = relay.claim(
                deviceId = candidate.deviceId,
                addressId = DeviceProtocol.addressId(candidate.address),
                deviceToken = DeviceProtocol.encodeDeviceToken(material.deviceToken),
                provideAttestation = previous?.deviceId != candidate.deviceId,
            )
        ) {
            is RelayEndpointResult.Success -> when (result.value) {
                RelayClaimOutcome.CLAIMED -> {
                    if (
                        dao.promoteCandidate(
                            candidateId = candidate.id,
                            now = currentTimeMillis(),
                            activeRole = DeviceIdentityRole.ACTIVE.storedName,
                            candidateRole = DeviceIdentityRole.CANDIDATE.storedName,
                            retiredRole = DeviceIdentityRole.RETIRED.storedName,
                        )
                    ) {
                        audit.record(
                            AuditRecord(
                                type = if (previous == null) {
                                    AuditEventType.PAIRING_ADDRESS_CLAIMED
                                } else {
                                    AuditEventType.PAIRING_ADDRESS_CHANGED
                                },
                                outcome = AuditOutcome.CHANGED,
                                subject = candidate.address,
                            ),
                        )
                        ClaimPairingAddressResult.Claimed
                    } else {
                        ClaimPairingAddressResult.NoCandidate
                    }
                }
                RelayClaimOutcome.ADDRESS_UNAVAILABLE ->
                    ClaimPairingAddressResult.AddressUnavailable
            }
            is RelayEndpointResult.Rejected -> ClaimPairingAddressResult.RelayRejected(
                status = result.status,
                code = result.code,
                message = result.message,
            )
            is RelayEndpointResult.Unavailable -> ClaimPairingAddressResult.RelayUnavailable(
                result.cause.message,
            )
            RelayEndpointResult.InvalidResponse -> ClaimPairingAddressResult.InvalidRelayResponse
        }
    }

    suspend fun discardCandidate() {
        dao.deleteIdentity(DeviceIdentityRole.CANDIDATE.storedName)
    }

    suspend fun saveInstructions(instructions: String): Boolean {
        val normalized = instructions.trim()
        val updated = dao.updateActiveInstructions(
            activeRole = DeviceIdentityRole.ACTIVE.storedName,
            instructions = normalized,
        ) == 1
        if (updated) {
            audit.record(
                AuditRecord(
                    type = AuditEventType.GENERAL_AI_REVIEW_INSTRUCTIONS_CHANGED,
                    outcome = AuditOutcome.CHANGED,
                ),
            )
        }
        return updated
    }

    override suspend fun activeDeviceCredentials(): RelayDeviceCredentialsResult {
        val identity = dao.getIdentity(DeviceIdentityRole.ACTIVE.storedName)
            ?: return RelayDeviceCredentialsResult.Missing
        return deviceCredentials(identity)
    }

    override suspend fun activeDeviceAuthorization(): RelayDeviceAuthorizationResult {
        val identity = dao.getIdentity(DeviceIdentityRole.ACTIVE.storedName)
            ?: return RelayDeviceAuthorizationResult.Missing
        val deviceToken = when (
            val result = decryptCredential(identity, DeviceCredentialKind.DEVICE_TOKEN)
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return RelayDeviceAuthorizationResult.Unavailable
            SecretResult.Corrupted, SecretResult.Missing -> {
                return RelayDeviceAuthorizationResult.Corrupted
            }
            SecretResult.Unsupported -> {
                return RelayDeviceAuthorizationResult.UnsupportedEncryption
            }
        }
        if (deviceToken.size != DEVICE_TOKEN_BYTES) {
            return RelayDeviceAuthorizationResult.Corrupted
        }
        return RelayDeviceAuthorizationResult.Available(
            RelayDeviceAuthorization(
                deviceIdentityId = identity.id,
                deviceId = identity.deviceId,
                deviceToken = DeviceProtocol.encodeDeviceToken(deviceToken),
            ),
        )
    }

    override suspend fun deviceCredentials(
        deviceIdentityId: String,
    ): RelayDeviceCredentialsResult {
        val identity = dao.getIdentityById(deviceIdentityId)
            ?: return RelayDeviceCredentialsResult.Missing
        return deviceCredentials(identity)
    }

    private suspend fun deviceCredentials(
        identity: DeviceIdentityEntity,
    ): RelayDeviceCredentialsResult {
        val credentials = dao.getCredentials(identity.id)
        val privateKey = when (
            val result = decryptCredential(
                identity,
                credentials,
                DeviceCredentialKind.DEVICE_PRIVATE_KEY,
            )
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return RelayDeviceCredentialsResult.CredentialsUnavailable
            SecretResult.Corrupted, SecretResult.Missing -> {
                return RelayDeviceCredentialsResult.CredentialsCorrupted
            }
            SecretResult.Unsupported -> return RelayDeviceCredentialsResult.UnsupportedEncryption
        }
        val deviceToken = when (
            val result = decryptCredential(
                identity,
                credentials,
                DeviceCredentialKind.DEVICE_TOKEN,
            )
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return RelayDeviceCredentialsResult.CredentialsUnavailable
            SecretResult.Corrupted, SecretResult.Missing -> {
                return RelayDeviceCredentialsResult.CredentialsCorrupted
            }
            SecretResult.Unsupported -> return RelayDeviceCredentialsResult.UnsupportedEncryption
        }
        if (
            privateKey.size != DEVICE_PRIVATE_KEY_BYTES ||
            deviceToken.size != DEVICE_TOKEN_BYTES
        ) {
            return RelayDeviceCredentialsResult.CredentialsCorrupted
        }
        return RelayDeviceCredentialsResult.Available(
            RelayDeviceCredentials(
                deviceIdentityId = identity.id,
                address = identity.address,
                addressId = DeviceProtocol.addressId(identity.address),
                deviceId = identity.deviceId,
                devicePublicKey = DeviceProtocol.deriveDevicePublicKey(privateKey),
                devicePrivateKey = privateKey,
                deviceToken = DeviceProtocol.encodeDeviceToken(deviceToken),
                instructions = identity.instructions,
            ),
        )
    }

    private suspend fun newCredential(
        identity: DeviceIdentityEntity,
        kind: DeviceCredentialKind,
        value: ByteArray,
        encryptionKeyId: String,
    ): DeviceCredentialEntity {
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = encryptionKeyId,
                location = location(identity, kind),
                plaintext = value,
            )
        }
        return DeviceCredentialEntity(
            identityId = identity.id,
            kind = kind.storedName,
            encryptedValue = encrypted,
        )
    }

    private fun newDeviceMaterial() = DeviceMaterial(
        deviceId = DeviceProtocol.generateDeviceId(
            timestampMillis = currentTimeMillis(),
        ),
        keyPair = DeviceProtocol.generateDeviceKeyPair(),
        deviceToken = DeviceProtocol.generateDeviceToken(),
    )

    private suspend fun deviceMaterial(identity: DeviceIdentityEntity): DeviceMaterialResult {
        val credentials = dao.getCredentials(identity.id)
        val privateKey = when (
            val result = decryptCredential(
                identity,
                credentials,
                DeviceCredentialKind.DEVICE_PRIVATE_KEY,
            )
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return DeviceMaterialResult.Unavailable
            SecretResult.Unsupported -> return DeviceMaterialResult.Unsupported
            SecretResult.Corrupted, SecretResult.Missing -> {
                return DeviceMaterialResult.Corrupted
            }
        }
        val deviceToken = when (
            val result = decryptCredential(
                identity,
                credentials,
                DeviceCredentialKind.DEVICE_TOKEN,
            )
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return DeviceMaterialResult.Unavailable
            SecretResult.Unsupported -> return DeviceMaterialResult.Unsupported
            SecretResult.Corrupted, SecretResult.Missing -> {
                return DeviceMaterialResult.Corrupted
            }
        }
        if (
            privateKey.size != DEVICE_PRIVATE_KEY_BYTES ||
            deviceToken.size != DEVICE_TOKEN_BYTES
        ) {
            return DeviceMaterialResult.Corrupted
        }
        return DeviceMaterialResult.Available(
            DeviceMaterial(
                deviceId = identity.deviceId,
                keyPair = dev.agentknock.protocol.DeviceKeyPair(
                    privateKey = privateKey,
                    publicKey = DeviceProtocol.deriveDevicePublicKey(privateKey),
                ),
                deviceToken = deviceToken,
            ),
        )
    }

    private suspend fun decryptCredential(
        identity: DeviceIdentityEntity,
        credentials: List<DeviceCredentialEntity>,
        kind: DeviceCredentialKind,
    ): SecretResult = decryptCredential(
        identity = identity,
        credential = credentials.singleOrNull { it.kind == kind.storedName },
        kind = kind,
    )

    private suspend fun decryptCredential(
        identity: DeviceIdentityEntity,
        kind: DeviceCredentialKind,
    ): SecretResult = decryptCredential(
        identity = identity,
        credential = dao.getCredential(identity.id, kind.storedName),
        kind = kind,
    )

    private suspend fun decryptCredential(
        identity: DeviceIdentityEntity,
        credential: DeviceCredentialEntity?,
        kind: DeviceCredentialKind,
    ): SecretResult {
        credential ?: return SecretResult.Missing
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = credential.encryptedValue,
                location = location(identity, kind),
            )
        }
        return when (result) {
            is DecryptionResult.Plaintext -> SecretResult.Available(result.value)
            DecryptionResult.KeyUnavailable -> SecretResult.Unavailable
            DecryptionResult.AuthenticationFailed -> SecretResult.Corrupted
            DecryptionResult.UnsupportedFormat -> SecretResult.Unsupported
        }
    }

    private fun location(
        identity: DeviceIdentityEntity,
        kind: DeviceCredentialKind,
    ) = EncryptionLocation(
        recordType = "device_credential",
        recordId = identity.id,
        fieldName = kind.storedName,
        bindings = listOf(
            EncryptionBinding("device_id", identity.deviceId),
        ),
    )

    private fun DeviceIdentityEntity.toModel(credentialsAvailable: Boolean) = DeviceIdentity(
        id = id,
        address = address,
        deviceId = deviceId,
        credentialsAvailable = credentialsAvailable,
        createdAt = createdAt,
        pairingEnabled = pairingEnabled,
        instructions = instructions,
    )

    private sealed interface SecretResult {
        data class Available(val value: ByteArray) : SecretResult

        data object Unavailable : SecretResult

        data object Corrupted : SecretResult

        data object Unsupported : SecretResult

        data object Missing : SecretResult
    }

    private sealed interface DeviceMaterialResult {
        data class Available(val value: DeviceMaterial) : DeviceMaterialResult

        data object Unavailable : DeviceMaterialResult

        data object Corrupted : DeviceMaterialResult

        data object Unsupported : DeviceMaterialResult
    }

    private companion object {
        const val DEVICE_TOKEN_BYTES = 32
        const val DEVICE_PRIVATE_KEY_BYTES = 32
        const val DEVICE_ID_REFRESH_AGE_MILLIS = 4 * 60 * 1_000L
    }
}

private data class DeviceMaterial(
    val deviceId: String,
    val keyPair: dev.agentknock.protocol.DeviceKeyPair,
    val deviceToken: ByteArray,
)
