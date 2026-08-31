package dev.agentknock.ui.auth

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

internal class DeviceAuthenticator(
    private val activity: FragmentActivity,
) {
    private val attempt = AuthenticationAttempt()

    private val deviceCredentialLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            completeSuccessfully()
        } else {
            completeWithError("Authentication was cancelled")
        }
    }

    private val biometricPrompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ) {
                completeSuccessfully()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                completeWithError(errString.toString())
            }
        },
    )

    fun authenticate(
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!attempt.start(onSuccess, onError)) {
            onError("Another authentication is already in progress")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            authenticateWithBiometricPrompt(title)
        } else {
            authenticateWithDeviceCredential(title)
        }
    }

    private fun authenticateWithBiometricPrompt(title: String) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            completeWithError("Set up a screen lock or strong biometric authentication first")
            return
        }

        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(authenticators)
                .setConfirmationRequired(true)
                .build(),
        )
    }

    @Suppress("DEPRECATION")
    private fun authenticateWithDeviceCredential(title: String) {
        val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguardManager.isDeviceSecure) {
            completeWithError("Set up a screen lock before using device authentication")
            return
        }
        val intent = keyguardManager.createConfirmDeviceCredentialIntent(
            title,
            "Confirm your screen lock to continue",
        )
        if (intent == null) {
            completeWithError("Device authentication is unavailable")
            return
        }
        deviceCredentialLauncher.launch(intent)
    }

    private fun completeSuccessfully() {
        attempt.succeed()
    }

    private fun completeWithError(message: String) {
        attempt.fail(message)
    }
}

internal class AuthenticationAttempt {
    private var callbacks: Callbacks? = null

    fun start(onSuccess: () -> Unit, onError: (String) -> Unit): Boolean {
        if (callbacks != null) return false
        callbacks = Callbacks(onSuccess, onError)
        return true
    }

    fun succeed() {
        val current = callbacks ?: return
        callbacks = null
        current.onSuccess()
    }

    fun fail(message: String) {
        val current = callbacks ?: return
        callbacks = null
        current.onError(message)
    }

    private data class Callbacks(
        val onSuccess: () -> Unit,
        val onError: (String) -> Unit,
    )
}
