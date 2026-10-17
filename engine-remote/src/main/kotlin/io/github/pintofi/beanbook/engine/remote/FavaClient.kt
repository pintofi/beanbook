package io.github.pintofi.beanbook.engine.remote

import io.github.pintofi.beanbook.core.domain.TransactionDraft
import java.util.UUID

/** What a server said about itself. Unknown fields are unknown, never guessed. */
data class ServerCapabilities(
    val slug: String,
    val favaVersion: String?,
    val beancountVersion: String?,
) {
    fun describe(): String = buildString {
        append("Connected to \"").append(slug).append("\"")
        if (favaVersion != null) append(" · Fava ").append(favaVersion)
        if (beancountVersion != null) append(" · Beancount ").append(beancountVersion)
        if (favaVersion == null) {
            append(" · version unknown (this server does not report one)")
        }
    }
}

/** The outcome of submitting an entry. */
sealed interface SubmitResult {
    data class Stored(val key: String) : SubmitResult

    /** The entry was already on the server — found by its key, so nothing was sent again. */
    data class AlreadyPresent(val key: String) : SubmitResult

    data class Failed(val failure: RemoteFailure) : SubmitResult
}

/**
 * A self-hosted Fava, spoken to over HTTP or HTTPS.
 *
 * The idempotency rule is the reason this class exists rather than a handful of request
 * helpers. **Fava does not deduplicate**: two identical `PUT`s create two identical
 * transactions, and a submit that times out leaves the client genuinely unable to tell
 * whether the server got it. So every submission carries a key in its metadata, and any
 * retry asks the server whether that key is already there *before* sending anything
 * (the rule: a retried submit cannot create a duplicate entry).
 */
