package dev.agentknock.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.agentknock.presentation.formatRelativeTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Localized UI dates with a named month and the user's explicit clock preference. */
internal class UiDateTimeFormatter(
    private val locale: Locale,
    use24Hour: Boolean,
    private val timeZone: TimeZone,
) {
    private val datePattern = DateFormat.getBestDateTimePattern(locale, "yMMMd")
    private val timePattern =
        DateFormat.getBestDateTimePattern(locale, if (use24Hour) "Hms" else "hms")
    private val timestampPattern =
        DateFormat.getBestDateTimePattern(
            locale,
            if (use24Hour) "yMMMdHm" else "yMMMdhm",
        )
    private val preciseTimestampPattern =
        DateFormat.getBestDateTimePattern(
            locale,
            if (use24Hour) "yMMMdHms" else "yMMMdhms",
        )

    fun date(timestamp: Long): String = format(timestamp, datePattern)

    fun time(timestamp: Long): String = format(timestamp, timePattern)

    fun timestamp(timestamp: Long, includeSeconds: Boolean = false): String =
        format(timestamp, if (includeSeconds) preciseTimestampPattern else timestampPattern)

    fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String =
        formatRelativeTime(timestamp, now, ::date)

    private fun format(timestamp: Long, pattern: String): String =
        SimpleDateFormat(pattern, locale)
            .apply { timeZone = this@UiDateTimeFormatter.timeZone }
            .format(Date(timestamp))
}

internal val LocalDateTimeFormatter = staticCompositionLocalOf<UiDateTimeFormatter?> { null }

@Composable
internal fun rememberDateTimeFormatter(): UiDateTimeFormatter {
    LocalDateTimeFormatter.current?.let {
        return it
    }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var use24Hour by remember(context) { mutableStateOf(DateFormat.is24HourFormat(context)) }
    var timeZone by remember { mutableStateOf(TimeZone.getDefault()) }
    LifecycleResumeEffect(context) {
        use24Hour = DateFormat.is24HourFormat(context)
        timeZone = TimeZone.getDefault()
        onPauseOrDispose {}
    }
    return remember(locale, use24Hour, timeZone) {
        UiDateTimeFormatter(locale, use24Hour, timeZone)
    }
}
