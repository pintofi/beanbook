package io.github.pintofi.beanbook.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.ui.theme.DarkColoursForTest
import io.github.pintofi.beanbook.app.ui.theme.LightColoursForTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every surface role is this app's, not Material's baseline.
 *
 * `lightColorScheme()` and `darkColorScheme()` fill any role you omit, and what they fill it
 * with is the **purple** baseline. The five `surfaceContainer*` roles were omitted, and those
 * are exactly the ones `Card`, `NavigationBar`, menus and sheets read — so the app rendered
 * teal accents on lilac containers while every colour it had actually declared was correct.
 * Nothing failed, and no screenshot test noticed, because each individual colour was fine.
 *
 * Two assertions, because either alone can pass for the wrong reason:
 *
 * 1. **Not equal to the baseline.** Catches the role being dropped again.
 * 2. **Not purple-tinted.** Catches it being set to something that is still Material's hue.
 *    These neutrals are mixed towards the teal primary, so green is never below red; the
 *    baseline's are mixed towards purple, where red leads. That single channel comparison
 *    separates the two families cleanly and does not pin an exact value, so retuning the
 *    palette does not break this test — only abandoning it does.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceRolesTest {

    private fun roles(scheme: ColorScheme) = mapOf(
        "surfaceBright" to scheme.surfaceBright,
        "surfaceDim" to scheme.surfaceDim,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    )

    private fun assertNotBaseline(ours: ColorScheme, baseline: ColorScheme, which: String) {
        val theirs = roles(baseline)
        roles(ours).forEach { (name, colour) ->
            // Pure white and pure black are exempt. They are the endpoints of every neutral
            // ramp, so both palettes reach them for the same reason and matching there means
            // agreement rather than omission -- `surfaceContainerLowest` in a light scheme is
            // legitimately #FFFFFF. They also carry no hue, which is what this is looking for.
            if (colour == Color.White || colour == Color.Black) return@forEach
            assertTrue(
                "$which.$name is still Material's baseline value — the role was not declared, " +
                    "so Card and NavigationBar will draw it in the baseline hue",
                colour != theirs.getValue(name),
            )
        }
    }

    /** Material's neutral baseline leads with red; these neutrals lean teal, so green does. */
    private fun assertNotPurple(scheme: ColorScheme, which: String) {
        roles(scheme).forEach { (name, colour) ->
            assertTrue(
                "$which.$name has more red than green (${colour.toArgbHex()}), which is the " +
                    "signature of Material's purple neutrals rather than this app's",
                colour == Color.White || colour.green >= colour.red,
            )
        }
    }

    private fun Color.toArgbHex() = "#%08X".format(value.shr(32).toInt())

    @Test fun theLightSchemeDeclaresItsOwnSurfaces() {
        assertNotBaseline(LightColoursForTest, lightColorScheme(), "light")
        assertNotPurple(LightColoursForTest, "light")
    }

    @Test fun theDarkSchemeDeclaresItsOwnSurfaces() {
        assertNotBaseline(DarkColoursForTest, darkColorScheme(), "dark")
        assertNotPurple(DarkColoursForTest, "dark")
    }
}