class FavaClient(
    private val address: RemoteAddress,
    private val transport: Transport = PlatformTransport(),
    private val credentials: BasicCredentials? = null,
) {

    /**
     * Finds the ledger slug and, if the server will say, its versions.
     *
     * The slug comes from the redirect rather than from the file name, because Fava derives
     * it from the ledger's title — guessing it breaks on every titled ledger.
     */
    fun connect(): ServerCapabilities {
        val root = transport.request(address.resolve("/"), credentials = credentials)
        val slug = slugFrom(root)
            ?: throw RemoteException(
                RemoteFailure.Unexpected(
                    "that address did not look like a Fava server (no ledger to open)",
                ),
            )

        // Best effort: the only place a version is served is the help index, as prose. A
        // server that will not say is used anyway, and said to be unknown.
        val versions = runCatching {
            val help = transport.request(
                address.resolve("/$slug/api/help?page_slug=_index"),
                credentials = credentials,
            )
            versionsFrom(help.body)
        }.getOrNull() ?: (null to null)

        return ServerCapabilities(slug, versions.first, versions.second)
    }

    /**
     * Submits [draft], exactly once.
     *
     * @param key the idempotency key. Pass the **same** key when retrying a submission whose
     *   outcome is unknown; a fresh one is a different entry.
     */
    fun submit(
        slug: String,
        draft: TransactionDraft,
        key: String = newKey(),
        checkFirst: Boolean = false,
    ): SubmitResult {
        try {
            if (checkFirst) {
                when (isPresent(slug, key)) {
                    Presence.PRESENT -> return SubmitResult.AlreadyPresent(key)
                    // Sending now could duplicate the entry, and a duplicate in someone's
                    // ledger is worse than a retry they have to press again.
                    Presence.UNKNOWN -> return SubmitResult.Failed(
                        RemoteFailure.Unexpected(
                            "the server could not say whether the entry is already there, " +
                                "so nothing was sent",
                        ),
                    )
                    Presence.ABSENT -> Unit
                }
            }

            val response = transport.request(
                url = address.resolve("/$slug/api/add_entries"),
                method = "PUT",
                credentials = credentials,
                body = entryJson(draft, key),
            )
            return if (response.isSuccess) {
                SubmitResult.Stored(key)
            } else {
                SubmitResult.Failed(RemoteFailure.Rejected(response.status, response.body))
            }
        } catch (remote: RemoteException) {
            return SubmitResult.Failed(remote.failure)
        }
    }

    /**
     * Retries a submission whose outcome is unknown.
     *
     * This is the only correct way to retry: it asks first. Calling [submit] again with a
     * fresh key after a timeout is how a duplicate is created.
     */
    fun retry(slug: String, draft: TransactionDraft, key: String): SubmitResult =
        submit(slug, draft, key, checkFirst = true)

    /** The three answers to "is it already there?" — including the honest one. */
    enum class Presence { PRESENT, ABSENT, UNKNOWN }

    /**
     * Whether an entry carrying [key] is already on the server.
     *
     * Two things here are not obvious and were both learned the hard way on a device.
     *
     * First, [refresh]: Fava answers queries from the ledger it has **in memory**, and it
     * only re-reads the file when a request makes it check. An entry written a moment ago is
     * on disk and invisible to a query until then — so the check said "not there", the retry
     * sent it again, and the ledger ended up with two.
     *
     * Second, [Presence.UNKNOWN]: a query that fails is not evidence of absence. Treating it
     * as absence is the same duplicate by another route.
     */
    fun isPresent(slug: String, key: String): Presence {
        refresh(slug)
        val query = "SELECT narration WHERE any_meta('$META_KEY') = '${escape(key)}'"
        val response = try {
            transport.request(
                url = address.resolve("/$slug/api/query?query_string=" + urlEncode(query)),
                credentials = credentials,
            )
        } catch (ignored: RemoteException) {
            // Deliberately not propagated: "could not ask" is a third answer, not an error.
            // A caller that cannot reach the server must not conclude the entry is absent.
            return Presence.UNKNOWN
        }
        if (!response.isSuccess) return Presence.UNKNOWN
        // Rows are the only thing that matters, and any row means it is there.
        val rows = ROWS.find(response.body)?.groupValues?.get(1)
            ?: return Presence.UNKNOWN
        return if (rows.isNotBlank()) Presence.PRESENT else Presence.ABSENT
    }

    /**
     * Makes the server re-read its ledger if the file changed underneath it.
     *
     * Best effort on purpose: if this fails the query below still runs, and a stale answer
     * comes back as [Presence.ABSENT] rather than as a wrong [Presence.PRESENT] — the
     * direction that costs a retry rather than a duplicate.
     */
    private fun refresh(slug: String) {
        runCatching {
            transport.request(address.resolve("/$slug/api/changed"), credentials = credentials)
        }
    }

    /** The JSON Fava expects, with the idempotency key carried as entry metadata. */
    internal fun entryJson(draft: TransactionDraft, key: String): String {
        val postings = draft.postings.joinToString(",") { posting ->
            val amount = posting.amount?.let { "${it.number.toPlainString()} ${it.currency}" }.orEmpty()
            """{"account":${quote(posting.account)},"amount":${quote(amount)}}"""
        }
        val meta = buildString {
            append("{").append(quote(META_KEY)).append(":").append(quote(key))
            draft.metadata.forEach { (name, value) ->
                append(",").append(quote(name)).append(":").append(quote(value))
            }
            append("}")
        }
        val tags = draft.tags.joinToString(",") { quote(it) }
        val links = draft.links.joinToString(",") { quote(it) }

        return """{"entries":[{"t":"Transaction","type":"Transaction",""" +
            """"date":${quote(draft.date)},"flag":${quote(draft.flag)},""" +
            """"payee":${quote(draft.payee.orEmpty())},"narration":${quote(draft.narration.orEmpty())},""" +
            """"meta":$meta,"tags":[$tags],"links":[$links],"postings":[$postings]}]}"""
    }

    private fun slugFrom(response: RemoteResponse): String? {
        val location = response.location ?: return null
        // "https://host/probe/income_statement/" or "/probe/income_statement/"
        var path = if ("://" in location) {
            location.substringAfter("://").substringAfter('/', "")
        } else {
            location
        }.trimStart('/')
        // A server mounted under a prefix redirects to "/fava/probe/...": the prefix is part
        // of the address already, not the slug.
        val prefix = address.basePath.trim('/')
        if (prefix.isNotEmpty() && (path == prefix || path.startsWith("$prefix/"))) {
            path = path.removePrefix(prefix).trimStart('/')
        }
        val slug = path.split('/').firstOrNull().orEmpty()
        // The slug is spliced into every later URL, and it comes from the network. Anything
        // that is not a plain path segment is treated as "not a Fava server".
        return slug.takeIf { SLUG.matches(it) && it != "." && it != ".." }
    }

    internal fun versionsFrom(body: String): Pair<String?, String?> {
        val codes = CODE.findAll(body).map { it.groupValues[1] }.toList()
        // "…Beancount version <code>3.2.3</code> and Fava <code>1.30.15</code>…"
        val beancount = codes.getOrNull(0)
        val fava = codes.getOrNull(1)
        return fava to beancount
    }

    companion object {
        /** The metadata key an idempotency token is written to. */
        const val META_KEY = "bca-id"

        fun newKey(): String = UUID.randomUUID().toString()

        private val SLUG = Regex("[A-Za-z0-9._~%-]+")
        private val CODE = Regex("<code>([^<]+)</code>")
        private val ROWS = Regex("\"rows\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)

        private fun quote(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }

        /** A key is ours and UUID-shaped, but a query string is still not a place to trust. */
        private fun escape(value: String): String = value.replace("'", "")

        private fun urlEncode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")
    }
}
