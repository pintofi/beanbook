package dev.beancount.engine.local

import android.content.Context
import dev.beancount.core.domain.AccountBalance
import dev.beancount.core.domain.Amount
import dev.beancount.core.domain.JournalEntry
import dev.beancount.core.domain.JournalPosting
import dev.beancount.core.domain.LedgerError
import dev.beancount.core.domain.LedgerSnapshot
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
 * JSON is the wire format across the Chaquopy boundary; it is decoded with the platform's
 * own `org.json`, which keeps a GPL-incompatible JSON library out of the dependency graph
 * (docs/architecture.md §7.1).
 */
class LedgerReader(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun read(ledgerPath: String, journalLimit: Int = 200): Result<LedgerSnapshot> =
        withContext(io) {
            runCatching {
                val json = PythonRuntime.ensureStarted(context)
                    .getModule("ledger")
                    .callAttr("inspect", ledgerPath, journalLimit)
                    .toString()
                parse(JSONObject(json))
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
        loadMillis = root.optDouble("load_ms", 0.0).toLong(),
    )

    private fun JSONObject.toAccountBalance(): AccountBalance = AccountBalance(
        name = getString("name"),
        positions = optJSONArray("positions").map { it.toAmount() }.filterNotNull(),
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
    )

    private fun JSONObject.toLedgerError(): LedgerError = LedgerError(
        message = optString("message"),
        file = optStringOrNull("file"),
        line = if (isNull("line")) null else optInt("line"),
        type = optString("type"),
    )
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
