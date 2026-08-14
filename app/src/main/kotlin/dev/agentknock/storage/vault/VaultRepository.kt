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
    val routeId: String,
    val secretsAvailable: Boolean,
    val createdAt: Long,
    val claimedAt: Long?,
)

internal data class VaultRelayCredentials(
    val identityId: String,
    val address: String,
    val routeId: String,
    val routePublicKey: ByteArray,
    val routePrivateKey: ByteArray,
    val authenticationToken: String,
)

internal sealed interface VaultRelayCredentialsResult {
    data class Available(val credentials: VaultRelayCredentials) : VaultRelayCredentialsResult

    data object Missing : VaultRelayCredentialsResult

    data object SecretsUnavailable : VaultRelayCredentialsResult

    data object SecretsCorrupted : VaultRelayCredentialsResult

    data object UnsupportedEncryption : VaultRelayCredentialsResult
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

internal interface VaultCredentialSource {
    suspend fun activeRelayCredentials(): VaultRelayCredentialsResult

    suspend fun relayCredentials(identityId: String): VaultRelayCredentialsResult
}

internal class VaultRepository(
    private val dao: VaultDao,
    private val keyManager: LocalEncryptionKeyManager,
    private val encryption: AesGcmEncryption,
    private val relay: RelayClaimClient,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VaultCredentialSource {
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
        if (dao.getIdentity(VaultIdentityRole.ACTIVE.storedName)?.address == address) {
            return ClaimVaultResult.SameAddress
        }
        if (dao.getIdentity(VaultIdentityRole.CANDIDATE.storedName)?.address == address) {
            return claimCandidate()
        }

        val identityId = newId()
        val routeId = VaultProtocol.routeId(address)
        val routeKeyPair = VaultProtocol.generateRouteKeyPair()
        val authenticationToken = VaultProtocol.generateAuthenticationToken()
        val encryptionKey = keyManager.activeKey()
        val now = currentTimeMillis()
        val identity = VaultIdentityEntity(
            id = identityId,
            role = VaultIdentityRole.CANDIDATE.storedName,
            address = address,
            routeId = routeId,
            routePublicKey = routeKeyPair.publicKey,
            createdAt = now,
            claimedAt = null,
        )
        val secrets = listOf(
            newSecret(
                identity = identity,
                kind = VaultSecretKind.ROUTE_PRIVATE_KEY,
                value = routeKeyPair.privateKey,
                encryptionKeyId = encryptionKey.id,
                now = now,
            ),
            newSecret(
                identity = identity,
                kind = VaultSecretKind.RELAY_AUTHENTICATION_TOKEN,
                value = authenticationToken,
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
        val secrets = dao.getSecrets(candidate.id)
        when (val result = decryptSecret(candidate, secrets, VaultSecretKind.ROUTE_PRIVATE_KEY)) {
            is SecretResult.Available -> if (result.value.size != ROUTE_PRIVATE_KEY_BYTES) {
                return ClaimVaultResult.SecretsCorrupted
            }
            SecretResult.Unavailable -> return ClaimVaultResult.SecretsUnavailable
            SecretResult.Corrupted -> return ClaimVaultResult.SecretsCorrupted
            SecretResult.Unsupported -> return ClaimVaultResult.UnsupportedEncryption
            SecretResult.Missing -> return ClaimVaultResult.SecretsCorrupted
        }
        val token = when (
            val result = decryptSecret(
                candidate,
                secrets,
                VaultSecretKind.RELAY_AUTHENTICATION_TOKEN,
            )
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return ClaimVaultResult.SecretsUnavailable
            SecretResult.Corrupted -> return ClaimVaultResult.SecretsCorrupted
            SecretResult.Unsupported -> return ClaimVaultResult.UnsupportedEncryption
            SecretResult.Missing -> return ClaimVaultResult.SecretsCorrupted
        }
        if (token.size != AUTHENTICATION_TOKEN_BYTES) return ClaimVaultResult.SecretsCorrupted

        return when (
            val result = relay.claim(
                routeId = candidate.routeId,
                authenticationToken = VaultProtocol.encodeAuthenticationToken(token),
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

    override suspend fun activeRelayCredentials(): VaultRelayCredentialsResult {
        val identity = dao.getIdentity(VaultIdentityRole.ACTIVE.storedName)
            ?: return VaultRelayCredentialsResult.Missing
        return relayCredentials(identity)
    }

    override suspend fun relayCredentials(identityId: String): VaultRelayCredentialsResult {
        val identity = dao.getIdentityById(identityId)
            ?: return VaultRelayCredentialsResult.Missing
        return relayCredentials(identity)
    }

    private suspend fun relayCredentials(
        identity: VaultIdentityEntity,
    ): VaultRelayCredentialsResult {
        val secrets = dao.getSecrets(identity.id)
        val privateKey = when (
            val result = decryptSecret(identity, secrets, VaultSecretKind.ROUTE_PRIVATE_KEY)
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return VaultRelayCredentialsResult.SecretsUnavailable
            SecretResult.Corrupted, SecretResult.Missing -> {
                return VaultRelayCredentialsResult.SecretsCorrupted
            }
            SecretResult.Unsupported -> return VaultRelayCredentialsResult.UnsupportedEncryption
        }
        val token = when (
            val result = decryptSecret(
                identity,
                secrets,
                VaultSecretKind.RELAY_AUTHENTICATION_TOKEN,
            )
        ) {
            is SecretResult.Available -> result.value
            SecretResult.Unavailable -> return VaultRelayCredentialsResult.SecretsUnavailable
            SecretResult.Corrupted, SecretResult.Missing -> {
                return VaultRelayCredentialsResult.SecretsCorrupted
            }
            SecretResult.Unsupported -> return VaultRelayCredentialsResult.UnsupportedEncryption
        }
        if (
            privateKey.size != ROUTE_PRIVATE_KEY_BYTES ||
            identity.routePublicKey.size != ROUTE_PRIVATE_KEY_BYTES ||
            token.size != AUTHENTICATION_TOKEN_BYTES
        ) {
            return VaultRelayCredentialsResult.SecretsCorrupted
        }
        return VaultRelayCredentialsResult.Available(
            VaultRelayCredentials(
                identityId = identity.id,
                address = identity.address,
                routeId = identity.routeId,
                routePublicKey = identity.routePublicKey,
                routePrivateKey = privateKey,
                authenticationToken = VaultProtocol.encodeAuthenticationToken(token),
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
            EncryptionBinding("address", identity.address),
            EncryptionBinding("identity_id", identity.id),
            EncryptionBinding("kind", kind.storedName),
            EncryptionBinding("route_id", identity.routeId),
        ),
    )

    private fun VaultIdentityEntity.toModel(secretsAvailable: Boolean) = VaultIdentity(
        id = id,
        address = address,
        routeId = routeId,
        secretsAvailable = secretsAvailable,
        createdAt = createdAt,
        claimedAt = claimedAt,
    )

    private sealed interface SecretResult {
        data class Available(val value: ByteArray) : SecretResult

        data object Unavailable : SecretResult

        data object Corrupted : SecretResult

        data object Unsupported : SecretResult

        data object Missing : SecretResult
    }

    private companion object {
        const val AUTHENTICATION_TOKEN_BYTES = 32
        const val ROUTE_PRIVATE_KEY_BYTES = 32
    }
}

private enum class VaultIdentityRole(val storedName: String) {
    ACTIVE("active"),
    CANDIDATE("candidate"),
}

private enum class VaultSecretKind(val storedName: String) {
    RELAY_AUTHENTICATION_TOKEN("relay_authentication_token"),
    ROUTE_PRIVATE_KEY("route_private_key"),
}
