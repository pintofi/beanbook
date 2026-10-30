package io.github.pintofi.beanbook.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import io.github.pintofi.beanbook.app.R
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

    companion object {
        /** Sent by the Units, At cost and Market value row on a widget. */
        const val ACTION_BASIS = "io.github.pintofi.beanbook.WIDGET_BASIS"
        const val EXTRA_BASIS = "basis"
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
    ) {
        widgetIds.forEach { id -> update(context, manager, id, manager.getAppWidgetOptions(id)) }
    }

    /** Draws one widget at the size the launcher last reported for it, which a resize hands over. */
    private fun update(context: Context, manager: AppWidgetManager, id: Int, sizes: android.os.Bundle) {
        val cache = WidgetCache(context.filesDir.resolve("state"))
        val accounts = WidgetAccounts(context)
        manager.updateAppWidget(
            id,
            WidgetRenderer.render(
                context = context,
                metric = metric,
                snapshot = cache.read(prefer = accounts.accountsFor(id)),
                redacted = WidgetRenderer.shouldRedact(context, id, WidgetPreferences(context).hideAmounts()),
                chosenAccounts = accounts.accountsFor(id),
                widgetId = id,
                option = WidgetOptions(context).optionFor(id),
                sizes = sizes,
            ),
        )
        @Suppress("DEPRECATION") // Its replacement, RemoteCollectionItems, is API 31.
        manager.notifyAppWidgetViewDataChanged(intArrayOf(id), R.id.widget_list)
    }

    /**
     * A tap on Units, At cost or Market value on a widget.
     *
     * The id has to be one of this provider's own, and the basis one of the three: the provider is
     * exported for the launcher, so anything else that sends this is ignored.
     */
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action != ACTION_BASIS) {
            super.onReceive(context, intent)
            return
        }
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val basis = runCatching {
            io.github.pintofi.beanbook.core.domain.Valuation.valueOf(intent.getStringExtra(EXTRA_BASIS).orEmpty())
        }.getOrNull() ?: return
        val manager = AppWidgetManager.getInstance(context)
        val ours = manager.getAppWidgetIds(android.content.ComponentName(context, this::class.java))
        if (id !in ours) return
        val options = WidgetOptions(context)
        options.setOptionFor(id, options.optionFor(id).copy(basis = basis))
        onUpdate(context, manager, intArrayOf(id))
    }

    /** A removed widget's account choice goes with it -- ids are reused. */
    override fun onDeleted(context: Context, widgetIds: IntArray) {
        WidgetAccounts(context).forget(widgetIds)
        WidgetOptions(context).forget(widgetIds)
    }

    /** A widget moved to or from the lock screen has to be redrawn, not just resized. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        update(context, manager, widgetId, newOptions)
    }
}

class BalanceWidget : MetricWidget(WidgetMetric.BALANCE)

class NetWorthWidget : MetricWidget(WidgetMetric.NET_WORTH)

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
 * Which accounts each balance widget was told to show.
 *
 * A default was never enough. `WidgetSnapshot.primaryBalance` picks assets-then-alphabetical
 * so the figure at least means something, but somebody with two current accounts cannot put
 * the *other* one on their home screen, and two balance widgets could only ever show the
 * same number.
 *
 * **A list, not one name.** A widget the size of four app icons can hold several balances,
 * and "current account, savings, card" is one glance rather than three widgets.
 *
 * Keyed by app-widget id, because the choice belongs to the placed widget rather than to the
 * app: two of them are the point.
 *
 * A flat `id=a,b,c` file, like [WidgetPreferences] beside it. A Beancount account name
 * cannot contain `=`, `,` or a newline, so the format needs no escaping, and the whole store
 * is a handful of lines that must be readable from a broadcast receiver without touching the
 * engine. A file written by an older version holds one name per line and parses as a
 * one-element list, so an upgrade keeps whatever a placed widget was showing.
 */
class WidgetAccounts(private val context: Context) {

    private val file get() = context.filesDir.resolve("state/widget-accounts")

