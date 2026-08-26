package dev.beancount.app.ui.theme

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
enum class ThemeChoice(val label: String) {
    SYSTEM("Follow the system"),
    LIGHT("Always light"),
    DARK("Always dark"),
    ;

    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }
}

private val LocalLedgerColours = staticCompositionLocalOf { LedgerColours.Light }

/** Colours for figures. See [LedgerColours] for why they are not Material roles. */
object BeancountTheme {
    val ledger: LedgerColours
        @Composable @ReadOnlyComposable get() = LocalLedgerColours.current

    val amount get() = AmountTextStyle
}

/**
 * The app's theme.
 *
 * Dark mode is first-class, not an afterthought: it follows the system by default and can
 * be overridden, because "follow the system" is wrong for the person who reads their ledger
 * in bed with the phone on light mode (docs/ux.md §3).
 *
 * Dynamic colour is used where the platform has it (Android 12+), so the app takes on the
 * user's wallpaper palette like the rest of their phone, and falls back to the teal in
 * [LightColours] below that. Dynamic colour can be turned off — some people dislike it, and
 * on an unlucky wallpaper it can produce a palette that fights a dense table of numbers.
 */
@Composable
fun BeancountTheme(
    choice: ThemeChoice = ThemeChoice.SYSTEM,
    dynamicColour: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = choice.isDark()
    val context = LocalContext.current

    val colours: ColorScheme = when {
        dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColours
        else -> LightColours
    }

    CompositionLocalProvider(
        LocalLedgerColours provides if (dark) LedgerColours.Dark else LedgerColours.Light,
    ) {
        MaterialTheme(
            colorScheme = colours,
            typography = BeancountTypography,
            content = content,
        )
    }
}
