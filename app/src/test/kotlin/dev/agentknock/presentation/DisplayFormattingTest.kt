package dev.agentknock.presentation

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFormattingTest {
    @Test
    fun timestamp_isFixedAndUnambiguous() {
        assertEquals(
            "2026-05-23 17:55:32",
            formatTimestamp(1_779_558_932_000, ZoneOffset.UTC),
        )
    }

    @Test
    fun command_preservesItsExactNameAndShowsArgumentsAsShellWords() {
        assertEquals(
            "/opt/custom/git commit -m 'Keep the command path' '' 'it'\"'\"'s exact'",
            renderShellCommand(
                "/opt/custom/git",
                listOf("commit", "-m", "Keep the command path", "", "it's exact"),
            ),
        )
    }

    @Test
    fun command_keepsControlCharactersOnOneReadableLine() {
        assertEquals(
            "printf $'first\\nsecond\\tline'",
            renderShellCommand("printf", listOf("first\nsecond\tline")),
        )
    }

    @Test
    fun knownPlatformNames_areHumanizedWithoutRewritingUnknownValues() {
        assertEquals("Linux", formatPlatformName("linux"))
        assertEquals("macOS", formatPlatformName("Darwin"))
        assertEquals("Plan 9", formatPlatformName("Plan 9"))
    }
}
