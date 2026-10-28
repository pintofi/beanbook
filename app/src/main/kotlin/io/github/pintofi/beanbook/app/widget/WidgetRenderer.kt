package io.github.pintofi.beanbook.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import io.github.pintofi.beanbook.app.MainActivity
import io.github.pintofi.beanbook.app.R
import io.github.pintofi.beanbook.core.data.WidgetSnapshot
import io.github.pintofi.beanbook.core.domain.Valuation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a widget shows. */
enum class WidgetMetric(val label: String, val provider: Class<out MetricWidget>) {
    BALANCE("Balance", BalanceWidget::class.java),
    NET_WORTH("Net worth", NetWorthWidget::class.java),
}

/**
 * Turns a cached snapshot into a widget, and decides what to say when there isn't one.
 *
 * The rule this exists to enforce: **never show a plausible but outdated number as if it
 * were current**. Someone glances at a balance to decide whether to buy something. So every
 * widget carries when it was taken, an old one says so before it says the figure, and a
 * ledger that failed to parse is reported rather than papered over with the last good number
 * (docs/ux.md §8).
 */
object WidgetRenderer {

    fun render(
        context: Context,
        metric: WidgetMetric,
        snapshot: WidgetSnapshot?,
        redacted: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
        chosenAccounts: List<String> = emptyList(),
        widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
        option: WidgetOption = WidgetOption(),
    ): RemoteViews {
        // Several accounts on one widget is its own layout: a row each, rather than one
        // figure with a label. One account keeps the original single-figure shape,
        // which reads better at the size most people place a balance widget at.
        if (metric == WidgetMetric.BALANCE && chosenAccounts.size > 1) {
            return renderRows(context, snapshot, chosenAccounts, redacted, nowEpochMs, widgetId, option)
        }
        val chosenAccount = chosenAccounts.firstOrNull()
        val views = RemoteViews(context.packageName, R.layout.widget_metric)
        // The figure is coloured here rather than in `paint`, because the multi-account
        // layout has rows instead of one figure and an action naming a view it does not have
        // fails when the launcher applies it.
        val palette = WidgetPalette.of(context)
        views.paint(palette)
        views.setTextColor(R.id.widget_value, palette.value)

        // The balance widget says *which* account, because an unlabelled figure on a home
        // screen is not information. Only the leaf: "Checking" fits where
        // "Assets:Bank:Checking" would be truncated mid-word.
        // The account this widget is for: the one the user picked when they placed it, and
        // otherwise the deterministic default. Resolved before the snapshot is checked so the
        // label is right even when there is nothing to show yet.
        val account = if (metric == WidgetMetric.BALANCE) {
            chosenAccount ?: snapshot?.primaryBalance?.first
        } else {
            null
        }
        val shownCommodities = account?.let { option.commodities[it] }.orEmpty().sorted()
        val label = listOfNotNull(
            account?.substringAfterLast(':')?.let { "${metric.label} · $it" } ?: metric.label,
            shownCommodities.joinToString(" ").takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        views.setTextViewText(R.id.widget_label, label)
        // The whole widget, not just the figure: a home-screen tile is one target, and a tap
        // that lands on the label or the footnote and does nothing reads as a broken widget.
        views.setOnClickPendingIntent(R.id.widget_root, launchIntent(context, snapshot, metric))
        views.setOnClickPendingIntent(R.id.widget_add, addIntent(context, snapshot))
        views.bindBasis(context, metric.provider, widgetId, effectiveBasis(option, snapshot), palette)

        if (snapshot == null) {
            views.setTextViewText(R.id.widget_value, "—")
            views.setTextViewText(R.id.widget_footnote, "Open the app once to set this up.")
            return views
        }

        val value = when (metric) {
            WidgetMetric.NET_WORTH -> option.basis?.let { snapshot.netWorthBy[it.name] } ?: snapshot.netWorth
            WidgetMetric.BALANCE -> account?.let { figureFor(snapshot, it, option) }
        }

        views.setTextViewText(
            R.id.widget_value,
            when {
                value == null -> "—"
                redacted -> "•••"
                else -> widgetFigure(value)
            },
        )

        // An account the user chose that the ledger no longer has is said plainly, and the
        // widget does **not** quietly fall back to the default account. Falling back would
        // put a different account's money under this widget's label, which is precisely the
        // "plausible but outdated number" this class exists to prevent -- and worse, because
        // it would not even be the same account.
        val missing = metric == WidgetMetric.BALANCE && account != null && value == null
        views.setTextViewText(
            R.id.widget_footnote,
            if (missing) "$account is not in this ledger any more."
            else footnote(snapshot, nowEpochMs, redacted),
        )
        return views
    }

    /**
     * Several balances, one row each, in a list that scrolls.
     *
     * Every chosen account has a row. The list used to stop at six and say how many it had
     * left out; a list the launcher scrolls has no reason to leave any out.
     */
    private fun renderRows(
        context: Context,
        snapshot: WidgetSnapshot?,
        accounts: List<String>,
        redacted: Boolean,
        nowEpochMs: Long,
        widgetId: Int,
        option: WidgetOption,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_balances)
        val palette = WidgetPalette.of(context)
        views.paint(palette)
        views.bindBasis(context, WidgetMetric.BALANCE.provider, widgetId, effectiveBasis(option, snapshot), palette)
        views.setTextViewText(R.id.widget_label, WidgetMetric.BALANCE.label)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            launchIntent(context, snapshot, WidgetMetric.BALANCE),
        )
        views.setOnClickPendingIntent(R.id.widget_add, addIntent(context, snapshot))

