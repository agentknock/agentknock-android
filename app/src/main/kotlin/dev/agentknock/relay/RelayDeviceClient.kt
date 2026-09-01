package dev.agentknock.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal enum class RelayMessageKind(val wireName: String) {
    REQUEST("request"),
    RESPONSE("response"),
    COMPLETION("completion"),
}

internal enum class RelayClientState(val wireName: String) {
    PENDING("pending"),
    ACTIVE("active"),
    SUSPENDED("suspended"),
    REVOKED("revoked"),
}

internal enum class RelayExchangeState(val wireName: String) {
    OPEN("open"),
    CLOSING("closing"),
    SETTLED("settled"),
    EXPIRED("expired"),
}

internal enum class RelayMessageState(val wireName: String) {
    ABSENT("absent"),
    ACCEPTED("accepted"),
    DELIVERED("delivered"),
    DISCARDED("discarded"),
}

internal enum class RelayPushRegistrationState(val wireName: String) {
    MISSING("missing"),
    REGISTERED("registered"),
    INVALID("invalid"),
}

internal sealed interface RelayDeviceFrame {
    data class Response(
        val clientId: String,
        val requestId: String,
        val payload: JsonElement,
    ) : RelayDeviceFrame

    data class Acknowledgement(
        val clientId: String,
        val requestId: String,
        val kind: RelayMessageKind,
    ) : RelayDeviceFrame

    data class Resume(
        val clientId: String,
        val requestId: String,
    ) : RelayDeviceFrame

    data class SetClientState(
        val clientId: String,
        val state: RelayClientState,
    ) : RelayDeviceFrame
}

internal sealed interface RelayDeviceErrorScope {
    data object Unscoped : RelayDeviceErrorScope

    data class Exchange(
        val clientId: String,
        val requestId: String,
        val kind: RelayMessageKind?,
    ) : RelayDeviceErrorScope
}

internal sealed interface RelayDeviceEvent {
    data class Message(
        val clientId: String,
        val requestId: String,
        val kind: RelayMessageKind,
        val payload: JsonElement,
        val addressId: String?,
    ) : RelayDeviceEvent

    data class Acknowledgement(
        val clientId: String,
        val requestId: String,
        val kind: RelayMessageKind,
    ) : RelayDeviceEvent

    data class Receipt(
        val clientId: String,
        val requestId: String,
        val kind: RelayMessageKind,
    ) : RelayDeviceEvent

    data class State(
        val clientId: String,
        val requestId: String,
        val exchange: RelayExchangeState,
        val response: RelayMessageState,
    ) : RelayDeviceEvent

    data class Inactive(
        val clientId: String,
        val requestId: String,
        val kind: RelayMessageKind?,
    ) : RelayDeviceEvent

    data class ClientState(
        val clientId: String,
        val state: RelayClientState,
    ) : RelayDeviceEvent

    data class PushRegistration(
        val state: RelayPushRegistrationState,
    ) : RelayDeviceEvent

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
        val scope: RelayDeviceErrorScope,
        val retryAfterMillis: Long? = null,
    ) : RelayDeviceEvent {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0)
        }
    }

    data object CaughtUp : RelayDeviceEvent

    data class Closed(val code: Int, val reason: String) : RelayDeviceEvent

    data class Failed(val message: String?) : RelayDeviceEvent
}

internal sealed interface RelayDeviceConnectionResult {
    data class Connected(val connection: RelayDeviceConnection) : RelayDeviceConnectionResult

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : RelayDeviceConnectionResult

    data class Unavailable(
        val message: String?,
        val retryAfterMillis: Long? = null,
    ) : RelayDeviceConnectionResult {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0)
        }
    }
}

internal sealed interface RelayFrameSendResult {
    data object Sent : RelayFrameSendResult

    data object Unavailable : RelayFrameSendResult

    data object FrameTooLarge : RelayFrameSendResult
}

internal interface RelayDeviceConnection {
    val events: ReceiveChannel<RelayDeviceEvent>

    fun send(frame: RelayDeviceFrame): RelayFrameSendResult

    suspend fun close()
}

internal interface RelayDeviceClient {
    suspend fun connect(
        deviceId: String,
        deviceToken: String,
    ): RelayDeviceConnectionResult
}

