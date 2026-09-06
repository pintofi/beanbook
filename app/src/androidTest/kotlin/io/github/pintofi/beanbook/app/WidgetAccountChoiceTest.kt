package io.github.pintofi.beanbook.app

import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.widget.WidgetAccounts
import io.github.pintofi.beanbook.app.widget.WidgetMetric
import io.github.pintofi.beanbook.app.widget.WidgetRenderer
import io.github.pintofi.beanbook.core.data.WidgetSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A balance widget shows the account it was told to show (B1).
 *
 * The default — assets first, then alphabetically — was never wrong, only fixed: someone
 * with two current accounts could not put the *other* one on their home screen, and two
 * balance widgets could only ever show the same figure.
 *
 * The case worth the most care is an account that has since left the ledger. The widget must
 * say so rather than fall back to the default, because falling back would put a different
 * account's money under this widget's label — a plausible, current, and wrong number, which
 * is the one thing `WidgetRenderer` exists to prevent.
 */
@RunWith(AndroidJUnit4::class)
class WidgetAccountChoiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val accounts = WidgetAccounts(context)
    private val ids = intArrayOf(910_001, 910_002, 910_003)

    @After fun clean() {
        accounts.forget(ids)
    }

    private fun snapshot() = WidgetSnapshot(
        projectId = "p",
        projectTitle = "Books",
        generatedAtEpochMs = System.currentTimeMillis(),
        balances = mapOf(
            "Assets:Bank:Current" to "10.00 USD",
            "Assets:Bank:Savings" to "250.00 USD",
            "Liabilities:Card" to "-30.00 USD",
        ),
        netWorth = "230.00 USD",
        periodIncome = null,
        periodExpense = null,
        clean = true,
        entitled = true,
    )

    /** Reads the three text fields a metric widget renders. */
    private fun rendered(chosen: String?): Triple<String, String, String> {
        val views = WidgetRenderer.render(
            context = context,
            metric = WidgetMetric.BALANCE,
            snapshot = snapshot(),
            redacted = false,
            chosenAccounts = listOfNotNull(chosen),
        )
        val root = views.apply(context, null)
        fun text(id: Int) = root.findViewById<TextView>(id).text.toString()
        return Triple(
            text(R.id.widget_label),
            text(R.id.widget_value),
            text(R.id.widget_footnote),
        )
    }

    @Test fun withNoChoiceItStillShowsTheDeterministicDefault() {
        val (label, value, _) = rendered(chosen = null)
        assertTrue("the default lost its account label: $label", label.contains("Current"))
        assertEquals("10.00 USD", value)
    }

    @Test fun aChosenAccountIsTheOneShown() {
        val (label, value, _) = rendered(chosen = "Assets:Bank:Savings")
        assertTrue("the label did not name the chosen account: $label", label.contains("Savings"))
        assertEquals("250.00 USD", value)
    }

    /** Two widgets, two accounts — the reason a per-widget choice exists at all. */
    @Test fun twoWidgetsCanShowDifferentAccounts() {
        accounts.setAccountsFor(ids[0], listOf("Assets:Bank:Current"))
        accounts.setAccountsFor(ids[1], listOf("Liabilities:Card"))

        assertEquals(listOf("Assets:Bank:Current"), accounts.accountsFor(ids[0]))
        assertEquals(listOf("Liabilities:Card"), accounts.accountsFor(ids[1]))
        assertEquals("10.00 USD", rendered(accounts.accountsFor(ids[0]).first()).second)
        assertEquals("-30.00 USD", rendered(accounts.accountsFor(ids[1]).first()).second)
    }

    @Test fun anAccountThatLeftTheLedgerIsSaidRatherThanSubstituted() {
        val (label, value, footnote) = rendered(chosen = "Assets:Bank:Closed")

        assertEquals("a missing account showed a figure", "—", value)
        assertTrue("the label stopped naming the chosen account: $label", label.contains("Closed"))
        assertTrue(
            "the widget did not say why it is empty: $footnote",
            footnote.contains("not in this ledger"),
        )
        // The specific failure this guards: quietly showing the default account's money.
        assertTrue("a different account's balance was substituted", !value.contains("10.00"))
    }


    /**
     * Several accounts on one widget (B48).
     *
     * Read out of the rendered view tree rather than asserted against the code that built
     * it: the rows are added with `RemoteViews.addView`, and the failure worth catching is a
     * row that never reaches the layout.
     */
    private fun rows(chosen: List<String>, redacted: Boolean = false): Pair<List<String>, String> {
        val views = WidgetRenderer.render(
            context = context,
            metric = WidgetMetric.BALANCE,
            snapshot = snapshot(),
            redacted = redacted,
            chosenAccounts = chosen,
        )
        val root = views.apply(context, null) as android.view.ViewGroup
        val container = root.findViewById<android.view.ViewGroup>(R.id.widget_rows)
        val lines = (0 until container.childCount).map { index ->
            val row = container.getChildAt(index) as android.view.ViewGroup
            row.findViewById<TextView>(R.id.row_account).text.toString() + " " +
                row.findViewById<TextView>(R.id.row_amount).text.toString()
        }
        return lines to root.findViewById<TextView>(R.id.widget_footnote).text.toString()
    }

    @Test fun severalChosenAccountsEachGetTheirOwnRow() {
        val (lines, _) = rows(listOf("Assets:Bank:Current", "Liabilities:Card"))
        assertEquals(listOf("Current 10.00 USD", "Card -30.00 USD"), lines)
    }

    @Test fun aMissingAccountKeepsItsRowAndIsNamed() {
        val (lines, footnote) = rows(listOf("Assets:Bank:Current", "Assets:Bank:Closed"))
        assertEquals(listOf("Current 10.00 USD", "Closed —"), lines)
        assertTrue("the widget did not say which account is gone: $footnote",
            footnote.contains("Assets:Bank:Closed"))
        assertTrue("another account's money was substituted", !lines[1].contains("250.00"))
    }

    @Test fun hidingAmountsHidesEveryRow() {
        val (lines, _) = rows(
            listOf("Assets:Bank:Current", "Assets:Bank:Savings"),
            redacted = true,
        )
        assertTrue("a figure survived redaction: $lines", lines.none { it.contains("USD") })
    }

    /** Ids are reused, so a deleted widget's choice must not be inherited by the next one. */
    @Test fun aForgottenWidgetLeavesNoChoiceBehind() {
        accounts.setAccountsFor(ids[2], listOf("Assets:Bank:Savings"))
        assertEquals(listOf("Assets:Bank:Savings"), accounts.accountsFor(ids[2]))

        accounts.forget(intArrayOf(ids[2]))

        assertTrue(
            "a deleted widget's account outlived it",
            accounts.accountsFor(ids[2]).isEmpty(),
        )
    }

    /** Forgetting one widget must not disturb the others. */
    @Test fun forgettingOneWidgetKeepsTheRest() {
        accounts.setAccountsFor(ids[0], listOf("Assets:Bank:Current"))
        accounts.setAccountsFor(ids[1], listOf("Assets:Bank:Savings"))

        accounts.forget(intArrayOf(ids[0]))

        assertTrue(accounts.accountsFor(ids[0]).isEmpty())
        assertEquals(listOf("Assets:Bank:Savings"), accounts.accountsFor(ids[1]))
    }
}
