package dev.agentknock

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationSavedStateTest {
    @Test
    fun pendingTargetsSurviveActivityStateAndConsumedTargetsDoNot() {
        val original =
            MainActivityNavigationState().apply {
                open(ExternalNavigation.Request(null))
            }
        val restored = MainActivityNavigationState().apply { restore(original.save()) }

        assertEquals(ExternalNavigation.Request(null), restored.target.value)

        restored.consume(ExternalNavigation.Request(null))
        val afterConsumption = MainActivityNavigationState().apply { restore(restored.save()) }

        assertNull(afterConsumption.target.value)
    }

    @Test
    fun redemptionTokenIsNotWrittenToActivityState() {
        val token = "sensitive-redemption-credential-for-this-test"
        val original =
            MainActivityNavigationState().apply {
                open(ExternalNavigation.SubscriptionRedemption(token))
            }

        val saved = original.save()
        @Suppress("DEPRECATION") val savedText = saved.keySet().mapNotNull { saved[it] as? String }
        assertFalse(
            "Redemption credentials must not enter Android saved state",
            savedText.any { token in it },
        )
        val restored = MainActivityNavigationState().apply { restore(saved) }

        assertNull(restored.target.value)
    }
}
