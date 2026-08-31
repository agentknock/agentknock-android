package dev.agentknock

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationSavedStateTest {
    @Test
    fun pendingTargetsSurviveActivityStateAndConsumedTargetsDoNot() {
        val original = MainActivityNavigationState().apply {
            openRequest(null)
            openSubscription(SubscriptionNavigation.Redemption(REDEMPTION_TOKEN))
        }
        val restored = MainActivityNavigationState().apply { restore(original.save()) }

        assertEquals(RequestNavigation(null), restored.request.value)
        assertEquals(
            SubscriptionNavigation.Redemption(REDEMPTION_TOKEN),
            restored.subscription.value,
        )

        restored.consumeRequest(RequestNavigation(null))
        restored.consumeSubscription(SubscriptionNavigation.Redemption(REDEMPTION_TOKEN))
        val afterConsumption = MainActivityNavigationState().apply { restore(restored.save()) }

        assertNull(afterConsumption.request.value)
        assertNull(afterConsumption.subscription.value)
    }

    private companion object {
        const val REDEMPTION_TOKEN = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDI"
    }
}
