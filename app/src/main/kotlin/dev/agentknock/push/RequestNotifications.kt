package dev.agentknock.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dev.agentknock.AgentknockApplication
import dev.agentknock.MainActivity
import dev.agentknock.R
import dev.agentknock.storage.request.RequestDecision
import dev.agentknock.storage.request.RequestNotification
import dev.agentknock.storage.request.RequestNotificationDetail
import dev.agentknock.storage.request.RequestSyncResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PushSynchronizationWorker(
    applicationContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(applicationContext, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            RequestNotifications.FOREGROUND_NOTIFICATION_ID,
            RequestNotifications.processingNotification(
                applicationContext,
                RequestProcessingState.PROCESSING,
            ),
        )

    override suspend fun doWork(): Result {
        val container = (applicationContext as AgentknockApplication).container
        val retryDelay = inputData.getLong(RETRY_DELAY_KEY, INITIAL_RETRY_DELAY_MILLIS)
        if (container.factoryResetInProgress) return Result.success()
        return container.processingNotifications.start().use { processing ->
            container.localStorage.await()
            if (container.factoryResetInProgress) return Result.success()
            when (
                val synchronization =
                    container.requestConnection.synchronizeOnce(processing::setProcessing)
            ) {
                dev.agentknock.storage.request.OneShotSynchronizationResult.Covered ->
                    Result.success()
                is dev.agentknock.storage.request.OneShotSynchronizationResult.Deferred ->
                    enqueueRetry(applicationContext, synchronization.retryAfterMillis, retryDelay)
                is dev.agentknock.storage.request.OneShotSynchronizationResult.Completed ->
                    when (synchronization.result) {
                        RequestSyncResult.ContinuationRequired -> {
                            container.requestNotifications.reconcile()
                            enqueueSynchronization(applicationContext)
                        }
                        RequestSyncResult.Success,
                        RequestSyncResult.NoDevice,
                        RequestSyncResult.DeviceCredentialsUnavailable,
                        RequestSyncResult.DeviceCredentialsCorrupted,
                        RequestSyncResult.UnsupportedDeviceCredentialEncryption -> {
                            container.requestNotifications.reconcile()
                            Result.success()
                        }
                        is RequestSyncResult.RelayUnavailable -> {
                            synchronization.result.retryAfterMillis
                                ?.takeIf { it > 0 }
                                ?.let { enqueueRetry(applicationContext, it, retryDelay) }
                                ?: enqueueRetry(
                                    applicationContext,
                                    retryDelay,
                                    nextSynchronizationRetryDelayMillis(retryDelay),
                                )
                        }
                        is RequestSyncResult.InternalFailure -> {
                            Log.e(
                                TAG,
                                "Request synchronization stopped after an internal " +
                                    synchronization.result.type,
                            )
                            // Keep later pushes eligible after a transient local failure.
                            Result.success()
                        }
                        is RequestSyncResult.RelayRejected -> {
                            // The manager exposes the rejection. Keep later pushes eligible.
                            Result.success()
                        }
                    }
            }
        }
    }

    companion object {
        private const val WORK_NAME = "push-synchronization"
        private const val DEADLINE_WORK_NAME = "push-synchronization-deadline"
        private const val TAG = "AgentknockPush"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    workRequest(),
                )
        }

        internal suspend fun enqueueAndAwait(
            context: Context,
            retryDelayMillis: Long = INITIAL_RETRY_DELAY_MILLIS,
        ) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    workRequest(retryDelayMillis),
                )
                .await()
        }

        private fun workRequest(retryDelayMillis: Long = INITIAL_RETRY_DELAY_MILLIS) =
            OneTimeWorkRequestBuilder<PushSynchronizationWorker>()
                .setInputData(workDataOf(RETRY_DELAY_KEY to retryDelayMillis))
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        internal suspend fun enqueueRetry(
            context: Context,
            delayMillis: Long,
            retryDelayMillis: Long,
        ): Result {
            val request =
                OneTimeWorkRequestBuilder<RelayRetryWorker>()
                    .setInputData(workDataOf(RETRY_DELAY_KEY to retryDelayMillis))
                    .setInitialDelay(
                        deadlineRetryWorkDelayMillis(delayMillis),
                        TimeUnit.MILLISECONDS,
                    )
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
            return try {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        DEADLINE_WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
                    .await()
                Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Could not schedule the relay retry", failure)
                Result.retry()
            }
        }
    }
}