internal class WebSocketRelayDeviceClient(
    client: OkHttpClient,
    private val relayUrl: String = DEFAULT_RELAY_URL,
    private val codec: RelayFrameCodec = RelayFrameCodec(),
    private val eventBufferCapacity: Int = MAXIMUM_PENDING_EVENTS,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : RelayDeviceClient {
    private val client = client.newBuilder()
        // The application connection manager owns retry timing.
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun connect(
        deviceId: String,
        deviceToken: String,
    ): RelayDeviceConnectionResult {
        val opened = CompletableDeferred<RelayDeviceConnectionResult>()
        val terminated = CompletableDeferred<Unit>()
        val events = RelayEventBuffer(eventBufferCapacity)
        lateinit var connection: OkHttpRelayDeviceConnection
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/v1/device/$deviceId")
            .header("Authorization", "Bearer $deviceToken")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connection = OkHttpRelayDeviceConnection(
                    socket = webSocket,
                    events = events.events,
                    codec = codec,
                    terminated = terminated,
                )
                opened.complete(RelayDeviceConnectionResult.Connected(connection))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.encodeToByteArray().size > MAXIMUM_FRAME_BYTES) {
                    webSocket.close(1009, "frame too large")
                    events.offer(
                        RelayDeviceEvent.Failed("Relay frame was too large."),
                    ) { abortOverflow(webSocket, terminated) }
                    return
                }
                val event = runCatching { codec.decode(text) }.getOrElse {
                    webSocket.close(1008, "invalid frame")
                    RelayDeviceEvent.Failed("Relay sent an invalid frame.")
                }
                events.offer(event) { abortOverflow(webSocket, terminated) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.close(1008, "text frames required")
                events.offer(
                    RelayDeviceEvent.Failed("Relay sent a binary frame."),
                ) { abortOverflow(webSocket, terminated) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                terminated.complete(Unit)
                events.finish(
                    RelayDeviceEvent.Closed(code, reason),
                ) { abortOverflow(webSocket, terminated) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                terminated.complete(Unit)
                if (!opened.isCompleted) {
                    opened.complete(connectionFailure(t, response))
                } else {
                    events.finish(
                        RelayDeviceEvent.Failed(t.message),
                    ) { abortOverflow(webSocket, terminated) }
                }
            }
        }
        val socket = client.newWebSocket(request, listener)
        return try {
            opened.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                closeWebSocket(socket, terminated)
            }
            throw cancelled
        }
    }

    private fun connectionFailure(
        throwable: Throwable,
        response: Response?,
    ): RelayDeviceConnectionResult {
        if (response == null) {
            return RelayDeviceConnectionResult.Unavailable(
                throwable.message ?: "Could not connect to the relay.",
            )
        }
        val body = runCatching { response.body.string() }.getOrNull()
        val error = body?.let(::decodeRelayError)
        if (response.code.isTransientRelayStatus()) {
            return RelayDeviceConnectionResult.Unavailable(
                message = error?.message
                    ?: "Relay is temporarily unavailable (HTTP ${response.code}).",
                retryAfterMillis = maximumRetryDelay(
                    error?.retryAfterMillis,
                    response.header("Retry-After")?.toRetryAfterMillis(currentTimeMillis()),
                ),
            )
        }
        return RelayDeviceConnectionResult.Rejected(
            status = response.code,
            code = error?.code,
            message = error?.message,
        )
    }
}

internal class RelayEventBuffer(capacity: Int) {
    private val channel: Channel<RelayDeviceEvent>
    private val finished = AtomicBoolean(false)

    init {
        require(capacity > 0) { "Relay event buffer capacity must be positive" }
        channel = Channel(capacity)
    }

    val events: ReceiveChannel<RelayDeviceEvent> = channel

    fun offer(event: RelayDeviceEvent, onOverflow: () -> Unit): Boolean {
        if (finished.get()) return false
        if (channel.trySend(event).isSuccess) return true
        overflow(onOverflow)
        return false
    }

    fun finish(event: RelayDeviceEvent, onOverflow: () -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        if (channel.trySend(event).isFailure) {
            drain()
            check(
                channel.trySend(
                    RelayDeviceEvent.Failed("Relay event backlog exceeded its safe limit."),
                ).isSuccess,
            )
            onOverflow()
        }
        channel.close()
    }

    private fun overflow(onOverflow: () -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        drain()
        check(
            channel.trySend(
                RelayDeviceEvent.Failed("Relay event backlog exceeded its safe limit."),
            ).isSuccess,
        )
        channel.close()
        onOverflow()
    }

    private fun drain() {
        while (channel.tryReceive().isSuccess) {
            // A reconnect replays any relay messages discarded with this overloaded connection.
        }
    }
}

