package io.github.pintofi.beanbook.app

import android.app.ActivityManager
import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.widget.BalanceWidget
import io.github.pintofi.beanbook.app.widget.NetWorthWidget
import io.github.pintofi.beanbook.app.widget.WidgetMetric
import io.github.pintofi.beanbook.app.widget.WidgetRenderer
import io.github.pintofi.beanbook.core.data.WidgetCache
import io.github.pintofi.beanbook.core.data.WidgetSnapshot
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rule the whole widget design exists to keep: **a widget never starts the engine.**
 *
 * A home screen redrawing four widgets on wake would otherwise start a Python interpreter
 * four times, on a device that has just woken up, for figures that were already known
 * (docs/ux.md §8).
 *
 * The assertion is possible because the engine runs in its own process — `:engine`, split
 * out in M5.5 — so "did it start" is a question with a checkable answer.
 * `getRunningAppProcesses` returns only this app's own processes on modern Android, which is
 * exactly the scope needed.
 */
@RunWith(AndroidJUnit4::class)
class WidgetEngineIsolationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stateDir = File(context.filesDir, "state")
    private val cache = WidgetCache(stateDir)
    private var saved: WidgetSnapshot? = null

    /**
     * The engine process's pid, or null when it is not running.
     *
     * A pid rather than a boolean, because this suite shares one device with tests that
     * legitimately start the engine. "Is it running?" therefore depends on what ran before;
     * "did *this* start it?" does not.
     */
    private fun enginePid(): Int? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses.orEmpty()
            .firstOrNull { it.processName.endsWith(":engine") }
            ?.pid
    }

    private fun engineRunning(): Boolean = enginePid() != null

    private val snapshot = WidgetSnapshot(
        projectId = "home",
        projectTitle = "Home",
        generatedAtEpochMs = System.currentTimeMillis(),
        balances = mapOf("Assets:Bank:Checking" to "1240.00 USD"),
        netWorth = "940.00 USD",
        periodIncome = "3000.00 USD",
        periodExpense = "262.00 USD",
        clean = true,
    )

    @After fun restore() {
        saved?.let { cache.write(it) } ?: cache.clear()
    }

    /**
     * The milestone's required evidence, and the control for it, in one test because the
     * order matters.
     *
     * Asserting a *negative* — "no engine was started" — passes just as happily if
     * `engineRunning()` could never return true. So this checks the detector works in the
     * same run, after the measurement that depends on it. Two tests could not do this: the
     * one that starts the engine leaves it running for whichever runs next, and the guard
     * below would then refuse to proceed, correctly.
     */
    @Test fun renderingEveryWidgetStartsNoEngineProcess() = kotlinx.coroutines.runBlocking {
        saved = cache.read()
        cache.write(snapshot)

        val before = enginePid()

        val manager = AppWidgetManager.getInstance(context)
        listOf(BalanceWidget(), NetWorthWidget())
            .forEach { provider -> provider.onUpdate(context, manager, IntArray(0)) }

        // Unchanged, whatever it was. Null before and after is the strong case — nothing was
        // running and drawing four widgets did not change that. A pid that is the same before
        // and after says the widgets did not start or restart it either, which is the claim
        // being made and is true regardless of what an earlier test left behind.
        assertEquals("a widget started or restarted the engine process", before, enginePid())

        // Now prove the measurement above could have failed. Start the engine on purpose and
        // require the detector to see it; without this the assertion is unfalsifiable.
        val connection = io.github.pintofi.beanbook.engine.local.EngineConnection(context)
        val started = connection.withEngine<String> { it.engineVersions() }
        assertTrue("the engine would not start, so this proves nothing: $started", started.isSuccess)
        assertTrue("the engine is running but the detector did not see it", engineRunning())
    }

    @Test fun aWidgetRendersEntirelyFromTheCache() {
        cache.write(snapshot)

        val views = WidgetRenderer.render(
            context = context,
            metric = WidgetMetric.NET_WORTH,
            snapshot = cache.read(),
            redacted = false,
        )

        // Inflating is the honest check: a RemoteViews that cannot be inflated is a widget
        // that shows the launcher's error placeholder on someone's home screen.
        val inflated = views.apply(context, android.widget.FrameLayout(context))
        val value = inflated.findViewById<android.widget.TextView>(R.id.widget_value)
        assertEquals("940.00 USD", value.text.toString())
    }

    @Test fun anOldSnapshotSaysSoBeforeItSaysTheNumber() {
        // The failure this design is arranged against: a plausible but outdated figure
        // presented as current, to someone deciding whether they can afford something.
        val old = snapshot.copy(
            generatedAtEpochMs = System.currentTimeMillis() - WidgetSnapshot.DEFAULT_MAX_AGE_MS - 1,
        )

        val views = WidgetRenderer.render(context, WidgetMetric.NET_WORTH, old, redacted = false)
        val inflated = views.apply(context, android.widget.FrameLayout(context))
        val footnote = inflated.findViewById<android.widget.TextView>(R.id.widget_footnote)

        assertTrue(
            "an out-of-date widget did not say so: ${footnote.text}",
            footnote.text.toString().startsWith("Out of date"),
        )
    }

    @Test fun aLedgerWithErrorsIsReportedRatherThanPaperedOver() {
        val broken = snapshot.copy(clean = false)

        val views = WidgetRenderer.render(context, WidgetMetric.NET_WORTH, broken, redacted = false)
        val inflated = views.apply(context, android.widget.FrameLayout(context))
        val footnote = inflated.findViewById<android.widget.TextView>(R.id.widget_footnote)

        assertTrue(
            "a broken ledger was shown as if it were fine: ${footnote.text}",
            footnote.text.toString().contains("errors"),
        )
    }

    @Test fun redactionHidesTheFigureAndNothingElse() {
        val views = WidgetRenderer.render(context, WidgetMetric.NET_WORTH, snapshot, redacted = true)
        val inflated = views.apply(context, android.widget.FrameLayout(context))

        val value = inflated.findViewById<android.widget.TextView>(R.id.widget_value)
        val label = inflated.findViewById<android.widget.TextView>(R.id.widget_label)
        val footnote = inflated.findViewById<android.widget.TextView>(R.id.widget_footnote)

        assertEquals("•••", value.text.toString())
        assertEquals("Net worth", label.text.toString())
        // The amount must not survive anywhere on the widget, including the line that
        // usually carries the project name.
        assertFalse("the amount leaked into the footnote", footnote.text.contains("940"))
    }
}
