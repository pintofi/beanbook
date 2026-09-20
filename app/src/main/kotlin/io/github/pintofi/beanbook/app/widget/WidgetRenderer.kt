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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a widget shows. */
enum class WidgetMetric(val label: String) {
    BALANCE("Balance"),
    NET_WORTH("Net worth"),
    QUICK_ADD("Add an entry"),
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
    ): RemoteViews {
        // Several accounts on one widget is its own layout: a row each, rather than one
        // figure with a label (B48). One account keeps the original single-figure shape,
        // which reads better at the size most people place a balance widget at.
        if (metric == WidgetMetric.BALANCE && chosenAccounts.size > 1) {
            return renderRows(context, snapshot, chosenAccounts, redacted, nowEpochMs)
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
        // screen is not information (B1). Only the leaf: "Checking" fits where
        // "Assets:Bank:Checking" would be truncated mid-word.
        // The account this widget is for: the one the user picked when they placed it, and
        // otherwise the deterministic default. Resolved before the snapshot is checked so the
        // label is right even when there is nothing to show yet.
        val account = if (metric == WidgetMetric.BALANCE) {
            chosenAccount ?: snapshot?.primaryBalance?.first
        } else {
            null
        }
        val label = account?.substringAfterLast(':')?.let { "${metric.label} · $it" }
            ?: metric.label
        views.setTextViewText(R.id.widget_label, label)
        // The whole widget, not just the figure: a home-screen tile is one target, and a tap
        // that lands on the label or the footnote and does nothing reads as a broken widget
        // (B54).
        views.setOnClickPendingIntent(R.id.widget_root, launchIntent(context, snapshot, metric))

        if (snapshot == null) {
            views.setTextViewText(R.id.widget_value, "—")
            views.setTextViewText(R.id.widget_footnote, "Open the app once to set this up.")
            return views
        }

        if (metric == WidgetMetric.QUICK_ADD) {
            views.setTextViewText(R.id.widget_value, "+")
            views.setTextViewText(R.id.widget_footnote, snapshot.projectTitle)
            return views
        }

        val value = when (metric) {
            WidgetMetric.NET_WORTH -> snapshot.netWorth
            WidgetMetric.BALANCE -> account?.let { snapshot.balances[it] }
            WidgetMetric.QUICK_ADD -> null
        }

        views.setTextViewText(
            R.id.widget_value,
            when {
                value == null -> "—"
                redacted -> "•••"
                else -> value
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
     * Several balances, one row each (B48).
     *
     * Capped at [MAX_ROWS], and the widget **says** when it is showing fewer than were
     * chosen rather than silently dropping the rest -- a home-screen widget quietly omitting
     * an account is the same class of error as showing an outdated figure.
     *
     * An account the ledger no longer has keeps its row and shows an em dash, for the reason
     * the single-account path never substitutes: the row names an account, so putting some
     * other account's money beside that name would be worse than showing nothing.
     */
    private fun renderRows(
        context: Context,
        snapshot: WidgetSnapshot?,
        accounts: List<String>,
        redacted: Boolean,
        nowEpochMs: Long,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_balances)
        val palette = WidgetPalette.of(context)
        views.paint(palette)
        views.setTextViewText(R.id.widget_label, WidgetMetric.BALANCE.label)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            launchIntent(context, snapshot, WidgetMetric.BALANCE),
        )
        views.removeAllViews(R.id.widget_rows)

        if (snapshot == null) {
            views.setTextViewText(R.id.widget_footnote, "Open the app once to set this up.")
            return views
        }

        val shown = accounts.take(MAX_ROWS)
        shown.forEach { account ->
            val row = RemoteViews(context.packageName, R.layout.widget_balance_row)
            row.setTextColor(R.id.row_account, palette.label)
            row.setTextColor(R.id.row_amount, palette.value)
            row.setTextViewText(R.id.row_account, account.substringAfterLast(':'))
            val value = snapshot.balances[account]
            row.setTextViewText(
                R.id.row_amount,
                when {
                    value == null -> "—"
                    redacted -> "•••"
                    else -> value
                },
            )
            views.addView(R.id.widget_rows, row)
        }

        val missing = shown.filter { snapshot.balances[it] == null }
        val hidden = accounts.size - shown.size
        views.setTextViewText(
            R.id.widget_footnote,
            when {
                missing.isNotEmpty() ->
                    missing.joinToString(", ") + " is not in this ledger any more."
                hidden > 0 -> "$hidden more not shown · " + footnote(snapshot, nowEpochMs, redacted)
                else -> footnote(snapshot, nowEpochMs, redacted)
            },
        )
        return views
    }

    /**
     * Colours one widget from the app's appearance settings (B92).
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
    }

    /** As many rows as stay legible on a widget somebody has actually resized. */
    private const val MAX_ROWS = 6

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
                "This ledger has errors — last good figures, $time"
            snapshot.isStale(nowEpochMs) ->
                "Out of date — from $time. Open the app to refresh."
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
            // Each widget opens where its own figure lives (B54): a balance is a line in the
            // journal, and net worth is the headline on Home. Both used to land on Balances,
            // which answered a question the user had not asked.
            putExtra(
                EXTRA_SURFACE,
                when (metric) {
                    WidgetMetric.QUICK_ADD -> SURFACE_ADD
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

    const val EXTRA_PROJECT = "bca_widget_project"
    const val EXTRA_SURFACE = "bca_widget_surface"
    const val SURFACE_ADD = "ADD"
    const val SURFACE_BALANCES = "BALANCES"
    const val SURFACE_JOURNAL = "JOURNAL"
    const val SURFACE_HOME = "HOME"
}