        // The rows are a list the launcher scrolls, filled by [WidgetRowsService] from
        // the same cache. It used to be six rows and "N more not shown". The widget id is in
        // the intent's data, not only its extras, because the launcher keeps one adapter per
        // distinct intent and two widgets must not share one.
        val rows = Intent(context, WidgetRowsService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        rows.data = android.net.Uri.parse(rows.toUri(Intent.URI_INTENT_SCHEME))
        @Suppress("DEPRECATION") // The collection API that replaces it is API 31; minSdk is 29.
        views.setRemoteAdapter(R.id.widget_list, rows)
        views.setEmptyView(R.id.widget_list, R.id.widget_list_empty)
        // A row is part of the widget, so tapping one opens the journal like the rest of it.
        views.setPendingIntentTemplate(
            R.id.widget_list,
            launchIntent(context, snapshot, WidgetMetric.BALANCE),
        )

        if (snapshot == null) {
            views.setTextViewText(R.id.widget_footnote, "Open the app once to set this up.")
            return views
        }

        val missing = accounts.filter { snapshot.balances[it] == null && snapshot.holdings[it].isNullOrEmpty() }
        views.setTextViewText(
            R.id.widget_footnote,
            if (missing.isNotEmpty()) {
                missing.joinToString(", ") + " is not in this ledger any more."
            } else {
                footnote(snapshot, nowEpochMs, redacted)
            },
        )
        return views
    }

