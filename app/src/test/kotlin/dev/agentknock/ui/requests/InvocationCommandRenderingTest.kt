package dev.agentknock.ui.requests

import androidx.compose.ui.graphics.Color
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvocationCommandRenderingTest {
    private val command = "/nix/store/abc-openjdk-21/bin/jarsigner"
    private val arguments =
        listOf(
            "-keystore",
            "/home/dev/upload keystore.p12",
            "-storepass:env",
            "KEYSTORE_PASSWORD",
            "it's",
            "multi\nline",
            "",
        )

    @Test
    fun listedCommand_copiesAsAnEquivalentShellCommand() {
        val listed =
            annotatedCommand(
                    command,
                    arguments,
                    listed = true,
                    Color.Unspecified,
                    Color.Unspecified,
                )
                .text
        assertTrue("Listed commands remain multiline", listed.contains('\n'))
        // Let the shell check quoting and continuation semantics independently of our formatter.
        // `set --` parses arguments without executing the displayed command.
        val shell = ProcessBuilder("bash", "-c", "set -- $listed\nprintf '%s\\0' \"\$@\"").start()
        try {
            assertTrue("Shell parsing timed out", shell.waitFor(5, TimeUnit.SECONDS))
            assertEquals(shell.errorStream.bufferedReader().readText(), 0, shell.exitValue())
            assertEquals(
                listOf(command) + arguments,
                shell.inputStream.bufferedReader().readText().split('\u0000').dropLast(1),
            )
        } finally {
            shell.destroyForcibly()
        }
    }
}
