package dev.agentknock.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.agentknock.AgentKnockApplication
import dev.agentknock.MainActivity
import dev.agentknock.R
import dev.agentknock.storage.request.RequestSyncResult
import java.util.concurrent.TimeUnit

class AgentKnockMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        PushRegistrationWorker.enqueue(this, installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != WAKE_MESSAGE_TYPE) return

        RequestNotifications.showWake(this)
        val application = application as AgentKnockApplication
        if (
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                Lifecycle.State.STARTED,
            )
        ) {
            application.container.requestConnection.refresh()
        } else {
            PushSynchronizationWorker.enqueue(this)
        }
    }
}

class PushRegistrationWorker(
    applicationContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(applicationContext, parameters) {
    override suspend fun doWork(): Result {
        val firebaseInstallationId = inputData.getString(FIREBASE_INSTALLATION_ID_KEY)
            ?.takeIf(String::isNotEmpty)
            ?: return Result.failure()
        val container = (applicationContext as AgentKnockApplication).container
        container.localStorage.await()
        return when (val result = container.pushRegistration.register(firebaseInstallationId)) {
            PushRegistrationResult.Registered,
            PushRegistrationResult.NoVault,
            PushRegistrationResult.VaultSecretsUnavailable,
            PushRegistrationResult.VaultSecretsCorrupted,
            PushRegistrationResult.UnsupportedVaultEncryption,
            -> Result.success()
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
                Result.failure()
            }
            PushRegistrationResult.InvalidRelayResponse -> {
                Log.w(TAG, "Relay returned an invalid FCM registration response")
                Result.failure()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "push-registration"
        private const val FIREBASE_INSTALLATION_ID_KEY = "firebase-installation-id"
        private const val TAG = "AgentKnockPush"

        fun enqueue(context: Context, firebaseInstallationId: String) {
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setInputData(
                    workDataOf(FIREBASE_INSTALLATION_ID_KEY to firebaseInstallationId),
                )
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

class PushSynchronizationWorker(
    applicationContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(applicationContext, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AgentKnockApplication).container
        container.localStorage.await()
        return when (container.requests.sync()) {
            RequestSyncResult.Success,
            RequestSyncResult.NoVault,
            RequestSyncResult.VaultSecretsUnavailable,
            RequestSyncResult.VaultSecretsCorrupted,
            RequestSyncResult.UnsupportedVaultEncryption,
            -> Result.success()
            is RequestSyncResult.RelayUnavailable,
            RequestSyncResult.InvalidRelayResponse,
            -> Result.retry()
            is RequestSyncResult.RelayRejected -> Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "push-synchronization"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushSynchronizationWorker>()
                .setConstraints(networkConstraints())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

internal object RequestNotifications {
    const val OPEN_REQUESTS_ACTION = "dev.agentknock.action.OPEN_REQUESTS"

    private const val CHANNEL_ID = "requests"
    private const val NOTIFICATION_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.request_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.request_notification_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun showWake(context: Context) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                action = OPEN_REQUESTS_ACTION
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.request_waiting))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}

private fun networkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

private const val WAKE_MESSAGE_TYPE = "wake"
