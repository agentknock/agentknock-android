package dev.agentknock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityNavigationStateTest {
    @Test
    fun `consume clears only the request target that was handled`() {
        val state = MainActivityNavigationState()
        val first = RequestNavigation("first")
        val replacement = RequestNavigation("replacement")

        state.openRequest(first.requestId)
        state.openRequest(replacement.requestId)
        state.consumeRequest(first)

        assertEquals(replacement, state.request.value)
        state.consumeRequest(replacement)
        assertNull(state.request.value)
    }

    @Test
    fun `consume clears only the subscription target that was handled`() {
        val state = MainActivityNavigationState()
        val first = SubscriptionNavigation.Redemption("first")

        state.openSubscription(first)
        state.openSubscription(SubscriptionNavigation.InvalidLink)
        state.consumeSubscription(first)

        assertEquals(SubscriptionNavigation.InvalidLink, state.subscription.value)
        state.consumeSubscription(SubscriptionNavigation.InvalidLink)
        assertNull(state.subscription.value)
    }
}
