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
    private var onSuccess: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

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
        this.onSuccess = onSuccess
        this.onError = onError

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
            completeWithError("Set up a screen lock before managing secret values")
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
        val callback = onSuccess
        clearCallbacks()
        callback?.invoke()
    }

    private fun completeWithError(message: String) {
        val callback = onError
        clearCallbacks()
        callback?.invoke(message)
    }

    private fun clearCallbacks() {
        onSuccess = null
        onError = null
    }
}
