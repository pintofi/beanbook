package dev.beancount.core.engine

import java.net.URI

/**
 * Decides whether the WebView may load a URL. Deliberately strict and deliberately
 * testable without a device: this is a security control, and Phase 0 risk R3 is that it
 * was never negatively tested.
 *
 * Rules:
 *  - the origin (scheme, host, port) must match an allowed origin exactly;
 *  - loopback is only ever allowed over http, and only for 127.0.0.1;
 *  - everything remote must be https;
 *  - no userinfo, because `https://evil.com@127.0.0.1/` style URLs must not slip through.
 */
class OriginAllowlist(private val allowed: Set<String>) {

    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.userInfo != null) return false

        val port = if (uri.port != -1) uri.port else defaultPort(scheme) ?: return false

        if (host == LOOPBACK_V4 || host == LOOPBACK_V6) {
            if (scheme != "http") return false
        } else if (scheme != "https") {
            return false
        }

        return "$scheme://$host:$port" in allowed
    }

    private fun defaultPort(scheme: String): Int? = when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> null
    }

    companion object {
        private const val LOOPBACK_V4 = "127.0.0.1"
        private const val LOOPBACK_V6 = "[::1]"

        fun loopback(port: Int): OriginAllowlist =
            OriginAllowlist(setOf("http://$LOOPBACK_V4:$port"))
    }
}
