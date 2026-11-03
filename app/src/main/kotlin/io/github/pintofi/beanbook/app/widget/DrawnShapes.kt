package io.github.pintofi.beanbook.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

/**
 * What about a widget's size and place changes the way it is drawn: whether the Units, At cost
 * and Market value row fits, and whether it is on the lock screen, where amounts are hidden.
 * Every other change of size draws the same widget.
 */
internal data class WidgetShape(val basisRow: Boolean, val keyguard: Boolean) {
    companion object {
        fun of(options: Bundle, landscape: Boolean): WidgetShape = WidgetShape(
            basisRow = WidgetRenderer.basisRowFits(
                minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
                maxHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
                landscape = landscape,
            ),
            keyguard = options.getInt(
                AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
            ) == AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
        )
    }
}

/**
 * The shape each widget was last drawn in by this process.
 *
 * A launcher reports a widget's options again and again, often unchanged, and each redraw makes
 * it rebuild the widget and flash. A widget is only redrawn for a report that changes its shape.
 * A process that was stopped starts with nothing remembered and so draws once, which is safe.
 */
internal object DrawnShapes {
    private val drawn = ConcurrentHashMap<Int, WidgetShape>()

    fun remember(widgetId: Int, shape: WidgetShape) {
        drawn[widgetId] = shape
    }

    fun isDrawn(widgetId: Int, shape: WidgetShape): Boolean = drawn[widgetId] == shape

    fun forget(widgetIds: IntArray) = widgetIds.forEach { drawn.remove(it) }
}
