package dev.agentknock.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySubscriptionBillingTest {
    @Test
    fun `describes an automatically renewing monthly plan`() {
        val summary = checkNotNull(
            summarizeSubscriptionPricing(
                listOf(
                    phase(
                        formattedPrice = "€4.99",
                        billingPeriod = "P1M",
                        recurrence = PricingRecurrence.INFINITE,
                    ),
                ),
            ),
        )

        assertEquals("€4.99/month", summary.price)
        assertEquals(
            "Renews automatically until canceled. Manage or cancel in Google Play.",
            summary.terms,
        )
        assertTrue(summary.autoRenewing)
    }

    @Test
    fun `describes a free trial before its recurring price`() {
        val summary = checkNotNull(
            summarizeSubscriptionPricing(
                listOf(
                    phase(
                        formattedPrice = "€0.00",
                        priceAmountMicros = 0,
                        billingPeriod = "P1W",
                        billingCycleCount = 1,
                        recurrence = PricingRecurrence.FINITE,
                    ),
                    phase(
                        formattedPrice = "€4.99",
                        billingPeriod = "P1M",
                        recurrence = PricingRecurrence.INFINITE,
                    ),
                ),
            ),
        )

        assertEquals("€4.99/month", summary.price)
        assertEquals(
            "1 week free, then €4.99/month. " +
                "Renews automatically until canceled. Manage or cancel in Google Play.",
            summary.terms,
        )
    }

    @Test
    fun `describes finite recurring introductory pricing accurately`() {
        val summary = checkNotNull(
            summarizeSubscriptionPricing(
                listOf(
                    phase(
                        formattedPrice = "€1.99",
                        billingPeriod = "P1M",
                        billingCycleCount = 3,
                        recurrence = PricingRecurrence.FINITE,
                    ),
                    phase(
                        formattedPrice = "€4.99",
                        billingPeriod = "P3M",
                        recurrence = PricingRecurrence.INFINITE,
                    ),
                ),
            ),
        )

        assertEquals("€4.99/3 months", summary.price)
        assertEquals(
            "€1.99/month for 3 months, then €4.99/3 months. " +
                "Renews automatically until canceled. Manage or cancel in Google Play.",
            summary.terms,
        )
    }

    @Test
    fun `describes a non-renewing prepaid plan`() {
        val summary = checkNotNull(
            summarizeSubscriptionPricing(
                listOf(
                    phase(
                        formattedPrice = "€9.99",
                        billingPeriod = "P3M",
                        recurrence = PricingRecurrence.NONE,
                    ),
                ),
            ),
        )

        assertEquals("€9.99 for 3 months", summary.price)
        assertEquals("Does not renew automatically.", summary.terms)
        assertFalse(summary.autoRenewing)
    }

    private fun phase(
        formattedPrice: String,
        priceAmountMicros: Long = 4_990_000,
        billingPeriod: String,
        billingCycleCount: Int = 0,
        recurrence: PricingRecurrence,
    ) = SubscriptionPricingPhase(
        formattedPrice = formattedPrice,
        priceAmountMicros = priceAmountMicros,
        billingPeriod = billingPeriod,
        billingCycleCount = billingCycleCount,
        recurrence = recurrence,
    )
}
