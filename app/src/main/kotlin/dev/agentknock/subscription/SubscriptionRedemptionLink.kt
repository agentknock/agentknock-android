package dev.agentknock.subscription

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal sealed interface SubscriptionRedemptionLink {
    data class Valid(val token: String) : SubscriptionRedemptionLink

    data object Invalid : SubscriptionRedemptionLink

    data object Unrelated : SubscriptionRedemptionLink

    companion object {
        fun parse(value: String?): SubscriptionRedemptionLink {
            val url = value?.toHttpUrlOrNull() ?: return Unrelated
            if (
                url.scheme != "https" ||
                    url.host != HOST ||
                    url.port != HTTPS_PORT ||
                    url.encodedPath != PATH
            ) {
                return Unrelated
            }
            if (url.query != null) return Invalid
            val token =
                url.fragment
                    ?.takeIf { it.startsWith(FRAGMENT_PREFIX) }
                    ?.removePrefix(FRAGMENT_PREFIX)
            return if (token != null && REDEMPTION_TOKEN.matches(token)) {
                Valid(token)
            } else {
                Invalid
            }
        }

        private const val HOST = "agentknock.dev"
        private const val HTTPS_PORT = 443
        private const val PATH = "/subscription/redeem"
        private const val FRAGMENT_PREFIX = "token="
        private val REDEMPTION_TOKEN = Regex("[A-Za-z0-9_-]{43}")
    }
}
