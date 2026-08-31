package dev.agentknock.presentation

import java.time.Duration
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

internal fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = Duration.ofMillis((now - timestamp).coerceAtLeast(0))
    return when {
        elapsed.toMinutes() < 1 -> "just now"
        elapsed.toHours() < 1 -> elapsed.toMinutes().relativeUnit("minute")
        elapsed.toDays() < 1 -> elapsed.toHours().relativeUnit("hour")
        elapsed.toDays() < 30 -> elapsed.toDays().relativeUnit("day")
        else -> formatTimestamp(timestamp).substringBefore(' ')
    }
}

private fun Long.relativeUnit(unit: String): String =
    "$this $unit${if (this == 1L) "" else "s"} ago"

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

internal data class GitSigningContent(
    val requestTitle: String,
    val messageLabel: String?,
    val message: String?,
)

internal fun describeGitSigningContent(content: ByteArray): GitSigningContent {
    val text = runCatching { content.decodeToString(throwOnInvalidSequence = true) }
        .getOrNull()
        ?.takeIf { value ->
            value.all { character ->
                character == '\n' || character == '\r' || character == '\t' ||
                    !character.isISOControl()
            }
        }
        ?: return GitSigningContent("Git signature", null, null)
    val header = text.substringBefore("\n\n")
    val message = text.substringAfter("\n\n", missingDelimiterValue = "")
        .trimEnd()
        .takeIf(String::isNotEmpty)
    val headerLines = header.lineSequence().toList()
    return when {
        headerLines.firstOrNull()?.startsWith("tree ") == true ->
            GitSigningContent("Git commit signature", "Commit message", message)
        headerLines.firstOrNull()?.startsWith("object ") == true &&
            headerLines.any { it.startsWith("type ") } &&
            headerLines.any { it.startsWith("tag ") } ->
            GitSigningContent("Git tag signature", "Tag message", message)
        else -> GitSigningContent("Git signature", null, null)
    }
}

internal fun renderShellWord(value: String): String = when {
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
