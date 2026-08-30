package dev.agentknock.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
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
import dev.agentknock.AgentknockApplication
import dev.agentknock.MainActivity
import dev.agentknock.R
import dev.agentknock.storage.request.RequestSyncResult
import dev.agentknock.storage.request.RequestNotification
import dev.agentknock.storage.request.RequestNotificationDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class AgentknockMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        PushRegistrationWorker.enqueue(this, installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != WAKE_MESSAGE_TYPE) return

        if (
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                Lifecycle.State.STARTED,
            )
        ) {
            // The foreground websocket already receives this request. Restarting it here can
            // cancel work that the websocket started, including an in-flight AI review.
            return
        }
        RequestNotifications.showWake(this)
        PushSynchronizationWorker.enqueue(this)
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
        val container = (applicationContext as AgentknockApplication).container
        container.localStorage.await()
        return when (val result = container.pushRegistration.register(firebaseInstallationId)) {
            PushRegistrationResult.Registered,
            PushRegistrationResult.NoDevice,
            PushRegistrationResult.DeviceCredentialsUnavailable,
            PushRegistrationResult.DeviceCredentialsCorrupted,
            PushRegistrationResult.UnsupportedDeviceCredentialEncryption,
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
        private const val TAG = "AgentknockPush"

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
        val container = (applicationContext as AgentknockApplication).container
        container.localStorage.await()
        return when (container.requests.sync()) {
            RequestSyncResult.Success,
            RequestSyncResult.NoDevice,
            RequestSyncResult.DeviceCredentialsUnavailable,
            RequestSyncResult.DeviceCredentialsCorrupted,
            RequestSyncResult.UnsupportedDeviceCredentialEncryption,
            -> {
                RequestNotifications.showRequests(
                    applicationContext,
                    container.requests.pendingNotifications(),
                )
                Result.success()
            }
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
    const val OPEN_REQUEST_ACTION = "dev.agentknock.action.OPEN_REQUEST"
    const val DECIDE_REQUEST_ACTION = "dev.agentknock.action.DECIDE_REQUEST"
    const val REQUEST_ID_EXTRA = "request_id"
    const val DECISION_EXTRA = "decision"
    const val APPROVE_DECISION = "approve"
    const val DENY_DECISION = "deny"

    const val ACTION_CHANNEL_ID = "requests"
    const val BACKGROUND_CHANNEL_ID = "background_processing"
    private const val WAKE_NOTIFICATION_ID = 1
    private const val REQUEST_NOTIFICATION_ID_BASE = 10_000

    fun createChannel(context: Context) {
        val actionChannel = NotificationChannel(
            ACTION_CHANNEL_ID,
            context.getString(R.string.request_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.request_notification_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val backgroundChannel = NotificationChannel(
            BACKGROUND_CHANNEL_ID,
            context.getString(R.string.background_notification_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.background_notification_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(actionChannel, backgroundChannel),
        )
    }

    fun appNotificationsEnabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun actionNotificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(ACTION_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
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
        val notification = Notification.Builder(context, BACKGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.request_waiting))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(WAKE_NOTIFICATION_ID, notification)
    }

    fun showRequests(context: Context, requests: List<RequestNotification>) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(WAKE_NOTIFICATION_ID)
        val activeIds = requests.mapTo(mutableSetOf()) { notificationId(it.requestId) }
        manager.activeNotifications
            .filter { it.id >= REQUEST_NOTIFICATION_ID_BASE && it.id !in activeIds }
            .forEach { manager.cancel(it.id) }
        requests.forEach { request ->
            val openRequest = PendingIntent.getActivity(
                context,
                request.requestId.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    action = OPEN_REQUEST_ACTION
                    putExtra(REQUEST_ID_EXTRA, request.requestId)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val publicVersion = Notification.Builder(context, ACTION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.notification_accent))
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.request_waiting))
                .setContentIntent(openRequest)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build()
            val builder = Notification.Builder(context, ACTION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.notification_accent))
                .setContentTitle(request.title)
                .setContentText(request.summary)
                .setStyle(Notification.BigTextStyle().bigText(styledDetails(request.details)))
                .setContentIntent(openRequest)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
            if (request.decisionAvailable) {
                builder.addAction(decisionAction(context, request.requestId, DENY_DECISION, "Deny once"))
                builder.addAction(
                    decisionAction(context, request.requestId, APPROVE_DECISION, "Approve once"),
                )
            }
            manager.notify(notificationId(request.requestId), builder.build())
        }
    }

    private fun decisionAction(
        context: Context,
        requestId: Long,
        decision: String,
        title: String,
    ): Notification.Action {
        if (decision == APPROVE_DECISION && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val openRequest = PendingIntent.getActivity(
                context,
                requestId.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    action = OPEN_REQUEST_ACTION
                    putExtra(REQUEST_ID_EXTRA, requestId)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Action.Builder(null, title, openRequest).build()
        }
        val intent = PendingIntent.getBroadcast(
            context,
            (requestId.hashCode() * 31) + decision.hashCode(),
            Intent(context, RequestNotificationActionReceiver::class.java).apply {
                action = DECIDE_REQUEST_ACTION
                putExtra(REQUEST_ID_EXTRA, requestId)
                putExtra(DECISION_EXTRA, decision)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, title, intent).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAuthenticationRequired(true)
            }
        }.build()
    }

    private fun notificationId(requestId: Long): Int =
        REQUEST_NOTIFICATION_ID_BASE + (requestId.hashCode() and 0x1fffffff)

    private fun styledDetails(details: List<RequestNotificationDetail>): CharSequence =
        SpannableStringBuilder().apply {
            details.forEachIndexed { index, detail ->
                if (index > 0) append('\n')
                detail.label?.let { label ->
                    val start = length
                    append(label)
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    append(": ")
                }
                append(detail.value)
            }
        }

    private fun canNotify(context: Context): Boolean = actionNotificationsEnabled(context) &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
}

class RequestNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RequestNotifications.DECIDE_REQUEST_ACTION) return
        val requestId = intent.getLongExtra(RequestNotifications.REQUEST_ID_EXTRA, -1L)
        if (requestId < 0) return
        val decision = intent.getStringExtra(RequestNotifications.DECISION_EXTRA) ?: return
        val pendingResult = goAsync()
        val application = context.applicationContext as AgentknockApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                application.container.localStorage.await()
                when (decision) {
                    RequestNotifications.APPROVE_DECISION ->
                        application.container.requests.approvePendingRequest(requestId)
                    RequestNotifications.DENY_DECISION ->
                        application.container.requests.denyPendingRequest(requestId)
                    else -> return@launch
                }
                RequestNotifications.showRequests(
                    context,
                    application.container.requests.pendingNotifications(),
                )
                PushSynchronizationWorker.enqueue(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun networkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

private const val WAKE_MESSAGE_TYPE = "wake"
