package io.github.pintofi.beanbook.engine.remote

import java.net.URI

/**
 * The address of a self-hosted Fava, after it has been checked.
 *
 * `http` and `https` are both accepted. Cleartext exists because a great many people host
 * Fava on a home LAN or behind a VPN, where getting a certificate for `192.168.1.5` ranges
 * from awkward to impossible, and refusing outright pushed those users towards worse
 * workarounds. It is not free: over `http` the ledger and any proxy password are readable
 * and modifiable by anything on the path, and the app cannot tell a home LAN from a hotel
 * network. So cleartext is never a default, never silent, and never inferred — [isCleartext]
 * exists so every surface that shows an address can say what it is
 * (docs/threat-model.md section 3.5).
 *
 * What has not changed: when the address *is* `https`, verification is strict and a
 * certificate error is fatal. Allowing cleartext is a different decision from accepting
 * broken TLS, and only the first one was made.
 */
class RemoteAddress private constructor(
    val origin: String,
    val basePath: String,
) {
    override fun equals(other: Any?): Boolean =
        other is RemoteAddress && origin == other.origin && basePath == other.basePath

    override fun hashCode(): Int = origin.hashCode() * 31 + basePath.hashCode()

    override fun toString(): String = base

    /** `https://fava.example.com` or `https://host/fava`, without a trailing slash. */
    val base: String get() = origin + basePath

    /** True when this address sends everything in the clear. Never inferred, always shown. */
    val isCleartext: Boolean get() = origin.startsWith("http://")

    /** `http` or `https`. */
    val scheme: String get() = origin.substringBefore("://")

    /**
     * The origin in the form [io.github.pintofi.beanbook.core.engine.OriginAllowlist] compares against:
     * the port is always explicit.
     *
     * [origin] leaves out `:443` (or `:80`), because that is how a person writes an address
     * and how it should be shown back to them. The allowlist normalises the other way,
     * always adding the default port, so handing it [origin] silently blocked every server
     * on a default port — which is very nearly all of them.
     */
    val allowlistOrigin: String
        get() {
            // Read the port from the parsed URI: looking for a colon in the text mistakes an
            // IPv6 literal such as [::1] for "has a port".
            if (URI(origin).port != -1) return origin
            return "$origin:" + if (isCleartext) "80" else "443"
        }

    fun resolve(path: String): String =
        base + "/" + path.removePrefix("/")

    companion object {
        /** Why an address was refused. Each says what to change. */
        sealed interface Problem {
            data object Empty : Problem
            data class UnsupportedScheme(val scheme: String) : Problem
            data object NoHost : Problem
            data class Malformed(val reason: String) : Problem

            fun describe(): String = when (this) {
                Empty -> "Enter the address of your Fava server, for example " +
                    "https://fava.example.com."
                is UnsupportedScheme ->
                    "\"$scheme\" is not an address this app can use. Enter an http:// or " +
                        "https:// address."
                NoHost -> "That address has no server name in it."
                is Malformed -> "That is not a usable address: $reason."
            }
        }

        fun parse(input: String): Result<RemoteAddress> {
            val text = input.trim()
            if (text.isEmpty()) return failure(Problem.Empty)

            // A bare host is a common way to type it, and assuming HTTPS is the safe guess:
            // the worst it can do is fail the handshake. Guessing http would put a ledger on
            // the wire in the clear because someone left six characters off.
            val candidate = if ("://" in text) text else "https://$text"

            val uri = runCatching { URI(candidate) }.getOrElse {
                return failure(Problem.Malformed(it.message ?: "unparseable"))
            }

            val scheme = uri.scheme?.lowercase() ?: return failure(Problem.NoHost)
            if (scheme != "https" && scheme != "http") {
                return failure(Problem.UnsupportedScheme(scheme))
            }
            // Lower-cased because the origin allowlist compares lower-cased hosts: keeping
            // "Fava.Example.com" as typed blocked every page the server served.
            val host = uri.host?.lowercase() ?: return failure(Problem.NoHost)
            if (host.isBlank()) return failure(Problem.NoHost)

            val port = if (uri.port == -1) "" else ":${uri.port}"
            // The raw path: the decoded one turns "%20" into a space, which is not a URL.
            val path = uri.rawPath.orEmpty().trimEnd('/')

            return Result.success(RemoteAddress("$scheme://$host$port", path))
        }

        private fun failure(problem: Problem): Result<RemoteAddress> =
            Result.failure(RemoteAddressRejected(problem))
    }
}

/** Carries a [RemoteAddress.Companion.Problem] out of parsing. */
class RemoteAddressRejected(val problem: RemoteAddress.Companion.Problem) :
    Exception(problem.describe())
