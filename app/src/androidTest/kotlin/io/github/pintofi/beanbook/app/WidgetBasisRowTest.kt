package io.github.pintofi.beanbook.app

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.widget.BalanceWidget
import io.github.pintofi.beanbook.app.widget.MetricWidget
import io.github.pintofi.beanbook.app.widget.WidgetMetric
import io.github.pintofi.beanbook.app.widget.WidgetOption
import io.github.pintofi.beanbook.app.widget.WidgetOptions
import io.github.pintofi.beanbook.app.widget.WidgetRenderer
import io.github.pintofi.beanbook.core.data.WidgetSnapshot
import io.github.pintofi.beanbook.core.domain.Valuation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Units, At cost and Market value row on the widget itself: it is there, it marks the basis the
 * widget is on, and a tap that does not come from the widget's own launcher changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class WidgetBasisRowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = 930_001

    @After fun clean() {
        WidgetOptions(context).forget(intArrayOf(id))
    }

    private fun snapshot(valuation: String?) = WidgetSnapshot(
        projectId = "p", projectTitle = "Books", generatedAtEpochMs = System.currentTimeMillis(),
        balances = mapOf("Assets:Cash" to "10.00 USD"), netWorth = "10.00 USD",
        periodIncome = null, periodExpense = null, clean = true, valuation = valuation,
    )

    private fun inflate(option: WidgetOption, valuation: String?): View {
        val views = WidgetRenderer.render(
            context = context,
            metric = WidgetMetric.NET_WORTH,
            snapshot = snapshot(valuation),
            redacted = false,
            option = option,
        )
        return views.apply(context, FrameLayout(context))
    }

    /** The chosen segment carries a tick, as the app's connected buttons do. */
    private fun isMarked(view: TextView): Boolean = view.text.toString().startsWith("\u2713")

    @Test fun theRowMarksTheBasisTheWidgetChose() {
        val view = inflate(WidgetOption(basis = Valuation.COST), valuation = "VALUE")

        assertTrue(isMarked(view.findViewById(R.id.widget_basis_cost)))
        assertFalse(isMarked(view.findViewById(R.id.widget_basis_units)))
        assertFalse(isMarked(view.findViewById(R.id.widget_basis_value)))
        assertEquals("Units", (view.findViewById<TextView>(R.id.widget_basis_units)).text.toString())
        assertEquals("Market value", (view.findViewById<TextView>(R.id.widget_basis_value)).text.toString())
        assertEquals("\u2713 At cost", (view.findViewById<TextView>(R.id.widget_basis_cost)).text.toString())
    }

    @Test fun aWidgetWithNoChoiceMarksTheBasisTheAppWasSetTo() {
        val view = inflate(WidgetOption(), valuation = "UNITS")
        assertTrue(isMarked(view.findViewById(R.id.widget_basis_units)))
    }

    @Test fun aTapNamingAWidgetThatIsNotOursChangesNothing() {
        val options = WidgetOptions(context)
        val intent = Intent(MetricWidget.ACTION_BASIS)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(MetricWidget.EXTRA_BASIS, Valuation.COST.name)

        BalanceWidget().onReceive(context, intent)

        assertEquals(null, options.optionFor(id).basis)
    }

    @Test fun aTapNamingABasisThatDoesNotExistChangesNothing() {
        val options = WidgetOptions(context)
        val intent = Intent(MetricWidget.ACTION_BASIS)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(MetricWidget.EXTRA_BASIS, "NOT_A_BASIS")

        BalanceWidget().onReceive(context, intent)

        assertEquals(null, options.optionFor(id).basis)
    }
}
