package dev.agentknock.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class RelayMessage(
    val requestId: String,
    val request: JsonElement?,
    val hasRequest: Boolean,
    val completion: JsonElement?,
    val hasCompletion: Boolean,
)

internal data class RelayPendingBatch(
    val messages: List<RelayMessage>,
    val hasMore: Boolean,
)

internal data class RelayUpdate(
    val requestId: String,
    val requestDelivered: Boolean = false,
    val response: JsonElement? = null,
    val completionDelivered: Boolean = false,
)

internal sealed interface RelayInboxResult<out T> {
    data class Success<T>(val value: T) : RelayInboxResult<T>

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : RelayInboxResult<Nothing>

    data class Unavailable(val cause: IOException) : RelayInboxResult<Nothing>

    data object InvalidResponse : RelayInboxResult<Nothing>
}

internal interface RelayInboxClient {
    suspend fun pending(
        routeId: String,
        authenticationToken: String,
    ): RelayInboxResult<RelayPendingBatch>

    suspend fun update(
        routeId: String,
        authenticationToken: String,
        updates: List<RelayUpdate>,
    ): RelayInboxResult<Int>
}

internal class HttpRelayInboxClient(
    private val client: OkHttpClient,
    private val relayUrl: String = "https://relay.agentknock.dev/",
    private val json: Json = Json,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RelayInboxClient {
    override suspend fun pending(
        routeId: String,
        authenticationToken: String,
    ): RelayInboxResult<RelayPendingBatch> = post(
        routeId = routeId,
        path = "pending",
        authenticationToken = authenticationToken,
        body = JsonObject(emptyMap()),
    ) { response ->
        val messages = response["messages"]?.jsonArray?.map { element ->
            val message = element.jsonObject
            val requestId = message["request_id"]?.jsonPrimitive?.content
                ?: error("Missing relay request id")
            RelayMessage(
                requestId = requestId,
                request = message["request"],
                hasRequest = "request" in message,
                completion = message["completion"],
                hasCompletion = "completion" in message,
            )
        } ?: error("Missing relay messages")
        RelayPendingBatch(
            messages = messages,
            hasMore = response["has_more"]?.jsonPrimitive?.booleanOrNull
                ?: error("Missing relay pagination state"),
        )
    }

    override suspend fun update(
        routeId: String,
        authenticationToken: String,
        updates: List<RelayUpdate>,
    ): RelayInboxResult<Int> = post(
        routeId = routeId,
        path = "update",
        authenticationToken = authenticationToken,
        body = buildJsonObject {
            put(
                "updates",
                buildJsonArray {
                    updates.forEach { update ->
                        add(
                            buildJsonObject {
                                put("request_id", JsonPrimitive(update.requestId))
                                if (update.requestDelivered) {
                                    put("request_delivered", JsonPrimitive(true))
                                }
                                update.response?.let { put("response", it) }
                                if (update.completionDelivered) {
                                    put("completion_delivered", JsonPrimitive(true))
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { response ->
        response["updated"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("Missing relay update count")
    }

    private suspend fun <T> post(
        routeId: String,
        path: String,
        authenticationToken: String,
        body: JsonObject,
        decode: (JsonObject) -> T,
    ): RelayInboxResult<T> = withContext(dispatcher) {
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/v1/route/$routeId/$path")
            .header("Authorization", "Bearer $authenticationToken")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    val error = runCatching {
                        json.parseToJsonElement(responseBody).jsonObject
                    }.getOrNull()
                    return@withContext RelayInboxResult.Rejected(
                        status = response.code,
                        code = error?.get("error")?.jsonPrimitive?.content,
                        message = error?.get("message")?.jsonPrimitive?.content,
                    )
                }
                runCatching {
                    decode(json.parseToJsonElement(responseBody).jsonObject)
                }.fold(
                    onSuccess = { RelayInboxResult.Success(it) },
                    onFailure = { RelayInboxResult.InvalidResponse },
                )
            }
        } catch (exception: IOException) {
            RelayInboxResult.Unavailable(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
