package dev.agentknock.storage.device

import dev.agentknock.protocol.DeviceProtocol
import dev.agentknock.relay.RelayClaimClient
import dev.agentknock.relay.RelayClaimOutcome
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.storage.WriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.audit.auditDataOf
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
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

/**
 * Credential access for an existing identity. Source lookups return null for an absent identity.
 */
internal sealed interface DeviceCredentialResult<out T> {
    data class Available<T>(val value: T) : DeviceCredentialResult<T>

    sealed interface Failure : DeviceCredentialResult<Nothing>

    data object Unavailable : Failure

    data object Corrupted : Failure

    data object UnsupportedEncryption : Failure
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
    suspend fun activeDeviceCredentials(): DeviceCredentialResult<RelayDeviceCredentials>?

    suspend fun deviceCredentials(
        deviceIdentityId: String
    ): DeviceCredentialResult<RelayDeviceCredentials>?
}

internal fun interface RelayDeviceAuthorizationSource {
    suspend fun activeDeviceAuthorization(): DeviceCredentialResult<RelayDeviceAuthorization>?
}

internal class DeviceIdentityRepository(
    private val dao: DeviceIdentityDao,
    private val keyManager: VaultKeyManager,
    private val encryption: AesGcmEncryption,
    private val relay: RelayClaimClient,
    private val audit: AuditSink,
    private val writeTransaction: WriteTransaction,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelayDeviceCredentialSource, RelayDeviceAuthorizationSource {
    suspend fun pruneRetiredIdentities(): Int =
        dao.deleteOrphanedRetiredIdentities(DeviceIdentityRole.RETIRED.storedName)

    fun observeConfiguration(): Flow<DeviceConfiguration> =
        combine(
            dao.observeIdentities(),
            dao.observeCredentials(),
        ) { identities, credentials ->
            val availability =
                credentials
                    .map { it.encryptedValue.keyId }
                    .distinct()
                    .associateWith { keyId -> keyManager.keyAvailable(keyId) }
            val configuredIdentities =
                identities
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
                    credentialsAvailable =
                        DeviceCredentialKind.entries.all { kind ->
                            identityCredentials
                                .singleOrNull { it.kind == kind.storedName }
                                ?.let { availability.getValue(it.encryptedValue.keyId) } == true
                        }
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
                    ) == 1
                )
            }
            return claimCandidate()
        }

        val device =
            if (active == null) {
                newDeviceMaterial()
            } else {
                when (val result = deviceMaterial(active)) {
                    is DeviceCredentialResult.Available -> result.value
                    DeviceCredentialResult.Unavailable -> newDeviceMaterial()
                    DeviceCredentialResult.Corrupted ->
                        return ClaimPairingAddressResult.CredentialsCorrupted
                    DeviceCredentialResult.UnsupportedEncryption -> {
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
        val identity =
            DeviceIdentityEntity(
                id = newId(),
                role = DeviceIdentityRole.CANDIDATE.storedName,
                address = address,
                deviceId = device.deviceId,
                createdAt = now,
                claimAttemptedAt = null,
                pairingEnabled =
                    settings?.pairingEnabled?.takeIf { settings.deviceId == device.deviceId }
                        ?: true,
                instructions = settings?.instructions.orEmpty(),
            )
        val credentials =
            listOf(
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
        var candidate =
            dao.getIdentity(DeviceIdentityRole.CANDIDATE.storedName)
                ?: return ClaimPairingAddressResult.NoCandidate
        val previous = dao.getIdentity(DeviceIdentityRole.ACTIVE.storedName)
        val now = currentTimeMillis()
        if (
            previous == null &&
                candidate.claimAttemptedAt == null &&
                candidate.createdAt < now - DEVICE_ID_REFRESH_AGE_MILLIS
        ) {
            candidate =
                stageCandidate(
                    address = candidate.address,
                    device = newDeviceMaterial(),
                    settings = candidate,
                )
        }
        val material =
            when (val result = deviceMaterial(candidate)) {
                is DeviceCredentialResult.Available -> result.value
                DeviceCredentialResult.Corrupted -> {
                    return ClaimPairingAddressResult.CredentialsCorrupted
                }
                DeviceCredentialResult.UnsupportedEncryption -> {
                    return ClaimPairingAddressResult.UnsupportedEncryption
                }
                DeviceCredentialResult.Unavailable -> {
                    val (replacement, replacementSettings) =
                        if (previous == null) {
                            newDeviceMaterial() to candidate
                        } else {
                            when (val activeMaterial = deviceMaterial(previous)) {
                                is DeviceCredentialResult.Available ->
                                    activeMaterial.value to previous
                                DeviceCredentialResult.Unavailable ->
                                    newDeviceMaterial() to candidate
                                DeviceCredentialResult.Corrupted -> {
                                    return ClaimPairingAddressResult.CredentialsCorrupted
                                }
                                DeviceCredentialResult.UnsupportedEncryption -> {
                                    return ClaimPairingAddressResult.UnsupportedEncryption
                                }
                            }
                        }
                    candidate =
                        stageCandidate(
                            address = candidate.address,
                            device = replacement,
                            settings = replacementSettings,
                        )
                    replacement
                }
            }
        val deviceMustBeClaimed = previous?.deviceId != candidate.deviceId
        if (deviceMustBeClaimed && candidate.claimAttemptedAt == null) {
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

        val deviceToken = DeviceProtocol.encodeDeviceToken(material.deviceToken)
        val result =
            if (deviceMustBeClaimed) {
                relay.claimAndSetAddress(
                    deviceId = candidate.deviceId,
                    addressId = DeviceProtocol.addressId(candidate.address),
                    deviceToken = deviceToken,
                )
            } else {
                relay.setAddress(
                    deviceId = candidate.deviceId,
                    addressId = DeviceProtocol.addressId(candidate.address),
                    deviceToken = deviceToken,
                )
            }
        return when (result) {
            is RelayEndpointResult.Success ->
                when (result.value) {
                    RelayClaimOutcome.CLAIMED -> {
                        writeTransaction.execute {
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
                                        type =
                                            if (previous == null) {
                                                AuditEventType.PAIRING_ADDRESS_CLAIMED
                                            } else {
                                                AuditEventType.PAIRING_ADDRESS_CHANGED
                                            },
                                        outcome = AuditOutcome.CHANGED,
                                        subject = candidate.address,
                                        data =
                                            auditDataOf(
                                                "device_identity_id" to candidate.id,
                                                "device_id" to candidate.deviceId,
                                                "pairing_address" to candidate.address,
                                                "previous_device_identity_id" to previous?.id,
                                                "previous_device_id" to previous?.deviceId,
                                                "previous_pairing_address" to previous?.address,
                                                "previous_pairing_enabled" to
                                                    previous?.pairingEnabled,
                                                "previous_instructions" to previous?.instructions,
                                                "identity_role" to
                                                    DeviceIdentityRole.ACTIVE.storedName,
                                                "pairing_enabled" to candidate.pairingEnabled,
                                                "instructions" to candidate.instructions,
                                                "created_at" to candidate.createdAt,
                                                "claim_attempted_at" to candidate.claimAttemptedAt,
                                            ),
                                    )
                                )
                                ClaimPairingAddressResult.Claimed
                            } else {
                                ClaimPairingAddressResult.NoCandidate
                            }
                        }
                    }
                    RelayClaimOutcome.ADDRESS_UNAVAILABLE ->
                        ClaimPairingAddressResult.AddressUnavailable
                }
            is RelayEndpointResult.Rejected ->
                ClaimPairingAddressResult.RelayRejected(
                    status = result.status,
                    code = result.code,
                    message = result.message,
                )
            is RelayEndpointResult.Unavailable ->
                ClaimPairingAddressResult.RelayUnavailable(result.cause.message)
            RelayEndpointResult.InvalidResponse -> ClaimPairingAddressResult.InvalidRelayResponse
        }
    }

    suspend fun discardCandidate() {
        dao.deleteIdentity(DeviceIdentityRole.CANDIDATE.storedName)
    }

    suspend fun saveInstructions(instructions: String): Boolean {
        val normalized = instructions.trim()
        return writeTransaction.execute {
            val active =
                dao.getIdentity(DeviceIdentityRole.ACTIVE.storedName) ?: return@execute false
            val updated =
                dao.updateActiveInstructions(
                    activeRole = DeviceIdentityRole.ACTIVE.storedName,
                    instructions = normalized,
                ) == 1
            if (updated) {
                audit.record(
                    AuditRecord(
                        type = AuditEventType.GENERAL_AI_REVIEW_INSTRUCTIONS_CHANGED,
                        outcome = AuditOutcome.CHANGED,
                        data =
                            auditDataOf(
                                "device_identity_id" to active.id,
                                "device_id" to active.deviceId,
                                "pairing_address" to active.address,
                                "previous_instructions" to active.instructions,
                                "instructions" to normalized,
                            ),
                    )
                )
            }
            updated
        }
    }

    override suspend fun activeDeviceCredentials():
        DeviceCredentialResult<RelayDeviceCredentials>? {
        val identity = dao.getIdentity(DeviceIdentityRole.ACTIVE.storedName) ?: return null
        return deviceCredentials(identity)
    }

    override suspend fun activeDeviceAuthorization():
        DeviceCredentialResult<RelayDeviceAuthorization>? {
        val identity = dao.getIdentity(DeviceIdentityRole.ACTIVE.storedName) ?: return null
        val deviceToken =
            when (val result = decryptCredential(identity, DeviceCredentialKind.DEVICE_TOKEN)) {
                is DeviceCredentialResult.Available -> result.value
                is DeviceCredentialResult.Failure -> return result
            }
        if (deviceToken.size != DEVICE_TOKEN_BYTES) {
            return DeviceCredentialResult.Corrupted
        }
        return DeviceCredentialResult.Available(
            RelayDeviceAuthorization(
                deviceIdentityId = identity.id,
                deviceId = identity.deviceId,
                deviceToken = DeviceProtocol.encodeDeviceToken(deviceToken),
            )
        )
    }

    override suspend fun deviceCredentials(
        deviceIdentityId: String
    ): DeviceCredentialResult<RelayDeviceCredentials>? {
        val identity = dao.getIdentityById(deviceIdentityId) ?: return null
        return deviceCredentials(identity)
    }

    private suspend fun deviceCredentials(
        identity: DeviceIdentityEntity
    ): DeviceCredentialResult<RelayDeviceCredentials> {
        val material =
            when (val result = deviceMaterial(identity)) {
                is DeviceCredentialResult.Available -> result.value
                is DeviceCredentialResult.Failure -> return result
            }
        return DeviceCredentialResult.Available(
            RelayDeviceCredentials(
                deviceIdentityId = identity.id,
                address = identity.address,
                addressId = DeviceProtocol.addressId(identity.address),
                deviceId = identity.deviceId,
                devicePublicKey = material.keyPair.publicKey,
                devicePrivateKey = material.keyPair.privateKey,
                deviceToken = DeviceProtocol.encodeDeviceToken(material.deviceToken),
                instructions = identity.instructions,
            )
        )
    }

    private suspend fun newCredential(
        identity: DeviceIdentityEntity,
        kind: DeviceCredentialKind,
        value: ByteArray,
        encryptionKeyId: String,
    ): DeviceCredentialEntity {
        val encrypted =
            withContext(cryptographyDispatcher) {
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

    private fun newDeviceMaterial() =
        DeviceMaterial(
            deviceId = DeviceProtocol.generateDeviceId(timestampMillis = currentTimeMillis()),
            keyPair = DeviceProtocol.generateDeviceKeyPair(),
            deviceToken = DeviceProtocol.generateDeviceToken(),
        )

    private suspend fun deviceMaterial(
        identity: DeviceIdentityEntity
    ): DeviceCredentialResult<DeviceMaterial> {
        val credentials = dao.getCredentials(identity.id)
        val privateKey =
            when (
                val result =
                    decryptCredential(
                        identity,
                        credentials,
                        DeviceCredentialKind.DEVICE_PRIVATE_KEY,
                    )
            ) {
                is DeviceCredentialResult.Available -> result.value
                is DeviceCredentialResult.Failure -> return result
            }
        val deviceToken =
            when (
                val result =
                    decryptCredential(
                        identity,
                        credentials,
                        DeviceCredentialKind.DEVICE_TOKEN,
                    )
            ) {
                is DeviceCredentialResult.Available -> result.value
                is DeviceCredentialResult.Failure -> return result
            }
        if (privateKey.size != DEVICE_PRIVATE_KEY_BYTES || deviceToken.size != DEVICE_TOKEN_BYTES) {
            return DeviceCredentialResult.Corrupted
        }
        return DeviceCredentialResult.Available(
            DeviceMaterial(
                deviceId = identity.deviceId,
                keyPair =
                    dev.agentknock.protocol.DeviceKeyPair(
                        privateKey = privateKey,
                        publicKey = DeviceProtocol.deriveDevicePublicKey(privateKey),
                    ),
                deviceToken = deviceToken,
            )
        )
    }

    private suspend fun decryptCredential(
        identity: DeviceIdentityEntity,
        credentials: List<DeviceCredentialEntity>,
        kind: DeviceCredentialKind,
    ): DeviceCredentialResult<ByteArray> =
        decryptCredential(
            identity = identity,
            credential = credentials.singleOrNull { it.kind == kind.storedName },
            kind = kind,
        )

    private suspend fun decryptCredential(
        identity: DeviceIdentityEntity,
        kind: DeviceCredentialKind,
    ): DeviceCredentialResult<ByteArray> =
        decryptCredential(
            identity = identity,
            credential = dao.getCredential(identity.id, kind.storedName),
            kind = kind,
        )

    private suspend fun decryptCredential(
        identity: DeviceIdentityEntity,
        credential: DeviceCredentialEntity?,
        kind: DeviceCredentialKind,
    ): DeviceCredentialResult<ByteArray> {
        credential ?: return DeviceCredentialResult.Corrupted
        val result =
            withContext(cryptographyDispatcher) {
                encryption.decrypt(
                    encrypted = credential.encryptedValue,
                    location = location(identity, kind),
                )
            }
        return when (result) {
            is DecryptionResult.Plaintext -> DeviceCredentialResult.Available(result.value)
            DecryptionResult.KeyUnavailable -> DeviceCredentialResult.Unavailable
            DecryptionResult.AuthenticationFailed -> DeviceCredentialResult.Corrupted
            DecryptionResult.UnsupportedFormat -> DeviceCredentialResult.UnsupportedEncryption
        }
    }

    private fun location(
        identity: DeviceIdentityEntity,
        kind: DeviceCredentialKind,
    ) =
        EncryptionLocation(
            recordType = "device_credential",
            recordId = identity.id,
            fieldName = kind.storedName,
            bindings = listOf(EncryptionBinding("device_id", identity.deviceId)),
        )

    private fun DeviceIdentityEntity.toModel(credentialsAvailable: Boolean) =
        DeviceIdentity(
            id = id,
            address = address,
            deviceId = deviceId,
            credentialsAvailable = credentialsAvailable,
            createdAt = createdAt,
            pairingEnabled = pairingEnabled,
            instructions = instructions,
        )

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
