package dev.agentknock.storage.device

import dev.agentknock.protocol.DeviceKeyPair
import dev.agentknock.protocol.DeviceProtocol
import dev.agentknock.storage.crypto.DecryptionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Holds access to encrypted material, never a cached plaintext key. */
internal class DeviceKeyAccess(
    private val dispatcher: CoroutineDispatcher,
    private val decrypt: () -> DecryptionResult,
) {
    /**
     * The operation borrows the key only for synchronous cryptography. It must not retain or return
     * the private bytes. Cleanup happens before the cancellable dispatcher return. This clears our
     * buffer; it cannot erase copies inside the crypto provider or JVM.
     */
    suspend fun <T> use(operation: (DeviceKeyPair) -> T): T =
        withContext(dispatcher) {
            val decrypted =
                try {
                    decrypt()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    throw DeviceKeyAccessException(DeviceCredentialResult.Unavailable, failure)
                }
            val privateKey =
                when (decrypted) {
                    is DecryptionResult.Plaintext -> decrypted.value
                    DecryptionResult.KeyUnavailable ->
                        throw DeviceKeyAccessException(DeviceCredentialResult.Unavailable)
                    DecryptionResult.AuthenticationFailed ->
                        throw DeviceKeyAccessException(DeviceCredentialResult.Corrupted)
                    DecryptionResult.UnsupportedFormat ->
                        throw DeviceKeyAccessException(DeviceCredentialResult.UnsupportedEncryption)
                }
            try {
                if (privateKey.size != 32)
                    throw DeviceKeyAccessException(DeviceCredentialResult.Corrupted)
                operation(
                    DeviceKeyPair(privateKey, DeviceProtocol.deriveDevicePublicKey(privateKey))
                )
            } finally {
                privateKey.fill(0)
            }
        }

    override fun toString(): String = "DeviceKeyAccess([REDACTED])"
}

internal class DeviceKeyAccessException(
    val failure: DeviceCredentialResult.Failure,
    cause: Exception? = null,
) : IllegalStateException("Device key access failed: $failure", cause)
