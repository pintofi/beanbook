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
    /**
     * The same colours for dark, when the launcher is to pick between light and dark itself
     * (Android 14 and later), so a change of the system's setting shows without a redraw. Null where
     * these colours are the ones to use.
     */
    val night: WidgetPalette? = null,
) {

    companion object {

        fun of(context: Context): WidgetPalette {
            val settings = ThemeSettings(context.filesDir.resolve("state"))
            val choice = settings.choice.value
            val dynamic = settings.dynamicColour.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val source: ((Int) -> Int)? = if (dynamic) { id -> context.getColor(id) } else null
            val day = build(dark = false, black = false, dynamic = source)
            val night = build(dark = true, black = choice == ThemeChoice.BLACK, dynamic = source)
            return when (choice) {
                // The launcher chooses, where it can; before Android 14 the system's setting now is used.
                ThemeChoice.SYSTEM -> when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> day.copy(night = night)
                    systemIsDark(context) -> night
                    else -> day
                }
                ThemeChoice.LIGHT -> day
                ThemeChoice.DARK, ThemeChoice.BLACK -> night
            }
        }

        /**
         * The colours for light or dark. With [dynamic] (a way to read the system's colour resources,
         * on when dynamic colour is on) they are the ones the app's own dynamic scheme takes from the
         * wallpaper: surface, text, the raised segment and its chosen state, and the accent.
         */
        internal fun build(dark: Boolean, black: Boolean, dynamic: ((Int) -> Int)?): WidgetPalette {
            val accent = if (dynamic != null) {
                dynamic(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            } else if (dark) {
                DARK_TEAL
            } else {
                LIGHT_TEAL
            }
            val base = if (dark) {
                WidgetPalette(
                    // AMOLED is black paper in the app, and the widget follows it.
                    paper = if (black) BLACK else DARK_PAPER,
                    rule = accent,
                    label = 0xFFAFBDB8.toInt(),
                    value = 0xFFE4EAE7.toInt(),
                    footnote = 0xFF8A9A94.toInt(),
                    segment = if (black) 0xFF141C1A.toInt() else 0xFF1A2220.toInt(),
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
            if (dynamic == null) return base
            fun pick(light: Int, night: Int) = dynamic(if (dark) night else light)
            return base.copy(
                paper = if (black) BLACK else pick(
                    android.R.color.system_neutral1_10, android.R.color.system_neutral1_900,
                ),
                label = pick(android.R.color.system_neutral2_700, android.R.color.system_neutral2_200),
                value = pick(android.R.color.system_neutral1_900, android.R.color.system_neutral1_100),
                footnote = pick(android.R.color.system_neutral2_700, android.R.color.system_neutral2_300),
                segment = pick(android.R.color.system_neutral1_100, android.R.color.system_neutral1_800),
                segmentOn = pick(android.R.color.system_accent2_100, android.R.color.system_accent2_700),
                segmentOnText = pick(android.R.color.system_accent2_900, android.R.color.system_accent2_100),
            )
        }

        private fun systemIsDark(context: Context): Boolean =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

        private const val LIGHT_PAPER = 0xFFF3F5F2.toInt()
        private const val DARK_PAPER = 0xFF141A19.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val LIGHT_TEAL = 0xFF0B5F5B.toInt()
        private const val DARK_TEAL = 0xFF6FD3CA.toInt()
    }
}
