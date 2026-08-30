package dev.beancount.core.domain

/**
 * A parsed ledger, as the UI sees it.
 *
 * Everything here comes from Beancount itself — the app computes no accounting facts of
 * its own. Amounts arrive as [Amount] (decimal), never as numbers that have been through
 * a `Double` (invariant 1).
 */
data class LedgerSnapshot(
    val title: String?,
    val operatingCurrencies: List<String>,
    val entryCount: Int,
    val entryCounts: Map<String, Int>,
    val accounts: List<AccountBalance>,
    val journal: List<JournalEntry>,
    val errors: List<LedgerError>,
    val includedFiles: List<String>,
    val loadMillis: Long,
    /**
     * Commodities the ledger *declares*, which is not the same as the ones it holds.
     *
     * The engine has always reported these and `LedgerReader` discarded them, so a commodity
     * you had declared but not yet used could not be picked in the Add form (B6).
     */
    val commodities: List<String> = emptyList(),
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()

    /**
     * Totals per currency across the accounts under [prefix] (e.g. "Assets").
     *
     * Beancount account names are colon-separated, so a prefix match is the whole of the
     * tree logic — no separate hierarchy needs maintaining.
     */
    fun totalFor(prefix: String): List<Amount> =
        Balance.of(
            accounts
                .filter { it.name == prefix || it.name.startsWith("$prefix:") }
                .flatMap { it.positions }
        ).residuals()
}

/**
 * One account's balance, in the three forms a holding actually has (B14).
 *
 * Ten shares, what they cost, and what they are worth are three different answers, and a
 * ledger that can only give the first cannot answer the question people open the app to ask.
 *
 * [atValue] falls back to units for a commodity the ledger never priced -- Beancount's own
 * behaviour, and the honest one: presenting a purchase price as a current valuation would be
 * wrong every day after the purchase.
 */
data class AccountBalance(
    val name: String,
    val positions: List<Amount>,
    val atCost: List<Amount> = positions,
    val atValue: List<Amount> = positions,
) {
    /** The last segment, for display in a tree: "Assets:Bank:Checking" -> "Checking". */
    val leaf: String get() = name.substringAfterLast(':')

    val depth: Int get() = name.count { it == ':' }
}

data class JournalEntry(
    val date: String,
    val flag: String?,
    val payee: String?,
    val narration: String?,
    val tags: List<String>,
    val links: List<String>,
    val postings: List<JournalPosting>,
    val file: String?,
    val line: Int?,
    /** The user's own metadata keys. Beancount's bookkeeping is filtered out upstream. */
    val metadata: Map<String, String> = emptyMap(),
) {
    /** What to show as the entry's headline, mirroring how Beancount reads on paper. */
    val description: String
        get() = listOfNotNull(payee, narration).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * Documents this entry links to, by the convention `fava.plugins.link_documents` uses
     * (B17): a `document` metadata key whose value is a path.
     *
     * A list because a transaction can carry several, and because the singular key name
     * would otherwise suggest the app only ever looks at one.
     */
    val linkedDocuments: List<String>
        get() = metadata.filterKeys { it == "document" || it.startsWith("document-") }
            .values.filter { it.isNotBlank() }.toList()
}

data class JournalPosting(val account: String, val amount: Amount?)

/**
 * A Beancount error, with the position it came from.
 *
 * Errors are a normal state of a ledger, not a crash: the app shows them and keeps
 * working (docs/ux.md §2).
 */
data class LedgerError(
    val message: String,
    val file: String?,
    val line: Int?,
    val type: String,
) {
    /** "accounts.beancount:32" — the form an editor can jump to. */
    val position: String?
        get() = file?.substringAfterLast('/')?.let { name ->
            if (line != null) "$name:$line" else name
        }
}
