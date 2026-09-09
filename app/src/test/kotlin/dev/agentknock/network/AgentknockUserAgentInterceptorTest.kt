package dev.agentknock.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentknockUserAgentInterceptorTest {
    @Test
    fun `identifies the app version and build on every request`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).build())
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(
                        AgentknockUserAgentInterceptor(
                            versionName = "1.2.3",
                            versionCode = 456,
                        )
                    )
                    .build()
            val request =
                Request.Builder()
                    .url(server.url("/"))
                    .header("User-Agent", "caller-supplied value")
                    .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            assertEquals(
                "Agentknock-Android/1.2.3 (build 456)",
                server.takeRequest().headers["User-Agent"],
            )
        }
    }
}
