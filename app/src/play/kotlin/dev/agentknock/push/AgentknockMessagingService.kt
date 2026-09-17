package dev.agentknock.push

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.agentknock.AgentknockApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class AgentknockMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        if ((application as AgentknockApplication).container.factoryResetInProgress) return
        PushRegistrationWorker.enqueue(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != WAKE_MESSAGE_TYPE) return
        requestSynchronization()
    }

    override fun onDeletedMessages() {
        requestSynchronization()
    }

    private fun requestSynchronization() {
        val container = (application as AgentknockApplication).container
        if (container.factoryResetInProgress) return

        // Usually the foreground socket already covers the wake. Announcing it is still required:
        // a visible session may have stopped on a terminal device result and needs new work to
        // make it eligible to connect again.
        container.requestConnection.requestSynchronization()
    }
}

class PushRegistrationWorker(
    applicationContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(applicationContext, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AgentknockApplication).container
        if (container.factoryResetInProgress) return Result.success()
        container.localStorage.await()
        if (container.factoryResetInProgress) return Result.success()
        // Resolve on every attempt so a retry cannot restore a queued, superseded FID.
        val firebaseInstallationId =
            try {
                FirebaseInstallations.getInstance().id.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Could not get the Firebase installation ID", failure)
                return Result.retry()
            }
        if (container.factoryResetInProgress) return Result.success()
        return when (val result = container.pushRegistration.register(firebaseInstallationId)) {
            PushRegistrationResult.Registered,
            PushRegistrationResult.NoDevice,
            PushRegistrationResult.DeviceCredentialsUnavailable,
            PushRegistrationResult.DeviceCredentialsCorrupted,
            PushRegistrationResult.UnsupportedDeviceCredentialEncryption -> Result.success()
            is PushRegistrationResult.RelayUnavailable -> {
                val detail = result.message?.let { ": $it" }.orEmpty()
                Log.w(TAG, "Relay unavailable while registering FCM$detail")
                Result.retry()
            }
            is PushRegistrationResult.RelayRejected -> {
                Log.w(
                    TAG,
                    "Relay rejected FCM registration (HTTP ${result.status}, ${result.code})",
                )
                if (result.needsAutomaticRetry()) Result.retry() else Result.failure()
            }
            PushRegistrationResult.InvalidRelayResponse -> {
                Log.w(TAG, "Relay returned an invalid FCM registration response")
                Result.failure()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "push-registration"
        private const val TAG = "AgentknockPush"

        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
        }
    }
}

/** Re-establishes the FCM installation registration when the relay reports it missing. */
class FirebaseRegistrationWorker(
    applicationContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(applicationContext, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AgentknockApplication).container
        if (container.factoryResetInProgress) return Result.success()
        return try {
            FirebaseMessaging.getInstance().register().await()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Could not register this installation with FCM", failure)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "firebase-registration"
        private const val TAG = "AgentknockPush"

        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<FirebaseRegistrationWorker>()
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
        }
    }
}

private const val WAKE_MESSAGE_TYPE = "wake"
