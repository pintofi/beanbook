package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal

/**
 * A transaction being composed in the UI, and the Beancount text it will become.
 *
 * Rendering lives here — pure Kotlin, no Android — because this is where guided entry can
 * quietly corrupt a ledger. Two rules the tests hold it to:
 *
 *  * **Nothing is invented.** The app never silently balances a transaction, rounds an
 *    amount, substitutes a currency, or creates an account (docs/ux.md §4). An imbalance is
 *    shown to the user, not fixed behind their back.
 *  * **Nothing is mangled.** Payees, narrations and metadata values go through Beancount's
 *    own quoting rules, so a quote or backslash in a payee name cannot break the file.
 */
data class PostingDraft(
    val account: String,
    val amount: Amount? = null,
    /**
     * What these units were acquired at, written `{...}`.
     *
     * A holding is units *and* the lot they belong to. Dropping the cost turns a fund bought
     * at 400 and one bought at 103 into the same line, and turns the gain on a sale into a
     * number nothing in the file explains.
     */
    val cost: Cost? = null,
    /** What these units were exchanged at, per unit, written `@ ...`. */
    val price: Amount? = null,
    val comment: String? = null,
    /**
     * Keys describing this leg of the transaction rather than the whole event.
     *
     * Beancount distinguishes the two, and so does this: an invoice number against one
     * account, or a document linked to one side of a transfer, is not a statement about the
     * other side.
     */
    val metadata: Map<String, String> = emptyMap(),
    /**
     * The rest of the line after the units, as typed: a lot, a price, or both.
     *
     * Written into the file exactly as entered, to the right of the amount and commodity. When
     * set it takes the place of [cost] and [price] in the text; they stay for the drafts that
     * arrive already knowing them, such as an entry being edited as fields.
     */
    val details: String? = null,
    /**
     * Full-line comments written above this posting, kept exactly as the file has them (what
     * follows the `;`). An entry edited as fields must not lose a comment, and the form has no
     * box for one, so it is carried here and written back where it was.
     */
    val notesBefore: List<String> = emptyList(),
    /** Keys in [metadata] whose values the file writes bare (a number, a date), not as a string. */
    val bareMetadata: Set<String> = emptySet(),
) {
    val isElided: Boolean get() = amount == null

    /** [details] read back, or null when there are none. */
    val readDetails: CommodityDetails?
        get() = details?.takeIf { it.isNotBlank() }?.let { CommodityDetails.parse(it) }

    /**
     * What this posting contributes to the balance.
     *
     * Beancount weighs a posting held at cost by its cost and one exchanged at a price by
     * that price; units are the weight only when it has neither. A transaction that converts
     * 150.00 units of one fund into 710.47 of another balances in rupees and in nothing else,
     * so weighing it by units would report every such entry as broken.
     *
     * Cost wins over price when a posting carries both, which is Beancount's own rule: the
     * price on a reduction records what it went out at, and the books move at cost.
     */
    fun weight(): Amount? {
        val units = amount ?: return null
        readDetails?.let { return it.weight(units) }
        val per = cost?.amount ?: price ?: return units
        return Amount(units.number.multiply(per.number), per.currency)
    }
}

