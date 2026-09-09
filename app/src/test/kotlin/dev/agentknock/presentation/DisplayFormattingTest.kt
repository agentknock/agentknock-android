package dev.agentknock.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFormattingTest {
    @Test
    fun relativeTime_isCompactForRecentActivity() {
        val now = 2_000_000_000_000
        val unusedDate: (Long) -> String = { error("Recent activity should use relative time") }
        assertEquals("just now", formatRelativeTime(now - 20_000, now, unusedDate))
        assertEquals("7 minutes ago", formatRelativeTime(now - 7 * 60_000, now, unusedDate))
        assertEquals("5 hours ago", formatRelativeTime(now - 5 * 60 * 60_000, now, unusedDate))
        assertEquals(
            "12 days ago",
            formatRelativeTime(now - 12 * 24 * 60 * 60_000L, now, unusedDate),
        )
    }

    @Test
    fun relativeTime_usesTheUiDateFormatterForOlderActivity() {
        val timestamp = 1_000_000L
        val now = timestamp + 30 * 24 * 60 * 60_000L
        assertEquals(
            "7 Dec 2026",
            formatRelativeTime(timestamp, now) {
                assertEquals(timestamp, it)
                "7 Dec 2026"
            },
        )
    }

    @Test
    fun parentAge_usesRelativeReceiptTimeRatherThanTheCurrentClock() {
        assertEquals(
            "Parent request received less than a minute earlier",
            formatParentRequestAge(1_000, 30_000),
        )
        assertEquals(
            "Parent request received 1 minute earlier",
            formatParentRequestAge(1_000, 61_000),
        )
        assertEquals(
            "Parent request received 4 hours earlier",
            formatParentRequestAge(1_000, 14_401_000),
        )
        assertEquals(
            "Parent request received 2 days earlier",
            formatParentRequestAge(1_000, 172_801_000),
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
                identities = listOf("Author" to "Example"),
                objectLabel = "Commit",
            ),
            describeGitSigningContent(
                "tree abc\nauthor Example\n\nExplain the change\n\nWith useful detail\n"
                    .encodeToByteArray()
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
                identities = listOf("Tagger" to "Example"),
                objectLabel = "Tag",
            ),
            describeGitSigningContent(
                "object abc\ntype commit\ntag v1.0\ntagger Example\n\nRelease 1.0\n"
                    .encodeToByteArray()
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

    @Test
    fun gitIdentities_comeFromSignedHeadersAndKeepDifferentAuthorAndCommitter() {
        val content =
            ("tree abc\n" +
                    "author Alex Example <alex@example.com> 1780000000 +0300\n" +
                    "committer CI <build@example.com> 1780000010 +0000\n" +
                    "\nFix checks\n\nauthor Not a header")
                .encodeToByteArray()
        assertEquals(
            listOf(
                "Author" to "Alex Example <alex@example.com>",
                "Committer" to "CI <build@example.com>",
            ),
            describeGitSigningContent(content).identities,
        )
        assertEquals(
            "Fix checks\n\nauthor Not a header",
            describeGitSigningContent(content).message,
        )
    }
}
