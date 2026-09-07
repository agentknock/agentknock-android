package dev.agentknock.ui.requests

import androidx.compose.ui.graphics.Color
import dev.agentknock.presentation.renderShellCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InvocationCommandRenderingTest {
    private val command = "/nix/store/abc-openjdk-21/bin/jarsigner"
    private val arguments = listOf(
        "-keystore", "/home/dev/upload keystore.p12",
        "-storepass:env", "KEYSTORE_PASSWORD",
        "it's", "multi\nline", "",
    )

    @Test
    fun listedCommand_copiesAsAnEquivalentShellCommand() {
        val listed = annotatedCommand(command, arguments, listed = true, Color.Unspecified, Color.Unspecified).text
        assertEquals(renderShellCommand(command, arguments), listed.replace(COMMAND_LINE_CONTINUATION, " "))
        assertEquals(arguments.size, listed.count { it == '\n' })
        assertFalse("every newline must be a shell continuation", listed.contains(Regex("(?<!\\\\)\n")))
    }

    @Test
    fun inlineCommand_matchesTheShellRendering() {
        val inline = annotatedCommand(command, arguments, listed = false, Color.Unspecified, Color.Unspecified).text
        assertEquals(renderShellCommand(command, arguments), inline)
    }
}
