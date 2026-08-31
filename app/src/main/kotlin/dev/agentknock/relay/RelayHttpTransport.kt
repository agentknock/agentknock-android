package dev.agentknock.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val DEFAULT_RELAY_URL = "https://relay.agentknock.dev/"

internal sealed interface RelayEndpointResult<out T> {
    data class Success<T>(val value: T) : RelayEndpointResult<T>

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : RelayEndpointResult<Nothing>

    data class Unavailable(val cause: IOException) : RelayEndpointResult<Nothing>

    data object InvalidResponse : RelayEndpointResult<Nothing>
}

internal inline fun <T> RelayEndpointResult<String>.decodeSuccess(
    crossinline decode: (String) -> T,
): RelayEndpointResult<T> = when (this) {
    is RelayEndpointResult.Success -> try {
        RelayEndpointResult.Success<T>(decode(value))
    } catch (_: Exception) {
        RelayEndpointResult.InvalidResponse
    }
    is RelayEndpointResult.Rejected -> RelayEndpointResult.Rejected(status, code, message)
    is RelayEndpointResult.Unavailable -> RelayEndpointResult.Unavailable(cause)
    RelayEndpointResult.InvalidResponse -> RelayEndpointResult.InvalidResponse
}

internal class RelayHttpTransport(
    private val client: OkHttpClient,
    relayUrl: String = DEFAULT_RELAY_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val baseUrl = relayUrl.trimEnd('/')

    suspend fun post(
        path: String,
        body: String,
        bearerToken: String? = null,
    ): RelayEndpointResult<String> {
        val request = Request.Builder()
            .url("$baseUrl/${path.trimStart('/')}")
            .apply {
                bearerToken?.let { header("Authorization", "Bearer $it") }
            }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            runInterruptible(dispatcher) {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    if (response.isSuccessful) {
                        RelayEndpointResult.Success(responseBody)
                    } else {
                        val error = decodeRelayError(responseBody, json)
                        RelayEndpointResult.Rejected(
                            status = response.code,
                            code = error.code,
                            message = error.message,
                        )
                    }
                }
            }
        } catch (exception: IOException) {
            RelayEndpointResult.Unavailable(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal data class RelayError(
    val code: String?,
    val message: String?,
)

internal fun decodeRelayError(
    encoded: String,
    json: Json = Json { ignoreUnknownKeys = true },
): RelayError {
    val body = runCatching { json.parseToJsonElement(encoded).jsonObject }.getOrNull()
    return RelayError(
        code = body.stringMember("error"),
        message = body.stringMember("message"),
    )
}

private fun JsonObject?.stringMember(name: String): String? =
    (this?.get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
