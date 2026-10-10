package io.github.pintofi.beanbook.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** What the user asked for, which is not always what the system is doing. */
/**
 * What the user asked for, which is not always what the system is doing.
 *
 * Dynamic colour is deliberately *not* one of these. Material treats it as where a palette
 * comes from, not which mode is on — there is a `dynamicLightColorScheme` and a
 * `dynamicDarkColorScheme` — so it applies to every entry here and stays a switch of its
 * own. Listing it beside "Dark" would make the two look exclusive when they compose.
 *
 * BLACK is a dark mode, not a fourth palette: it is [DARK] with the ground taken to #000000
 * for OLED screens, and it composes with dynamic colour like the others.
 */
enum class ThemeChoice(val label: String) {
    SYSTEM("Follow the system"),
    LIGHT("Light"),
    DARK("Dark"),
    BLACK("AMOLED"),
    ;

    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK, BLACK -> true
    }
}

private val LocalLedgerColours = staticCompositionLocalOf { LedgerColours.Light }

/** Colours for figures, and the band the header sits in. See [LedgerColours]. */
object BeancountTheme {
    val ledger: LedgerColours
        @Composable @ReadOnlyComposable get() = LocalLedgerColours.current
}

/**
 * The app's theme.
 *
 * Dark mode is first-class, not an afterthought: it follows the system by default and can be
 * overridden, because "follow the system" is wrong for the person who reads their ledger in
 * bed with the phone on light mode (docs/ux.md 3).
 *
 * **Dynamic colour is taken as an accent, not as the whole design (B74.)** Before, the
 * wallpaper palette replaced every role, so on an unlucky wallpaper the app was a lilac
 * dashboard and nothing of its own composition survived. Now the wallpaper supplies the
 * accents -- primary, secondary, tertiary and their containers, which is where a person
 * actually sees "this matches my phone" -- and the app keeps its own paper, ink, rules and
 * raised surfaces. The result still changes visibly with the wallpaper and still looks like
 * this app.
 *
 * **Material 3 Expressive (docs/material-expressive.md.)** The theme is
 * [MaterialExpressiveTheme], so components take the expressive shapes, springs and type
 * where this app has not stated its own. The motion scheme is the expressive one while
 * animation is on and [StillMotionScheme] while it is off, so every Material component -- a
 * toggle button changing shape, a navigation indicator, a floating toolbar -- honours the
 * same switch the app's own motion does, rather than only the motion written here.
 */
@Composable
fun BeancountTheme(
    choice: ThemeChoice = ThemeChoice.SYSTEM,
    dynamicColour: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = choice.isDark()
    val context = LocalContext.current
    val own = if (dark) DarkColours else LightColours

    val base: ColorScheme = when {
        dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val wallpaper =
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            own.withAccentsFrom(wallpaper)
        }
        else -> own
    }
    // Applied over whichever dark scheme won above, so pure black composes with the wallpaper
    // accents instead of cancelling them.
    val colours = if (choice == ThemeChoice.BLACK) base.onBlack() else base

    // The rules follow the accent, so a wallpaper palette moves the whole page together
    // instead of leaving teal rules under a lilac button. Without dynamic colour this is the
    // app's own teal and nothing changes.
    val ledger = (if (dark) LedgerColours.Dark else LedgerColours.Light)
        .copy(rule = colours.primary)

    // Read once, here, rather than per composable: the animator duration scale is a
    // content-provider lookup, and a screen of animated rows asking individually is a screen
    // making one lookup per row (B76).
    val motion = animationsEnabled()

    // The system bar icons follow the app's theme, not the phone's (B88). `enableEdgeToEdge`
    // picks their colour from the system setting, so a person who chose "Dark" on a
    // phone in light mode got dark clock and battery icons on the dark band: there, but
    // unreadable. Set on every change of theme, against the window this content is in.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalLedgerColours provides ledger,
        LocalMotionEnabled provides motion,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colours,
            motionScheme = ledgerMotionScheme(motion),
            typography = BeancountTypography,
            shapes = BeancountShapes,
            content = content,
        )
    }
}

/**
 * This app's paper and rules, wearing the wallpaper's accents.
 *
 * Only the roles a person reads as "my phone's colour" are taken: the three accent families
 * -- including their fixed variants, which stay one tone in light and dark -- and the tint.
 * Backgrounds, surfaces, outlines and the inverse roles stay as designed,
 * because those are the composition -- the paper the figures sit on and the rules that
 * organise them -- and handing them to a wallpaper is how an app loses its identity on
 * somebody else's home screen.
 */
private fun ColorScheme.withAccentsFrom(dynamic: ColorScheme): ColorScheme = copy(
    primary = dynamic.primary,
    onPrimary = dynamic.onPrimary,
    primaryContainer = dynamic.primaryContainer,
    onPrimaryContainer = dynamic.onPrimaryContainer,
    secondary = dynamic.secondary,
    onSecondary = dynamic.onSecondary,
    secondaryContainer = dynamic.secondaryContainer,
    onSecondaryContainer = dynamic.onSecondaryContainer,
    tertiary = dynamic.tertiary,
    onTertiary = dynamic.onTertiary,
    tertiaryContainer = dynamic.tertiaryContainer,
    onTertiaryContainer = dynamic.onTertiaryContainer,
    primaryFixed = dynamic.primaryFixed,
    primaryFixedDim = dynamic.primaryFixedDim,
    onPrimaryFixed = dynamic.onPrimaryFixed,
    onPrimaryFixedVariant = dynamic.onPrimaryFixedVariant,
    secondaryFixed = dynamic.secondaryFixed,
    secondaryFixedDim = dynamic.secondaryFixedDim,
    onSecondaryFixed = dynamic.onSecondaryFixed,
    onSecondaryFixedVariant = dynamic.onSecondaryFixedVariant,
    tertiaryFixed = dynamic.tertiaryFixed,
    tertiaryFixedDim = dynamic.tertiaryFixedDim,
    onTertiaryFixed = dynamic.onTertiaryFixed,
    onTertiaryFixedVariant = dynamic.onTertiaryFixedVariant,
    inversePrimary = dynamic.inversePrimary,
    surfaceTint = dynamic.surfaceTint,
)
