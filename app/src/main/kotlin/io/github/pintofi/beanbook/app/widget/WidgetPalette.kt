package io.github.pintofi.beanbook.app.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import io.github.pintofi.beanbook.app.ui.theme.ThemeChoice
import io.github.pintofi.beanbook.app.ui.theme.ThemeSettings

/**
 * The colours one widget is drawn in, taken from the app's own appearance settings.
 *
 * The widget used to be four fixed colour resources, so an app running on a wallpaper palette
 * or held in AMOLED still had teal rules on grey paper on the home screen. A widget that does
 * not look like the app it belongs to is the thing the widget layout exists to avoid.
 *
 * Resolved in Kotlin rather than through resource qualifiers because two of the three inputs
 * are not configuration: the theme choice is the app's own setting, and the accent under
 * dynamic colour comes from the system palette, which has no light/dark qualifier of its own.
 */
internal data class WidgetPalette(
    val paper: Int,
    val rule: Int,
    val label: Int,
    val value: Int,
    val footnote: Int,
    /** An unchosen segment of the basis row: the app's raised paper. */
    val segment: Int,
    /** The chosen segment: the app's sage container, with [segmentOnText] on it. */
    val segmentOn: Int,
    val segmentOnText: Int,
) {

    companion object {

        fun of(context: Context): WidgetPalette {
            val settings = ThemeSettings(context.filesDir.resolve("state"))
            val choice = settings.choice.value
            val dark = when (choice) {
                ThemeChoice.SYSTEM -> systemIsDark(context)
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK, ThemeChoice.BLACK -> true
            }
            val accent = accent(context, dark, settings.dynamicColour.value)
            return if (dark) {
                WidgetPalette(
                    // AMOLED is black paper in the app, and the widget follows it.
                    paper = if (choice == ThemeChoice.BLACK) BLACK else DARK_PAPER,
                    rule = accent,
                    label = 0xFFAFBDB8.toInt(),
                    value = 0xFFE4EAE7.toInt(),
                    footnote = 0xFF8A9A94.toInt(),
                    segment = if (choice == ThemeChoice.BLACK) 0xFF141C1A.toInt() else 0xFF1A2220.toInt(),
                    segmentOn = 0xFF2A4A44.toInt(),
                    segmentOnText = 0xFFCFE4DE.toInt(),
                )
            } else {
                WidgetPalette(
                    paper = LIGHT_PAPER,
                    rule = accent,
                    label = 0xFF4A5754.toInt(),
                    value = 0xFF16201E.toInt(),
                    footnote = 0xFF5E6B66.toInt(), // 4.9:1 on the paper; #6B7873 was 4.2 (WCAG 1.4.3 asks 4.5)
                    segment = 0xFFE1E7E1.toInt(),
                    segmentOn = 0xFFCFE4DE.toInt(),
                    segmentOnText = 0xFF05201C.toInt(),
                )
            }
        }

        private fun systemIsDark(context: Context): Boolean =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

        /**
         * The rule's colour: the wallpaper accent when the app is using one, and the app's own
         * teal otherwise. The shades are the ones Material takes for `primary` -- 600 on light
         * paper, 200 on dark -- so the widget's rule is the same colour as the rules in the app.
         */
        private fun accent(context: Context, dark: Boolean, dynamic: Boolean): Int {
            if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val id = if (dark) {
                    android.R.color.system_accent1_200
                } else {
                    android.R.color.system_accent1_600
                }
                runCatching { return context.getColor(id) }
            }
            return if (dark) DARK_TEAL else LIGHT_TEAL
        }

        private const val LIGHT_PAPER = 0xFFF3F5F2.toInt()
        private const val DARK_PAPER = 0xFF141A19.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val LIGHT_TEAL = 0xFF0B5F5B.toInt()
        private const val DARK_TEAL = 0xFF6FD3CA.toInt()
    }
}
