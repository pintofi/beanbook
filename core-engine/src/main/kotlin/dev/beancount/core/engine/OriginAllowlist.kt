package dev.beancount.core.engine

import java.net.URI

/**
 * Decides whether the WebView may load a URL. Deliberately strict and deliberately
 * testable without a device: this is a security control, and Phase 0 risk R3 is that it
 * was never negatively tested.
 *
 * Rules:
 *  - the scheme must be http or https, so `file:`, `content:`, `javascript:` and `intent:`
 *    URLs are refused before anything else is considered;
 *  - no userinfo, because `https://evil.com@127.0.0.1/` style URLs must not slip through;
 *  - the origin (scheme, host, port) must then match an allowed origin **exactly**.
 *
 * Exact matching is what makes this safe rather than the scheme rules. An allowlist built
 * from `https://fava.example.com` cannot be satisfied by `http://fava.example.com`: they are
 * different strings, so downgrading the scheme is refused by the same check that refuses a
 * different host.
 *
 * An earlier version also hard-coded "everything remote must be https". That was removed
 * when remote mode began accepting cleartext addresses for LAN and VPN hosting: it made
 * `http://192.168.1.5:5000` unusable even when the user had explicitly chosen it, and it was
 * belt-and-braces over an exact string match that already did the work.
 */
class OriginAllowlist(private val allowed: Set<String>) {

    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.userInfo != null) return false

        if (scheme != "http" && scheme != "https") return false
        val port = if (uri.port != -1) uri.port else defaultPort(scheme) ?: return false

        return "$scheme://$host:$port" in allowed
    }

    /**
     * Whether [host] is the host of an allowed origin.
     *
     * Used for one thing only: deciding whether to answer an HTTP auth challenge. Android
     * hands `onReceivedHttpAuthRequest` a bare host with no scheme and no port, so [isAllowed]
     * cannot be asked directly — and inventing a scheme to make it fit is how the check ends
     * up passing loopback through `https://` and silently refusing every challenge.
     *
     * Host granularity is the right granularity here: browsers scope Basic credentials by
     * host and realm too, and a port on the same host is the same trust boundary.
     */
    fun allowsHost(host: String): Boolean {
        val wanted = host.lowercase()
        return allowed.any { origin ->
            runCatching { URI(origin).host?.lowercase() }.getOrNull() == wanted
        }
    }

    private fun defaultPort(scheme: String): Int? = when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> null
    }

    companion object {
        private const val LOOPBACK_V4 = "127.0.0.1"

        fun loopback(port: Int): OriginAllowlist =
            OriginAllowlist(setOf("http://$LOOPBACK_V4:$port"))
    }
}
