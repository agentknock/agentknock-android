package dev.agentknock.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySubscriptionBillingTest {
    @Test
    fun `describes an automatically renewing monthly plan`() {
        val summary =
            checkNotNull(
                summarizeSubscriptionPricing(
                    listOf(
                        phase(
                            formattedPrice = "€4.99",
                            billingPeriod = "P1M",
                            recurrence = PricingRecurrence.INFINITE,
                        )
                    )
                )
            )

        assertEquals("€4.99/month", summary.price)
        assertEquals(
            "Renews automatically until canceled. Manage or cancel in Google Play.",
            summary.terms,
        )
        assertTrue(summary.autoRenewing)
        assertNull(summary.freeTrialDuration)
    }

    @Test
    fun `describes a free trial before its recurring price`() {
        val summary =
            checkNotNull(
                summarizeSubscriptionPricing(
                    listOf(
                        phase(
                            formattedPrice = "€0.00",
                            priceAmountMicros = 0,
                            billingPeriod = "P14D",
                            billingCycleCount = 1,
                            recurrence = PricingRecurrence.FINITE,
                        ),
                        phase(
                            formattedPrice = "€4.99",
                            billingPeriod = "P1M",
                            recurrence = PricingRecurrence.INFINITE,
                        ),
                    )
                )
            )

        assertEquals("€4.99/month", summary.price)
        assertEquals("14 days", summary.freeTrialDuration)
        assertEquals(
            "14 days free, then €4.99/month. " +
                "You will be charged automatically unless you cancel before the trial ends. " +
                "Renews automatically until canceled. Manage or cancel in Google Play.",
            summary.terms,
        )
    }

    @Test
    fun `recognizes a non-recurring two-week trial`() {
        val summary =
            checkNotNull(
                summarizeSubscriptionPricing(
                    listOf(
                        phase("€0.00", 0, "P2W", recurrence = PricingRecurrence.NONE),
                        phase(
                            "€4.99",
                            billingPeriod = "P1M",
                            recurrence = PricingRecurrence.INFINITE,
                        ),
                    )
                )
            )
        assertEquals("2 weeks", summary.freeTrialDuration)
        assertEquals("€4.99/month", summary.price)
    }

    @Test
    fun `rejects offers whose introductory terms cannot be described`() {
        assertNull(
            summarizeSubscriptionPricing(
                listOf(
                    phase("€0.00", 0, "invalid", recurrence = PricingRecurrence.NONE),
                    phase("€4.99", billingPeriod = "P1M", recurrence = PricingRecurrence.INFINITE),
                )
            )
        )
    }

    @Test
    fun `a free standalone plan is not a trial`() {
        val summary =
            checkNotNull(
                summarizeSubscriptionPricing(
                    listOf(phase("€0.00", 0, "P14D", recurrence = PricingRecurrence.NONE))
                )
            )
        assertNull(summary.freeTrialDuration)
        assertFalse(summary.autoRenewing)
    }

    @Test
    fun `eligible trial replaces only its paid base plan regardless of ordering`() {
        val base = offer("monthly", null, null)
        val trial = offer("monthly", "free-trial-14-days", "14 days")
        assertEquals(listOf(trial), preferFreeTrialOffers(listOf(base, trial)))
        assertEquals(listOf(trial), preferFreeTrialOffers(listOf(trial, base)))
    }

    @Test
    fun `paid plan remains available without an eligible trial`() {
        val base = offer("monthly", null, null)
        val discount = offer("monthly", "discount", null)
        assertEquals(listOf(base), preferFreeTrialOffers(listOf(base)))
        assertEquals(listOf(base, discount), preferFreeTrialOffers(listOf(base, discount)))
    }

    @Test
    fun `trial does not hide other plans products or offers`() {
        val base = offer("monthly", null, null)
        val trial = offer("monthly", "free-trial-14-days", "14 days")
        val annual = offer("annual", null, null)
        val otherProduct = base.copy(id = base.id.copy(productId = "another_subscription"))
        val discount = offer("monthly", "discount", null)
        assertEquals(
            listOf(trial, annual, otherProduct, discount),
            preferFreeTrialOffers(listOf(base, trial, annual, otherProduct, discount)),
        )
    }

    private fun offer(basePlanId: String, offerId: String?, freeTrialDuration: String?) =
        PlaySubscriptionOffer(
            id = PlaySubscriptionOfferId(GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID, basePlanId, offerId),
            price = "€4.99/month",
            terms = "Renews automatically until canceled.",
            autoRenewing = true,
            freeTrialDuration = freeTrialDuration,
        )

    @Test
    fun `describes finite recurring introductory pricing accurately`() {
        val summary =
            checkNotNull(
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
                    )
                )
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
        val summary =
            checkNotNull(
                summarizeSubscriptionPricing(
                    listOf(
                        phase(
                            formattedPrice = "€9.99",
                            billingPeriod = "P3M",
                            recurrence = PricingRecurrence.NONE,
                        )
                    )
                )
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
    ) =
        SubscriptionPricingPhase(
            formattedPrice = formattedPrice,
            priceAmountMicros = priceAmountMicros,
            billingPeriod = billingPeriod,
            billingCycleCount = billingCycleCount,
            recurrence = recurrence,
        )
}
