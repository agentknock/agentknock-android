package dev.agentknock.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.request.RequestNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestProcessingNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun prepare() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation
                .executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                )
                .use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
                }
        }
        RequestNotifications.createChannel(context)
        RequestNotifications.clearProcessing(context)
    }

    @After
    fun cleanUp() {
        RequestNotifications.clearProcessing(context)
        RequestNotifications.showRequests(context, emptyList())
    }

    @Test
    fun manualRequestUpdatesCannotCancelProcessingAndIdleUsesThePlatformAppropriateNotice() {
        val coordinator = RequestProcessingCoordinator {
            RequestNotifications.showProcessing(context, it)
        }
        coordinator.start().use { session ->
            awaitNotifications { processingNotification() != null }
            RequestNotifications.showRequests(context, emptyList())
            assertNotNull(processingNotification())

            val manual =
                RequestNotification(
                    requestId = "notification-lifecycle-test",
                    title = "Approval needed",
                    summary = "A command needs your approval",
                    details = emptyList(),
                    decisionAvailable = true,
                )
            RequestNotifications.showRequests(context, listOf(manual))
            awaitNotifications { manager.activeNotifications.any { it.tag == manual.requestId } }
            assertNotNull(processingNotification())
            assertEquals(1, manager.activeNotifications.count { it.tag == manual.requestId })
            val notification = checkNotNull(processingNotification())
            assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            val channel = manager.getNotificationChannel(RequestNotifications.PROCESSING_CHANNEL_ID)
            assertEquals(null, channel.sound)
            assertFalse(channel.shouldVibrate())
            assertFalse(channel.canShowBadge())
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)

            session.setProcessing(false)
            if (Build.VERSION.SDK_INT >= 31) {
                awaitNotifications { processingNotification() == null }
                assertEquals(null, processingNotification())
            } else {
                // Pre-31 expedited WorkManager execution requires a foreground notification
                // for the whole worker, including the brief connection-reuse window.
                assertNotNull(processingNotification())
            }
            assertEquals(1, manager.activeNotifications.count { it.tag == manual.requestId })

            session.setProcessing(true)
            awaitNotifications { processingNotification() != null }
            assertNotNull(processingNotification())
        }
        if (Build.VERSION.SDK_INT >= 31) awaitNotifications { processingNotification() == null }
    }

    @Test
    fun channelUpgradeReplacesOldProcessingSettingsWithoutChangingApprovalAlerts() {
        manager.createNotificationChannel(
            NotificationChannel(
                "background_processing",
                "Background processing",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val approvals = manager.getNotificationChannel(RequestNotifications.ACTION_CHANNEL_ID)

        RequestNotifications.createChannel(context)

        assertEquals(null, manager.getNotificationChannel("background_processing"))
        val processing = manager.getNotificationChannel(RequestNotifications.PROCESSING_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, processing.importance)
        assertEquals(null, processing.sound)
        assertFalse(processing.shouldVibrate())
        assertFalse(processing.canShowBadge())
        assertEquals(
            approvals,
            manager.getNotificationChannel(RequestNotifications.ACTION_CHANNEL_ID),
        )
    }

    @Test
    fun processStartupClearsOrphanedProcessingWithoutRemovingManualRequests() {
        val manual =
            RequestNotification(
                requestId = "notification-recovery-test",
                title = "Approval needed",
                summary = "A command needs your approval",
                details = emptyList(),
                decisionAvailable = true,
            )
        RequestNotifications.showRequests(context, listOf(manual))
        RequestNotifications.showProcessing(context, RequestProcessingState.PROCESSING)
        awaitNotifications {
            processingNotification() != null &&
                manager.activeNotifications.any { it.tag == manual.requestId }
        }

        RequestNotifications.clearProcessing(context)
        awaitNotifications { processingNotification() == null }

        assertEquals(null, processingNotification())
        assertEquals(1, manager.activeNotifications.count { it.tag == manual.requestId })
    }

    private fun awaitNotifications(condition: () -> Boolean) {
        // NotificationManager enqueues changes in the system process; posting is asynchronous.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Notification update did not arrive" }
            Thread.sleep(10)
        }
    }

    private fun processingNotification(): Notification? =
        manager.activeNotifications
            .singleOrNull {
                it.notification.channelId == RequestNotifications.PROCESSING_CHANNEL_ID
            }
            ?.notification
}
