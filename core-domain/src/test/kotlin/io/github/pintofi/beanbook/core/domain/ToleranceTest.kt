package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What counts as balanced is Beancount's call, so these cases are Beancount's answers, taken by
 * running `bean-check` (3.2.3) on the same entries, not what looked reasonable.
 *
 * The rule, from `beancount/core/interpolate.py` `infer_tolerances`: for each currency, half of
 * the last decimal place of the *units* of every posting written with decimals, the largest of
 * them; whole numbers contribute nothing. `option "inferred_tolerance_default"` is a floor for a
 * named currency, and `*` is what a currency with nothing else gets.
 */
class ToleranceTest {

    private fun amount(text: String, currency: String) = Amount.parse(text, currency)

    private fun tolerance(
        currency: String,
        vararg units: Amount,
        defaults: Map<String, String> = emptyMap(),
    ): BigDecimal = Tolerances.infer(
        units.toList(),
        defaults.mapValues { BigDecimal(it.value) },
    ).forCurrency(currency)

    private fun assertTolerance(expected: String, actual: BigDecimal) =
        // compareTo: a tolerance is a threshold, so its scale means nothing.
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected, got $actual")

    @Test
    fun `the least precise number sets the tolerance`() {
        assertTolerance(
            "0.005",
            tolerance("USD", amount("1.00", "USD"), amount("-0.999", "USD")),
        )
    }

    @Test
    fun `whole numbers contribute nothing, but do not cancel what decimals say`() {
        assertTolerance(
            "0.005",
            tolerance("USD", amount("10", "USD"), amount("-5.005", "USD"), amount("-4.99", "USD")),
        )
    }

    @Test
    fun `only whole numbers means no tolerance at all`() {
        assertTolerance("0", tolerance("USD", amount("10", "USD"), amount("-9", "USD")))
    }

    @Test
    fun `each currency has its own tolerance`() {
        val units = arrayOf(amount("100.00", "USD"), amount("-90.5", "EUR"))
        assertTolerance("0.005", tolerance("USD", *units))
        assertTolerance("0.05", tolerance("EUR", *units))
    }

    @Test
    fun `the file's default is a floor, not a replacement`() {
        // 1000.5 PKR has one decimal: 0.05, which is more than the file's 0.01.
        assertTolerance(
            "0.05",
            tolerance("PKR", amount("1000.5", "PKR"), defaults = mapOf("PKR" to "0.01")),
        )
        // 1000.50 has two: 0.005, which is less than the file's 0.01.
        assertTolerance(
            "0.01",
            tolerance("PKR", amount("1000.50", "PKR"), defaults = mapOf("PKR" to "0.01")),
        )
    }

    @Test
    fun `a currency with nothing to infer from gets the file's default, then the star`() {
        val defaults = mapOf("PKR" to "0.01", "*" to "0.002")
        assertTolerance("0.01", tolerance("PKR", amount("100", "PKR"), defaults = defaults))
        assertTolerance("0.002", tolerance("CHF", amount("100", "CHF"), defaults = defaults))
    }

    @Test
    fun `the star does not stand in for a currency that has decimals`() {
        assertTolerance(
            "0.005",
            tolerance("USD", amount("1.00", "USD"), defaults = mapOf("*" to "0.1")),
        )
    }

    @Test
    fun `no information means no tolerance`() {
        assertTolerance("0", tolerance("USD"))
    }

    // The three entries below balance in Beancount 3.2.3 (bean-check, no errors). Each was
    // refused here before, with a "does not balance" the ledger would not have reported.

    private val pkrFile = LedgerFormat(tolerances = mapOf("PKR" to BigDecimal("0.01")))

    private fun assertBalances(format: LedgerFormat, vararg postings: PostingDraft) {
        val problems = TransactionDraft(date = "2026-02-01", postings = postings.toList())
            .validate(format)
        assertTrue(problems.none { it is DraftProblem.DoesNotBalance }, "should balance: $problems")
    }

    @Test
    fun `an entry written to fewer places than the file's default still uses its own tolerance`() {
        assertBalances(
            pkrFile,
            PostingDraft("Assets:A", amount("1000.5", "PKR")),
            PostingDraft("Assets:B", amount("-1000.45", "PKR")),
        )
    }

    @Test
    fun `a whole-number leg does not forbid the tolerance its neighbours imply`() {
        assertBalances(
            LedgerFormat.PLAIN,
            PostingDraft("Assets:A", amount("10", "USD")),
            PostingDraft("Assets:B", amount("-5.005", "USD")),
            PostingDraft("Assets:C", amount("-4.99", "USD")),
        )
    }

    @Test
    fun `a residual exactly at the tolerance balances`() {
        assertBalances(
            LedgerFormat.PLAIN,
            PostingDraft("Assets:A", amount("1.00", "CHF")),
            PostingDraft("Assets:B", amount("-0.995", "CHF")),
        )
    }

    @Test
    fun `a residual past the tolerance still does not`() {
        val problems = TransactionDraft(
            date = "2026-02-01",
            postings = listOf(
                PostingDraft("Assets:A", amount("100.00", "USD")),
                PostingDraft("Assets:B", amount("-99.98", "USD")),
            ),
        ).validate(LedgerFormat.PLAIN)
        assertTrue(problems.any { it is DraftProblem.DoesNotBalance }, "$problems")
    }
}
