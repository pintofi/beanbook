package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A price converts an amount to the very digit Beancount does, forwards and by its inverse.
 *
 * `tools/gen-balance-cases.py` declared one price and converted amounts through it with Beancount 3.2.3's
 * own price map and `convert_amount`: 1,400 conversions, half of them through the inverse, which Beancount
 * stores as `1 / rate` worked to 28 digits. What it returned is what is expected here, digits and all.
 */
class BeancountPriceCasesTest {

    private class Case(val base: String, val quote: String, val rate: String, val units: Amount, val expected: Amount)

    private fun amount(text: String): Amount {
        val (number, currency) = text.trim().split(' ')
        return Amount(BigDecimal(number), currency)
    }

    private val cases: List<Case> by lazy {
        javaClass.getResourceAsStream("/beancount-price-cases.txt")!!.bufferedReader().readLines()
            .filter { it.startsWith("price ") }
            .map { line ->
                val (head, tail) = line.removePrefix("price ").split('|')
                val (base, quote, rate) = head.trim().split(' ')
                val (from, to) = tail.split("->")
                Case(base, quote, rate, amount(from), amount(to))
            }
    }

    @Test fun `the cases were read`() {
        assertTrue(cases.size >= 1300, "only ${cases.size} cases")
        assertTrue(cases.any { it.units.currency == it.base } && cases.any { it.units.currency == it.quote })
    }

    @Test fun `every conversion gives Beancount's digits`() {
        val wrong = cases.filter { case ->
            val book = PriceBook(listOf(PriceRate(case.base, case.quote, BigDecimal(case.rate))))
            val mine = book.convert(case.units, case.expected.currency)?.number?.toPlainString()
            mine != case.expected.number.toPlainString()
        }
        assertEquals(
            emptyList(),
            wrong.take(10).map { case ->
                val book = PriceBook(listOf(PriceRate(case.base, case.quote, BigDecimal(case.rate))))
                "${case.units.format()} at ${case.base}/${case.quote} ${case.rate}: app " +
                    "${book.convert(case.units, case.expected.currency)?.number?.toPlainString()}, " +
                    "Beancount ${case.expected.number.toPlainString()}"
            },
            "conversions that differ (${wrong.size} in all)",
        )
    }

    @Test fun `the figure Beancount and the app disagreed on is settled`() {
        // 0.165 at a rate of 3, used backwards: Beancount's 0.165 x 0.3333...3 is 0.05499..., shown as 0.05.
        val book = PriceBook(listOf(PriceRate("SAR", "PKR", BigDecimal("3"))))
        val converted = book.convert(Amount(BigDecimal("0.165"), "PKR"), "SAR")!!
        assertEquals("0.05", LedgerFormat(precision = mapOf("SAR" to 2)).number(converted))
    }
}
