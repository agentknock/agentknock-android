package dev.agentknock.storage.request

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Owns the process-local lifetime of billable AI review attempts.
 *
 * REVIEWING remains the durable source of request state. This coordinator only prevents two live
 * coroutines from performing the same review. A process restart deliberately does not recreate an
 * uncertain review attempt.
 */
internal class AiReviewCoordinator(private val scope: CoroutineScope) {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    val hasActiveReviews: Boolean
        get() = synchronized(lock) { jobs.isNotEmpty() }

    suspend fun awaitIdle() {
        while (true) {
            val active = synchronized(lock) { jobs.values.toList() }
            if (active.isEmpty()) return
            active.joinAll()
        }
    }

    fun launch(requestId: String, onCompletion: () -> Unit, review: suspend () -> Unit): Boolean {
        val job =
            synchronized(lock) {
                if (requestId in jobs) return false
                scope
                    .launch(start = CoroutineStart.LAZY) {
                        review()
                    }
                    .also { newJob ->
                        jobs[requestId] = newJob
                        newJob.invokeOnCompletion {
                            synchronized(lock) {
                                if (jobs[requestId] === newJob) jobs.remove(requestId)
                            }
                            // Notify the relay owner after removing the completed review.
                            onCompletion()
                        }
                    }
            }
        job.start()
        // An idle waiter can also start a lazy job via join(); either starter admits the review.
        return !job.isCancelled
    }
}
