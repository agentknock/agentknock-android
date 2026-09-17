package dev.agentknock.push

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.request.RequestNotification
import dev.agentknock.storage.request.RequestNotificationDetail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestNotificationActionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After
    fun cleanUp() {
        RequestNotifications.showRequests(context, emptyList())
    }

    @Test
    fun failedDecisionReplacesActionsWithReviewWithoutRealertingOrExposingLockedDetails() {
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
        val request =
            RequestNotification(
                requestId = "notification-action-test",
                title = "Development workstation",
                summary = "deployment-token · ./deploy.sh production",
                details =
                    listOf(
                        RequestNotificationDetail("Secrets", "deployment-token"),
                        RequestNotificationDetail("Command", "./deploy.sh production"),
                    ),
                decisionAvailable = true,
                kindLabel = "Secret access",
            )
        RequestNotifications.showRequests(context, listOf(request))
        val initial = awaitNotification { it.actions?.size == 2 }
        assertEquals("Deny once", initial.actions[0].title.toString())
        assertEquals(
            RequestNotifications.decisionPendingIntent(
                context,
                request.requestId,
                RequestNotifications.DENY_DECISION,
            ),
            initial.actions[0].actionIntent,
        )
        val approve = initial.actions[1]
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals("Allow once", approve.title.toString())
            assertEquals(
                RequestNotifications.decisionPendingIntent(
                    context,
                    request.requestId,
                    RequestNotifications.APPROVE_DECISION,
                ),
                approve.actionIntent,
            )
            assertTrue(approve.isAuthenticationRequired)
        } else {
            assertEquals("Review", approve.title.toString())
            assertEquals(
                RequestNotifications.openRequestPendingIntent(
                    context,
                    request.requestId,
                    RequestNotifications.APPROVE_DECISION,
                ),
                approve.actionIntent,
            )
        }

        RequestNotifications.showRequests(
            context,
            listOf(request.copy(actionFailure = "Couldn’t approve")),
        )
        val failed = awaitNotification { it.actions?.size == 1 }
        assertEquals("Open to review", failed.actions.single().title.toString())
        assertEquals(
            RequestNotifications.openRequestPendingIntent(context, request.requestId),
            failed.actions.single().actionIntent,
        )
        assertEquals(
            "Couldn’t approve · Open to review",
            failed.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertTrue(
            failed.extras
                .getCharSequence(Notification.EXTRA_BIG_TEXT)
                .toString()
                .contains("./deploy.sh production")
        )
        assertTrue(failed.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, failed.visibility)
        assertTrue(failed.publicVersion.actions.isNullOrEmpty())
    }

    private fun awaitNotification(predicate: (Notification) -> Boolean): Notification {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val notification =
                manager.activeNotifications
                    .singleOrNull { it.tag == "notification-action-test" }
                    ?.notification
            if (notification != null && predicate(notification)) return notification
            Thread.sleep(10)
        }
        error("Notification update did not arrive")
    }
}
