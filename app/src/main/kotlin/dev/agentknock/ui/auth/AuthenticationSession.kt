package dev.agentknock.ui.auth

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DeviceAuthenticationMode(val storedName: String) {
    DEVICE_LOCK("device_lock"),
    SENSITIVE_VALUES_AND_PAIRING("sensitive_values_and_pairing"),
    APP_LOCK("app_lock");

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

    suspend fun authorizeProtectedAction(
        title: String,
        authenticate: suspend (String) -> DeviceAuthenticationResult,
    ): DeviceAuthenticationResult {
        if (_mode.value.protectedActionAvailable(_authenticated.value)) {
            return DeviceAuthenticationResult.Success
        }
        return authenticate(title).also { result ->
            if (result == DeviceAuthenticationResult.Success) _authenticated.value = true
        }
    }

    suspend fun changeMode(
        newMode: DeviceAuthenticationMode,
        authenticate: suspend (String) -> DeviceAuthenticationResult,
    ): DeviceAuthenticationResult {
        if (newMode == _mode.value) return DeviceAuthenticationResult.Success
        val applyChange = {
            preferences.edit { putString(MODE_KEY, newMode.storedName) }
            _mode.value = newMode
            _authenticated.value = newMode != DeviceAuthenticationMode.DEVICE_LOCK
        }
        if (_authenticated.value) {
            applyChange()
            return DeviceAuthenticationResult.Success
        }
        val result = authenticate("Change device authentication")
        if (result == DeviceAuthenticationResult.Success) {
            _authenticated.value = true
            applyChange()
        }
        return result
    }

    fun onForeground() {
        handler.removeCallbacks(lockSession)
    }

    fun onBackground() {
        handler.removeCallbacks(lockSession)
        handler.postDelayed(lockSession, AUTHENTICATION_BACKGROUND_GRACE_MILLIS)
    }

    private fun readMode(): DeviceAuthenticationMode {
        val storedName =
            preferences.getString(MODE_KEY, null) ?: return DeviceAuthenticationMode.default
        return DeviceAuthenticationMode.fromStoredName(storedName)
    }

    private companion object {
        const val PREFERENCES_NAME = "agentknock_authentication"
        const val MODE_KEY = "mode"
    }
}

internal const val AUTHENTICATION_BACKGROUND_GRACE_MILLIS = 15_000L

internal class ProtectedActionAuthorizer(
    private val session: AuthenticationSession,
    private val deviceAuthentication: DeviceAuthenticationCoordinator,
) {
    suspend fun authorize(title: String): DeviceAuthenticationResult =
        session.authorizeProtectedAction(title, deviceAuthentication::authenticate)

    suspend fun changeMode(mode: DeviceAuthenticationMode): DeviceAuthenticationResult =
        session.changeMode(mode, deviceAuthentication::authenticate)
}
