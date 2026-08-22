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

internal sealed interface ParsedShellCommand {
    data class Valid(val tokens: List<String>) : ParsedShellCommand

    data class Invalid(val message: String) : ParsedShellCommand
}

/** Parses shell-style quoting only. It never expands variables or interprets operators. */
internal fun parseShellCommand(value: String): ParsedShellCommand {
    val tokens = mutableListOf<String>()
    val token = StringBuilder()
    var tokenStarted = false
    var quote: Char? = null
    var escaped = false

    fun finishToken() {
        if (tokenStarted) {
            tokens += token.toString()
            token.clear()
            tokenStarted = false
        }
    }

    value.forEach { character ->
        when {
            escaped -> {
                token.append(character)
                tokenStarted = true
                escaped = false
            }
            quote == '\'' -> if (character == '\'') {
                quote = null
            } else {
                token.append(character)
                tokenStarted = true
            }
            quote == '"' -> when (character) {
                '"' -> quote = null
                '\\' -> escaped = true
                else -> {
                    token.append(character)
                    tokenStarted = true
                }
            }
            character == '\\' -> {
                escaped = true
                tokenStarted = true
            }
            character == '\'' || character == '"' -> {
                quote = character
                tokenStarted = true
            }
            character.isWhitespace() -> finishToken()
            else -> {
                token.append(character)
                tokenStarted = true
            }
        }
    }
    if (escaped) return ParsedShellCommand.Invalid("The command ends with an unfinished escape.")
    if (quote != null) return ParsedShellCommand.Invalid("The command contains an unmatched quote.")
    finishToken()
    if (tokens.isEmpty() || tokens.first().isEmpty()) {
        return ParsedShellCommand.Invalid("Enter a command.")
    }
    return ParsedShellCommand.Valid(tokens)
}
