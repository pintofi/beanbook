package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app balances entries, and fills in the blank posting, the way Beancount does.
 *
 * `tools/gen-balance-cases.py` wrote the cases: random entries with the precision and tolerance options
 * a ledger can set, each loaded into Beancount 3.2.3 and asked whether it balances and, where one posting
 * is blank, what number it put there. Nothing below is worked out by hand. A failure names the case, and
 * `src/test/resources/beancount-balance-cases.txt` has its text.
 */
class BeancountBalanceCasesTest {

    private class Case(
        val number: Int,
        val options: Map<String, List<String>>,
        val postings: List<PostingDraft>,
        val balanced: Boolean,
        val inferred: Amount?,
    ) {
        val format: LedgerFormat = LedgerFormat(
            tolerances = options["inferred_tolerance_default"].orEmpty().associate {
                it.substringBefore(':') to BigDecimal(it.substringAfter(':'))
            },
            toleranceMultiplier = options["tolerance_multiplier"]?.first()?.let { BigDecimal(it) }
                ?: Tolerances.DEFAULT_MULTIPLIER,
            inferToleranceFromCost = options["infer_tolerance_from_cost"]?.first().equals("TRUE", ignoreCase = true),
        )
        val draft = TransactionDraft(date = "2024-02-01", postings = postings)
        override fun toString() = "case $number (${options.entries.joinToString { "${it.key}=${it.value}" }})"
    }

    private fun amount(text: String): Amount {
        val (number, currency) = text.trim().split(' ')
        return Amount(BigDecimal(number), currency)
    }

    private val cases: List<Case> by lazy {
        val lines = javaClass.getResourceAsStream("/beancount-balance-cases.txt")!!
            .bufferedReader().readLines().filterNot { it.startsWith("#") || it.isBlank() }
        val parsed = mutableListOf<Case>()
        var number = 0
        var options = linkedMapOf<String, MutableList<String>>()
        var postings = mutableListOf<PostingDraft>()
        var balanced = true
        var inferred: Amount? = null
        for (line in lines) {
            val key = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            when (key) {
                "case" -> {
                    number = rest.trim().toInt()
                    options = linkedMapOf(); postings = mutableListOf(); balanced = true; inferred = null
                }
                "option" -> {
                    val name = rest.substringBefore(' ')
                    options.getOrPut(name) { mutableListOf() }.add(rest.substringAfter(' '))
                }
                "posting" -> {
                    val (account, units, details) = rest.split('|').map { it.trim() }
                    postings += PostingDraft(
                        account = account,
                        amount = units.takeIf { it.isNotEmpty() }?.let(::amount),
                        details = details.takeIf { it.isNotEmpty() },
                    )
                }
                "verdict" -> balanced = rest.trim() == "balanced"
                "inferred" -> inferred = amount(rest)
                "end" -> parsed += Case(number, options, postings, balanced, inferred)
            }
        }
        parsed
    }

    @Test fun `the cases were read`() {
        assertTrue(cases.size >= 800, "only ${cases.size} cases")
        assertTrue(cases.any { !it.balanced } && cases.any { it.balanced })
        assertTrue(cases.any { it.inferred != null })
    }

    @Test fun `an entry balances exactly when Beancount says it does`() {
        val wrong = cases.filter { it.postings.none { p -> p.isElided } }.filter { case ->
            val refused = case.draft.validate(case.format).any { it is DraftProblem.DoesNotBalance }
            refused == case.balanced
        }
        assertEquals(emptyList(), wrong.map { it.toString() }, "cases where the app and Beancount disagree")
    }

    @Test fun `the figure put on a blank posting is the one Beancount puts there, to the digit`() {
        val blanks = cases.filter { it.inferred != null }
        val wrong = blanks.filter { case ->
            val mine = case.draft.inferredForBlank(case.format)
            val theirs = case.inferred!!
            // The same number written to the same number of places, not merely equal in value.
            mine.size != 1 || mine.single().currency != theirs.currency ||
                mine.single().number.toPlainString() != theirs.number.toPlainString()
        }
        assertEquals(
            emptyList(),
            wrong.map { case ->
                "$case: app ${case.draft.inferredForBlank(case.format).joinToString { it.format() }}, " +
                    "Beancount ${case.inferred!!.format()}"
            },
            "cases where the blank posting would be written differently",
        )
    }
}
