package dev.beancount.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import dev.beancount.core.data.WidgetCache

/**
 * The home-screen widgets.
 *
 * **None of these starts the engine.** They read the cache the app writes after a successful
 * parse and nothing else — no Python, no Fava, no binding the `:engine` process. A home
 * screen redrawing four widgets on wake would otherwise start an interpreter four times for
 * figures that were already known (docs/ux.md §8). `WidgetEngineIsolationTest` asserts it on
 * a device rather than trusting the absence of an import.
 *
 * Updates are **opportunistic**: `updatePeriodMillis` is 0, so Android never wakes the app
 * on a timer. They refresh when the app writes the cache — after a save, an import, or a
 * ledger reload — which is also the only time the numbers can have changed. This sidesteps
 * background limits rather than fighting them: there is no background work to be limited.
 */
abstract class MetricWidget(private val metric: WidgetMetric) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
    ) {
        val snapshot = WidgetCache(context.filesDir.resolve("state")).read()
        val hideAmounts = WidgetPreferences(context).hideAmounts()

        widgetIds.forEach { id ->
            manager.updateAppWidget(
                id,
                WidgetRenderer.render(
                    context = context,
                    metric = metric,
                    snapshot = snapshot,
                    redacted = WidgetRenderer.shouldRedact(context, id, hideAmounts),
                ),
            )
        }
    }

    /** A widget moved to or from the lock screen has to be redrawn, not just resized. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        onUpdate(context, manager, intArrayOf(widgetId))
    }
}

class BalanceWidget : MetricWidget(WidgetMetric.BALANCE)

class NetWorthWidget : MetricWidget(WidgetMetric.NET_WORTH)

class PeriodWidget : MetricWidget(WidgetMetric.PERIOD)

class QuickAddWidget : MetricWidget(WidgetMetric.QUICK_ADD)

/**
 * Asks every widget to redraw.
 *
 * Called by the app after it writes the cache. This is the entire refresh mechanism: no
 * alarm, no worker, no polling.
 */
object WidgetRefresh {

    private val PROVIDERS = listOf(
        BalanceWidget::class.java,
        NetWorthWidget::class.java,
        PeriodWidget::class.java,
        QuickAddWidget::class.java,
    )

    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        PROVIDERS.forEach { provider ->
            val ids = runCatching {
                manager.getAppWidgetIds(android.content.ComponentName(context, provider))
            }.getOrNull() ?: return@forEach
            if (ids.isEmpty()) return@forEach
            val intent = android.content.Intent(
                AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                null,
                context,
                provider,
            ).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}

/** Whether the user has asked for amounts to be hidden on the home screen. */
class WidgetPreferences(private val context: Context) {

    private val file get() = context.filesDir.resolve("state/widget-privacy")

    fun hideAmounts(): Boolean = file.isFile

    fun setHideAmounts(hide: Boolean) {
        file.parentFile?.mkdirs()
        if (hide) file.writeText("hidden") else file.delete()
    }
}
