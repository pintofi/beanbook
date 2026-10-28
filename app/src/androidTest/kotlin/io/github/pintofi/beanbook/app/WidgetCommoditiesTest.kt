package io.github.pintofi.beanbook.app

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.widget.WidgetMetric
import io.github.pintofi.beanbook.app.widget.WidgetOption
import io.github.pintofi.beanbook.app.widget.WidgetOptions
import io.github.pintofi.beanbook.app.widget.WidgetPalette
import io.github.pintofi.beanbook.app.widget.WidgetRenderer
import io.github.pintofi.beanbook.core.data.WidgetFormatting
import io.github.pintofi.beanbook.core.data.WidgetHolding
import io.github.pintofi.beanbook.core.data.WidgetSnapshot
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.Valuation
import java.math.BigDecimal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An account that holds two commodities, on a widget.
 *
 * Two lines used to go into a one-line text box, which showed the first and ended it with an
 * ellipsis ("PK..."). Every line of a figure has to be on screen, and a widget set to one
 * commodity, or to one basis, has to show exactly that.
 */
@RunWith(AndroidJUnit4::class)
class WidgetCommoditiesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val options = WidgetOptions(context)
    private val ids = intArrayOf(920_001, 920_002)

    @After fun clean() {
        options.forget(ids)
    }

    private fun amt(n: String, c: String) = Amount(BigDecimal(n), c)

    private fun snapshot() = WidgetSnapshot(
        projectId = "p", projectTitle = "Books", generatedAtEpochMs = System.currentTimeMillis(),
        balances = mapOf("Assets:Cash" to "5,200.00 PKR\n200.00 SAR", "Assets:Bank" to "10.00 PKR"),
        netWorth = "x", periodIncome = null, periodExpense = null, clean = true,
        holdings = mapOf(
            "Assets:Cash" to listOf(
                WidgetHolding("PKR", listOf(amt("5200.00", "PKR")), listOf(amt("5200.00", "PKR")), listOf(amt("5200.00", "PKR"))),
                WidgetHolding("SAR", listOf(amt("200.00", "SAR")), listOf(amt("200.00", "SAR")), listOf(amt("15000.00", "PKR"))),
            ),
        ),
        valuation = "UNITS",
        netWorthBy = mapOf("UNITS" to "u", "COST" to "c", "VALUE" to "v"),
        formatting = WidgetFormatting(groupThousands = true, precision = mapOf("PKR" to 2, "SAR" to 2)),
    )

    private fun row(account: String, option: WidgetOption): View {
        val view = WidgetRenderer.row(context, account, snapshot(), false, WidgetPalette.of(context), option).apply(context, null)
        // Laid out at a phone-widget width, so a line that does not fit would be cut here too.
        view.measure(
            View.MeasureSpec.makeMeasureSpec((300 * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED,
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    @Test fun bothCommoditiesOfAnAccountAreShownInFull() {
        val amount = row("Assets:Cash", WidgetOption()).findViewById<TextView>(R.id.row_amount)
        assertEquals("5,200.00 PKR\n200.00 SAR", amount.text.toString())
        assertEquals("a second line was cut off", 2, amount.lineCount)
        assertFalse("a line ends in an ellipsis", (0 until amount.lineCount).any { amount.layout.getEllipsisCount(it) > 0 })
    }

    @Test fun oneCommodityCanBeChosen() {
        val option = WidgetOption(commodities = mapOf("Assets:Cash" to setOf("SAR")))
        assertEquals("200.00 SAR", row("Assets:Cash", option).findViewById<TextView>(R.id.row_amount).text.toString())
    }

    @Test fun theBasisCanBeChosenPerWidget() {
        val value = WidgetOption(basis = Valuation.VALUE)
        assertEquals("20,200.00 PKR", row("Assets:Cash", value).findViewById<TextView>(R.id.row_amount).text.toString())
    }

    @Test fun theChoiceIsRememberedPerWidgetAndForgottenWithIt() {
        val one = WidgetOption(Valuation.COST, mapOf("Assets:Cash" to setOf("PKR", "SAR")))
        options.setOptionFor(ids[0], one)
        options.setOptionFor(ids[1], WidgetOption(Valuation.VALUE))
        assertEquals(one, options.optionFor(ids[0]))
        assertEquals(Valuation.VALUE, options.optionFor(ids[1]).basis)
        options.forget(intArrayOf(ids[0]))
        assertEquals(WidgetOption(), options.optionFor(ids[0]))
        assertTrue(options.optionFor(ids[1]).basis != null)
    }

    @Test fun aNetWorthWidgetReadsOnItsOwnBasis() {
        val views = WidgetRenderer.render(
            context = context, metric = WidgetMetric.NET_WORTH, snapshot = snapshot(), redacted = false,
            option = WidgetOption(basis = Valuation.COST),
        )
        val root = views.apply(context, null)
        assertEquals("c", root.findViewById<TextView>(R.id.widget_value).text.toString())
        // The basis is named by the row on the widget now, not by its label.
        assertFalse(root.findViewById<TextView>(R.id.widget_label).text.toString().contains("At cost"))
        assertEquals("\u2713 At cost", root.findViewById<TextView>(R.id.widget_basis_cost).text.toString())
    }
}
