package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.time.LocalDate

/** One posting of a template. A null [amount] stays elided when the template is applied. */
data class TemplatePosting(
    val account: String,
    val amount: BigDecimal? = null,
)

/**
 * A named starting point for a transaction: rent, the weekly shop, a payslip split.
 *
 * Templates **prefill, they never decide** (docs/ux.md §4). Three rules follow from that,
 * and the tests hold this class to all three:
 *
 *  * **A template has no date.** It is applied to a date the user chose; storing one would
 *    let a stale date be written without anyone looking at it.
 *  * **Applying produces an ordinary [TransactionDraft]**, which is then validated and
 *    previewed like anything typed by hand. A template cannot smuggle text into the ledger
 *    along a path that skips the preview.
 *  * **Elision is preserved.** A posting saved without an amount stays without one, so
 *    Beancount still computes the remainder; the template never turns into a balancing rule
 *    of our own.
 */
data class EntryTemplate(
    val name: String,
    val currency: String,
    val postings: List<TemplatePosting>,
    val payee: String? = null,
    val narration: String? = null,
    val flag: String = "*",
    val tags: List<String> = emptyList(),
    val links: List<String> = emptyList(),
) {

    /** The draft this template becomes on [date]. Validated by the caller, like any other. */
    fun apply(date: LocalDate): TransactionDraft = TransactionDraft(
        date = date.toString(),
        flag = flag,
        payee = payee?.takeIf { it.isNotBlank() },
        narration = narration?.takeIf { it.isNotBlank() },
        tags = tags,
        links = links,
        postings = postings.map { posting ->
            PostingDraft(
                account = posting.account,
                amount = posting.amount?.let { Amount(it, currency) },
            )
        },
    )

    /**
     * Problems with the template itself. A template is checked when it is saved rather than
     * only when it is used: a template that can never produce a valid entry is a trap that
     * would otherwise be sprung later, on a date the user cares about.
     */
    fun validate(): List<DraftProblem> {
        val problems = mutableListOf<DraftProblem>()
        if (name.isBlank()) problems += DraftProblem.MissingText("template name")
        if (!BeancountText.CURRENCY.matches(currency)) {
            problems += DraftProblem.InvalidCurrency(currency)
        }
        // Everything else is the draft's own business: applying it to any date exercises the
        // same account, flag, tag and elision rules the editor uses.
        problems += apply(REFERENCE_DATE).validate().filterNot { it is DraftProblem.InvalidDate }
        return problems
    }

    companion object {
        /**
         * A date that is only ever used to validate — never written. Applying a template
         * always uses the date the user is entering against.
         */
        private val REFERENCE_DATE: LocalDate = LocalDate.of(2000, 1, 1)

        /**
         * Captures what is on screen as a template, keeping the currency of the first
         * posting that has one so the amounts mean what they meant when it was saved.
         */
        fun from(name: String, draft: TransactionDraft, fallbackCurrency: String): EntryTemplate =
            EntryTemplate(
                name = name.trim(),
                currency = draft.postings.firstNotNullOfOrNull { it.amount?.currency }
                    ?: fallbackCurrency,
                postings = draft.postings.map { TemplatePosting(it.account, it.amount?.number) },
                payee = draft.payee,
                narration = draft.narration,
                flag = draft.flag,
                tags = draft.tags,
                links = draft.links,
            )
    }
}
