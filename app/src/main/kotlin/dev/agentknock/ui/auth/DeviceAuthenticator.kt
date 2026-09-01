package dev.agentknock.ui.auth

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DeviceAuthenticator(
    private val activity: FragmentActivity,
    private val authentication: DeviceAuthenticationCoordinator,
) {
    private var biometricRequestId: Long? = null
    private var biometricPrompt: BiometricPrompt? = null
    private var legacyRequestId: Long? = null

    private val deviceCredentialLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val requestId = legacyRequestId ?: return@registerForActivityResult
        legacyRequestId = null
        if (result.resultCode == Activity.RESULT_OK) {
            completeSuccessfully(requestId)
        } else {
            completeWithError(requestId, "Authentication was cancelled")
        }
    }

    fun bind(request: DeviceAuthenticationRequest?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (request != null && legacyRequestId == null) legacyRequestId = request.id
            return
        }
        if (request == null) {
            biometricPrompt?.cancelAuthentication()
            biometricPrompt = null
            biometricRequestId = null
        } else if (biometricRequestId != request.id) {
            biometricPrompt?.cancelAuthentication()
            biometricRequestId = request.id
            biometricPrompt = biometricPrompt(request.id)
        }
    }

    fun launch(request: DeviceAuthenticationRequest) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bind(request)
                authenticateWithBiometricPrompt(request)
            } else {
                if (legacyRequestId != request.id) {
                    completeWithError(
                        request.id,
                        "A previous device authentication is still finishing",
                    )
                    return
                }
                authenticateWithDeviceCredential(request)
            }
        } catch (failure: RuntimeException) {
            completeWithError(
                request.id,
                failure.message ?: "Device authentication is unavailable",
            )
        }
    }

    private fun biometricPrompt(requestId: Long): BiometricPrompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ) {
                completeSuccessfully(requestId)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                completeWithError(requestId, errString.toString())
            }
        },
    )

    private fun authenticateWithBiometricPrompt(request: DeviceAuthenticationRequest) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            completeWithError(
                request.id,
                "Set up a screen lock or strong biometric authentication first",
            )
            return
        }

        val prompt = biometricPrompt.takeIf { biometricRequestId == request.id }
        if (prompt == null) {
            completeWithError(request.id, "Device authentication is unavailable")
            return
        }
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(request.title)
                .setAllowedAuthenticators(authenticators)
                .setConfirmationRequired(true)
                .build(),
        )
    }

    @Suppress("DEPRECATION")
    private fun authenticateWithDeviceCredential(request: DeviceAuthenticationRequest) {
        val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguardManager.isDeviceSecure) {
            completeWithError(
                request.id,
                "Set up a screen lock before using device authentication",
            )
            return
        }
        val intent = keyguardManager.createConfirmDeviceCredentialIntent(
            request.title,
            "Confirm your screen lock to continue",
        )
        if (intent == null) {
            completeWithError(request.id, "Device authentication is unavailable")
            return
        }
        deviceCredentialLauncher.launch(intent)
    }

    private fun completeSuccessfully(requestId: Long) {
        if (legacyRequestId == requestId) legacyRequestId = null
        authentication.succeed(requestId)
    }

    private fun completeWithError(requestId: Long, message: String) {
        if (legacyRequestId == requestId) legacyRequestId = null
        authentication.fail(requestId, message)
    }
}

internal data class DeviceAuthenticationRequest(
    val id: Long,
    val title: String,
)

internal sealed interface DeviceAuthenticationResult {
    data object Success : DeviceAuthenticationResult
    data class Error(val message: String) : DeviceAuthenticationResult
}

/** Process-local rendezvous between feature ViewModels and the current Activity host. */
internal class DeviceAuthenticationCoordinator {
    private data class Pending(
        val request: DeviceAuthenticationRequest,
        val result: CompletableDeferred<DeviceAuthenticationResult>,
        var claimedByHost: Boolean = false,
    )

    private var nextRequestId = 0L
    private var pending: Pending? = null
    private val _request = MutableStateFlow<DeviceAuthenticationRequest?>(null)

    val request: StateFlow<DeviceAuthenticationRequest?> = _request.asStateFlow()

    suspend fun authenticate(title: String): DeviceAuthenticationResult {
        if (pending != null) {
            return DeviceAuthenticationResult.Error(
                "Another authentication is already in progress",
            )
        }
        val attempt = Pending(
            request = DeviceAuthenticationRequest(
                id = ++nextRequestId,
                title = title,
            ),
            result = CompletableDeferred(),
        )
        pending = attempt
        _request.value = attempt.request
        return try {
            attempt.result.await()
        } finally {
            abandon(attempt)
        }
    }

    fun claimForLaunch(request: DeviceAuthenticationRequest): Boolean {
        val current = pending ?: return false
        if (current.request != request || current.claimedByHost) return false
        current.claimedByHost = true
        return true
    }

    fun succeed(requestId: Long) = complete(requestId, DeviceAuthenticationResult.Success)

    fun fail(requestId: Long, message: String) =
        complete(requestId, DeviceAuthenticationResult.Error(message))

    private fun abandon(attempt: Pending) {
        if (pending !== attempt) return
        pending = null
        _request.value = null
    }

    private fun complete(requestId: Long, result: DeviceAuthenticationResult) {
        val current = pending ?: return
        if (current.request.id != requestId) return
        pending = null
        _request.value = null
        current.result.complete(result)
    }
}

internal class SensitiveDataBackgroundGuard(
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val graceMillis: Long = AUTHENTICATION_BACKGROUND_GRACE_MILLIS,
) {
    private var deferredAt: Long? = null

    fun onStop(authenticationInProgress: Boolean): Boolean {
        deferredAt = elapsedRealtimeMillis().takeIf { authenticationInProgress }
        return !authenticationInProgress
    }

    fun onAuthenticationChanged(inProgress: Boolean, foreground: Boolean): Boolean {
        val stoppedAt = deferredAt
        if (inProgress || stoppedAt == null) return false
        deferredAt = null
        val now = elapsedRealtimeMillis()
        val graceExpired = now >= stoppedAt && now - stoppedAt >= graceMillis
        return !foreground || graceExpired
    }
}