class RelayRetryWorker(
    applicationContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(applicationContext, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AgentknockApplication).container
        if (container.factoryResetInProgress) return Result.success()
        return enqueueSynchronization(
            applicationContext,
            inputData.getLong(RETRY_DELAY_KEY, INITIAL_RETRY_DELAY_MILLIS),
        )
    }
}

private suspend fun enqueueSynchronization(
    context: Context,
    retryDelayMillis: Long = INITIAL_RETRY_DELAY_MILLIS,
): ListenableWorker.Result =
    try {
        PushSynchronizationWorker.enqueueAndAwait(context, retryDelayMillis)
        ListenableWorker.Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w("AgentknockPush", "Could not enqueue relay synchronization", failure)
        ListenableWorker.Result.retry()
    }

private const val RETRY_DELAY_KEY = "synchronization_retry_delay_ms"
private const val INITIAL_RETRY_DELAY_MILLIS = 10_000L

internal fun nextSynchronizationRetryDelayMillis(previousMillis: Long): Long =
    previousMillis.coerceAtMost(TimeUnit.HOURS.toMillis(5) / 2) * 2

internal fun deadlineRetryWorkDelayMillis(remainingMillis: Long): Long {
    require(remainingMillis > 0)
    return minOf(remainingMillis, MAXIMUM_DEADLINE_WORK_DELAY_MILLIS)
}

private val MAXIMUM_DEADLINE_WORK_DELAY_MILLIS = TimeUnit.HOURS.toMillis(24)

internal object RequestNotifications {
    const val OPEN_REQUESTS_ACTION = "dev.agentknock.action.OPEN_REQUESTS"
    const val OPEN_REQUEST_ACTION = "dev.agentknock.action.OPEN_REQUEST"
    const val DECIDE_REQUEST_ACTION = "dev.agentknock.action.DECIDE_REQUEST"
    const val REQUEST_ID_EXTRA = "request_id"
    const val DECISION_EXTRA = "decision"
    const val APPROVE_DECISION = "approve"
    const val DENY_DECISION = "deny"

    const val ACTION_CHANNEL_ID = "requests"
    const val PROCESSING_CHANNEL_ID = "request_processing"
    private const val PROCESSING_NOTIFICATION_ID = 1
    const val FOREGROUND_NOTIFICATION_ID = 2
    private const val REQUEST_NOTIFICATION_ID = 10_000
    private const val OPEN_INTENT_ACTION = "open"
    private const val REQUEST_INTENT_SCHEME = "agentknock-request"

