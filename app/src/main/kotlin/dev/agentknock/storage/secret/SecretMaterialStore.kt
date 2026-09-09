package dev.agentknock.storage.secret

import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionBinding
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Encrypts and reconstructs typed secret material; it does not decide storage policy. */
internal class SecretMaterialStore(
    private val keyManager: VaultKeyManager,
    private val encryption: AesGcmEncryption,
    private val sshKeys: SshKeyCodec,
    private val cryptographyDispatcher: CoroutineDispatcher,
) {
    suspend fun encryptEnvironmentValue(
        id: String,
        secretId: String,
        name: String,
        sensitive: Boolean,
        value: String,
    ): EncryptedValue {
        val key = keyManager.activeKey(VaultKeyPurpose.SECRET_VALUES)
        return withContext(cryptographyDispatcher) {
            encryption.encrypt(
                keyId = key.id,
                location = environmentLocation(id, secretId, name, sensitive),
                plaintext = value.encodeToByteArray(),
            )
        }
    }

    suspend fun decryptEnvironmentValue(variable: EnvironmentVariableEntity): DecryptionResult =
        withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = variable.encryptedValue,
                location =
                    environmentLocation(
                        variable.id,
                        variable.secretId,
                        variable.name,
                        variable.sensitive,
                    ),
            )
        }

    suspend fun encryptedSshKey(
        secretId: String,
        privateKey: SshPrivateKey,
    ): SshKeyEntity {
        validateSshPrivateKey(privateKey)
        val key = keyManager.activeKey(VaultKeyPurpose.SECRET_VALUES)
        val encrypted =
            withContext(cryptographyDispatcher) {
                encryption.encrypt(
                    keyId = key.id,
                    location =
                        sshKeyLocation(
                            secretId,
                            privateKey.algorithm,
                            privateKey.publicKey,
                        ),
                    plaintext = privateKey.privateKey,
                )
            }
        return SshKeyEntity(
            secretId = secretId,
            algorithm = privateKey.algorithm.storedName,
            publicKey = privateKey.publicKey.copyOf(),
            comment = privateKey.comment,
            encryptedPrivateKey = encrypted,
        )
    }

    suspend fun decryptSshKey(
        key: SshKeyEntity,
        algorithm: SshKeyAlgorithm,
    ): DecryptionResult =
        withContext(cryptographyDispatcher) {
            encryption.decrypt(
                encrypted = key.encryptedPrivateKey,
                location =
                    sshKeyLocation(
                        key.secretId,
                        algorithm,
                        key.publicKey,
                    ),
            )
        }

    fun storedPrivateKey(
        key: SshKeyEntity,
        algorithm: SshKeyAlgorithm,
        plaintext: ByteArray,
    ): SshPrivateKey = sshKeys.fromStored(algorithm, plaintext, key.publicKey, key.comment)

    fun publicKey(key: SshKeyEntity, algorithm: SshKeyAlgorithm): SshPublicKey =
        sshKeys.publicKey(algorithm, key.publicKey, key.comment)

    fun publicKey(key: SshKeyEntity): SshPublicKey =
        sshKeys.publicKey(key.algorithm, key.publicKey, key.comment)

    fun publicKey(key: SshPrivateKey): SshPublicKey =
        SshPublicKey(key.algorithm, key.publicKey, key.comment)

    fun validateSshPrivateKey(key: SshPrivateKey) {
        sshKeys.fromStored(
            key.algorithm,
            key.privateKey,
            key.publicKey,
            key.comment,
        )
    }

    private fun environmentLocation(
        id: String,
        secretId: String,
        name: String,
        sensitive: Boolean,
    ) =
        EncryptionLocation(
            recordType = "environment_variable",
            recordId = id,
            fieldName = "value",
            bindings =
                listOf(
                    EncryptionBinding("secret_id", secretId),
                    EncryptionBinding("name", name),
                    EncryptionBinding("sensitive", sensitive.toString()),
                ),
        )

    private fun sshKeyLocation(
        secretId: String,
        algorithm: SshKeyAlgorithm,
        publicKey: ByteArray,
    ) =
        EncryptionLocation(
            recordType = "ssh_key",
            recordId = secretId,
            fieldName = "private_key",
            bindings =
                listOf(
                    EncryptionBinding("algorithm", algorithm.storedName),
                    EncryptionBinding("private_key_format", algorithm.canonicalPrivateKeyFormat()),
                    EncryptionBinding("public_key", Base64.getEncoder().encodeToString(publicKey)),
                ),
        )
}
