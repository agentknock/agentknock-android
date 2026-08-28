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
    fun notificationText_exposesLineBreaksAndDirectionalControls() {
        assertEquals(
            "Looks safe\\nCommand: fake\\u202evalue",
            renderSingleLineText("Looks safe\nCommand: fake\u202evalue"),
        )
    }

    @Test
    fun knownPlatformNames_areHumanizedWithoutRewritingUnknownValues() {
        assertEquals("Linux", formatPlatformName("linux"))
        assertEquals("macOS", formatPlatformName("Darwin"))
        assertEquals("Plan 9", formatPlatformName("Plan 9"))
    }

    @Test
    fun gitCommit_isDescribedUsingItsHumanMessage() {
        assertEquals(
            GitSigningContent(
                requestTitle = "Git commit signature",
                messageLabel = "Commit message",
                message = "Explain the change\n\nWith useful detail",
            ),
            describeGitSigningContent(
                "tree abc\nauthor Example\n\nExplain the change\n\nWith useful detail\n"
                    .encodeToByteArray(),
            ),
        )
    }

    @Test
    fun annotatedTag_isNotMislabelledAsACommit() {
        assertEquals(
            GitSigningContent(
                requestTitle = "Git tag signature",
                messageLabel = "Tag message",
                message = "Release 1.0",
            ),
            describeGitSigningContent(
                "object abc\ntype commit\ntag v1.0\ntagger Example\n\nRelease 1.0\n".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun unknownOrBinarySigningContent_isKeptGeneric() {
        assertEquals(
            GitSigningContent("Git signature", null, null),
            describeGitSigningContent(byteArrayOf(0, 1, 2)),
        )
    }
}
