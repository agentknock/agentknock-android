package dev.agentknock.push

internal enum class RequestProcessingState {
    PROCESSING,
    LISTENING,
}

/** Owns transient status separately from notifications asking the user to make a decision. */
internal class RequestProcessingCoordinator(
    private val display: (RequestProcessingState?) -> Unit
) {
    private val lock = Any()
    private val sessions = mutableMapOf<Session, RequestProcessingState>()
    private var displayed: RequestProcessingState? = null

    fun start(): Session =
        synchronized(lock) {
            Session().also {
                sessions[it] = RequestProcessingState.PROCESSING
                reconcile()
            }
        }

    private fun reconcile() {
        val state =
            when {
                RequestProcessingState.PROCESSING in sessions.values ->
                    RequestProcessingState.PROCESSING
                sessions.isNotEmpty() -> RequestProcessingState.LISTENING
                else -> null
            }
        if (state != displayed) {
            display(state)
            displayed = state
        }
    }

    inner class Session internal constructor() : AutoCloseable {
        fun setProcessing(processing: Boolean) =
            synchronized(lock) {
                if (this in sessions) {
                    sessions[this] =
                        if (processing) RequestProcessingState.PROCESSING
                        else RequestProcessingState.LISTENING
                    reconcile()
                }
            }

        override fun close() =
            synchronized(lock) {
                sessions.remove(this)
                reconcile()
            }
    }
}
