package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** The colours one accent family takes in one theme: the accent, its container, and the ink on each. */
internal data class AccentTones(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    /** Only where the family would collide with the app's terracotta "unfinished" colour. */
    val tertiary: Tertiary? = null,
)

internal data class Tertiary(
    val tertiary: Color,
    val onTertiary: Color,
    val container: Color,
    val onContainer: Color,
)

/**
 * The accent colour the app is drawn in, when the wallpaper is not supplying one.
 *
 * Only the accents change. Paper, ink, rules and the raised surfaces stay the app's own, so a
 * palette is a different accent on the same page. TEAL is the app's own and the default.
 * Every pair is chosen to clear 4.5:1 (AccentPaletteTest computes them), because the accent is
 * used as text on the paper as well as behind it.
 */
enum class AccentPalette(val label: String) {
    TEAL("Teal"),
    INDIGO("Indigo"),
    PLUM("Plum"),
    OCHRE("Ochre"),
    SLATE("Slate"),
    ;

    internal val light: AccentTones get() = tones(this, dark = false)
    internal val dark: AccentTones get() = tones(this, dark = true)

    /** The accent as it shows on a swatch. */
    fun swatch(dark: Boolean): Color = (if (dark) this.dark else light).primary

    companion object {
        fun named(name: String?): AccentPalette? = entries.firstOrNull { it.name == name }
    }
}

/**
 * How one project is coloured: from the wallpaper (dynamic colour), or in one of the [AccentPalette]s.
 * There is no app-wide choice; the colour belongs to the project that is open.
 */
sealed interface ProjectColour {
    /** Accents from the wallpaper, where the phone supports it. */
    data object Dynamic : ProjectColour

    data class Palette(val palette: AccentPalette) : ProjectColour
}

private fun c(argb: Long) = Color(argb)

private fun tones(palette: AccentPalette, dark: Boolean): AccentTones = when (palette) {
    AccentPalette.TEAL -> if (dark) {
        AccentTones(
            c(0xFF6FD3CA), c(0xFF00332F), c(0xFF0A4A46), c(0xFFB9E7E2),
            c(0xFFA9C9C1), c(0xFF12332E), c(0xFF2A4A44), c(0xFFCFE4DE),
        )
    } else {
        AccentTones(
            c(0xFF0B5F5B), c(0xFFFFFFFF), c(0xFFB9E7E2), c(0xFF002B28),
            c(0xFF3F5C56), c(0xFFFFFFFF), c(0xFFCFE4DE), c(0xFF05201C),
        )
    }
    AccentPalette.INDIGO -> if (dark) {
        AccentTones(
            c(0xFFB8BEFF), c(0xFF1E2670), c(0xFF353E86), c(0xFFDDE0FF),
            c(0xFFC0C5EA), c(0xFF292E4D), c(0xFF3F4465), c(0xFFDDE1F9),
        )
    } else {
        AccentTones(
            c(0xFF3F4A9E), c(0xFFFFFFFF), c(0xFFDDE0FF), c(0xFF0E1550),
            c(0xFF565B7D), c(0xFFFFFFFF), c(0xFFDDE1F9), c(0xFF141A38),
        )
    }
    AccentPalette.PLUM -> if (dark) {
        AccentTones(
            c(0xFFF5AEDC), c(0xFF560A47), c(0xFF722A5B), c(0xFFFFD7F0),
            c(0xFFDBBDD0), c(0xFF3E2A36), c(0xFF553F4D), c(0xFFF9DAEC),
        )
    } else {
        AccentTones(
            c(0xFF8A3B73), c(0xFFFFFFFF), c(0xFFFFD7F0), c(0xFF3A0030),
            c(0xFF6F5468), c(0xFFFFFFFF), c(0xFFF9DAEC), c(0xFF29131F),
        )
    }
    AccentPalette.OCHRE -> if (dark) {
        AccentTones(
            c(0xFFEFBF4E), c(0xFF402D00), c(0xFF5C4300), c(0xFFFFDF9E),
            c(0xFFD6C5A0), c(0xFF3A2F15), c(0xFF51452A), c(0xFFF4E1BA),
            // Ochre sits too near terracotta, so "unfinished" takes the teal here.
            Tertiary(c(0xFF6FD3CA), c(0xFF00332F), c(0xFF0A4A46), c(0xFFB9E7E2)),
        )
    } else {
        AccentTones(
            c(0xFF7A5900), c(0xFFFFFFFF), c(0xFFFFDF9E), c(0xFF261A00),
            c(0xFF6A5C3E), c(0xFFFFFFFF), c(0xFFF4E1BA), c(0xFF231A04),
            Tertiary(c(0xFF0B5F5B), c(0xFFFFFFFF), c(0xFFB9E7E2), c(0xFF002B28)),
        )
    }
    AccentPalette.SLATE -> if (dark) {
        AccentTones(
            c(0xFFA6CCE6), c(0xFF0B3449), c(0xFF254B62), c(0xFFD0E4F5),
            c(0xFFB9C8D6), c(0xFF243240), c(0xFF3A4957), c(0xFFD5E4F2),
        )
    } else {
        AccentTones(
            c(0xFF3D5566), c(0xFFFFFFFF), c(0xFFD0E4F5), c(0xFF001E2F),
            c(0xFF515F6B), c(0xFFFFFFFF), c(0xFFD5E4F2), c(0xFF0D1D28),
        )
    }
}

/** This scheme with [palette]'s accents, the paper and ink untouched. */
internal fun ColorScheme.withAccent(palette: AccentPalette, dark: Boolean): ColorScheme {
    val t = if (dark) palette.dark else palette.light
    val scheme = copy(
        primary = t.primary,
        onPrimary = t.onPrimary,
        primaryContainer = t.primaryContainer,
        onPrimaryContainer = t.onPrimaryContainer,
        secondary = t.secondary,
        onSecondary = t.onSecondary,
        secondaryContainer = t.secondaryContainer,
        onSecondaryContainer = t.onSecondaryContainer,
        inversePrimary = if (dark) palette.light.primary else palette.dark.primary,
        surfaceTint = t.primary,
        primaryFixed = palette.light.primaryContainer,
        primaryFixedDim = palette.dark.primary,
        onPrimaryFixed = palette.light.onPrimaryContainer,
        onPrimaryFixedVariant = palette.light.primary,
        secondaryFixed = palette.light.secondaryContainer,
        secondaryFixedDim = palette.dark.secondary,
        onSecondaryFixed = palette.light.onSecondaryContainer,
        onSecondaryFixedVariant = palette.light.secondary,
    )
    val tertiary = t.tertiary ?: return scheme
    return scheme.copy(
        tertiary = tertiary.tertiary,
        onTertiary = tertiary.onTertiary,
        tertiaryContainer = tertiary.container,
        onTertiaryContainer = tertiary.onContainer,
    )
}
