package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * The window a spending card covers, and the one before it to compare against.
 *
 * Offered rather than fixed: "this month" is the wrong question on the 1st, when it holds one
 * day and last month holds thirty; and somebody who reconciles quarterly wants a year. The
 * comparison period is always the same length as the period, immediately before it, so the
 * two figures mean the same thing.
 */
enum class SpendingPeriod(val label: String, val shortLabel: String) {
    THIS_MONTH("This month", "Month"),
    LAST_30_DAYS("Last 30 days", "30 days"),
    THIS_YEAR("This year", "Year"),
    ;

    /** The window, inclusive of both ends. */
    fun range(today: LocalDate): ClosedRange<LocalDate> = when (this) {
        THIS_MONTH -> today.withDayOfMonth(1)..today
        LAST_30_DAYS -> today.minusDays(29)..today
        THIS_YEAR -> today.withDayOfYear(1)..today
    }

    /**
     * The window before it, of the same length.
     *
     * For a calendar month that is the previous calendar month in full, which is what a
     * person means by "against last month" -- not the same number of days ending a month ago.
     */
    fun previousRange(today: LocalDate): ClosedRange<LocalDate> = when (this) {
        THIS_MONTH -> {
            val start = today.withDayOfMonth(1).minusMonths(1)
            start..start.withDayOfMonth(start.lengthOfMonth())
        }
        LAST_30_DAYS -> today.minusDays(59)..today.minusDays(30)
        THIS_YEAR -> {
            val start = today.withDayOfYear(1).minusYears(1)
            start..start.withDayOfYear(start.lengthOfYear())
        }
    }
}

