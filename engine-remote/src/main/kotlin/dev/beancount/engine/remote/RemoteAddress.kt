package dev.beancount.engine.remote

import java.net.URI

/**
 * The address of a self-hosted Fava, after it has been checked.
 *
 * There is exactly one way to construct this — [parse] — and it refuses anything that is not
 * HTTPS. That refusal is the whole point: a remote ledger travels over someone else's
 * network, and "it worked when I typed http://" is how a ledger ends up readable by a café's
 * router (docs/threat-model.md §3.5). The app has no "connect anyway" affordance, and this
 * type is why: the rest of the code cannot express an insecure address.
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

    /**
     * The origin in the form [dev.beancount.core.engine.OriginAllowlist] compares against:
     * the port is always explicit.
     *
     * [origin] leaves out `:443`, because that is how a person writes an address and how it
     * should be shown back to them. The allowlist normalises the other way, always adding
     * the default port, so handing it [origin] silently blocked every server on 443 — which
     * is very nearly all of them.
     */
    val allowlistOrigin: String
        get() = if (":" in origin.removePrefix("https://")) origin else "$origin:443"

    fun resolve(path: String): String =
        base + "/" + path.removePrefix("/")

    companion object {
        /** Why an address was refused. Each says what to change. */
        sealed interface Problem {
            data object Empty : Problem
            data class NotHttps(val scheme: String) : Problem
            data object NoHost : Problem
            data class Malformed(val reason: String) : Problem

            fun describe(): String = when (this) {
                Empty -> "Enter the address of your Fava server, for example " +
                    "https://fava.example.com."
                is NotHttps ->
                    "Only https:// addresses are accepted; \"$scheme\" is not encrypted. " +
                        "There is no option to connect anyway — your ledger would be " +
                        "readable by anything between this device and the server."
                NoHost -> "That address has no server name in it."
                is Malformed -> "That is not a usable address: $reason."
            }
        }

        fun parse(input: String): Result<RemoteAddress> {
            val text = input.trim()
            if (text.isEmpty()) return failure(Problem.Empty)

            // A bare host is a common way to type it, and assuming HTTPS is safe: the only
            // thing it can do is fail the handshake, never fall back to cleartext.
            val candidate = if ("://" in text) text else "https://$text"

            val uri = runCatching { URI(candidate) }.getOrElse {
                return failure(Problem.Malformed(it.message ?: "unparseable"))
            }

            val scheme = uri.scheme?.lowercase() ?: return failure(Problem.NoHost)
            if (scheme != "https") return failure(Problem.NotHttps(scheme))
            val host = uri.host ?: return failure(Problem.NoHost)
            if (host.isBlank()) return failure(Problem.NoHost)

            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.path.orEmpty().trimEnd('/')

            return Result.success(RemoteAddress("https://$host$port", path))
        }

        private fun failure(problem: Problem): Result<RemoteAddress> =
            Result.failure(RemoteAddressRejected(problem))
    }
}

/** Carries a [RemoteAddress.Companion.Problem] out of parsing. */
class RemoteAddressRejected(val problem: RemoteAddress.Companion.Problem) :
    Exception(problem.describe())
