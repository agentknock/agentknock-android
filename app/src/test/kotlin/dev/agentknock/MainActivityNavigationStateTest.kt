package dev.agentknock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityNavigationStateTest {
    @Test
    fun `the latest target wins across navigation kinds`() {
        val state = MainActivityNavigationState()
        val first = ExternalNavigation.Request("first")
        val replacement = ExternalNavigation.SubscriptionRedemption("token")

        state.open(first)
        state.open(replacement)
        state.consume(first)

        assertEquals(replacement, state.target.value)
        state.consume(replacement)
        assertNull(state.target.value)
    }
}
