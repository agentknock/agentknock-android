package dev.agentknock.storage.request

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Owns the process-local lifetime of billable AI review attempts.
 *
 * REVIEWING remains the durable source of request state. This coordinator only prevents two
 * live coroutines from performing the same review and gives factory reset a bounded set of jobs
 * to cancel later. A process restart deliberately does not recreate an uncertain review attempt.
 */
internal class AiReviewCoordinator(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private var paused = false

    fun launch(requestId: String, review: suspend () -> Unit): Boolean {
        val job = synchronized(lock) {
            if (paused || requestId in jobs) return false
            scope.launch(start = CoroutineStart.LAZY) {
                review()
            }.also { newJob ->
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

    suspend fun pauseAndCancel() {
        val activeJobs = synchronized(lock) {
            paused = true
            jobs.values.toList()
        }
        activeJobs.forEach { it.cancel() }
        activeJobs.joinAll()
    }

    fun resume() {
        synchronized(lock) {
            paused = false
        }
    }
}