/** One category and what it took, this period and last. */
data class CategorySpend(
    val account: String,
    val amount: Amount,
    val previous: Amount?,
) {
    /** What changed, or null when there is nothing to compare against. */
    val change: Amount? get() = previous?.let { Amount(amount.number.subtract(it.number), amount.currency) }

    /** The share of [total] this category takes, 0f..1f, for a bar that is read at a glance. */
    fun shareOf(total: Amount): Float {
        if (total.number.signum() == 0) return 0f
        return amount.number.divide(total.number, 4, java.math.RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
    }
}

/**
 * Where the money went, by category, against the period before.
 *
 * The Journal lists entries; this totals them, which is the question the Journal cannot
 * answer without a person adding numbers up by eye.
 *
 * One currency, because a total of three currencies is a list of holdings rather than a
 * total: the ledger's operating currency where it declares one, otherwise the currency most
 * of the period's spending is in. Anything in another currency is counted in [ignored] rather
 * than dropped silently, so a figure is never quietly short.
 */
data class SpendingReport(
    val period: SpendingPeriod,
    val currency: String,
    val total: Amount,
    val previousTotal: Amount?,
    val categories: List<CategorySpend>,
    val ignoredCurrencies: List<String>,
) {
    val isEmpty: Boolean get() = categories.isEmpty()
}

/**
 * What the app knows needs a person, in one place.
 *
 * Every one of these already existed and was on a different screen: errors on the Journal,
 * unfiled documents in Organize, a mirror waiting on a decision under More. A home screen
 * that says "three things need you" and takes you to each is the one thing a home screen can
 * do that a list of entries cannot.
 */
data class AttentionItem(
    val headline: String,
    val detail: String,
    val destination: Destination,
) {
    enum class Destination { JOURNAL, DOCUMENTS, MIRROR, PLUGINS }
}

/** The computations behind the home cards. Pure, so the arithmetic is testable. */
object HomeInsights {

    /** How deep a category is: `Expenses:Food:Coffee` counts under `Expenses:Food`. */
    const val CATEGORY_DEPTH = 2

    /**
     * Spending per category over [period], with the same window a period earlier.
     *
     * Only postings under the ledger's own expenses tree, and only ones carrying an amount:
     * an interpolated leg is filled in by Beancount before this sees it, and a posting with
     * no amount at all is not a number anybody can total.
     */
    fun spending(
        ledger: LedgerSnapshot,
        period: SpendingPeriod,
        today: LocalDate,
        topCategories: Int = DEFAULT_CATEGORIES,
        valuation: Valuation = Valuation.UNITS,
    ): SpendingReport {
        val root = ledger.accountNames.expenses
        val now = period.range(today)
        val before = period.previousRange(today)

        val current = mutableMapOf<String, MutableMap<String, BigDecimal>>()
        val past = mutableMapOf<String, MutableMap<String, BigDecimal>>()
        val seenCurrencies = mutableMapOf<String, BigDecimal>()
        val book = PriceBook(ledger.rates)
        val operating = ledger.operatingCurrencies.firstOrNull()

        ledger.journal.forEach { entry ->
            val date = runCatching { LocalDate.parse(entry.date) }.getOrNull() ?: return@forEach
            val bucket = when {
                date in now -> current
                date in before -> past
                else -> return@forEach
            }
            entry.postings.forEach { posting ->
                if (!posting.account.startsWith("$root:") && posting.account != root) return@forEach
                // On the chosen basis: units as written, the cost they were bought at,
                // or their value at the latest price in the operating currency.
                val amount = valuation.ofPosting(posting, book, operating) ?: return@forEach
                val category = category(posting.account)
                bucket.getOrPut(category) { mutableMapOf() }
                    .merge(amount.currency, amount.number, BigDecimal::add)
                if (bucket === current) {
                    seenCurrencies.merge(amount.currency, amount.number.abs(), BigDecimal::add)
                }
            }
        }

        // One currency, chosen the way the ledger would choose it.
        val currency = ledger.operatingCurrencies.firstOrNull()
            ?: seenCurrencies.maxByOrNull { it.value }?.key
            ?: ""
        if (currency.isEmpty()) {
            return SpendingReport(period, "", Amount(BigDecimal.ZERO, "USD"), null, emptyList(), emptyList())
        }

        fun totals(bucket: Map<String, Map<String, BigDecimal>>): Map<String, BigDecimal> =
            bucket.mapValues { (_, byCurrency) -> byCurrency[currency] ?: BigDecimal.ZERO }
                .filterValues { it.signum() != 0 }

        val nowTotals = totals(current)
        val pastTotals = totals(past)

        val categories = nowTotals.entries
            .sortedByDescending { it.value }
            .take(topCategories.coerceAtLeast(1))
            .map { (account, value) ->
                CategorySpend(
                    account = account,
                    amount = Amount(value, currency),
                    previous = pastTotals[account]?.let { Amount(it, currency) }
                        // Zero, not null, when the period before holds this ledger at all:
                        // "nothing last month" is a comparison, and hiding it would read as
                        // "no data" for a category that is genuinely new.
                        ?: if (pastTotals.isEmpty()) null else Amount(BigDecimal.ZERO, currency),
                )
            }

        return SpendingReport(
            period = period,
            currency = currency,
            total = Amount(nowTotals.values.fold(BigDecimal.ZERO, BigDecimal::add), currency),
            previousTotal = if (pastTotals.isEmpty()) {
                null
            } else {
                Amount(pastTotals.values.fold(BigDecimal.ZERO, BigDecimal::add), currency)
            },
            categories = categories,
            ignoredCurrencies = seenCurrencies.keys.filterNot { it == currency }.sorted(),
        )
    }

    /**
     * Entries the file marks `!`: seen, not settled.
     *
     * A to-do list rather than a log, which is why it is not the Journal with a filter: the
     * point is the count going down.
     */
    fun pending(ledger: LedgerSnapshot, limit: Int = DEFAULT_PENDING): List<JournalEntry> =
        ledger.journal.filter { it.flag == "!" }.take(limit)

    /** How many entries are marked `!`, including the ones a capped list does not show. */
    fun pendingCount(ledger: LedgerSnapshot): Int = ledger.journal.count { it.flag == "!" }

    /**
     * What needs a person, from the ledger and from the two things only the app knows.
     *
     * [unfiledDocuments] and [brokenDocumentLinks] come from the documents screen's own
     * reconciliation, and [mirrorConflicts] from the mirror: this function does not go
     * looking for either, because a domain that reached for a folder would not be one.
     */
    fun attention(
        ledger: LedgerSnapshot,
        unfiledDocuments: Int = 0,
        brokenDocumentLinks: Int = 0,
        mirrorConflicts: Int = 0,
    ): List<AttentionItem> {
        val items = mutableListOf<AttentionItem>()
        val onEntries = JournalErrors.attach(ledger.journal, ledger.errors)
        val errorCount = ledger.errors.size
        if (errorCount > 0) {
            items += AttentionItem(
                headline = if (errorCount == 1) "1 error in this ledger" else "$errorCount errors in this ledger",
                detail = if (onEntries.isEmpty()) {
                    "None of them is on a particular entry. Tap to read them in the Journal."
                } else {
                    "${onEntries.values.sumOf { it.size }} of them are on entries, marked in the Journal."
                },
                destination = AttentionItem.Destination.JOURNAL,
            )
        }
        // Fava extensions that do not import: Fava lists them only on its own Errors page,
        // so the ledger read as healthy while a report page it names was missing.
        val failedExtensions = ledger.extensionHealth.filterValues { it != null }.keys.sorted()
        if (failedExtensions.isNotEmpty()) {
            items += AttentionItem(
                headline = if (failedExtensions.size == 1) {
                    "1 report extension did not load"
                } else {
                    "${failedExtensions.size} report extensions did not load"
                },
                detail = failedExtensions.joinToString(", ") +
                    (if (failedExtensions.size == 1) ". Its pages are" else ". Their pages are") +
                    " missing from Reports. Tap to see why in Plugins.",
                destination = AttentionItem.Destination.PLUGINS,
            )
        }
        if (brokenDocumentLinks > 0) {
            items += AttentionItem(
                headline = if (brokenDocumentLinks == 1) {
                    "1 document is missing"
                } else {
                    "$brokenDocumentLinks documents are missing"
                },
                detail = "The ledger links to a file that is no longer in the project.",
                destination = AttentionItem.Destination.DOCUMENTS,
            )
        }
        if (unfiledDocuments > 0) {
            items += AttentionItem(
                headline = if (unfiledDocuments == 1) {
                    "1 document is not linked"
                } else {
                    "$unfiledDocuments documents are not linked"
                },
                detail = "They are in the project, but no entry or account links to them.",
                destination = AttentionItem.Destination.DOCUMENTS,
            )
        }
        if (mirrorConflicts > 0) {
            items += AttentionItem(
                headline = if (mirrorConflicts == 1) {
                    "1 file needs your decision"
                } else {
                    "$mirrorConflicts files need your decision"
                },
                detail = "Changed here and in your folder. Both versions are kept.",
                destination = AttentionItem.Destination.MIRROR,
            )
        }
        return items
    }

    private fun category(account: String): String =
        account.split(':').take(CATEGORY_DEPTH).joinToString(":")

    /** Enough to see the shape of a month without becoming a report. */
    const val DEFAULT_CATEGORIES = 5

    /** A to-do list nobody reads past ten. */
    const val DEFAULT_PENDING = 10
}
