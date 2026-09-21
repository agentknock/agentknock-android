package dev.agentknock.push

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeDeliveryStoreTest {
    @Test
    fun latestWakeSurvivesRecreationAndUpdatesAnOpenObserver() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("agentknock_wake_delivery", 0)
        preferences.edit().clear().commit()
        try {
            val store = WakeDeliveryStore(context)
            assertNull(store.read())
            store.record(10_000, 8_000, WakePriority.REDUCED)
            assertEquals(
                WakeDelivery(10_000, 2_000, WakePriority.REDUCED),
                WakeDeliveryStore(context).read(),
            )
            val updated = async { store.observe().first { it?.receivedAt == 20_000L } }
            testScheduler.runCurrent()
            WakeDeliveryStore(context).record(20_000, 19_900, WakePriority.HIGH)
            assertEquals(WakeDelivery(20_000, 100, WakePriority.HIGH), updated.await())
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun missingOrFutureSendTimeDoesNotLeaveAnOldOrNegativeDelay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("agentknock_wake_delivery", 0)
        val store = WakeDeliveryStore(context)
        try {
            store.record(10_000, 8_000, WakePriority.HIGH)
            store.record(20_000, 0, WakePriority.NORMAL)
            assertNull(store.read()!!.delayMillis)
            store.record(30_000, 31_000, WakePriority.REDUCED)
            assertNull(store.read()!!.delayMillis)
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