data class TransactionDraft(
    /** ISO date, as Beancount writes it. */
    val date: String,
    val flag: String = "*",
    val payee: String? = null,
    val narration: String? = null,
    val tags: List<String> = emptyList(),
    val links: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val postings: List<PostingDraft> = emptyList(),
    /** A comment at the end of the first line, after the `;`, as the file has it. */
    val headerComment: String? = null,
    /** Full-line comments between the first line and the first metadata or posting line. */
    val notes: List<String> = emptyList(),
    /** Full-line comments after the last posting. */
    val trailingNotes: List<String> = emptyList(),
    /** Keys in [metadata] whose values the file writes bare (a number, a date), not as a string. */
    val bareMetadata: Set<String> = emptySet(),
) {

    /**
     * Problems that must be resolved before this can be written.
     *
     * @param format the open ledger's own rules. `option "inferred_tolerance_default"`
     *   is what *the file* calls close enough to balance, and an app deciding that for itself
     *   is an app disagreeing with the ledger it is showing.
     */
    fun validate(
        format: LedgerFormat = LedgerFormat.PLAIN,
        /**
         * The accounts the ledger has opened, or null when that is not known or does not
         * matter (a ledger with `auto_accounts`). A posting to any other account is an error
         * Beancount reports for the whole ledger, so it is refused here, before the write.
         */
        openAccounts: Set<String>? = null,
    ): List<DraftProblem> {
        val problems = mutableListOf<DraftProblem>()

        if (!DATE.matches(date)) problems += DraftProblem.InvalidDate(date)
        if (flag !in VALID_FLAGS) problems += DraftProblem.InvalidFlag(flag)

        // A key Beancount cannot parse would be written into the file and break the next
        // load, so it is refused here rather than discovered after the write.
        (metadata.keys + postings.flatMap { it.metadata.keys }).forEach { key ->
            if (!BeancountText.META_KEY.matches(key)) {
                problems += DraftProblem.InvalidMetadataKey(key)
            }
        }
        if (postings.size < 2) problems += DraftProblem.TooFewPostings(postings.size)

        postings.forEach { posting ->
            if (!ACCOUNT.matches(posting.account)) {
                problems += DraftProblem.InvalidAccount(posting.account)
            } else if (openAccounts != null && posting.account !in openAccounts) {
                problems += DraftProblem.UnknownAccount(posting.account)
            }
        }

        // At most one posting may be elided: Beancount can infer one missing amount, not two.
        val elided = postings.count { it.isElided }
        if (elided > 1) problems += DraftProblem.TooManyElidedPostings(elided)

        postings.forEach { posting ->
            (posting.readDetails as? CommodityDetails.Unreadable)?.let {
                problems += DraftProblem.UnreadableDetails(posting.account, it.reason)
            }
        }
        // A posting whose weight only Beancount's booking can decide -- an empty `{}`, say --
        // is left to Beancount, like an elided amount: the write re-parses the file, and a
        // lot that cannot be booked comes back as the error it is.
        val unweighable = postings.any { it.amount != null && it.readDetails != null && it.weight() == null }

        // Only complain about imbalance when nothing is elided — an elided posting is
        // exactly how the user asks Beancount to work the remainder out.
        if (elided == 0 && postings.isNotEmpty() && !unweighable) {
            val weights = postings.mapNotNull { it.weight() }
            val residuals = Balance.of(weights).residuals()
            val tolerances = Tolerances.infer(
                postings.mapNotNull { it.amount },
                format.tolerances,
                format.toleranceMultiplier,
            )
            if (residuals.any { it.number.abs() > tolerances.forCurrency(it.currency) }) {
                problems += DraftProblem.DoesNotBalance(residuals)
            }
        }

        tags.forEach { if (!TAG.matches(it)) problems += DraftProblem.InvalidTag(it) }
        links.forEach { if (!TAG.matches(it)) problems += DraftProblem.InvalidLink(it) }

        return problems
    }

    val isValid: Boolean get() = validate().isEmpty()

    /** The residual the user is shown while typing; empty when it balances. */
    fun residuals(): List<Amount> =
        Balance.of(postings.mapNotNull { it.weight() }).residuals()

    /**
     * The exact text that will be written. The UI shows this before saving, so what the
     * user approves is what lands in the file (docs/ux.md §4).
     *
     * @param currencyColumn where amounts are aligned, matching Fava's own default.
     */
    fun render(currencyColumn: Int = 61, indent: Int = 2): String {
        val builder = StringBuilder()
        val pad = " ".repeat(indent)

        builder.append(date).append(' ').append(flag)
        payee?.takeIf { it.isNotBlank() }?.let { builder.append(' ').append(quote(it)) }
        // Beancount reads a lone string as the narration, so a narration with no payee is
        // written as a single string — not as an empty payee followed by one.
        narration?.takeIf { it.isNotBlank() }?.let { builder.append(' ').append(quote(it)) }
        tags.forEach { builder.append(" #").append(it) }
        links.forEach { builder.append(" ^").append(it) }
        headerComment?.let { builder.append("  ;").append(it) }
        builder.append('\n')

        notes.forEach { builder.append(pad).append(';').append(it).append('\n') }

        metadata.forEach { (key, value) ->
            builder.append(pad).append(key).append(": ")
                .append(BeancountText.metadataValue(value, key in bareMetadata)).append('\n')
        }

        postings.forEach { posting ->
            posting.notesBefore.forEach { builder.append(pad).append(';').append(it).append('\n') }
            val left = pad + posting.account
            if (posting.amount == null) {
                builder.append(left)
            } else {
                val right = posting.amount.number.toPlainString() + " " + posting.amount.currency
                val padding = (currencyColumn - left.length - right.length).coerceAtLeast(1)
                builder.append(left).append(" ".repeat(padding)).append(right)
                // After the aligned amount, in the order Beancount reads them: what the units
                // were held at, then what they were exchanged at. Typed details go in as they
                // were typed.
                val typed = posting.details?.trim()?.takeIf { it.isNotEmpty() }
                if (typed != null) {
                    builder.append(' ').append(typed)
                } else {
                    posting.cost?.let { builder.append(' ').append(it.format()) }
                    posting.price?.let { builder.append(" @ ").append(it.format()) }
                }
            }
            posting.comment?.takeIf { it.isNotBlank() }?.let { builder.append("  ; ").append(it) }
            builder.append('\n')

            // Deeper than the posting. At the same depth Beancount reads these as the
            // transaction's, which is a different claim about the books.
            posting.metadata.forEach { (key, value) ->
                builder.append(pad).append(pad).append(key).append(": ")
                    .append(BeancountText.metadataValue(value, key in posting.bareMetadata)).append('\n')
            }
        }

        trailingNotes.forEach { builder.append(pad).append(';').append(it).append('\n') }

        return builder.toString()
    }

    companion object {
        // Shared with every other directive type, so escaping cannot drift between them.
        private val DATE = BeancountText.DATE
        private val ACCOUNT = BeancountText.ACCOUNT
        private val TAG = BeancountText.TAG
        private val VALID_FLAGS = setOf("*", "!")

        private fun quote(value: String): String = BeancountText.quote(value)

        /**
         * An entry already in the ledger, as a draft that can be entered again.
         *
         * [today] rather than the entry's own date, and that is the point: a clone is a *new*
         * transaction -- this month's rent, not a second copy of last month's. Carrying the
         * original date over would produce a duplicate that looks deliberate, which is the
         * failure invariant 6 exists to prevent, reached by hand rather than by a retry.
         *
         * The flag carries over. `!` means somebody wanted to look at this again, and cloning
         * it as `*` would quietly assert the copy is settled.
         */
        fun cloneOf(entry: JournalEntry, today: String): TransactionDraft = TransactionDraft(
            date = today,
            flag = entry.flag?.takeIf { it.isNotBlank() } ?: "*",
            payee = entry.payee,
            narration = entry.narration,
            tags = entry.tags,
            links = entry.links,
            postings = entry.postings.map {
                // Cost and price come too. A clone that kept the units and dropped the lot
                // would write a different transaction from the one it was made from, and
                // would not balance in the currency the original balanced in.
                PostingDraft(it.account, it.amount, cost = it.cost, price = it.price)
            },
        )
    }
}

