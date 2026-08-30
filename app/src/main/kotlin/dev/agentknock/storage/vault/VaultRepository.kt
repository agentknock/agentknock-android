package dev.agentknock.storage.vault

import dev.agentknock.protocol.DeviceProtocol
import dev.agentknock.relay.RelayClaimClient
import dev.agentknock.relay.RelayClaimResult
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
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
    val addressId: String,
    val deviceId: String,
    val credentialsAvailable: Boolean,
    val pairingEnabled: Boolean,
    val createdAt: Long,
    val claimedAt: Long?,
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

    data object CredentialsUnavailable : ClaimPairingAddressResult

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

internal class VaultRepository(
    private val dao: VaultDao,
    private val keyManager: VaultKeyManager,
    private val encryption: AesGcmEncryption,
    private val relay: RelayClaimClient,
    private val audit: AuditSink = NoOpAuditSink,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelayDeviceCredentialSource {
    fun observeConfiguration(): Flow<DeviceConfiguration> = combine(
        dao.observeIdentities(),
        dao.observeSecrets(),
    ) { identities, secrets ->
        val availability = secrets
            .map(VaultSecretEntity::encryptionKeyId)
            .distinct()
            .associateWith { keyId -> keyManager.keyAvailable(keyId) }
        val identityModels = identities.associate { identity ->
            val identitySecrets = secrets.filter { it.identityId == identity.id }
            identity.role to identity.toModel(
                credentialsAvailable = VaultSecretKind.entries.all { kind ->
                    identitySecrets.singleOrNull { it.kind == kind.storedName }
                        ?.let { availability.getValue(it.encryptionKeyId) } == true
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
            return ClaimPairingAddressResult.SameAddress
        }
        if (dao.getIdentity(DeviceIdentityRole.CANDIDATE.storedName)?.address == address) {
            return claimCandidate()
        }

        val addressId = DeviceProtocol.addressId(address)
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
            addressId = addressId,
            device = device,
            settings = active,
        )
        return claimCandidate()
    }

    private suspend fun stageCandidate(
        address: String,
        addressId: String,
        device: DeviceMaterial,
        settings: DeviceIdentityEntity?,
    ): DeviceIdentityEntity {
        val encryptionKey = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val now = currentTimeMillis()
        val identity = DeviceIdentityEntity(
            id = newId(),
            role = DeviceIdentityRole.CANDIDATE.storedName,
            address = address,
            addressId = addressId,
            deviceId = device.deviceId,
            devicePublicKey = device.keyPair.publicKey,
            createdAt = now,
            claimedAt = null,
            claimAttemptedAt = null,
            pairingEnabled = settings?.pairingEnabled ?: true,
            instructions = settings?.instructions.orEmpty(),
        )
        val secrets = listOf(
            newSecret(
                identity = identity,
                kind = VaultSecretKind.DEVICE_PRIVATE_KEY,
                value = device.keyPair.privateKey,
                encryptionKeyId = encryptionKey.id,
                now = now,
            ),
            newSecret(
                identity = identity,
                kind = VaultSecretKind.DEVICE_TOKEN,
                value = device.deviceToken,
                encryptionKeyId = encryptionKey.id,
                now = now,
            ),
        )
        dao.replaceCandidate(
            identity = identity,
            secrets = secrets,
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
                addressId = candidate.addressId,
                device = newDeviceMaterial(),
                settings = candidate,
            )
        }
        val secrets = dao.getSecrets(candidate.id)
        when (val result = decryptSecret(candidate, secrets, VaultSecretKind.DEVICE_PRIVATE_KEY)) {
            is SecretResult.Available -> if (result.value.size != DEVICE_PRIVATE_KEY_BYTES) {
                return ClaimPairingAddressResult.CredentialsCorrupted
            }
            SecretResult.Unavailable -> return ClaimPairingAddressResult.CredentialsUnavailable
            SecretResult.Corrupted -> return ClaimPairingAddressResult.CredentialsCorrupted
            SecretResult.Unsupported -> return ClaimPairingAddressResult.UnsupportedEncryption
            SecretResult.Missing -> return ClaimPairingAddressResult.CredentialsCorrupted
        }
        val deviceToken = when (
            val result = decryptSecret(
                candidate,
                secrets,
                VaultSecretKind.DEVICE_TOKEN,
            )
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return ClaimPairingAddressResult.CredentialsUnavailable
            SecretResult.Corrupted -> return ClaimPairingAddressResult.CredentialsCorrupted
            SecretResult.Unsupported -> return ClaimPairingAddressResult.UnsupportedEncryption
            SecretResult.Missing -> return ClaimPairingAddressResult.CredentialsCorrupted
        }
        if (deviceToken.size != DEVICE_TOKEN_BYTES) return ClaimPairingAddressResult.CredentialsCorrupted
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
                addressId = candidate.addressId,
                deviceToken = DeviceProtocol.encodeDeviceToken(deviceToken),
                provideAttestation = previous?.deviceId != candidate.deviceId,
            )
        ) {
            RelayClaimResult.Claimed -> {
                if (
                    dao.promoteCandidate(
                        candidateId = candidate.id,
                        claimedAt = currentTimeMillis(),
                        activeRole = DeviceIdentityRole.ACTIVE.storedName,
                        candidateRole = DeviceIdentityRole.CANDIDATE.storedName,
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
            RelayClaimResult.AddressUnavailable -> ClaimPairingAddressResult.AddressUnavailable
            is RelayClaimResult.Rejected -> ClaimPairingAddressResult.RelayRejected(
                status = result.status,
                code = result.code,
                message = result.message,
            )
            is RelayClaimResult.Unavailable -> ClaimPairingAddressResult.RelayUnavailable(
                result.cause.message,
            )
            RelayClaimResult.InvalidResponse -> ClaimPairingAddressResult.InvalidRelayResponse
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
        val secrets = dao.getSecrets(identity.id)
        val privateKey = when (
            val result = decryptSecret(identity, secrets, VaultSecretKind.DEVICE_PRIVATE_KEY)
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return RelayDeviceCredentialsResult.CredentialsUnavailable
            SecretResult.Corrupted, SecretResult.Missing -> {
                return RelayDeviceCredentialsResult.CredentialsCorrupted
            }
            SecretResult.Unsupported -> return RelayDeviceCredentialsResult.UnsupportedEncryption
        }
        val deviceToken = when (
            val result = decryptSecret(
                identity,
                secrets,
                VaultSecretKind.DEVICE_TOKEN,
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
            identity.devicePublicKey.size != DEVICE_PRIVATE_KEY_BYTES ||
            deviceToken.size != DEVICE_TOKEN_BYTES
        ) {
            return RelayDeviceCredentialsResult.CredentialsCorrupted
        }
        return RelayDeviceCredentialsResult.Available(
            RelayDeviceCredentials(
                deviceIdentityId = identity.id,
                address = identity.address,
                addressId = identity.addressId,
                deviceId = identity.deviceId,
                devicePublicKey = identity.devicePublicKey,
                devicePrivateKey = privateKey,
                deviceToken = DeviceProtocol.encodeDeviceToken(deviceToken),
                instructions = identity.instructions,
            ),
        )
    }

    private suspend fun newSecret(
        identity: DeviceIdentityEntity,
        kind: VaultSecretKind,
        value: ByteArray,
        encryptionKeyId: String,
        now: Long,
    ): VaultSecretEntity {
        val id = newId()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = encryptionKeyId,
                location = location(id, identity, kind),
                plaintext = value,
            )
        }
        return VaultSecretEntity(
            id = id,
            identityId = identity.id,
            kind = kind.storedName,
            encryptionFormat = encrypted.formatVersion,
            encryptionKeyId = encrypted.keyId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            createdAt = now,
            updatedAt = now,
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
        val secrets = dao.getSecrets(identity.id)
        val privateKey = when (
            val result = decryptSecret(identity, secrets, VaultSecretKind.DEVICE_PRIVATE_KEY)
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return DeviceMaterialResult.Unavailable
            SecretResult.Unsupported -> return DeviceMaterialResult.Unsupported
            SecretResult.Corrupted, SecretResult.Missing -> {
                return DeviceMaterialResult.Corrupted
            }
        }
        val deviceToken = when (
            val result = decryptSecret(
                identity,
                secrets,
                VaultSecretKind.DEVICE_TOKEN,
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
            identity.devicePublicKey.size != DEVICE_PRIVATE_KEY_BYTES ||
            deviceToken.size != DEVICE_TOKEN_BYTES
        ) {
            return DeviceMaterialResult.Corrupted
        }
        return DeviceMaterialResult.Available(
            DeviceMaterial(
                deviceId = identity.deviceId,
                keyPair = dev.agentknock.protocol.DeviceKeyPair(
                    privateKey = privateKey,
                    publicKey = identity.devicePublicKey,
                ),
                deviceToken = deviceToken,
            ),
        )
    }

    private suspend fun decryptSecret(
        identity: DeviceIdentityEntity,
        secrets: List<VaultSecretEntity>,
        kind: VaultSecretKind,
    ): SecretResult {
        val secret = secrets.singleOrNull { it.kind == kind.storedName }
            ?: return SecretResult.Missing
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = EncryptedValue(
                    formatVersion = secret.encryptionFormat,
                    keyId = secret.encryptionKeyId,
                    nonce = secret.nonce,
                    ciphertext = secret.ciphertext,
                ),
                location = location(secret.id, identity, kind),
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
        secretId: String,
        identity: DeviceIdentityEntity,
        kind: VaultSecretKind,
    ) = EncryptionLocation(
        recordType = "vault_secret",
        recordId = secretId,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("device_id", identity.deviceId),
            EncryptionBinding("identity_id", identity.id),
            EncryptionBinding("kind", kind.storedName),
        ),
    )

    private fun DeviceIdentityEntity.toModel(credentialsAvailable: Boolean) = DeviceIdentity(
        id = id,
        address = address,
        addressId = addressId,
        deviceId = deviceId,
        credentialsAvailable = credentialsAvailable,
        createdAt = createdAt,
        claimedAt = claimedAt,
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

private enum class DeviceIdentityRole(val storedName: String) {
    ACTIVE("active"),
    CANDIDATE("candidate"),
}

private enum class VaultSecretKind(val storedName: String) {
    DEVICE_TOKEN("device_token"),
    DEVICE_PRIVATE_KEY("device_private_key"),
}
