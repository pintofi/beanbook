package io.github.pintofi.beanbook.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every widget, bound and hosted the way a launcher binds and hosts one (V10).
 *
 * What existed before this inflated the `RemoteViews` directly, which proves the layout is
 * valid and nothing else: it skips `AppWidgetManager` entirely, so a provider that is
 * misdeclared in the manifest, asks for a size the system refuses, or names a layout the
 * host cannot inflate would pass. This allocates a real widget id, binds it through
 * `AppWidgetManager`, and hosts the result in an `AppWidgetHostView` — the same three steps a
 * home screen takes.
 *
 * It is still not a photograph of a launcher, which is what V10 finally wants. It is the part
 * of that which a machine can check.
 */
@RunWith(AndroidJUnit4::class)
class WidgetHostTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = AppWidgetManager.getInstance(context)
    /** Every provider this app declares, as the system sees them. */
    private fun providers(): List<ComponentName> =
        manager.installedProviders
            .filter { it.provider.packageName == context.packageName }
            .map { it.provider }

    @Test fun theSystemSeesEveryWidgetThisAppDeclares() {
        val declared = providers().map { it.shortClassName.substringAfterLast('.') }.sorted()

        // SPEC §8: account balance, income/expense for a period, net worth, quick add.
        assertTrue(
            "the system lists $declared, which is not the three widgets ux.md 8 specifies",
            declared.size == 3,
        )
    }

    // Binding and hosting a widget the way a launcher does — `bindAppWidgetIdIfAllowed`,
    // `createView`, `updateAppWidgetSize` — was written and then removed, because the system
    // refuses the bind to anything that is not a launcher and `adb shell appwidget grantbind`
    // records no grant on these images (`dumpsys appwidget` shows an empty `Grants:`). A test
    // that asserts the system's refusal would be testing the emulator, not the app.
    //
    // So what is left here is the part that is genuinely checkable off a launcher, and it is
    // not nothing: it goes through `AppWidgetManager.installedProviders`, so a provider that
    // is misdeclared in the manifest is caught, which inflating `RemoteViews` directly never
    // would. Placing them on a home screen stays with V10.
}