    fun createChannel(context: Context) {
        val actionChannel =
            NotificationChannel(
                    ACTION_CHANNEL_ID,
                    context.getString(R.string.request_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply {
                    description =
                        context.getString(R.string.request_notification_channel_description)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
        val processingChannel =
            NotificationChannel(
                    PROCESSING_CHANNEL_ID,
                    context.getString(R.string.processing_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description =
                        context.getString(R.string.processing_notification_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(actionChannel, processingChannel))
        manager.deleteNotificationChannel("background_processing")
    }

    fun appNotificationsEnabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun actionNotificationsEnabled(context: Context): Boolean =
        channelNotificationsEnabled(context, ACTION_CHANNEL_ID)

    fun channelNotificationsEnabled(context: Context, channelId: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(channelId)?.importance !=
                NotificationManager.IMPORTANCE_NONE
    }

    /** Removes transient status left by a previous process. */
    fun clearProcessing(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(PROCESSING_NOTIFICATION_ID)
        manager.cancel(FOREGROUND_NOTIFICATION_ID)
    }

    fun showProcessing(context: Context, state: RequestProcessingState?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (state != RequestProcessingState.PROCESSING) {
                manager.cancel(PROCESSING_NOTIFICATION_ID)
                return
            }
        } else if (state == null) {
            // WorkManager owns the foreground notification until its service stops.
            return
        }
        if (!channelNotificationsEnabled(context, PROCESSING_CHANNEL_ID)) return
        val notificationId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PROCESSING_NOTIFICATION_ID
            else FOREGROUND_NOTIFICATION_ID
        manager.notify(notificationId, processingNotification(context, checkNotNull(state)))
    }

    fun processingNotification(context: Context, state: RequestProcessingState): Notification {
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    action = OPEN_REQUESTS_ACTION
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(context, PROCESSING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(
                context.getString(
                    when (state) {
                        RequestProcessingState.PROCESSING -> R.string.processing_requests
                        RequestProcessingState.LISTENING -> R.string.listening_for_requests
                    }
                )
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    fun showRequests(context: Context, requests: List<RequestNotification>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!canNotify(context)) return
        val activeTags = requests.mapTo(mutableSetOf(), RequestNotification::requestId)
        manager.activeNotifications
            .filter {
                it.id == REQUEST_NOTIFICATION_ID && it.tag != null && it.tag !in activeTags
            }
            .forEach { manager.cancel(it.tag, REQUEST_NOTIFICATION_ID) }
        requests.forEach { request ->
            val openRequest = openRequestPendingIntent(context, request.requestId)
            val publicVersion =
                Notification.Builder(context, ACTION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(context.getColor(R.color.notification_accent))
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.request_waiting))
                    .setContentIntent(openRequest)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .build()
            val builder =
                Notification.Builder(context, ACTION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(context.getColor(R.color.notification_accent))
                    .setContentTitle(request.title)
                    .setSubText(request.kindLabel)
                    .setContentText(request.summary)
                    .setStyle(Notification.BigTextStyle().bigText(styledDetails(request.details)))
                    .setContentIntent(openRequest)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion)
            if (request.decisionAvailable) {
                builder.addAction(
                    decisionAction(context, request.requestId, DENY_DECISION, "Deny once")
                )
                builder.addAction(
                    decisionAction(context, request.requestId, APPROVE_DECISION, "Allow once")
                )
            }
            manager.notify(request.requestId, REQUEST_NOTIFICATION_ID, builder.build())
        }
    }

    private fun decisionAction(
        context: Context,
        requestId: String,
        decision: String,
        title: String,
    ): Notification.Action {
        if (decision == APPROVE_DECISION && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val openRequest = openRequestPendingIntent(context, requestId, decision)
            return Notification.Action.Builder(null, title, openRequest).build()
        }
        val intent = decisionPendingIntent(context, requestId, decision)
        return Notification.Action.Builder(null, title, intent)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAuthenticationRequired(true)
                }
            }
            .build()
    }

    internal fun openRequestPendingIntent(
        context: Context,
        requestId: String,
        intentAction: String = OPEN_INTENT_ACTION,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                action = OPEN_REQUEST_ACTION
                data = requestIntentData(requestId, intentAction)
                putExtra(REQUEST_ID_EXTRA, requestId)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun decisionPendingIntent(
        context: Context,
        requestId: String,
        decision: String,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RequestNotificationActionReceiver::class.java).apply {
                action = DECIDE_REQUEST_ACTION
                data = requestIntentData(requestId, decision)
                putExtra(REQUEST_ID_EXTRA, requestId)
                putExtra(DECISION_EXTRA, decision)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun requestIntentData(requestId: String, intentAction: String): Uri =
        Uri.fromParts(
            REQUEST_INTENT_SCHEME,
            "$requestId/$intentAction",
            null,
        )

    private fun styledDetails(details: List<RequestNotificationDetail>): CharSequence =
        SpannableStringBuilder().apply {
            details.forEachIndexed { index, detail ->
                if (index > 0) {
                    val besideCommand =
                        detail.label == "Command" || details[index - 1].label == "Command"
                    append(if (besideCommand) "\n\n" else "\n")
                }
                val start = length
                if (detail.label == "Command") {
                    append(detail.value)
                    setSpan(
                        TypefaceSpan("monospace"),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                } else {
                    detail.label?.let { append(it).append(": ") }
                    append(detail.value)
                }
            }
        }

    private fun canNotify(context: Context): Boolean =
        actionNotificationsEnabled(context) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
}

class RequestNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RequestNotifications.DECIDE_REQUEST_ACTION) return
        val requestId = intent.getStringExtra(RequestNotifications.REQUEST_ID_EXTRA) ?: return
        val decision = intent.getStringExtra(RequestNotifications.DECISION_EXTRA) ?: return
        val pendingResult = goAsync()
        val application = context.applicationContext as AgentknockApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                application.container.requestNotifications.performAction {
                    when (decision) {
                        RequestNotifications.APPROVE_DECISION ->
                            application.container.actions.decideRequest(
                                requestId,
                                RequestDecision.APPROVE,
                            )
                        RequestNotifications.DENY_DECISION ->
                            application.container.actions.decideRequest(
                                requestId,
                                RequestDecision.DENY,
                            )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal fun networkConstraints(): Constraints =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