sealed interface DraftProblem {
    data class InvalidDate(val value: String) : DraftProblem
    data class InvalidFlag(val value: String) : DraftProblem
    data class TooFewPostings(val count: Int) : DraftProblem
    data class InvalidAccount(val account: String) : DraftProblem

    /** A well-formed name the ledger has not opened. */
    data class UnknownAccount(val account: String) : DraftProblem
    data class TooManyElidedPostings(val count: Int) : DraftProblem
    data class DoesNotBalance(val residuals: List<Amount>) : DraftProblem
    data class InvalidTag(val tag: String) : DraftProblem
    data class InvalidLink(val link: String) : DraftProblem
    data class InvalidCurrency(val currency: String) : DraftProblem

    /** Metadata keys are lower-case and may not start with a digit. */
    data class InvalidMetadataKey(val key: String) : DraftProblem
    data class MissingText(val what: String) : DraftProblem
    data class PathOutsideProject(val path: String) : DraftProblem
    data object MissingAmount : DraftProblem

    /** A posting's commodity details that Beancount could not read. */
    data class UnreadableDetails(val account: String, val reason: String) : DraftProblem

    /** Wording the user sees. Says what is wrong, never "invalid input". */
    fun describe(): String = when (this) {
        is InvalidDate -> "\"$value\" is not a date in YYYY-MM-DD form"
        is InvalidFlag -> "\"$value\" is not a transaction flag (* or !)"
        is TooFewPostings -> "a transaction needs at least two postings; this has $count"
        is InvalidAccount -> "\"$account\" is not a valid account name; accounts look like Expenses:Food"
        is UnknownAccount ->
            "\"$account\" is not an account in this ledger; pick one from the list, or open it first"
        is TooManyElidedPostings -> "$count postings have no amount; Beancount can work out only one"
        is DoesNotBalance -> "does not balance: " + residuals.joinToString(", ") { it.format() }
        is InvalidTag -> "\"$tag\" is not a valid tag; tags are letters, digits, - _ / . and no spaces"
        is InvalidLink -> "\"$link\" is not a valid link; links are letters, digits, - _ / . and no spaces"
        is InvalidCurrency -> "\"$currency\" is not a valid commodity"
        is InvalidMetadataKey ->
            "\"$key\" cannot be a detail name; start it with a lower-case letter, then use " +
                "letters, digits, - or _"
        is MissingText -> "the $what is empty"
        is PathOutsideProject ->
            "\"$path\" points outside the project; document links must stay inside it"
        MissingAmount -> "an amount is required"
        is UnreadableDetails -> "the commodity details on $account: $reason"
    }
}

