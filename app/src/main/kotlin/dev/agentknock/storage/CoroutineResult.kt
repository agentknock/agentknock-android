package dev.agentknock.storage

import kotlinx.coroutines.CancellationException

/** Converts ordinary operation failures to [Result] while preserving coroutine cancellation. */
internal inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
