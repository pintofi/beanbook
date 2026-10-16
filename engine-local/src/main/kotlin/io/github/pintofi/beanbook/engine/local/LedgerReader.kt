package io.github.pintofi.beanbook.engine.local

import android.content.Context
import android.util.Log
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.AccountNames
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.Cost
import io.github.pintofi.beanbook.core.domain.DocumentDirective
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalPosting
import io.github.pintofi.beanbook.core.domain.LedgerError
import io.github.pintofi.beanbook.core.domain.LedgerFormat
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import java.io.File
import java.math.BigDecimal
import io.github.pintofi.beanbook.core.domain.LedgerPlugin
import io.github.pintofi.beanbook.core.domain.PriceRate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a ledger through Beancount and maps the result into domain models.
 *
 * Parsing a 25k-transaction ledger takes over a second on a mid-range device
 *, so every entry point here is `suspend` and dispatches to IO —
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

    /** Part of what a cached answer is valid for: an update can change what a ledger produces. */
    private val appVersion: Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    /**
     * @param pluginDirs the enabled plugins' directories, comma-separated. Beancount imports
     *   a plugin itself when it reaches a `plugin` directive; all this does is make them
     *   findable for the duration of the load.
     */
    suspend fun read(
        ledgerPath: String,
        /** 0 for every transaction. See the note beside `journal_limit` in ledger.py. */
        journalLimit: Int = 0,
        pluginDirs: String = "",
        allowBridge: Boolean = true,
        /** An inclusive ISO date range; empty bounds mean the whole file. */
        dateFrom: String = "",
        dateTo: String = "",
    ): Result<LedgerSnapshot> =
        withContext(io) {
            val inputs = SnapshotCache.Inputs(
                journalLimit, pluginDirs, allowBridge, dateFrom, dateTo, appVersion,
            )

            // The cached answer first, and only if it still applies to the files on disk.
            // This is the whole of the large-ledger startup fix: the native screens
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
                        dateFrom,
                        dateTo,
                    )
                }
                .mapCatching { result ->
                    check(result == "ok") { result.removePrefix("error: ") }
                    val text = payload.consumeText()
                    val snapshot = parse(JSONObject(text))
                    // Cached against the files this load actually read, which is what makes
                    // an added `include` invalidate it next time.
                    // Not when the extension answer is "not known yet": a cache would keep
                    // that for as long as the ledger's files did not change.
                    if (JSONObject(text).optBoolean("extension_health_complete", true)) {
                        cache.write(ledgerPath, snapshot.includedFiles + ledgerPath, inputs, text)
                    }
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
        format = root.toLedgerFormat(),
        accountNames = root.toAccountNames(),
        documentRoots = root.optJSONArray("document_dirs").toStringList(),
        commodityAttributes = root.optJSONArray("commodity_attributes").toStringList(),
        extensionHealth = root.optJSONObject("extension_health").let { health ->
            health?.keys()?.asSequence()?.associateWith { health.optStringOrNull(it) }.orEmpty()
        },
        plugins = root.optJSONArray("plugins").map {
            LedgerPlugin(it.getString("name"), it.optStringOrNull("config"))
        },
        rates = root.optJSONArray("rates").map { rate ->
            PriceRate(
                rate.getString("base"), rate.getString("quote"), BigDecimal(rate.getString("rate")),
            )
        },
        loadMillis = root.optDouble("load_ms", 0.0).toLong(),
    )

    /**
     * How the file asks to be shown.
     *
     * Everything here is Beancount's own answer -- the display context it built from the
     * numbers in the file, and the user's own options -- so an older snapshot without these
     * keys simply gets plain rendering rather than a guess.
     */
    /** What this file calls its five trees. A file that renames none gets the defaults. */
    private fun JSONObject.toAccountNames(): AccountNames {
        val names = optJSONObject("account_names") ?: return AccountNames.DEFAULT
        fun name(key: String, fallback: String) =
            names.optStringOrNull(key)?.takeIf { it.isNotBlank() } ?: fallback
        return AccountNames(
            assets = name("assets", "Assets"),
            liabilities = name("liabilities", "Liabilities"),
            equity = name("equity", "Equity"),
            income = name("income", "Income"),
            expenses = name("expenses", "Expenses"),
        )
    }

    private fun JSONObject.toLedgerFormat(): LedgerFormat {
        val precision = optJSONObject("display_precision")
        val tolerances = optJSONObject("tolerances")
        val fava = optJSONObject("fava_options")
        val grouped = optBoolean("render_commas", false)
        // Fava's own rule, followed rather than approximated: the `locale` option
        // decides both separators, and `render_commas` with no locale means the locale "en".
        // The platform's own symbols for that locale are used, which is what a German reader
        // means by "the comma scheme" -- not a guess at which character to swap in.
        val locale = fava?.optStringOrNull("locale")
            ?: if (grouped) "en" else null
        val symbols = locale?.let {
            runCatching {
                java.text.DecimalFormatSymbols(java.util.Locale.forLanguageTag(it.replace('_', '-')))
            }.getOrNull()
        }
        return LedgerFormat(
            groupThousands = grouped,
            groupSeparator = symbols?.groupingSeparator ?: ',',
            decimalSeparator = symbols?.decimalSeparator ?: '.',
            invertIncomeLiabilitiesEquity =
                fava?.optStringOrNull("invert_income_liabilities_equity").isTrue(),
            currencyColumn = fava?.optStringOrNull("currency_column")?.toIntOrNull()
                ?.takeIf { it > 0 } ?: LedgerFormat.DEFAULT_CURRENCY_COLUMN,
            indent = fava?.optStringOrNull("indent")?.toIntOrNull()?.takeIf { it > 0 }
                ?: LedgerFormat.DEFAULT_INDENT,
            // Under this file's own names for them, not the English three.
            invertedRoots = toAccountNames().let {
                listOf(it.income, it.liabilities, it.equity)
            },
            precision = precision?.keys()?.asSequence().orEmpty()
                .mapNotNull { key -> precision?.optInt(key, -1)?.takeIf { it >= 0 }?.let { key to it } }
                .toMap(),
            tolerances = tolerances?.keys()?.asSequence().orEmpty()
                .mapNotNull { key ->
                    tolerances?.optString(key)?.takeIf { it.isNotBlank() }
                        ?.let { value -> runCatching { key to java.math.BigDecimal(value) }.getOrNull() }
                }
                .toMap(),
        )
    }

    /** Fava reads "true", "True" and a bare option with no value as on; so does this. */
    private fun String?.isTrue(): Boolean =
        this != null && this.trim().lowercase() in setOf("true", "yes", "1", "on")

    private fun JSONObject.toDocumentDirective(): DocumentDirective = DocumentDirective(
        path = getString("path"),
        account = getString("account"),
        date = optStringOrNull("date").orEmpty(),
        file = optStringOrNull("file"),
        line = if (has("line") && !isNull("line")) optInt("line") else null,
        auto = optBoolean("auto", false),
        tags = optJSONArray("tags").toStringList(),
        links = optJSONArray("links").toStringList(),
    )

    private fun JSONObject.toAccountBalance(): AccountBalance = AccountBalance(
        name = getString("name"),
        positions = optJSONArray("positions").map { it.toAmount() }.filterNotNull(),
        atCost = optJSONArray("at_cost").map { it.toAmount() }.filterNotNull(),
        atValue = optJSONArray("at_value").map { it.toAmount() }.filterNotNull(),
        atOperating = optJSONArray("at_operating").map { it.toAmount() }.filterNotNull(),
    )

    private fun JSONObject.toAmount(): Amount? {
        val number = optStringOrNull("number") ?: return null
        val currency = optStringOrNull("currency") ?: return null
        // BigDecimal straight from the string Beancount produced: the value never passes
        // through a Double, so no precision is lost in transit.
        return Amount(BigDecimal(number), currency)
    }

    private fun JSONObject.toJournalPosting(): JournalPosting = JournalPosting(
        account = getString("account"),
        amount = toAmount(),
        cost = optJSONObject("cost")?.toCost(),
        price = optJSONObject("price")?.toAmount(),
    )

    /** A booked cost. Beancount has already resolved the lot, so the figure is the whole one. */
    private fun JSONObject.toCost(): Cost? = toAmount()?.let { amount ->
        Cost(amount = amount, date = optStringOrNull("date"), label = optStringOrNull("label"))
    }

    private fun JSONObject.toJournalEntry(): JournalEntry = JournalEntry(
        date = getString("date"),
        flag = optStringOrNull("flag"),
        payee = optStringOrNull("payee"),
        narration = optStringOrNull("narration"),
        tags = optJSONArray("tags").toStringList(),
        links = optJSONArray("links").toStringList(),
        postings = optJSONArray("postings").map { it.toJournalPosting() },
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
