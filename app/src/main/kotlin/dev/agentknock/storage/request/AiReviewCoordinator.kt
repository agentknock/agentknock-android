package dev.agentknock.storage.request

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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

    fun launch(requestId: String, review: suspend () -> Unit): Boolean {
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
                        }
                    }
            }
        return job.start()
    }
}
