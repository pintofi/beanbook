package dev.beancount.core.domain

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
    val comment: String? = null,
) {
    val isElided: Boolean get() = amount == null
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
) {

    /** Problems that must be resolved before this can be written. */
    fun validate(): List<DraftProblem> {
        val problems = mutableListOf<DraftProblem>()

        if (!DATE.matches(date)) problems += DraftProblem.InvalidDate(date)
        if (flag !in VALID_FLAGS) problems += DraftProblem.InvalidFlag(flag)
        if (postings.size < 2) problems += DraftProblem.TooFewPostings(postings.size)

        postings.forEach { posting ->
            if (!ACCOUNT.matches(posting.account)) {
                problems += DraftProblem.InvalidAccount(posting.account)
            }
        }

        // At most one posting may be elided: Beancount can infer one missing amount, not two.
        val elided = postings.count { it.isElided }
        if (elided > 1) problems += DraftProblem.TooManyElidedPostings(elided)

        // Only complain about imbalance when nothing is elided — an elided posting is
        // exactly how the user asks Beancount to work the remainder out.
        if (elided == 0 && postings.isNotEmpty()) {
            val residuals = Balance.of(postings.mapNotNull { it.amount }).residuals()
            val tolerance = Balance.toleranceFor(postings.mapNotNull { it.amount })
            if (residuals.any { it.number.abs() > tolerance }) {
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
        Balance.of(postings.mapNotNull { it.amount }).residuals()

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
        builder.append('\n')

        metadata.forEach { (key, value) ->
            builder.append(pad).append(key).append(": ").append(quote(value)).append('\n')
        }

        postings.forEach { posting ->
            val left = pad + posting.account
            if (posting.amount == null) {
                builder.append(left)
            } else {
                val right = posting.amount.number.toPlainString() + " " + posting.amount.currency
                val padding = (currencyColumn - left.length - right.length).coerceAtLeast(1)
                builder.append(left).append(" ".repeat(padding)).append(right)
            }
            posting.comment?.takeIf { it.isNotBlank() }?.let { builder.append("  ; ").append(it) }
            builder.append('\n')
        }

        return builder.toString()
    }

    private companion object {
        // Shared with every other directive type, so escaping cannot drift between them.
        val DATE = BeancountText.DATE
        val ACCOUNT = BeancountText.ACCOUNT
        val TAG = BeancountText.TAG
        val VALID_FLAGS = setOf("*", "!")

        fun quote(value: String): String = BeancountText.quote(value)
    }
}

sealed interface DraftProblem {
    data class InvalidDate(val value: String) : DraftProblem
    data class InvalidFlag(val value: String) : DraftProblem
    data class TooFewPostings(val count: Int) : DraftProblem
    data class InvalidAccount(val account: String) : DraftProblem
    data class TooManyElidedPostings(val count: Int) : DraftProblem
    data class DoesNotBalance(val residuals: List<Amount>) : DraftProblem
    data class InvalidTag(val tag: String) : DraftProblem
    data class InvalidLink(val link: String) : DraftProblem
    data class InvalidCurrency(val currency: String) : DraftProblem
    data class MissingText(val what: String) : DraftProblem
    data class PathOutsideProject(val path: String) : DraftProblem
    data object MissingAmount : DraftProblem

    /** Wording the user sees. Says what is wrong, never "invalid input". */
    fun describe(): String = when (this) {
        is InvalidDate -> "\"$value\" is not a date in YYYY-MM-DD form"
        is InvalidFlag -> "\"$value\" is not a transaction flag (* or !)"
        is TooFewPostings -> "a transaction needs at least two postings; this has $count"
        is InvalidAccount -> "\"$account\" is not a valid account name"
        is TooManyElidedPostings -> "$count postings have no amount; Beancount can work out only one"
        is DoesNotBalance -> "does not balance: " + residuals.joinToString(", ") { it.format() }
        is InvalidTag -> "\"$tag\" is not a valid tag"
        is InvalidLink -> "\"$link\" is not a valid link"
        is InvalidCurrency -> "\"$currency\" is not a valid currency or commodity"
        is MissingText -> "the $what is empty"
        is PathOutsideProject ->
            "\"$path\" points outside the project; document links must stay inside it"
        MissingAmount -> "an amount is required"
    }
}

/** Completion candidates, taken from the ledger itself rather than a parallel database. */
data class LedgerVocabulary(
    val accounts: List<String>,
    val payees: List<String>,
    val narrations: List<String>,
    val currencies: List<String>,
    val tags: List<String>,
) {
    fun accountsMatching(prefix: String): List<String> = match(accounts, prefix)
    fun payeesMatching(prefix: String): List<String> = match(payees, prefix)

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

    companion object {
        const val SUGGESTION_LIMIT = 12

        fun from(snapshot: LedgerSnapshot): LedgerVocabulary = LedgerVocabulary(
            accounts = snapshot.accounts.map { it.name }.distinct().sorted(),
            payees = snapshot.journal.mapNotNull { it.payee }.distinct().sorted(),
            narrations = snapshot.journal.mapNotNull { it.narration }.distinct().sorted(),
            currencies = snapshot.accounts
                .flatMap { account -> account.positions.map { it.currency } }
                .distinct().sorted(),
            tags = snapshot.journal.flatMap { it.tags }.distinct().sorted(),
        )
    }
}
