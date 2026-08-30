package dev.agentknock.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val DEFAULT_RELAY_URL = "https://relay.agentknock.dev/"

internal sealed interface RelayHttpResult {
    data class Success(val body: String) : RelayHttpResult

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : RelayHttpResult

    data class Unavailable(val cause: IOException) : RelayHttpResult
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
    ): RelayHttpResult {
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
                        RelayHttpResult.Success(responseBody)
                    } else {
                        val error = runCatching {
                            json.decodeFromString<RelayErrorResponse>(responseBody)
                        }.getOrNull()
                        RelayHttpResult.Rejected(
                            status = response.code,
                            code = error?.code,
                            message = error?.message,
                        )
                    }
                }
            }
        } catch (exception: IOException) {
            RelayHttpResult.Unavailable(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
private data class RelayErrorResponse(
    @SerialName("error") val code: String,
    val message: String,
)