private class OkHttpRelayDeviceConnection(
    private val socket: WebSocket,
    override val events: ReceiveChannel<RelayDeviceEvent>,
    private val codec: RelayFrameCodec,
    private val terminated: CompletableDeferred<Unit>,
) : RelayDeviceConnection {
    override fun send(frame: RelayDeviceFrame): RelayFrameSendResult {
        val encoded = codec.encode(frame)
        if (encoded.encodeToByteArray().size > MAXIMUM_FRAME_BYTES) {
            return RelayFrameSendResult.FrameTooLarge
        }
        return if (socket.send(encoded)) {
            RelayFrameSendResult.Sent
        } else {
            RelayFrameSendResult.Unavailable
        }
    }

    override suspend fun close() {
        withContext(NonCancellable) {
            closeWebSocket(socket, terminated)
        }
    }
}

private suspend fun closeWebSocket(
    socket: WebSocket,
    terminated: CompletableDeferred<Unit>,
) {
    if (!terminated.isCompleted) {
        socket.close(1000, "client disconnect")
    }
    val completed = withTimeoutOrNull(WEBSOCKET_CLOSE_TIMEOUT_MILLIS) {
        terminated.await()
        true
    } == true
    if (!completed) socket.cancel()
}

private fun abortOverflow(
    socket: WebSocket,
    terminated: CompletableDeferred<Unit>,
) {
    socket.cancel()
    terminated.complete(Unit)
}

internal class RelayFrameCodec(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun encode(frame: RelayDeviceFrame): String = buildJsonObject {
        when (frame) {
            is RelayDeviceFrame.Response -> {
                put("type", JsonPrimitive("message"))
                putIdentity(frame.clientId, frame.requestId)
                put("kind", JsonPrimitive(RelayMessageKind.RESPONSE.wireName))
                put("payload", frame.payload)
            }
            is RelayDeviceFrame.Acknowledgement -> {
                put("type", JsonPrimitive("ack"))
                putIdentity(frame.clientId, frame.requestId)
                put("kind", JsonPrimitive(frame.kind.wireName))
            }
            is RelayDeviceFrame.Resume -> {
                put("type", JsonPrimitive("resume"))
                putIdentity(frame.clientId, frame.requestId)
            }
            is RelayDeviceFrame.SetClientState -> {
                put("type", JsonPrimitive("set_client_state"))
                put("client_id", JsonPrimitive(frame.clientId))
                put("state", JsonPrimitive(frame.state.wireName))
            }
        }
    }.toString()

    fun decode(encoded: String): RelayDeviceEvent {
        val frame = json.parseToJsonElement(encoded).jsonObject
        return when (frame.requiredString("type")) {
            "message" -> RelayDeviceEvent.Message(
                clientId = frame.requiredString("client_id"),
                requestId = frame.requiredString("request_id"),
                kind = frame.requiredMessageKind(),
                payload = frame["payload"] ?: error("Missing relay payload"),
                addressId = frame.optionalString("address_id"),
            )
            "ack" -> RelayDeviceEvent.Acknowledgement(
                frame.requiredString("client_id"),
                frame.requiredString("request_id"),
                frame.requiredMessageKind(),
            )
            "receipt" -> RelayDeviceEvent.Receipt(
                frame.requiredString("client_id"),
                frame.requiredString("request_id"),
                frame.requiredMessageKind(),
            )
            "state" -> {
                val exchange = frame.requiredEnum("exchange", RelayExchangeState.entries) {
                    it.wireName
                }
                frame.requiredEnum("request", RelayMessageState.entries) { it.wireName }
                val response =
                    frame.requiredEnum("response", RelayMessageState.entries) { it.wireName }
                frame.requiredEnum(
                    "completion",
                    RelayMessageState.entries,
                ) { it.wireName }
                RelayDeviceEvent.State(
                    clientId = frame.requiredString("client_id"),
                    requestId = frame.requiredString("request_id"),
                    exchange = exchange,
                    response = response,
                )
            }
            "inactive" -> RelayDeviceEvent.Inactive(
                frame.requiredString("client_id"),
                frame.requiredString("request_id"),
                frame.optionalString("kind")?.toMessageKind(),
            )
            "client_state" -> RelayDeviceEvent.ClientState(
                frame.requiredString("client_id"),
                frame.requiredEnum("state", RelayClientState.entries) { it.wireName },
            )
            "push_registration" -> RelayDeviceEvent.PushRegistration(
                frame.requiredEnum("state", RelayPushRegistrationState.entries) { it.wireName },
            )
            "error" -> RelayDeviceEvent.Error(
                code = frame.requiredString("error"),
                message = frame.requiredString("message"),
                retryable = frame["retryable"]?.jsonPrimitive?.booleanOrNull
                    ?: error("Missing relay retryable flag"),
                scope = frame.errorScope(),
                retryAfterMillis = frame.optionalNonNegativeLong("retry_after_ms"),
            )
            "caught_up" -> RelayDeviceEvent.CaughtUp
            else -> error("Unknown relay frame type")
        }
    }

    private fun JsonObject.requiredMessageKind(): RelayMessageKind =
        requiredString("kind").toMessageKind()

    private fun String.toMessageKind(): RelayMessageKind =
        RelayMessageKind.entries.find { it.wireName == this } ?: error("Invalid relay kind")

    private fun JsonObject.errorScope(): RelayDeviceErrorScope {
        val clientId = optionalString("client_id")
        val requestId = optionalString("request_id")
        val kind = optionalString("kind")?.toMessageKind()
        return when {
            clientId == null && requestId == null && kind == null -> {
                RelayDeviceErrorScope.Unscoped
            }
            clientId != null && requestId != null -> {
                RelayDeviceErrorScope.Exchange(clientId, requestId, kind)
            }
            else -> error("Invalid relay error scope")
        }
    }

    private fun <T> JsonObject.requiredEnum(
        name: String,
        values: Iterable<T>,
        wireName: (T) -> String,
    ): T = requiredString(name).let { encoded ->
        values.find { wireName(it) == encoded } ?: error("Invalid relay $name")
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putIdentity(
        clientId: String,
        requestId: String,
    ) {
        put("client_id", JsonPrimitive(clientId))
        put("request_id", JsonPrimitive(requestId))
    }
}

private fun JsonObject.requiredString(name: String): String =
    optionalString(name) ?: error("Missing relay $name")

private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content
        ?: error("Relay $name must be a string")
    else -> error("Relay $name must be a string")
}

