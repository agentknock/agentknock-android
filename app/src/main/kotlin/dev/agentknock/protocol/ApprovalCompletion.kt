package dev.agentknock.protocol

internal sealed interface ApprovalCompletion {
    val clientSoftware: ClientSoftware
    val reason: String?
    val message: String?

    data class Approved(override val clientSoftware: ClientSoftware) : ApprovalCompletion {
        override val reason: String? get() = null
        override val message: String? get() = null
    }

    data class Denied(
        override val clientSoftware: ClientSoftware,
        override val reason: String,
        override val message: String,
    ) : ApprovalCompletion

    data class Aborted(
        override val clientSoftware: ClientSoftware,
        override val reason: String,
        override val message: String,
    ) : ApprovalCompletion
}
