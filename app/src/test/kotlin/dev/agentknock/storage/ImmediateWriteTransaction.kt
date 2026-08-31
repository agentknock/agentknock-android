package dev.agentknock.storage

internal object ImmediateWriteTransaction : WriteTransaction {
    override suspend fun <T> execute(block: suspend () -> T): T = block()
}
