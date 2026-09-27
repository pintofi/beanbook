package io.github.pintofi.beanbook.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.pintofi.beanbook.core.data.WidgetCache
import io.github.pintofi.beanbook.core.data.WidgetSnapshot

/**
 * The rows of the multi-account balance widget, so the list can scroll (B93).
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

        override fun onCreate() = Unit

        /** Read again whenever the widget is told its data changed. */
        override fun onDataSetChanged() {
            snapshot = WidgetCache(context.filesDir.resolve("state")).read()
            accounts = WidgetAccounts(context).accountsFor(widgetId)
            // Hidden unless it is known not to be: a lock-screen list is no place to guess.
            redacted = WidgetRenderer.shouldRedact(context, widgetId, WidgetPreferences(context).hideAmounts())
            palette = WidgetPalette.of(context)
        }

        override fun onDestroy() = Unit

        override fun getCount(): Int = accounts.size

        override fun getViewAt(position: Int): RemoteViews =
            WidgetRenderer.row(
                context,
                accounts[position],
                snapshot,
                redacted,
                palette ?: WidgetPalette.of(context),
            )

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = accounts[position].hashCode().toLong()

        override fun hasStableIds(): Boolean = true
    }
}
