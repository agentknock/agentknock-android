package dev.agentknock.storage.request

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** The relay's durable minimum time for the next connection attempt. */
internal class RelayRetryDeadline(
    private val readState: () -> RelayRetryDeadlineState,
    private val writeState: (RelayRetryDeadlineState) -> Unit,
    private val bootCount: Int?,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMillis: () -> Long,
) {
    private var elapsedRealtimeNotBeforeMillis = readState().let { stored ->
        if (bootCount != null && stored.bootCount == bootCount) {
            stored.elapsedRealtimeNotBeforeMillis
        } else {
            val remainingMillis = remainingUntil(
                stored.wallNotBeforeMillis,
                currentTimeMillis(),
            )
            deadlineAfter(
                elapsedRealtimeMillis(),
                remainingMillis,
            )
        }
    }

    @Synchronized
    fun remainingMillis(): Long {
        val remainingMillis =
            remainingUntil(elapsedRealtimeNotBeforeMillis, elapsedRealtimeMillis())
        if (remainingMillis == 0L) {
            try {
                if (readState() != EMPTY_RETRY_DEADLINE_STATE) {
                    writeState(EMPTY_RETRY_DEADLINE_STATE)
                }
            } catch (_: Exception) {
                // Expiry is authoritative on this boot; stale fallback state may be retried later.
            }
        }
        return remainingMillis
    }

    @Synchronized
    fun deferFor(delayMillis: Long) {
        require(delayMillis >= 0)
        val elapsedNow = elapsedRealtimeMillis()
        elapsedRealtimeNotBeforeMillis = maxOf(
            elapsedRealtimeNotBeforeMillis,
            deadlineAfter(elapsedNow, delayMillis),
        )
        val updated = RelayRetryDeadlineState(
            wallNotBeforeMillis = deadlineAfter(
                currentTimeMillis(),
                remainingUntil(elapsedRealtimeNotBeforeMillis, elapsedNow),
            ),
            elapsedRealtimeNotBeforeMillis = elapsedRealtimeNotBeforeMillis,
            bootCount = bootCount,
        )
        if (updated != readState()) writeState(updated)
    }
}

internal data class RelayRetryDeadlineState(
    val wallNotBeforeMillis: Long = 0,
    val elapsedRealtimeNotBeforeMillis: Long = 0,
    val bootCount: Int? = null,
)

private val EMPTY_RETRY_DEADLINE_STATE = RelayRetryDeadlineState()

internal fun persistentRelayRetryDeadline(context: Context): RelayRetryDeadline {
    val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    fun readState() = RelayRetryDeadlineState(
        wallNotBeforeMillis = preferences.getLong(WALL_NOT_BEFORE_KEY, 0),
        elapsedRealtimeNotBeforeMillis = preferences.getLong(ELAPSED_NOT_BEFORE_KEY, 0),
        bootCount = if (preferences.contains(BOOT_COUNT_KEY)) {
            preferences.getInt(BOOT_COUNT_KEY, 0)
        } else {
            null
        },
    )
    return RelayRetryDeadline(
        readState = ::readState,
        writeState = { state ->
            val editor = preferences.edit()
                .putLong(WALL_NOT_BEFORE_KEY, state.wallNotBeforeMillis)
                .putLong(ELAPSED_NOT_BEFORE_KEY, state.elapsedRealtimeNotBeforeMillis)
            if (state.bootCount == null) {
                editor.remove(BOOT_COUNT_KEY)
            } else {
                editor.putInt(BOOT_COUNT_KEY, state.bootCount)
            }
            check(editor.commit()) { "Could not persist the relay retry deadline" }
        },
        bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull(),
        elapsedRealtimeMillis = SystemClock::elapsedRealtime,
    )
}

private fun remainingUntil(deadlineMillis: Long, nowMillis: Long): Long =
    if (deadlineMillis <= nowMillis) {
        0
    } else if (deadlineMillis - nowMillis < 0) {
        Long.MAX_VALUE
    } else {
        deadlineMillis - nowMillis
    }

private fun deadlineAfter(nowMillis: Long, delayMillis: Long): Long =
    (nowMillis + delayMillis).takeIf { it >= nowMillis } ?: Long.MAX_VALUE

private const val PREFERENCES_NAME = "agentknock_relay_retry"
private const val WALL_NOT_BEFORE_KEY = "wall_not_before_ms"
private const val ELAPSED_NOT_BEFORE_KEY = "elapsed_not_before_ms"
private const val BOOT_COUNT_KEY = "boot_count"
