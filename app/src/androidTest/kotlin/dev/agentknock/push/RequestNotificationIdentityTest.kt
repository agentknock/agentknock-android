package dev.agentknock.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestNotificationIdentityTest {
    @Test
    fun collidingUlidHashesKeepDistinctPendingIntentIdentities() {
        assertTrue(ULID.matches(FIRST_REQUEST_ID))
        assertTrue(ULID.matches(SECOND_REQUEST_ID))
        assertEquals(FIRST_REQUEST_ID.hashCode(), SECOND_REQUEST_ID.hashCode())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstOpen = RequestNotifications.openRequestPendingIntent(context, FIRST_REQUEST_ID)
        val secondOpen = RequestNotifications.openRequestPendingIntent(context, SECOND_REQUEST_ID)
        val firstApprove = RequestNotifications.decisionPendingIntent(
            context,
            FIRST_REQUEST_ID,
            RequestNotifications.APPROVE_DECISION,
        )
        val secondApprove = RequestNotifications.decisionPendingIntent(
            context,
            SECOND_REQUEST_ID,
            RequestNotifications.APPROVE_DECISION,
        )
        val firstDeny = RequestNotifications.decisionPendingIntent(
            context,
            FIRST_REQUEST_ID,
            RequestNotifications.DENY_DECISION,
        )

        try {
            assertNotEquals(firstOpen, secondOpen)
            assertNotEquals(firstApprove, secondApprove)
            assertNotEquals(firstApprove, firstDeny)
        } finally {
            firstOpen.cancel()
            secondOpen.cancel()
            firstApprove.cancel()
            secondApprove.cancel()
            firstDeny.cancel()
        }
    }

    private companion object {
        val ULID = Regex("[0-7][0-9A-HJKMNP-TV-Z]{25}")
        const val FIRST_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6PAP"
        const val SECOND_REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6PB1"
    }
}
