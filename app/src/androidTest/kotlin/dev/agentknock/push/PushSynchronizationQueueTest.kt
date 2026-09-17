package dev.agentknock.push

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushSynchronizationQueueTest {
    @Test
    fun freshWakeCanFinishWhileNetworkRetryIsStillDelayed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        val names = listOf("push-synchronization", "push-synchronization-deadline")
        names.forEach { workManager.cancelUniqueWork(it).result.get(10, TimeUnit.SECONDS) }
        try {
            // Use the real scheduler: delayed WorkManager prerequisites used to block every
            // subsequently appended expedited wake, regardless of restored connectivity.
            assertEquals(
                ListenableWorker.Result.success(),
                PushSynchronizationWorker.enqueueRetry(context, 1_800_000, 20_000),
            )
            val retry =
                workManager.getWorkInfosForUniqueWork(names[1]).get(10, TimeUnit.SECONDS).single {
                    !it.state.isFinished
                }
            PushSynchronizationWorker.enqueueAndAwait(context)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (true) {
                val wakes =
                    workManager.getWorkInfosForUniqueWork(names[0]).get(10, TimeUnit.SECONDS)
                assertFalse(wakes.any { it.state == WorkInfo.State.BLOCKED })
                if (wakes.any { it.state == WorkInfo.State.SUCCEEDED }) break
                check(SystemClock.elapsedRealtime() < deadline) {
                    "Fresh wake did not finish: $wakes"
                }
                kotlinx.coroutines.delay(20)
            }
            assertEquals(
                WorkInfo.State.ENQUEUED,
                workManager.getWorkInfoById(retry.id).get(10, TimeUnit.SECONDS)!!.state,
            )
        } finally {
            names.forEach { workManager.cancelUniqueWork(it).result.get(10, TimeUnit.SECONDS) }
        }
    }
}