private fun JsonObject.optionalNonNegativeLong(name: String): Long? = when (
    val value = this[name]
) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.parseNonNegativeDecimalClamped()
        ?: error("Relay $name must be a non-negative integer")
    else -> error("Relay $name must be a non-negative integer")
}

private fun String.toRetryAfterMillis(nowMillis: Long): Long? {
    val encoded = trim()
    encoded.parseNonNegativeDecimalClamped(Long.MAX_VALUE / 1_000L)?.let { seconds ->
        return seconds * 1_000L
    }
    val retryAtMillis = encoded.httpDateMillis(nowMillis) ?: return null
    return when {
        retryAtMillis <= nowMillis -> 0
        retryAtMillis - nowMillis < 0 -> Long.MAX_VALUE
        else -> retryAtMillis - nowMillis
    }
}

private fun String.httpDateMillis(nowMillis: Long): Long? {
    runCatching {
        ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()?.let { return it }

    if (',' in this && '-' in this) {
        runCatching {
            ZonedDateTime.parse(substringAfter(',').trim(), RFC_850_DATE_TIME)
        }.getOrNull()?.let { parsed ->
            val fiftyYearsFromNow = Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneOffset.UTC)
                .plusYears(50)
            val candidate = parsed.withYear(
                (fiftyYearsFromNow.year / 100) * 100 + parsed.year % 100,
            )
            return candidate
                .let { if (it.isAfter(fiftyYearsFromNow)) it.minusYears(100) else it }
                .toInstant()
                .toEpochMilli()
        }
    }

    return runCatching {
        val withoutWeekday = substringAfter(' ').trim().replace(WHITESPACE, " ")
        ZonedDateTime.parse("$withoutWeekday GMT", ASCTIME_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}

private fun maximumRetryDelay(first: Long?, second: Long?): Long? =
    listOfNotNull(first, second).maxOrNull()

private val RFC_850_DATE_TIME = DateTimeFormatter.ofPattern(
    "dd-MMM-yy HH:mm:ss zzz",
    Locale.US,
)
private val ASCTIME_DATE_TIME = DateTimeFormatter.ofPattern(
    "MMM d HH:mm:ss yyyy zzz",
    Locale.US,
)
private val WHITESPACE = Regex(" +")

private const val MAXIMUM_FRAME_BYTES = 256 * 1024
private const val MAXIMUM_PENDING_EVENTS = 64
private const val WEBSOCKET_CLOSE_TIMEOUT_MILLIS = 5_000L
