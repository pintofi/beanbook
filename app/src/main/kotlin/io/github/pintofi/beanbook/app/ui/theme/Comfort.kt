package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** How a change from the last period is marked. */
enum class ChangeMarks(val label: String, val detail: String) {
    OFF("Off", "Changes are in ink, like every other figure."),
    SUBTLE("Subtle", "A change from the last period is coloured, and the sign says which way."),
    CLEAR("Clear", "As Subtle, with an arrow so the direction never depends on colour."),
    ;

    companion object {
        fun named(name: String?): ChangeMarks? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The reading-comfort options. None of them touches a ledger, and all of them are the device's.
 */
data class Comfort(
    /** Stronger text, rules and outlines than the app's own. */
    val highContrast: Boolean = false,
    /** Figures a size larger than the system text size gives them. */
    val largeFigures: Boolean = false,
    /** Rows a little shorter, never below a 48dp touch target. */
    val compactRows: Boolean = false,
    /** No animation inside the app, whatever the system's animation scale is. */
    val reduceMotion: Boolean = false,
    val changeMarks: ChangeMarks = ChangeMarks.SUBTLE,
)

val LocalComfort = staticCompositionLocalOf { Comfort() }

/** The figure sizes are scaled by this when [Comfort.largeFigures] is on. */
internal const val LARGE_FIGURE_SCALE = 1.15f

/**
 * This scheme with stronger ink and rules: secondary text close to full strength, and the
 * hairlines the layout is built on at the outline's strength, so a rule is easy to see in
 * sunlight. Backgrounds and accents are untouched.
 */
internal fun ColorScheme.withHigherContrast(dark: Boolean): ColorScheme = if (dark) {
    copy(
        onSurface = Color(0xFFF6FAF8),
        onBackground = Color(0xFFF6FAF8),
        onSurfaceVariant = Color(0xFFD8E2DE),
        outline = Color(0xFFA6B3AE),
        outlineVariant = Color(0xFF7A8883),
    )
} else {
    copy(
        onSurface = Color(0xFF050A09),
        onBackground = Color(0xFF050A09),
        onSurfaceVariant = Color(0xFF26322F),
        outline = Color(0xFF4F5C57),
        outlineVariant = Color(0xFF7A8680),
    )
}
