package io.github.pintofi.beanbook.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one-figure widget never hides its first line behind the ones after it. */
class WidgetFigureTest {

    @Test fun `one line is shown as it is`() {
        assertEquals("812,345.67 PKR", widgetFigure("812,345.67 PKR"))
    }

    @Test fun `two lines are shown as they are`() {
        assertEquals("100.00 PKR\n2.00 USD", widgetFigure("100.00 PKR\n2.00 USD"))
    }

    @Test fun `more than two lines keep the first and count the rest`() {
        assertEquals(
            "-250000.50 PKR\n+3 more",
            widgetFigure("-250000.50 PKR\n2000.00 SAR\n80.00 EGF-E\n6 ACME"),
        )
    }

    @Test fun `blank lines are not counted`() {
        assertEquals("1.00 EUR", widgetFigure("1.00 EUR\n"))
    }
}

/**
 * What a balance widget shows when it has been told which commodities and which basis.
 *
 * A cash account holding rupees and riyals is two figures. The widget can show both, one of them,
 * or both valued in the operating currency, and each has to be the sum of exactly what was chosen.
 */
class WidgetChoiceFigureTest {

    private fun amt(n: String, c: String) = io.github.pintofi.beanbook.core.domain.Amount(java.math.BigDecimal(n), c)

    private val snapshot = io.github.pintofi.beanbook.core.data.WidgetSnapshot(
        projectId = "p",
        projectTitle = "Books",
        generatedAtEpochMs = 0,
        balances = mapOf("Assets:Cash" to "5,200.00 PKR\n200.00 SAR"),
        netWorth = "x",
        periodIncome = null,
        periodExpense = null,
        clean = true,
        holdings = mapOf(
            "Assets:Cash" to listOf(
                io.github.pintofi.beanbook.core.data.WidgetHolding(
                    "PKR", listOf(amt("5200.00", "PKR")), listOf(amt("5200.00", "PKR")), listOf(amt("5200.00", "PKR")),
                ),
                io.github.pintofi.beanbook.core.data.WidgetHolding(
                    "SAR", listOf(amt("200.00", "SAR")), listOf(amt("200.00", "SAR")), listOf(amt("15000.00", "PKR")),
                ),
            ),
        ),
        valuation = "UNITS",
        formatting = io.github.pintofi.beanbook.core.data.WidgetFormatting(
            groupThousands = true,
            precision = mapOf("PKR" to 2, "SAR" to 2),
        ),
    )

    private fun figure(option: WidgetOption) = figureFor(snapshot, "Assets:Cash", option)

    @Test fun `no choice shows the figure the cache already holds`() {
        assertEquals("5,200.00 PKR\n200.00 SAR", figure(WidgetOption()))
    }

    @Test fun `one commodity of an account is shown alone`() {
        assertEquals("200.00 SAR", figure(WidgetOption(commodities = mapOf("Assets:Cash" to setOf("SAR")))))
        assertEquals("5,200.00 PKR", figure(WidgetOption(commodities = mapOf("Assets:Cash" to setOf("PKR")))))
    }

    @Test fun `market value adds the chosen holdings in the currency they were valued in`() {
        val value = io.github.pintofi.beanbook.core.domain.Valuation.VALUE
        assertEquals("20,200.00 PKR", figure(WidgetOption(basis = value)))
        val sar = WidgetOption(basis = value, commodities = mapOf("Assets:Cash" to setOf("SAR")))
        assertEquals("15,000.00 PKR", figure(sar))
    }

    @Test fun `units keeps each commodity on its own line`() {
        val units = io.github.pintofi.beanbook.core.domain.Valuation.UNITS
        assertEquals("5,200.00 PKR\n200.00 SAR", figure(WidgetOption(basis = units)))
    }

    @Test fun `a cache without holdings shows what it has and ignores the choice`() {
        val old = snapshot.copy(holdings = emptyMap())
        val option = WidgetOption(commodities = mapOf("Assets:Cash" to setOf("SAR")))
        assertEquals("5,200.00 PKR\n200.00 SAR", figureFor(old, "Assets:Cash", option))
    }

    @Test fun `a commodity the account no longer holds shows nothing rather than another one`() {
        assertEquals(null, figure(WidgetOption(commodities = mapOf("Assets:Cash" to setOf("EUR")))))
    }
}
