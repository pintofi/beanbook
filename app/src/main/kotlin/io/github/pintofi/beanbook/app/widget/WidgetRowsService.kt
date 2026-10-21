package io.github.pintofi.beanbook.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.pintofi.beanbook.core.data.WidgetCache
import io.github.pintofi.beanbook.core.data.WidgetSnapshot

/**
 * The rows of the multi-account balance widget, so the list can scroll.
 *
 * Bound by the launcher when it draws the list, never by the app. Like every other widget
 * path it reads the cache the app writes after a successful parse and nothing else: no engine,
 * no Python, no network (docs/ux.md §8, `WidgetEngineIsolationTest`).
 */
class WidgetRowsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Rows(
            applicationContext,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        )

    private class Rows(private val context: Context, private val widgetId: Int) : RemoteViewsFactory {
        private var accounts: List<String> = emptyList()
        private var snapshot: WidgetSnapshot? = null
        private var redacted = true
        private var palette: WidgetPalette? = null
        private var option = WidgetOption()

        override fun onCreate() = Unit

        /** Read again whenever the widget is told its data changed. */
        override fun onDataSetChanged() {
            accounts = WidgetAccounts(context).accountsFor(widgetId)
            snapshot = WidgetCache(context.filesDir.resolve("state")).read(prefer = accounts)
            // Hidden unless it is known not to be: a lock-screen list is no place to guess.
            redacted = WidgetRenderer.shouldRedact(context, widgetId, WidgetPreferences(context).hideAmounts())
            palette = WidgetPalette.of(context)
            option = WidgetOptions(context).optionFor(widgetId)
        }

        override fun onDestroy() = Unit

        override fun getCount(): Int = accounts.size

        override fun getViewAt(position: Int): RemoteViews {
            // Read once: the launcher can ask for a row while the list is being reread.
            val shown = accounts
            val account = shown.getOrNull(position)
                ?: return RemoteViews(context.packageName, io.github.pintofi.beanbook.app.R.layout.widget_balance_row)
            return WidgetRenderer.row(
                context,
                account,
                snapshot,
                redacted,
                palette ?: WidgetPalette.of(context),
                option,
            )
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = accounts.getOrNull(position).hashCode().toLong()

        override fun hasStableIds(): Boolean = true
    }
}
