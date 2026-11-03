package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app writes a figure the way Beancount's display context does: the ledger's places for the commodity,
 * rounding half to even, and thousands separators when `render_commas` is on.
 *
 * `tools/gen-balance-cases.py` ran 600 random figures (ties at the kept place, negatives, up to ten whole
 * digits) through Beancount 3.2.3 under `display_precision` and `render_commas`; its output is what is
 * expected here. One difference is on purpose: Beancount prints a small negative figure that rounds to zero
 * as `-0.00`, and the app prints `0.00`, since a sign in front of nothing says nothing.
 */
class BeancountDisplayCasesTest {

    private class Case(
        val currency: String,
        val places: Int,
        val commas: Boolean,
        val number: String,
        val expected: String,
    )

    private val cases: List<Case> by lazy {
        javaClass.getResourceAsStream("/beancount-display-cases.txt")!!.bufferedReader().readLines()
            .filter { it.startsWith("display ") }
            .map { line ->
                val parts = line.split(' ')
                Case(parts[1], parts[2].toInt(), parts[3] == "commas", parts[4], parts[5])
            }
    }

    private fun mine(case: Case): String {
        val format = LedgerFormat(groupThousands = case.commas, precision = mapOf(case.currency to case.places))
        return format.number(Amount(BigDecimal(case.number), case.currency))
    }

    private fun Case.expectedHere(): String {
        // Beancount's "-0.00" is the app's "0.00".
        val roundsToZero = expected.removePrefix("-").all { it == '0' || it == '.' || it == ',' }
        return if (expected.startsWith("-") && roundsToZero) expected.removePrefix("-") else expected
    }

    @Test fun `the cases were read`() {
        assertTrue(cases.size >= 600, "only ${cases.size} cases")
        assertTrue(cases.any { it.commas } && cases.any { !it.commas })
        assertTrue(cases.any { it.number.startsWith("-") })
    }

    @Test fun `every figure is shown as Beancount shows it`() {
        val wrong = cases.filter { mine(it) != it.expectedHere() }
        assertEquals(
            emptyList(),
            wrong.take(20).map {
                val style = if (it.commas) "commas" else "plain"
                "${it.number} ${it.currency} (${it.places} places, $style): app ${mine(it)}, Beancount ${it.expected}"
            },
            "figures shown differently (${wrong.size} in all)",
        )
    }
}
