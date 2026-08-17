package dev.agentknock.storage.vault

import dev.agentknock.protocol.VaultProtocol
import dev.agentknock.relay.RelayClaimClient
import dev.agentknock.relay.RelayClaimResult
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import dev.agentknock.storage.audit.AuditCategory
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

internal data class VaultConfiguration(
    val active: VaultIdentity?,
    val candidate: VaultIdentity?,
)

internal data class VaultIdentity(
    val id: String,
    val address: String,
    val addressId: String,
    val deviceId: String,
    val secretsAvailable: Boolean,
    val pairingEnabled: Boolean,
    val createdAt: Long,
    val claimedAt: Long?,
)

internal data class RelayDeviceCredentials(
    val vaultIdentityId: String,
    val address: String,
    val addressId: String,
    val deviceId: String,
    val devicePublicKey: ByteArray,
    val devicePrivateKey: ByteArray,
    val deviceToken: String,
)

internal sealed interface RelayDeviceCredentialsResult {
    data class Available(val credentials: RelayDeviceCredentials) : RelayDeviceCredentialsResult

    data object Missing : RelayDeviceCredentialsResult

    data object SecretsUnavailable : RelayDeviceCredentialsResult

    data object SecretsCorrupted : RelayDeviceCredentialsResult

    data object UnsupportedEncryption : RelayDeviceCredentialsResult
}

internal sealed interface ClaimVaultResult {
    data object Claimed : ClaimVaultResult

    data object AddressUnavailable : ClaimVaultResult

    data object SameAddress : ClaimVaultResult

    data object NoCandidate : ClaimVaultResult

    data object SecretsUnavailable : ClaimVaultResult

    data object SecretsCorrupted : ClaimVaultResult

    data object UnsupportedEncryption : ClaimVaultResult

    data class RelayRejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : ClaimVaultResult

    data class RelayUnavailable(val message: String?) : ClaimVaultResult

    data object InvalidRelayResponse : ClaimVaultResult
}

internal interface RelayDeviceCredentialSource {
    suspend fun activeDeviceCredentials(): RelayDeviceCredentialsResult

    suspend fun deviceCredentials(vaultIdentityId: String): RelayDeviceCredentialsResult
}

