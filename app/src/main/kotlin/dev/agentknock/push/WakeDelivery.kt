package dev.agentknock.push

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal enum class WakePriority {
    HIGH,
    NORMAL,
    REDUCED,
    UNKNOWN,
}

internal data class WakeDelivery(
    val receivedAt: Long,
    val delayMillis: Long?,
    val priority: WakePriority,
)

internal class WakeDeliveryStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("agentknock_wake_delivery", Context.MODE_PRIVATE)

    fun record(receivedAt: Long, sentAt: Long, priority: WakePriority) {
        preferences.edit {
            putLong("received_at", receivedAt)
            if (sentAt > 0 && sentAt <= receivedAt) putLong("delay_ms", receivedAt - sentAt)
            else remove("delay_ms")
            putString("priority", priority.name)
        }
    }

    fun read(): WakeDelivery? {
        if (!preferences.contains("received_at")) return null
        return WakeDelivery(
            preferences.getLong("received_at", 0),
            if (preferences.contains("delay_ms")) preferences.getLong("delay_ms", 0) else null,
            WakePriority.valueOf(checkNotNull(preferences.getString("priority", null))),
        )
    }

    fun observe() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(read())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
