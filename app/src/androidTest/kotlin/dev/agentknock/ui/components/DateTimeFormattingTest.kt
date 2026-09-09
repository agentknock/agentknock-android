package dev.agentknock.ui.components

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DateTimeFormattingTest {
    private val timestamp = Instant.parse("2026-12-07T17:05:09Z").toEpochMilli()
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun explicitClockPreferenceOverridesLocaleDefault() {
        val us24 = UiDateTimeFormatter(Locale.US, use24Hour = true, utc)
        val uk12 = UiDateTimeFormatter(Locale.UK, use24Hour = false, utc)

        assertEquals("17:05:09", us24.time(timestamp))
        assertTrue(us24.timestamp(timestamp).contains("17:05"))
        assertFalse(us24.timestamp(timestamp).contains("PM"))
        assertTrue(uk12.time(timestamp).startsWith("5:05:09"))
        assertTrue(uk12.time(timestamp).lowercase(Locale.UK).endsWith("pm"))
        assertTrue(uk12.timestamp(timestamp).contains("5:05"))
    }

    @Test
    fun monthNamesAndDateOrderFollowLocale() {
        assertEquals("Dec 7, 2026", UiDateTimeFormatter(Locale.US, true, utc).date(timestamp))
        assertEquals("7 Dec 2026", UiDateTimeFormatter(Locale.UK, true, utc).date(timestamp))
        val german = UiDateTimeFormatter(Locale.GERMANY, true, utc).date(timestamp)
        assertTrue(german.startsWith("7."))
        assertTrue(german.contains("Dez"))
        assertTrue(german.endsWith("2026"))
    }

    @Test
    fun dateAndTimeUseTheSameTimeZoneAcrossMidnight() {
        val dates = UiDateTimeFormatter(Locale.US, true, TimeZone.getTimeZone("GMT+02:00"))
        val lateUtc = Instant.parse("2026-12-07T23:05:09Z").toEpochMilli()

        assertEquals("Dec 8, 2026", dates.date(lateUtc))
        assertEquals("01:05:09", dates.time(lateUtc))
        assertTrue(dates.timestamp(lateUtc).contains("Dec 8, 2026"))
        assertTrue(dates.timestamp(lateUtc).contains("01:05"))
        assertTrue(dates.timestamp(lateUtc, includeSeconds = true).contains("01:05:09"))
    }

    @Test
    fun relativeDateFallbackUsesTheSameLocaleAndTimeZone() {
        val dates = UiDateTimeFormatter(Locale.UK, true, utc)
        assertEquals(
            "7 Dec 2026",
            dates.relativeTime(timestamp, timestamp + 31L * 24 * 60 * 60_000),
        )
    }
}
