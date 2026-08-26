package dev.agentknock.subscription

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionRedemptionLinkTest {
    @Test
    fun `accepts the Agentknock subscription redemption URL`() {
        assertEquals(
            SubscriptionRedemptionLink.Valid(TOKEN),
            SubscriptionRedemptionLink.parse(
                "https://agentknock.dev/subscription/redeem#token=$TOKEN",
            ),
        )
    }

    @Test
    fun `rejects an incomplete Agentknock subscription redemption URL`() {
        assertEquals(
            SubscriptionRedemptionLink.Invalid,
            SubscriptionRedemptionLink.parse(
                "https://agentknock.dev/subscription/redeem#token=short",
            ),
        )
        assertEquals(
            SubscriptionRedemptionLink.Invalid,
            SubscriptionRedemptionLink.parse(
                "https://agentknock.dev/subscription/redeem",
            ),
        )
        assertEquals(
            SubscriptionRedemptionLink.Invalid,
            SubscriptionRedemptionLink.parse(
                "https://agentknock.dev/subscription/redeem?token=$TOKEN",
            ),
        )
    }

    @Test
    fun `ignores URLs outside the subscription redemption route`() {
        listOf(
            null,
            "https://example.com/subscription/redeem#token=$TOKEN",
            "http://agentknock.dev/subscription/redeem#token=$TOKEN",
            "https://agentknock.dev/subscription#token=$TOKEN",
        ).forEach { value ->
            assertEquals(
                SubscriptionRedemptionLink.Unrelated,
                SubscriptionRedemptionLink.parse(value),
            )
        }
    }

    private companion object {
        const val TOKEN = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDI"
    }
}
