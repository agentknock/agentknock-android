package dev.agentknock.ui.auth

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DeviceAuthenticationMode(val storedName: String) {
    DEVICE_LOCK("device_lock"),
    SENSITIVE_VALUES_AND_PAIRING("sensitive_values_and_pairing"),
    APP_LOCK("app_lock"),
    ;

    companion object {
        val default = DEVICE_LOCK

        fun fromStoredName(storedName: String): DeviceAuthenticationMode =
            checkNotNull(entries.find { it.storedName == storedName }) {
                "Unknown device authentication mode"
            }
    }
}

internal fun DeviceAuthenticationMode.contentAvailable(authenticated: Boolean): Boolean =
    this != DeviceAuthenticationMode.APP_LOCK || authenticated

internal fun DeviceAuthenticationMode.protectedActionAvailable(authenticated: Boolean): Boolean =
    this == DeviceAuthenticationMode.DEVICE_LOCK || authenticated

internal class AuthenticationSession(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val _mode = MutableStateFlow(readMode())
    private val _authenticated = MutableStateFlow(false)
    private val lockSession = Runnable { _authenticated.value = false }

    val mode: StateFlow<DeviceAuthenticationMode> = _mode.asStateFlow()
    val authenticated: StateFlow<Boolean> = _authenticated.asStateFlow()

    fun authorizeProtectedAction(
        title: String,
        authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (_mode.value.protectedActionAvailable(_authenticated.value)) {
            onSuccess()
            return
        }
        authenticate(
            title,
            {
                _authenticated.value = true
                onSuccess()
            },
            onError,
        )
    }

    fun changeMode(
        newMode: DeviceAuthenticationMode,
        authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (newMode == _mode.value) return
        val applyChange = {
            preferences.edit().putString(MODE_KEY, newMode.storedName).apply()
            _mode.value = newMode
            _authenticated.value = newMode != DeviceAuthenticationMode.DEVICE_LOCK
        }
        if (_authenticated.value) {
            applyChange()
        } else {
            authenticate(
                "Change device authentication",
                {
                    _authenticated.value = true
                    applyChange()
                },
                onError,
            )
        }
    }

    fun onForeground() {
        handler.removeCallbacks(lockSession)
    }

    fun onBackground() {
        handler.removeCallbacks(lockSession)
        handler.postDelayed(lockSession, BACKGROUND_GRACE_MILLIS)
    }

    fun reset() {
        handler.removeCallbacks(lockSession)
        preferences.edit().remove(MODE_KEY).apply()
        _mode.value = DeviceAuthenticationMode.default
        _authenticated.value = false
    }

    private fun readMode(): DeviceAuthenticationMode {
        val storedName = preferences.getString(MODE_KEY, null)
            ?: return DeviceAuthenticationMode.default
        return DeviceAuthenticationMode.fromStoredName(storedName)
    }

    private companion object {
        const val PREFERENCES_NAME = "agentknock_authentication"
        const val MODE_KEY = "mode"
        const val BACKGROUND_GRACE_MILLIS = 15_000L
    }
}
