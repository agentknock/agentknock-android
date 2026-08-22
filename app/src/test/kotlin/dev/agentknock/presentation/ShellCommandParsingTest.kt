package dev.agentknock.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandParsingTest {
    @Test
    fun `parses quoted and escaped arguments`() {
        val parsed = parseShellCommand("gh issue create --title 'Broken login' empty\\ value \"\"")

        assertEquals(
            listOf("gh", "issue", "create", "--title", "Broken login", "empty value", ""),
            (parsed as ParsedShellCommand.Valid).tokens,
        )
    }

    @Test
    fun `joins adjacent quoted segments into one token`() {
        val parsed = parseShellCommand("command pre'joined value'post")

        assertEquals(
            listOf("command", "prejoined valuepost"),
            (parsed as ParsedShellCommand.Valid).tokens,
        )
    }

    @Test
    fun `rejects incomplete quoting and escaping`() {
        assertTrue(parseShellCommand("gh 'issue") is ParsedShellCommand.Invalid)
        assertTrue(parseShellCommand("gh issue\\") is ParsedShellCommand.Invalid)
    }
}
