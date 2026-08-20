package dev.agentknock.presentation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timestampFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)

private val unquotedShellWord = Regex("[A-Za-z0-9_@%+=:,./-]+")

internal fun formatTimestamp(
    timestamp: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = timestampFormatter.format(Instant.ofEpochMilli(timestamp).atZone(zoneId))

internal fun renderShellCommand(command: String, arguments: List<String>): String =
    (listOf(command) + arguments).joinToString(" ", transform = ::renderShellWord)

internal fun renderSingleLineText(value: String): String = buildString {
    value.forEach { character ->
        append(
            when (character) {
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> when {
                    character.code < 0x20 || character.code == 0x7f ->
                        "\\x${character.code.toString(16).padStart(2, '0')}"
                    Character.getType(character) == Character.FORMAT.toInt() ->
                        "\\u${character.code.toString(16).padStart(4, '0')}"
                    else -> character.toString()
                }
            },
        )
    }
}

internal fun formatPlatformName(platform: String): String = when (platform.lowercase(Locale.ROOT)) {
    "android" -> "Android"
    "darwin", "macos" -> "macOS"
    "freebsd" -> "FreeBSD"
    "ios" -> "iOS"
    "linux" -> "Linux"
    "openbsd" -> "OpenBSD"
    "windows" -> "Windows"
    else -> platform
}

private fun renderShellWord(value: String): String = when {
    value.isEmpty() -> "''"
    unquotedShellWord.matches(value) -> value
    value.any {
        it == '\n' || it == '\r' || it == '\t' || it.code < 0x20 || it.code == 0x7f ||
            Character.getType(it) == Character.FORMAT.toInt()
    } ->
        buildString {
            append("$'")
            value.forEach { character ->
                append(
                    when (character) {
                        '\\' -> "\\\\"
                        '\'' -> "\\'"
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        else -> if (character.code < 0x20 || character.code == 0x7f) {
                            "\\x${character.code.toString(16).padStart(2, '0')}"
                        } else if (Character.getType(character) == Character.FORMAT.toInt()) {
                            "\\u${character.code.toString(16).padStart(4, '0')}"
                        } else {
                            character
                        }
                    },
                )
            }
            append('\'')
        }
    else -> "'${value.replace("'", "'\"'\"'")}'"
}
