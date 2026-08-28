package dev.agentknock.network

import okhttp3.Interceptor
import okhttp3.Response

internal class AgentknockUserAgentInterceptor(
    versionName: String,
    versionCode: Int,
) : Interceptor {
    private val userAgent = "Agentknock-Android/$versionName (build $versionCode)"

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request()
            .newBuilder()
            .header("User-Agent", userAgent)
            .build(),
    )
}