    private fun all(): Map<Int, List<String>> =
        if (!file.isFile) emptyMap()
        else file.readLines().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) {
                null
            } else {
                line.take(at).toIntOrNull()?.let { id ->
                    id to line.substring(at + 1).split(',').filter { it.isNotBlank() }
                }
            }
        }.toMap()

    private fun save(entries: Map<Int, List<String>>) {
        file.parentFile?.mkdirs()
        val kept = entries.filterValues { it.isNotEmpty() }
        if (kept.isEmpty()) {
            file.delete()
        } else {
            file.writeText(
                kept.entries.joinToString("\n") { "${it.key}=${it.value.joinToString(",")}" },
            )
        }
    }

    /** The accounts chosen for [widgetId]. Empty means "whatever the default picks". */
    fun accountsFor(widgetId: Int): List<String> = all()[widgetId].orEmpty()

    fun setAccountsFor(widgetId: Int, accounts: List<String>) {
        save(all() + (widgetId to accounts))
    }

    /**
     * Forgets [widgetIds].
     *
     * Called from `onDeleted`, because ids are reused: without this, a new widget could
     * inherit the accounts a deleted one was showing, which looks like the app choosing at
     * random.
     */
    fun forget(widgetIds: IntArray) {
        val remaining = all() - widgetIds.toSet()
        save(remaining)
    }
}

/**
 * How one widget is asked to show its figures, beside which accounts it shows.
 *
 * @property basis units, cost or market value for this widget alone; null follows whatever basis
 *   the app is set to, which is what every widget did before there was a choice.
 * @property commodities per account: which of its commodities to show. An account that is not
 *   here, or has an empty set, shows all of them.
 */
data class WidgetOption(
    val basis: io.github.pintofi.beanbook.core.domain.Valuation? = null,
    val commodities: Map<String, Set<String>> = emptyMap(),
)

/**
 * The per-widget choices of basis and commodities, keyed by app-widget id like [WidgetAccounts].
 *
 * One line a widget: `id|BASIS|Account=CUR+CUR;Account=CUR`. Account names and commodities have
 * none of `|`, `=`, `;` or `+`, so the line needs no escaping. A widget with no line (every widget
 * placed before this existed) gets the defaults, which are what it always showed.
 */
class WidgetOptions(private val context: Context) {

    private val file get() = context.filesDir.resolve("state/widget-options")

    private fun all(): Map<Int, WidgetOption> =
        if (!file.isFile) emptyMap()
        else file.readLines().mapNotNull { line ->
            val parts = line.split('|')
            val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val basis = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { io.github.pintofi.beanbook.core.domain.Valuation.valueOf(it) }.getOrNull() }
            val commodities = parts.getOrNull(2).orEmpty().split(';').mapNotNull { pair ->
                val at = pair.indexOf('=')
                if (at <= 0) null
                else pair.take(at) to pair.substring(at + 1).split('+').filter { it.isNotBlank() }.toSet()
            }.toMap()
            id to WidgetOption(basis, commodities)
        }.toMap()

    fun optionFor(widgetId: Int): WidgetOption = all()[widgetId] ?: WidgetOption()

    fun setOptionFor(widgetId: Int, option: WidgetOption) {
        val next = all() + (widgetId to option)
        write(next)
    }

    fun forget(widgetIds: IntArray) = write(all() - widgetIds.toSet())

    private fun write(entries: Map<Int, WidgetOption>) {
        file.parentFile?.mkdirs()
        val kept = entries.filterValues { it.basis != null || it.commodities.values.any { set -> set.isNotEmpty() } }
        if (kept.isEmpty()) {
            file.delete()
        } else {
            file.writeText(
                kept.entries.joinToString("\n") { (id, option) ->
                    "$id|${option.basis?.name.orEmpty()}|" +
                        option.commodities.filterValues { it.isNotEmpty() }.entries
                            .joinToString(";") { (account, set) -> "$account=${set.sorted().joinToString("+")}" }
                },
            )
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
