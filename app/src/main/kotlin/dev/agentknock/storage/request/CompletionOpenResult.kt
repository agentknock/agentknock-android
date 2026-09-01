package dev.agentknock.storage.request

import kotlinx.serialization.SerializationException
import org.bouncycastle.crypto.InvalidCipherTextException

internal sealed interface CompletionOpenResult {
    data class Opened(val plaintext: ByteArray) : CompletionOpenResult
    data object IrrecoverablyInvalid : CompletionOpenResult
    data object RetryLater : CompletionOpenResult
}

internal fun Exception.isIrrecoverableCompletionFailure(): Boolean =
    this is SerializationException ||
        this is IllegalArgumentException ||
        this is InvalidCipherTextException ||
        this is IllegalStateException && message == X25519_AGREEMENT_FAILURE

private const val X25519_AGREEMENT_FAILURE = "X25519 agreement failed"
