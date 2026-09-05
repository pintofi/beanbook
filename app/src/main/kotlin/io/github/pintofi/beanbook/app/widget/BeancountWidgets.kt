package io.github.pintofi.beanbook.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import io.github.pintofi.beanbook.core.data.WidgetCache

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

        val accounts = WidgetAccounts(context)

        widgetIds.forEach { id ->
            manager.updateAppWidget(
                id,
                WidgetRenderer.render(
                    context = context,
                    metric = metric,
                    snapshot = snapshot,
                    redacted = WidgetRenderer.shouldRedact(context, id, hideAmounts),
                    chosenAccount = accounts.accountFor(id),
                ),
            )
        }
    }

    /** A removed widget's account choice goes with it -- ids are reused. */
    override fun onDeleted(context: Context, widgetIds: IntArray) {
        WidgetAccounts(context).forget(widgetIds)
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

/**
 * Which account each balance widget was told to show (B1).
 *
 * A default was never enough. `WidgetSnapshot.primaryBalance` picks assets-then-alphabetical
 * so the figure at least means something, but somebody with two current accounts cannot put
 * the *other* one on their home screen, and two balance widgets could only ever show the
 * same number.
 *
 * Keyed by app-widget id, because the choice belongs to the placed widget rather than to the
 * app: two of them are the point.
 *
 * A flat `id=account` file, like [WidgetPreferences] beside it. An account name cannot
 * contain `=` or a newline in Beancount, so the format needs no escaping, and the whole
 * store is a handful of lines that must be readable from a broadcast receiver without
 * touching the engine.
 */
class WidgetAccounts(private val context: Context) {

    private val file get() = context.filesDir.resolve("state/widget-accounts")

    private fun all(): Map<Int, String> =
        if (!file.isFile) emptyMap()
        else file.readLines().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) null else line.take(at).toIntOrNull()?.let { it to line.substring(at + 1) }
        }.toMap()

    private fun save(entries: Map<Int, String>) {
        file.parentFile?.mkdirs()
        if (entries.isEmpty()) {
            file.delete()
        } else {
            file.writeText(entries.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    /** The account chosen for [widgetId], or `null` to mean "whatever the default picks". */
    fun accountFor(widgetId: Int): String? = all()[widgetId]

    fun setAccountFor(widgetId: Int, account: String) {
        save(all() + (widgetId to account))
    }

    /**
     * Forgets [widgetIds].
     *
     * Called from `onDeleted`, because ids are reused: without this, a new widget could
     * inherit the account a deleted one was showing, which looks like the app choosing at
     * random.
     */
    fun forget(widgetIds: IntArray) {
        val remaining = all() - widgetIds.toSet()
        save(remaining)
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
