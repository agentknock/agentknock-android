package dev.agentknock.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

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
    data class Message(
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
        val request: RelayMessageState,
        val response: RelayMessageState,
        val completion: RelayMessageState,
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
        val retryAfterMillis: Long?,
        val clientId: String?,
        val requestId: String?,
        val kind: RelayMessageKind?,
    ) : RelayDeviceEvent

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

    data class Unavailable(val message: String?) : RelayDeviceConnectionResult

    data object InvalidResponse : RelayDeviceConnectionResult
}

internal interface RelayDeviceConnection : AutoCloseable {
    val events: ReceiveChannel<RelayDeviceEvent>

    fun send(frame: RelayDeviceFrame): Boolean
}

internal interface RelayDeviceClient {
    suspend fun connect(
        deviceId: String,
        deviceToken: String,
    ): RelayDeviceConnectionResult
}

internal class WebSocketRelayDeviceClient(
    private val client: OkHttpClient,
    private val relayUrl: String = "https://relay.agentknock.dev/",
    private val codec: RelayFrameCodec = RelayFrameCodec(),
) : RelayDeviceClient {
    override suspend fun connect(
        deviceId: String,
        deviceToken: String,
    ): RelayDeviceConnectionResult {
        val opened = CompletableDeferred<RelayDeviceConnectionResult>()
        val events = Channel<RelayDeviceEvent>(Channel.UNLIMITED)
        lateinit var connection: OkHttpRelayDeviceConnection
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/v1/device/$deviceId")
            .header("Authorization", "Bearer $deviceToken")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connection = OkHttpRelayDeviceConnection(webSocket, events, codec)
                opened.complete(RelayDeviceConnectionResult.Connected(connection))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.encodeToByteArray().size > MAXIMUM_FRAME_BYTES) {
                    webSocket.close(1009, "frame too large")
                    events.trySend(RelayDeviceEvent.Failed("Relay frame was too large."))
                    return
                }
                val event = runCatching { codec.decode(text) }.getOrElse {
                    webSocket.close(1008, "invalid frame")
                    RelayDeviceEvent.Failed("Relay sent an invalid frame.")
                }
                events.trySend(event)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.close(1008, "text frames required")
                events.trySend(RelayDeviceEvent.Failed("Relay sent a binary frame."))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(RelayDeviceEvent.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) {
                    opened.complete(connectionFailure(t, response))
                } else {
                    events.trySend(RelayDeviceEvent.Failed(t.message))
                }
            }
        }
        val socket = client.newWebSocket(request, listener)
        return try {
            opened.await()
        } catch (cancelled: CancellationException) {
            socket.cancel()
            throw cancelled
        }
    }

    private fun connectionFailure(
        throwable: Throwable,
        response: Response?,
    ): RelayDeviceConnectionResult {
        if (response == null) {
            return RelayDeviceConnectionResult.Unavailable(throwable.message)
        }
        val body = runCatching { response.body.string() }.getOrNull()
        val error = body?.let { encoded ->
            runCatching { Json.parseToJsonElement(encoded).jsonObject }.getOrNull()
        }
        return RelayDeviceConnectionResult.Rejected(
            status = response.code,
            code = error?.string("error"),
            message = error?.string("message"),
        )
    }
}

private class OkHttpRelayDeviceConnection(
    private val socket: WebSocket,
    override val events: ReceiveChannel<RelayDeviceEvent>,
    private val codec: RelayFrameCodec,
) : RelayDeviceConnection {
    override fun send(frame: RelayDeviceFrame): Boolean {
        val encoded = codec.encode(frame)
        return encoded.encodeToByteArray().size <= MAXIMUM_FRAME_BYTES && socket.send(encoded)
    }

    override fun close() {
        socket.close(1000, "client disconnect")
    }
}

internal class RelayFrameCodec(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun encode(frame: RelayDeviceFrame): String = buildJsonObject {
        when (frame) {
            is RelayDeviceFrame.Message -> {
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
                addressId = frame.string("address_id"),
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
            "state" -> RelayDeviceEvent.State(
                clientId = frame.requiredString("client_id"),
                requestId = frame.requiredString("request_id"),
                exchange = frame.requiredEnum("exchange", RelayExchangeState.entries) {
                    it.wireName
                },
                request = frame.requiredEnum("request", RelayMessageState.entries) { it.wireName },
                response = frame.requiredEnum("response", RelayMessageState.entries) { it.wireName },
                completion = frame.requiredEnum(
                    "completion",
                    RelayMessageState.entries,
                ) { it.wireName },
            )
            "inactive" -> RelayDeviceEvent.Inactive(
                frame.requiredString("client_id"),
                frame.requiredString("request_id"),
                frame.string("kind")?.toMessageKind(),
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
                retryAfterMillis = frame["retry_after_ms"]?.jsonPrimitive?.longOrNull,
                clientId = frame.string("client_id"),
                requestId = frame.string("request_id"),
                kind = frame.string("kind")?.toMessageKind(),
            )
            "caught_up" -> RelayDeviceEvent.CaughtUp
            else -> error("Unknown relay frame type")
        }
    }

    private fun JsonObject.requiredMessageKind(): RelayMessageKind =
        requiredString("kind").toMessageKind()

    private fun String.toMessageKind(): RelayMessageKind =
        RelayMessageKind.entries.find { it.wireName == this } ?: error("Invalid relay kind")

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
    string(name) ?: error("Missing relay $name")

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.content

private const val MAXIMUM_FRAME_BYTES = 256 * 1024
