package dev.beancount.engine.local

import android.content.Context
import android.util.Log
import dev.beancount.core.domain.AccountBalance
import dev.beancount.core.domain.Amount
import dev.beancount.core.domain.DocumentDirective
import dev.beancount.core.domain.JournalEntry
import dev.beancount.core.domain.JournalPosting
import dev.beancount.core.domain.LedgerError
import dev.beancount.core.domain.LedgerSnapshot
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a ledger through Beancount and maps the result into domain models.
 *
 * Parsing a 25k-transaction ledger takes over a second on a mid-range device
 * (docs/milestones/m3.md), so every entry point here is `suspend` and dispatches to IO —
 * the main thread never parses.
 *
 * JSON is the wire format from the engine *process*; it is decoded with the platform's own
 * `org.json`, which keeps a GPL-incompatible JSON library out of the dependency graph
 * (docs/architecture.md §7.1).
 */
class LedgerReader(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connection: EngineConnection = EngineConnection(context),
) {

    /**
     * Answers from previous loads, so opening a large ledger does not wait for a full one.
     *
     * See [SnapshotCache] for why this exists and what makes an entry valid. In the app's
     * own cache directory, so the platform may reclaim it: losing it costs a slow load, and
     * nothing else.
     */
    private val cache = SnapshotCache(File(context.cacheDir, "ledger-snapshots"))

    /**
     * @param pluginDirs the enabled plugins' directories, comma-separated. Beancount imports
     *   a plugin itself when it reaches a `plugin` directive; all this does is make them
     *   findable for the duration of the load.
     */
    suspend fun read(
        ledgerPath: String,
        journalLimit: Int = 200,
        pluginDirs: String = "",
        allowBridge: Boolean = true,
    ): Result<LedgerSnapshot> =
        withContext(io) {
            val inputs = SnapshotCache.Inputs(journalLimit, pluginDirs, allowBridge)

            // The cached answer first, and only if it still applies to the files on disk.
            // This is the whole of the large-ledger startup fix (B21): the native screens
            // need a 200-row window and a per-account balance, neither of which grows with
            // the ledger, so there is no reason for opening one to cost a full load.
            val started = System.nanoTime()
            cache.read(ledgerPath, inputs)?.let { cached ->
                runCatching { parse(JSONObject(cached)) }
                    .onSuccess { snapshot ->
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        Log.i(TAG, "snapshot served from cache in ${elapsed} ms")
                        // The stored load_ms describes the load that filled the cache, not
                        // this one. Reporting it would overstate what just happened by
                        // several seconds, in the one number anyone would use to judge it.
                        return@withContext Result.success(snapshot.copy(loadMillis = elapsed))
                    }
                    .onFailure { Log.w(TAG, "cached snapshot did not parse; loading instead", it) }
            }

            // The summary comes back through a cache file: for a large ledger the JSON
            // (every account, plus the journal window) is not safely inside Binder's ~1 MB
            // transaction budget.
            val payload = connection.payloadFile("inspect")
            connection
                .withEngine {
                    it.inspectToFile(
                        ledgerPath,
                        journalLimit,
                        payload.absolutePath,
                        pluginDirs,
                        allowBridge,
                    )
                }
                .mapCatching { result ->
                    check(result == "ok") { result.removePrefix("error: ") }
                    val text = payload.consumeText()
                    val snapshot = parse(JSONObject(text))
                    // Cached against the files this load actually read, which is what makes
                    // an added `include` invalidate it next time.
                    cache.write(ledgerPath, snapshot.includedFiles + ledgerPath, inputs, text)
                    // Wall clock, not the engine's own `load_ms`, and on both paths.
                    //
                    // The engine reports how long Beancount spent, which excludes starting
                    // the call, writing the payload and reading it back. Left as it was, a
                    // cached load -- which is measured end to end -- would compare against a
                    // number that never included those costs, and on a small ledger appeared
                    // *slower* than a real load: 36 ms against 1 ms, for work that skips
                    // seconds of booking. One meaning, measured at one boundary.
                    snapshot.copy(loadMillis = (System.nanoTime() - started) / 1_000_000)
                }
        }

    private fun parse(root: JSONObject): LedgerSnapshot = LedgerSnapshot(
        title = root.optStringOrNull("title"),
        operatingCurrencies = root.optJSONArray("operating_currency").toStringList(),
        entryCount = root.optInt("entry_count"),
        entryCounts = root.optJSONObject("entry_counts").toIntMap(),
        accounts = root.optJSONArray("accounts").map { it.toAccountBalance() },
        journal = root.optJSONArray("journal").map { it.toJournalEntry() }.asReversed(),
        errors = root.optJSONArray("errors").map { it.toLedgerError() },
        includedFiles = root.optJSONArray("included_files").toStringList(),
        commodities = root.optJSONArray("commodities").toStringList(),
        documents = root.optJSONArray("documents").map { it.toDocumentDirective() },
        loadMillis = root.optDouble("load_ms", 0.0).toLong(),
    )

    private fun JSONObject.toDocumentDirective(): DocumentDirective = DocumentDirective(
        path = getString("path"),
        account = getString("account"),
        date = optStringOrNull("date").orEmpty(),
        file = optStringOrNull("file"),
        line = if (has("line") && !isNull("line")) optInt("line") else null,
        tags = optJSONArray("tags").toStringList(),
        links = optJSONArray("links").toStringList(),
    )

    private fun JSONObject.toAccountBalance(): AccountBalance = AccountBalance(
        name = getString("name"),
        positions = optJSONArray("positions").map { it.toAmount() }.filterNotNull(),
        atCost = optJSONArray("at_cost").map { it.toAmount() }.filterNotNull(),
        atValue = optJSONArray("at_value").map { it.toAmount() }.filterNotNull(),
    )

    private fun JSONObject.toAmount(): Amount? {
        val number = optStringOrNull("number") ?: return null
        val currency = optStringOrNull("currency") ?: return null
        // BigDecimal straight from the string Beancount produced: the value never passes
        // through a Double, so no precision is lost in transit.
        return Amount(BigDecimal(number), currency)
    }

    private fun JSONObject.toJournalEntry(): JournalEntry = JournalEntry(
        date = getString("date"),
        flag = optStringOrNull("flag"),
        payee = optStringOrNull("payee"),
        narration = optStringOrNull("narration"),
        tags = optJSONArray("tags").toStringList(),
        links = optJSONArray("links").toStringList(),
        postings = optJSONArray("postings").map {
            JournalPosting(it.getString("account"), it.toAmount())
        },
        file = optStringOrNull("file"),
        line = if (isNull("line")) null else optInt("line"),
        metadata = optJSONObject("metadata").toStringMap(),
    )

    /** A flat JSON object as a string map, for metadata the app displays rather than parses. */
    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().mapNotNull { key ->
            optStringOrNull(key)?.let { key to it }
        }.toMap()
    }

    private fun JSONObject.toLedgerError(): LedgerError = LedgerError(
        message = optString("message"),
        file = optStringOrNull("file"),
        line = if (isNull("line")) null else optInt("line"),
        type = optString("type"),
    )

    private companion object {
        const val TAG = "LedgerReader"
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun JSONObject?.toIntMap(): Map<String, Int> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { getInt(it) }
}

private fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { transform(getJSONObject(it)) }
