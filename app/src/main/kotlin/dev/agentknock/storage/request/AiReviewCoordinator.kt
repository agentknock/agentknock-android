package dev.agentknock.storage.request

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()
    val hasActiveReviews: Boolean
        get() = active.value

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
                        _active.value = true
                        newJob.invokeOnCompletion {
                            synchronized(lock) {
                                if (jobs[requestId] === newJob) jobs.remove(requestId)
                                _active.value = jobs.isNotEmpty()
                            }
                            // Notify the relay owner after removing the completed review.
                            onCompletion()
                        }
                    }
            }
        job.start()
        return !job.isCancelled
    }
}