    /**
     * One row of the multi-account widget, for [WidgetRowsService].
     *
     * An account the ledger no longer has keeps its row and shows an em dash: the row names
     * an account, so putting some other account's money beside that name would be worse than
     * showing nothing.
     */
    internal fun row(
        context: Context,
        account: String,
        snapshot: WidgetSnapshot?,
        redacted: Boolean,
        palette: WidgetPalette,
        option: WidgetOption = WidgetOption(),
    ): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_balance_row)
        row.setTextColor(R.id.row_account, palette.label)
        row.setTextColor(R.id.row_amount, palette.value)
        row.setTextViewText(R.id.row_account, account.substringAfterLast(':'))
        val value = snapshot?.let { figureFor(it, account, option) }
        row.setTextViewText(
            R.id.row_amount,
            when {
                value == null -> "—"
                redacted -> "•••"
                else -> widgetFigure(value)
            },
        )
        row.setOnClickFillInIntent(R.id.row_root, Intent())
        return row
    }

    /** The basis a widget is showing: its own choice, else the one the app was set to, else market value. */
    internal fun effectiveBasis(option: WidgetOption, snapshot: WidgetSnapshot?): Valuation =
        option.basis
            ?: snapshot?.valuation?.let { runCatching { Valuation.valueOf(it) }.getOrNull() }
            ?: Valuation.VALUE

    private fun basisViewId(basis: Valuation): Int = when (basis) {
        Valuation.UNITS -> R.id.widget_basis_units
        Valuation.COST -> R.id.widget_basis_cost
        Valuation.VALUE -> R.id.widget_basis_value
    }

    private fun basisBackgroundId(basis: Valuation): Int = when (basis) {
        Valuation.UNITS -> R.id.widget_basis_units_bg
        Valuation.COST -> R.id.widget_basis_cost_bg
        Valuation.VALUE -> R.id.widget_basis_value_bg
    }

    /**
     * The Units, At cost and Market value row on the widget itself, drawn the way the app's
     * connected buttons are: three segments side by side, the outer corners round, the chosen one
     * filled with the app's sage container and ticked, the others on the raised paper.
     *
     * Tapping one asks the widget's provider to show that basis, for this widget only. The tick, as
     * in the app, says it in a mark as well as in colour. The row is left out on a widget too short
     * to hold it beside the figure.
     */
    private fun RemoteViews.bindBasis(
        context: Context,
        provider: Class<out MetricWidget>,
        widgetId: Int,
        selected: Valuation,
        palette: WidgetPalette,
    ) {
        val room = roomForBasis(context, widgetId)
        setViewVisibility(R.id.widget_basis, if (room) android.view.View.VISIBLE else android.view.View.GONE)
        if (!room) return
        Valuation.entries.forEach { basis ->
            val id = basisViewId(basis)
            val on = basis == selected
            setInt(basisBackgroundId(basis), "setColorFilter", if (on) palette.segmentOn else palette.segment)
            setTextViewText(id, if (on) "\u2713 ${basis.label}" else basis.label)
            setTextColor(id, if (on) palette.segmentOnText else palette.label)
            val intent = Intent(context, provider)
                .setAction(MetricWidget.ACTION_BASIS)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(MetricWidget.EXTRA_BASIS, basis.name)
            setOnClickPendingIntent(
                id,
                PendingIntent.getBroadcast(
                    context,
                    BASIS_REQUEST + widgetId * BASES + basis.ordinal,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    /** Whether the widget is tall enough for the basis row; one whose size is not known gets it. */
    private fun roomForBasis(context: Context, widgetId: Int): Boolean {
        val height = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        }.getOrDefault(0)
        return height == 0 || height >= MIN_HEIGHT_FOR_BASIS_DP
    }

    private const val BASIS_REQUEST = 1000
    private const val BASES = 3
    private const val MIN_HEIGHT_FOR_BASIS_DP = 90

    /**
     * Colours one widget from the app's appearance settings.
     *
     * The paper and the rule are tinted rather than set as background colours: a tint works on
     * every supported API, where `setBackgroundTintList` needs 31, and it keeps the rounded
     * corners a flat background colour would lose.
     */
    private fun RemoteViews.paint(palette: WidgetPalette) {
        setInt(R.id.widget_paper, "setColorFilter", palette.paper)
        setInt(R.id.widget_rule, "setColorFilter", palette.rule)
        setTextColor(R.id.widget_label, palette.label)
        setTextColor(R.id.widget_footnote, palette.footnote)
        setTextColor(R.id.widget_add, palette.rule)
    }

    /**
     * The line under the figure, which is doing more work than it looks.
     *
     * Order matters: a stale or broken ledger is said **first**, because the reason to read
     * this line at all is to find out whether to trust the number above it.
     */
    private fun footnote(snapshot: WidgetSnapshot, nowEpochMs: Long, redacted: Boolean): String {
        val time = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
            .format(Date(snapshot.generatedAtEpochMs))
        return when {
            !snapshot.clean ->
                "${snapshot.projectTitle} has errors to review · $time"
            snapshot.isStale(nowEpochMs) ->
                "Out of date, from $time. Open the app to refresh."
            redacted -> "Hidden · $time"
            else -> "${snapshot.projectTitle} · $time"
        }
    }

    /**
     * Whether amounts should be hidden.
     *
     * Two reasons, and the second is not a setting: the user asked for it, or the widget is
     * on the **lock screen**, where the platform says so and where anyone who picks the
     * phone up can read it (docs/threat-model.md — no secrets in widget previews).
     */
    fun shouldRedact(context: Context, widgetId: Int, userPreference: Boolean): Boolean {
        if (userPreference) return true
        val options: Bundle = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        }.getOrNull() ?: return false
        val category = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
        )
        return category == AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD
    }

    /**
     * Where tapping a widget goes.
     *
     * The project id travels in the intent, and `MainActivity` checks it against the projects
     * that actually exist before opening anything: a widget left behind by a deleted project
     * must not be able to name a directory and have the app take its word for it.
     */
    private fun launchIntent(
        context: Context,
        snapshot: WidgetSnapshot?,
        metric: WidgetMetric,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PROJECT, snapshot?.projectId)
            // Each widget opens where its own figure lives: a balance is a line in the
            // journal, and net worth is the headline on Home. Both used to land on Balances,
            // which answered a question the user had not asked.
            putExtra(
                EXTRA_SURFACE,
                when (metric) {
                    WidgetMetric.BALANCE -> SURFACE_JOURNAL
                    WidgetMetric.NET_WORTH -> SURFACE_HOME
                },
            )
        }
        return PendingIntent.getActivity(
            context,
            metric.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The + on every widget: straight to the entry form. It replaced a widget that did
     * only this. Its own request code, so it does not replace the intent that opens the
     * widget's figure.
     */
    private fun addIntent(context: Context, snapshot: WidgetSnapshot?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PROJECT, snapshot?.projectId)
            putExtra(EXTRA_SURFACE, SURFACE_ADD)
        }
        return PendingIntent.getActivity(
            context,
            ADD_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val ADD_REQUEST = 100

    const val EXTRA_PROJECT = "bca_widget_project"
    const val EXTRA_SURFACE = "bca_widget_surface"
    const val SURFACE_ADD = "ADD"
    const val SURFACE_BALANCES = "BALANCES"
    const val SURFACE_JOURNAL = "JOURNAL"
    const val SURFACE_HOME = "HOME"
}

