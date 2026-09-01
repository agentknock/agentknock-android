package dev.agentknock.protocol

internal fun isFreshRelayRequestId(requestId: String, now: Long): Boolean = runCatching {
    val bytes = requestId.ulidBytes()
    if (bytes.all { it == 0.toByte() }) return@runCatching false
    var timestamp = 0L
    repeat(6) { index ->
        timestamp = (timestamp shl 8) or (bytes[index].toLong() and 0xff)
    }
    timestamp >= now - REQUEST_ID_MAX_AGE_MILLIS &&
        timestamp <= now + REQUEST_ID_FUTURE_TOLERANCE_MILLIS
}.getOrDefault(false)

private const val REQUEST_ID_MAX_AGE_MILLIS = 24 * 60 * 60 * 1_000L
private const val REQUEST_ID_FUTURE_TOLERANCE_MILLIS = 5 * 60 * 1_000L
