package dev.beancount.engine.remote

import dev.beancount.core.domain.TransactionDraft
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
 * A self-hosted Fava, spoken to over HTTPS (docs/feasibility/s12-fava-api.md).
 *
 * The idempotency rule is the reason this class exists rather than a handful of request
 * helpers. **Fava does not deduplicate**: two identical `PUT`s create two identical
 * transactions, and a submit that times out leaves the client genuinely unable to tell
 * whether the server got it. So every submission carries a key in its metadata, and any
 * retry asks the server whether that key is already there *before* sending anything
 * (SPEC.md J4: a retried submit cannot create a duplicate entry).
 */
class FavaClient(
    private val address: RemoteAddress,
    private val transport: Transport = HttpsTransport(),
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
            if (checkFirst && isPresent(slug, key)) return SubmitResult.AlreadyPresent(key)

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

    /** Whether an entry carrying [key] is already on the server. */
    fun isPresent(slug: String, key: String): Boolean {
        val query = "SELECT narration WHERE any_meta('$META_KEY') = '${escape(key)}'"
        val response = transport.request(
            url = address.resolve("/$slug/api/query?query_string=" + urlEncode(query)),
            credentials = credentials,
        )
        if (!response.isSuccess) return false
        // Rows are the only thing that matters, and any row means it is there.
        return ROWS.find(response.body)?.groupValues?.get(1)?.isNotBlank() == true
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
        val path = location.substringAfter("://").substringAfter('/')
        return path.trim('/').split('/').firstOrNull()?.takeIf { it.isNotBlank() }
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