/**
 * The figure for [account] as this widget is set to show it.
 *
 * Without a choice of basis or commodities this is the text the cache already holds, exactly as
 * before. With one, it is built from the account's holdings: only the chosen commodities, on the
 * chosen basis, added up per currency and formatted the way the ledger writes numbers. A cache
 * with no holdings for the account (written before they were kept) can only show what it has, so
 * the choice is ignored rather than showing nothing.
 */
internal fun figureFor(snapshot: WidgetSnapshot, account: String, option: WidgetOption): String? {
    val holdings = snapshot.holdings[account].orEmpty()
    val chosen = option.commodities[account].orEmpty()
    if (holdings.isEmpty() || (option.basis == null && chosen.isEmpty())) return snapshot.balances[account]
    val basis = option.basis
        ?: snapshot.valuation?.let {
            runCatching { io.github.pintofi.beanbook.core.domain.Valuation.valueOf(it) }.getOrNull()
        }
        ?: io.github.pintofi.beanbook.core.domain.Valuation.VALUE
    val picked = if (chosen.isEmpty()) holdings else holdings.filter { it.commodity in chosen }
    if (picked.isEmpty()) return null
    val summed = io.github.pintofi.beanbook.core.domain.LedgerSummary.sumByCurrency(picked.flatMap { it.of(basis) })
    return io.github.pintofi.beanbook.core.domain.LedgerSummary.format(summed, snapshot.formatting.toLedgerFormat())
}

/**
 * A figure a widget can show whole, in its one-figure box or a balance row.
 *
 * In Units a net worth is one line per commodity, and the widget's two lines showed whichever
 * of them landed in the middle of the box -- "80.00 EGF-E" under "Net worth", with the
 * operating currency cut off above it. Two lines or fewer are shown as they are; more become
 * the first line and how many were left out, the same "+N more" Home uses. Nothing is
 * reformatted: the lines are the ledger's own.
 */
internal fun widgetFigure(value: String): String {
    val lines = value.lines().filter { it.isNotBlank() }
    return if (lines.size <= WIDGET_FIGURE_LINES) {
        lines.joinToString("\n")
    } else {
        lines.first() + "\n+${lines.size - 1} more"
    }
}

private const val WIDGET_FIGURE_LINES = 2