internal class VaultRepository(
    private val dao: VaultDao,
    private val keyManager: LocalEncryptionKeyManager,
    private val encryption: AesGcmEncryption,
    private val relay: RelayClaimClient,
    private val audit: AuditSink = NoOpAuditSink,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelayDeviceCredentialSource {
    fun observeConfiguration(): Flow<VaultConfiguration> = combine(
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
                secretsAvailable = VaultSecretKind.entries.all { kind ->
                    identitySecrets.singleOrNull { it.kind == kind.storedName }
                        ?.let { availability.getValue(it.encryptionKeyId) } == true
                },
            )
        }
        VaultConfiguration(
            active = identityModels[VaultIdentityRole.ACTIVE.storedName],
            candidate = identityModels[VaultIdentityRole.CANDIDATE.storedName],
        )
    }

    suspend fun stageAndClaim(address: String): ClaimVaultResult {
        require(VaultProtocol.validAddress(address)) { "Invalid vault address" }
        val active = dao.getIdentity(VaultIdentityRole.ACTIVE.storedName)
        if (active?.address == address) {
            return ClaimVaultResult.SameAddress
        }
        if (dao.getIdentity(VaultIdentityRole.CANDIDATE.storedName)?.address == address) {
            return claimCandidate()
        }

        val identityId = newId()
        val addressId = VaultProtocol.addressId(address)
        val device = if (active == null) {
            newDeviceMaterial()
        } else {
            when (val result = deviceMaterial(active)) {
                is DeviceMaterialResult.Available -> result.value
                DeviceMaterialResult.Unavailable -> newDeviceMaterial()
                DeviceMaterialResult.Corrupted -> return ClaimVaultResult.SecretsCorrupted
                DeviceMaterialResult.Unsupported -> {
                    return ClaimVaultResult.UnsupportedEncryption
                }
            }
        }
        val encryptionKey = keyManager.activeKey()
        val now = currentTimeMillis()
        val identity = VaultIdentityEntity(
            id = identityId,
            role = VaultIdentityRole.CANDIDATE.storedName,
            address = address,
            addressId = addressId,
            deviceId = device.deviceId,
            devicePublicKey = device.keyPair.publicKey,
            createdAt = now,
            claimedAt = null,
            pairingEnabled = active?.pairingEnabled ?: true,
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
            candidateRole = VaultIdentityRole.CANDIDATE.storedName,
        )
        return claimCandidate()
    }

    suspend fun claimCandidate(): ClaimVaultResult {
        val candidate = dao.getIdentity(VaultIdentityRole.CANDIDATE.storedName)
            ?: return ClaimVaultResult.NoCandidate
        val previous = dao.getIdentity(VaultIdentityRole.ACTIVE.storedName)
        val secrets = dao.getSecrets(candidate.id)
        when (val result = decryptSecret(candidate, secrets, VaultSecretKind.DEVICE_PRIVATE_KEY)) {
            is SecretResult.Available -> if (result.value.size != DEVICE_PRIVATE_KEY_BYTES) {
                return ClaimVaultResult.SecretsCorrupted
            }
            SecretResult.Unavailable -> return ClaimVaultResult.SecretsUnavailable
            SecretResult.Corrupted -> return ClaimVaultResult.SecretsCorrupted
            SecretResult.Unsupported -> return ClaimVaultResult.UnsupportedEncryption
            SecretResult.Missing -> return ClaimVaultResult.SecretsCorrupted
        }
        val deviceToken = when (
            val result = decryptSecret(
                candidate,
                secrets,
                VaultSecretKind.DEVICE_TOKEN,
            )
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return ClaimVaultResult.SecretsUnavailable
            SecretResult.Corrupted -> return ClaimVaultResult.SecretsCorrupted
            SecretResult.Unsupported -> return ClaimVaultResult.UnsupportedEncryption
            SecretResult.Missing -> return ClaimVaultResult.SecretsCorrupted
        }
        if (deviceToken.size != DEVICE_TOKEN_BYTES) return ClaimVaultResult.SecretsCorrupted

        return when (
            val result = relay.claim(
                deviceId = candidate.deviceId,
                addressId = candidate.addressId,
                deviceToken = VaultProtocol.encodeDeviceToken(deviceToken),
            )
        ) {
            RelayClaimResult.Claimed -> {
                if (
                    dao.promoteCandidate(
                        candidateId = candidate.id,
                        claimedAt = currentTimeMillis(),
                        activeRole = VaultIdentityRole.ACTIVE.storedName,
                        candidateRole = VaultIdentityRole.CANDIDATE.storedName,
                    )
                ) {
                    audit.record(
                        AuditRecord(
                            category = AuditCategory.DEVICE,
                            title = if (previous == null) {
                                "Pairing address claimed"
                            } else {
                                "Pairing address changed"
                            },
                            detail = candidate.address,
                            outcome = AuditOutcome.CHANGED,
                        ),
                    )
                    ClaimVaultResult.Claimed
                } else {
                    ClaimVaultResult.NoCandidate
                }
            }
            RelayClaimResult.AddressUnavailable -> ClaimVaultResult.AddressUnavailable
            is RelayClaimResult.Rejected -> ClaimVaultResult.RelayRejected(
                status = result.status,
                code = result.code,
                message = result.message,
            )
            is RelayClaimResult.Unavailable -> ClaimVaultResult.RelayUnavailable(
                result.cause.message,
            )
            RelayClaimResult.InvalidResponse -> ClaimVaultResult.InvalidRelayResponse
        }
    }

    suspend fun discardCandidate() {
        dao.deleteIdentity(VaultIdentityRole.CANDIDATE.storedName)
    }

    override suspend fun activeDeviceCredentials(): RelayDeviceCredentialsResult {
        val identity = dao.getIdentity(VaultIdentityRole.ACTIVE.storedName)
            ?: return RelayDeviceCredentialsResult.Missing
        return deviceCredentials(identity)
    }

    override suspend fun deviceCredentials(
        vaultIdentityId: String,
    ): RelayDeviceCredentialsResult {
        val identity = dao.getIdentityById(vaultIdentityId)
            ?: return RelayDeviceCredentialsResult.Missing
        return deviceCredentials(identity)
    }

    private suspend fun deviceCredentials(
        identity: VaultIdentityEntity,
    ): RelayDeviceCredentialsResult {
        val secrets = dao.getSecrets(identity.id)
        val privateKey = when (
            val result = decryptSecret(identity, secrets, VaultSecretKind.DEVICE_PRIVATE_KEY)
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return RelayDeviceCredentialsResult.SecretsUnavailable
            SecretResult.Corrupted, SecretResult.Missing -> {
                return RelayDeviceCredentialsResult.SecretsCorrupted
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
            SecretResult.Unavailable -> return RelayDeviceCredentialsResult.SecretsUnavailable
            SecretResult.Corrupted, SecretResult.Missing -> {
                return RelayDeviceCredentialsResult.SecretsCorrupted
            }
            SecretResult.Unsupported -> return RelayDeviceCredentialsResult.UnsupportedEncryption
        }
        if (
            privateKey.size != DEVICE_PRIVATE_KEY_BYTES ||
            identity.devicePublicKey.size != DEVICE_PRIVATE_KEY_BYTES ||
            deviceToken.size != DEVICE_TOKEN_BYTES
        ) {
            return RelayDeviceCredentialsResult.SecretsCorrupted
        }
        return RelayDeviceCredentialsResult.Available(
            RelayDeviceCredentials(
                vaultIdentityId = identity.id,
                address = identity.address,
                addressId = identity.addressId,
                deviceId = identity.deviceId,
                devicePublicKey = identity.devicePublicKey,
                devicePrivateKey = privateKey,
                deviceToken = VaultProtocol.encodeDeviceToken(deviceToken),
            ),
        )
    }

    private suspend fun newSecret(
        identity: VaultIdentityEntity,
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
        deviceId = VaultProtocol.generateDeviceId(
            timestampMillis = currentTimeMillis(),
        ),
        keyPair = VaultProtocol.generateDeviceKeyPair(),
        deviceToken = VaultProtocol.generateDeviceToken(),
    )

    private suspend fun deviceMaterial(identity: VaultIdentityEntity): DeviceMaterialResult {
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
        identity: VaultIdentityEntity,
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
        identity: VaultIdentityEntity,
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

    private fun VaultIdentityEntity.toModel(secretsAvailable: Boolean) = VaultIdentity(
        id = id,
        address = address,
        addressId = addressId,
        deviceId = deviceId,
        secretsAvailable = secretsAvailable,
        createdAt = createdAt,
        claimedAt = claimedAt,
        pairingEnabled = pairingEnabled,
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
    }
}

private data class DeviceMaterial(
    val deviceId: String,
    val keyPair: dev.agentknock.protocol.DeviceKeyPair,
    val deviceToken: ByteArray,
)

private enum class VaultIdentityRole(val storedName: String) {
    ACTIVE("active"),
    CANDIDATE("candidate"),
}

private enum class VaultSecretKind(val storedName: String) {
    DEVICE_TOKEN("device_token"),
    DEVICE_PRIVATE_KEY("device_private_key"),
}
