package dev.agentknock.ui

import dev.agentknock.ui.auth.SensitiveDataBackgroundGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveEditorBackgroundGuardTest {
    @Test
    fun `credential activity stop preserves the editor when authentication returns`() {
        var now = 1_000L
        val guard = SensitiveDataBackgroundGuard({ now }, graceMillis = 15_000L)

        assertFalse(guard.onStop(authenticationInProgress = true))
        now += 14_999L
        assertFalse(guard.onAuthenticationChanged(inProgress = false, foreground = true))
    }

    @Test
    fun `authentication ending while still backgrounded clears the editor`() {
        val guard = SensitiveDataBackgroundGuard({ 1_000L })

        assertFalse(guard.onStop(authenticationInProgress = true))
        assertTrue(guard.onAuthenticationChanged(inProgress = false, foreground = false))
    }

    @Test
    fun `credential activity cannot hide a long background interval`() {
        var now = 1_000L
        val guard = SensitiveDataBackgroundGuard({ now }, graceMillis = 15_000L)

        assertFalse(guard.onStop(authenticationInProgress = true))
        now += 15_000L

        assertTrue(guard.onAuthenticationChanged(inProgress = false, foreground = true))
    }

    @Test
    fun `ordinary backgrounding clears the editor immediately`() {
        assertTrue(
            SensitiveDataBackgroundGuard({ 1_000L }).onStop(authenticationInProgress = false),
        )
    }
}
