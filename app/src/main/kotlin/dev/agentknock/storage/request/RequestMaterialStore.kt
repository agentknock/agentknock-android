package dev.agentknock.storage.request

import dev.agentknock.protocol.OpenedPairedRequest
import dev.agentknock.protocol.PairedRequestKeySource
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.secret.privateKeyFormat
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Encrypts request-scoped values and client session keys without deciding request policy. */
internal class RequestMaterialStore(
    private val dao: RequestDao,
    private val keyManager: VaultKeyManager,
    private val encryption: AesGcmEncryption,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cryptographyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun encryptSecretUploadVariable(
        relayRequestId: String,
        clientId: String,
        name: String,
        value: String,
        sensitive: Boolean,
    ): SecretUploadEnvironmentVariableEntity {
        val id = newId()
        val key = keyManager.activeKey(VaultKeyPurpose.SECRET_VALUES)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = secretUploadVariableLocation(id, relayRequestId, clientId, name),
                plaintext = value.encodeToByteArray(),
            )
        }
        return SecretUploadEnvironmentVariableEntity(
            id = id,
            requestId = relayRequestId,
            name = name,
            sensitive = sensitive,
            encryptedValue = encrypted,
        )
    }

    suspend fun decryptSecretUploadEnvironmentVariable(
        request: InboxRequestEntity,
        variable: SecretUploadEnvironmentVariableEntity,
    ): DecryptionResult = withContext(cryptographyDispatcher) {
        encryption.decrypt(
            encrypted = variable.encryptedValue,
            location = secretUploadVariableLocation(
                variable.id,
                request.id,
                request.clientId,
                variable.name,
            ),
        )
    }

    suspend fun encryptSecretUploadSshKey(
        relayRequestId: String,
        clientId: String,
        privateKey: SshPrivateKey,
    ): SecretUploadSshKeyEntity {
        val key = keyManager.activeKey(VaultKeyPurpose.SECRET_VALUES)
        val privateKeyFormat = privateKey.algorithm.privateKeyFormat()
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = secretUploadSshKeyLocation(
                    relayRequestId,
                    clientId,
                    privateKey.algorithm.storedName,
                    privateKeyFormat,
                    privateKey.publicKey,
                ),
                plaintext = privateKey.privateKey,
            )
        }
        return SecretUploadSshKeyEntity(
            requestId = relayRequestId,
            algorithm = privateKey.algorithm.storedName,
            publicKey = privateKey.publicKey.copyOf(),
            comment = privateKey.comment,
            privateKeyFormat = privateKeyFormat,
            encryptedPrivateKey = encrypted,
        )
    }

    suspend fun decryptSecretUploadSshKey(
        request: InboxRequestEntity,
        key: SecretUploadSshKeyEntity,
    ): DecryptionResult {
        val algorithm = SshKeyAlgorithm.fromStoredName(key.algorithm)
            ?: return DecryptionResult.UnsupportedFormat
        if (key.privateKeyFormat != algorithm.privateKeyFormat()) {
            return DecryptionResult.UnsupportedFormat
        }
        return withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = key.encryptedPrivateKey,
                location = secretUploadSshKeyLocation(
                    request.id,
                    request.clientId,
                    key.algorithm,
                    key.privateKeyFormat,
                    key.publicKey,
                ),
            )
        }
    }

    suspend fun acceptedRequestPsks(
        client: ClientEntity,
        relayRequestId: String,
        opened: OpenedPairedRequest,
        currentClientPsk: ByteArray,
        now: Long,
    ): AcceptedRequestPsks {
        val requestPsk = encryptRequestPsk(
            deviceIdentityId = client.deviceIdentityId,
            clientId = client.clientId,
            relayRequestId = relayRequestId,
            clientPsk = opened.clientPsk,
        )
        if (opened.keySource != PairedRequestKeySource.ROTATED) {
            return AcceptedRequestPsks(requestPsk, null, null)
        }
        checkNotNull(dao.getClientPsk(client.clientId, ClientPskSlot.CURRENT.storedName)) {
            "The current client key is unavailable"
        }
        return AcceptedRequestPsks(
            requestPsk = requestPsk,
            currentClientPsk = encryptClientPsk(
                client = client,
                clientPsk = opened.clientPsk,
                now = now,
                slot = ClientPskSlot.CURRENT,
            ),
            previousClientPsk = encryptClientPsk(
                client = client,
                clientPsk = currentClientPsk,
                now = now,
                slot = ClientPskSlot.PREVIOUS,
            ),
        )
    }

    suspend fun encryptRequestPsk(
        deviceIdentityId: String,
        clientId: String,
        relayRequestId: String,
        clientPsk: ByteArray,
    ): RequestPskEntity {
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val key = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = requestPskLocation(relayRequestId, clientId, deviceIdentityId),
                plaintext = clientPsk,
            )
        }
        return RequestPskEntity(
            requestId = relayRequestId,
            encryptedPsk = encrypted,
        )
    }

    suspend fun decryptRequestPsk(request: InboxRequestEntity): ByteArray? {
        val secret = dao.getRequestPsk(request.id) ?: return null
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = secret.encryptedPsk,
                location = requestPskLocation(
                    request.id,
                    request.clientId,
                    request.deviceIdentityId,
                ),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == CLIENT_PSK_BYTES
        }
    }

    suspend fun encryptClientPsk(
        client: ClientEntity,
        clientPsk: ByteArray,
        now: Long,
    ): ClientPskEntity = encryptClientPsk(client, clientPsk, now, ClientPskSlot.CURRENT)

    suspend fun decryptClientPsk(client: ClientEntity): ByteArray? =
        decryptClientPsk(client, ClientPskSlot.CURRENT)

    suspend fun decryptPreviousClientPsk(client: ClientEntity): ByteArray? {
        val previous = dao.getClientPsk(client.clientId, ClientPskSlot.PREVIOUS.storedName)
            ?: return null
        if (!previousPskEligible(previous.storedAt, currentTimeMillis())) return null
        return decryptClientPsk(client, ClientPskSlot.PREVIOUS)
    }

    suspend fun withEncryptedPendingPsk(
        attempt: PairingAttemptEntity,
        request: InboxRequestEntity,
        clientPsk: ByteArray,
    ): PairingAttemptEntity {
        require(attempt.requestId == request.id)
        require(attempt.clientId == request.clientId)
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val key = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = pendingPskLocation(attempt, request),
                plaintext = clientPsk,
            )
        }
        return attempt.copy(pendingPsk = encrypted)
    }

    suspend fun decryptPendingPsk(
        attempt: PairingAttemptEntity,
        request: InboxRequestEntity,
    ): ByteArray? {
        require(attempt.requestId == request.id)
        require(attempt.clientId == request.clientId)
        val pendingPsk = attempt.pendingPsk ?: return null
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = pendingPsk,
                location = pendingPskLocation(attempt, request),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == CLIENT_PSK_BYTES
        }
    }

    private suspend fun encryptClientPsk(
        client: ClientEntity,
        clientPsk: ByteArray,
        now: Long,
        slot: ClientPskSlot,
    ): ClientPskEntity {
        require(clientPsk.size == CLIENT_PSK_BYTES)
        val key = keyManager.activeKey(VaultKeyPurpose.DEVICE_STATE)
        val encrypted = withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = clientPskLocation(client, slot),
                plaintext = clientPsk,
            )
        }
        return ClientPskEntity(
            clientId = client.clientId,
            slot = slot.storedName,
            encryptedPsk = encrypted,
            storedAt = now,
        )
    }

    private suspend fun decryptClientPsk(
        client: ClientEntity,
        slot: ClientPskSlot,
    ): ByteArray? {
        val secret = dao.getClientPsk(client.clientId, slot.storedName) ?: return null
        val result = withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = secret.encryptedPsk,
                location = clientPskLocation(client, slot),
            )
        }
        return (result as? DecryptionResult.Plaintext)?.value?.takeIf {
            it.size == CLIENT_PSK_BYTES
        }
    }

    private fun secretUploadVariableLocation(
        id: String,
        requestId: String,
        clientId: String,
        name: String,
    ) = EncryptionLocation(
        recordType = "secret_upload_variable",
        recordId = id,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("request_id", requestId),
            EncryptionBinding("client_id", clientId),
            EncryptionBinding("name", name),
        ),
    )

    private fun secretUploadSshKeyLocation(
        relayRequestId: String,
        clientId: String,
        algorithm: String,
        privateKeyFormat: String,
        publicKey: ByteArray,
    ) = EncryptionLocation(
        recordType = "secret_upload_ssh_key",
        recordId = relayRequestId,
        fieldName = "private_key",
        bindings = listOf(
            EncryptionBinding("client_id", clientId),
            EncryptionBinding("algorithm", algorithm),
            EncryptionBinding("private_key_format", privateKeyFormat),
            EncryptionBinding("public_key", Base64.getEncoder().encodeToString(publicKey)),
        ),
    )

    private fun requestPskLocation(
        requestId: String,
        clientId: String,
        deviceIdentityId: String,
    ) = EncryptionLocation(
        recordType = "request_psk",
        recordId = requestId,
        fieldName = "client_psk",
        bindings = listOf(
            EncryptionBinding("client_id", clientId),
            EncryptionBinding("device_identity_id", deviceIdentityId),
        ),
    )

    private fun clientPskLocation(
        client: ClientEntity,
        slot: ClientPskSlot,
    ) = EncryptionLocation(
        recordType = "client_psk",
        recordId = client.clientId,
        fieldName = "value",
        bindings = listOf(
            EncryptionBinding("slot", slot.storedName),
            EncryptionBinding("device_identity_id", client.deviceIdentityId),
        ),
    )

    private fun pendingPskLocation(
        attempt: PairingAttemptEntity,
        request: InboxRequestEntity,
    ) = EncryptionLocation(
        recordType = "pairing_attempt",
        recordId = attempt.requestId,
        fieldName = "pending_client_psk",
        bindings = listOf(
            EncryptionBinding("client_id", attempt.clientId),
            EncryptionBinding("device_identity_id", request.deviceIdentityId),
        ),
    )

    private enum class ClientPskSlot(val storedName: String) {
        CURRENT("current"),
        PREVIOUS("previous"),
    }

    private companion object {
        const val CLIENT_PSK_BYTES = 32
    }
}

internal data class AcceptedRequestPsks(
    val requestPsk: RequestPskEntity,
    val currentClientPsk: ClientPskEntity?,
    val previousClientPsk: ClientPskEntity?,
)

internal fun previousPskEligible(updatedAt: Long, now: Long): Boolean =
    now >= updatedAt && now - updatedAt <= PREVIOUS_PSK_OVERLAP_MILLIS

private const val PREVIOUS_PSK_OVERLAP_MILLIS = 10 * 60 * 1_000L