/** Completion candidates, taken from the ledger itself rather than a parallel database. */
data class LedgerVocabulary(
    val accounts: List<String>,
    val payees: List<String>,
    val narrations: List<String>,
    val currencies: List<String>,
    /**
     * What a new posting should be denominated in: the ledger's operating currency.
     *
     * It used to be whichever commodity sorted first, so a PKR ledger holding a little USD
     * offered "EUR" or "USD" by default and every entry had to be corrected.
     */
    val defaultCurrency: String? = null,
    val tags: List<String>,
    /** Links already used in the ledger, for the link field to offer back. */
    val links: List<String> = emptyList(),
    /** Newest first, for offering back what was used last time. */
    val recentEntries: List<JournalEntry> = emptyList(),
    /** What the ledger's `commodity_attr` plugin requires of a commodity directive. */
    val commodityAttributes: List<String> = emptyList(),
    /** Accounts the ledger has opened; null where any name is acceptable. */
    val openAccounts: Set<String>? = null,
) {
    fun accountsMatching(prefix: String): List<String> = match(accounts, prefix)
    fun payeesMatching(prefix: String): List<String> = match(payees, prefix)
    fun currenciesMatching(prefix: String): List<String> = match(currencies, prefix)

    private fun match(source: List<String>, prefix: String): List<String> {
        if (prefix.isBlank()) return source.take(SUGGESTION_LIMIT)
        val lower = prefix.lowercase()
        // Prefix matches first, then anything containing the text — how account names are
        // actually recalled ("Groceries" before "Expenses:Food:Groceries").
        val (starts, contains) = source
            .filter { it.lowercase().contains(lower) }
            .partition { it.lowercase().startsWith(lower) }
        return (starts + contains).take(SUGGESTION_LIMIT)
    }

    /**
     * The last transaction recorded for [payee], newest first.
     *
     * "Recent values" in the sense that helps: someone who shops at the same grocer every
     * week wants last week's accounts back, not a template they had to define first.
     * Nothing is filled in automatically — the UI offers it and the user chooses, because
     * silently reusing an old amount would be a way to record the wrong number.
     */
    fun lastEntryFor(payee: String): JournalEntry? =
        recentEntries.firstOrNull { it.payee.equals(payee, ignoreCase = true) }

    companion object {
        // The strip these fill scrolls horizontally, so this caps rendering cost rather than
        // hiding matches. At 12 a ledger with forty shops silently offered a third of them,
        // which reads as the app not knowing the payee.
        const val SUGGESTION_LIMIT = 60

        fun from(snapshot: LedgerSnapshot): LedgerVocabulary = LedgerVocabulary(
            // `leafonly` makes a posting to an account that has sub-accounts an error, so the
            // form does not offer one. The same list is used for every directive.
            accounts = snapshot.accounts.map { it.name }.distinct().sorted().let { names ->
                if (snapshot.hasPlugin("beancount.plugins.leafonly")) {
                    names.filterNot { name -> names.any { it.startsWith("$name:") } }
                } else {
                    names
                }
            },
            payees = snapshot.journal.mapNotNull { it.payee }.distinct().sorted(),
            narrations = snapshot.journal.mapNotNull { it.narration }.distinct().sorted(),
            // Declared *and* held. A commodity directive with nothing in it yet is still one
            // the user meant to use, and holding a balance in something never declared is
            // normal in a hand-written ledger, so neither source is sufficient alone.
            //
            // With `check_commodity` the declared ones are the only valid ones: a commodity
            // that is merely held is reported as an error by the plugin, so it is not offered.
            currencies = (
                snapshot.commodities +
                    if (snapshot.hasPlugin("beancount.plugins.check_commodity")) {
                        emptyList()
                    } else {
                        snapshot.accounts.flatMap { account -> account.positions.map { it.currency } }
                    }
                ).distinct().sorted(),
            defaultCurrency = snapshot.operatingCurrencies.firstOrNull()
                ?: snapshot.accounts.flatMap { it.positions }
                    .groupingBy { it.currency }.eachCount()
                    .maxByOrNull { it.value }?.key,
            tags = snapshot.journal.flatMap { it.tags }.distinct().sorted(),
            links = snapshot.journal.flatMap { it.links }.distinct().sorted(),
            recentEntries = snapshot.journal,
            commodityAttributes = snapshot.commodityAttributes,
            openAccounts = if (snapshot.hasPlugin("beancount.plugins.auto_accounts")) {
                null
            } else {
                snapshot.accounts.map { it.name }.toSet()
            },
        )
    }
}
