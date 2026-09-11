package io.github.pintofi.beanbook.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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
 * own. Listing it beside "Always dark" would make the two look exclusive when they compose.
 *
 * BLACK is a dark mode, not a fourth palette: it is [DARK] with the ground taken to #000000
 * for OLED screens, and it composes with dynamic colour like the others.
 */
enum class ThemeChoice(val label: String) {
    SYSTEM("Follow the system"),
    LIGHT("Always light"),
    DARK("Always dark"),
    BLACK("Always dark, pure black"),
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

    CompositionLocalProvider(
        LocalLedgerColours provides ledger,
        LocalMotionEnabled provides motion,
    ) {
        MaterialTheme(
            colorScheme = colours,
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
 * and the tint. Backgrounds, surfaces, outlines and the inverse roles stay as designed,
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
    inversePrimary = dynamic.inversePrimary,
    surfaceTint = dynamic.surfaceTint,
)
