package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPaletteTest {
    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(what: String, a: Color, b: Color) =
        assertTrue("$what: ${contrast(a, b)}", contrast(a, b) >= 4.5)

    @Test
    fun every_pair_clears_4_5_to_1_in_light_and_dark() {
        AccentPalette.entries.forEach { palette ->
            listOf(false to LightColours, true to DarkColours).forEach { (dark, own) ->
                val t = if (dark) palette.dark else palette.light
                val paper = own.withAccent(palette, dark).background
                val where = "${palette.name} ${if (dark) "dark" else "light"}"
                check("$where accent on paper", t.primary, paper)
                check("$where on primary", t.onPrimary, t.primary)
                check("$where on primary container", t.onPrimaryContainer, t.primaryContainer)
                check("$where on secondary", t.onSecondary, t.secondary)
                check("$where on secondary container", t.onSecondaryContainer, t.secondaryContainer)
                t.tertiary?.let {
                    check("$where tertiary on paper", it.tertiary, paper)
                    check("$where on tertiary container", it.onContainer, it.container)
                }
            }
        }
    }

    @Test
    fun teal_is_the_apps_own_colour_unchanged() {
        assertEquals(LightColours.primary, LightColours.withAccent(AccentPalette.TEAL, false).primary)
        assertEquals(DarkColours.primaryContainer, DarkColours.withAccent(AccentPalette.TEAL, true).primaryContainer)
    }

    @Test
    fun the_old_two_line_file_is_still_read_and_a_projects_colour_survives_a_restart() {
        val dir = Files.createTempDirectory("appearance").toFile()
        File(dir, "appearance").writeText("DARK\nfalse\n")
        val old = ThemeSettings(dir)
        assertEquals(ThemeChoice.DARK, old.choice.value)
        // Dynamic colour had been turned off, so a project with no choice of its own is in the teal palette.
        assertEquals(ProjectColour.Palette(AccentPalette.TEAL), old.colourOf("household"))

        old.setProjectColour("household", ProjectColour.Palette(AccentPalette.OCHRE))
        old.setProjectColour("garden", ProjectColour.Dynamic)
        val again = ThemeSettings(dir)
        assertEquals(ThemeChoice.DARK, again.choice.value)
        assertEquals(ProjectColour.Palette(AccentPalette.OCHRE), again.colourOf("household"))
        assertEquals(ProjectColour.Dynamic, again.colourOf("garden"))
        assertEquals(ProjectColour.Palette(AccentPalette.TEAL), again.colourOf("other"))
        dir.deleteRecursively()
    }

    @Test
    fun a_new_install_colours_every_project_from_the_wallpaper_until_it_chooses() {
        val dir = Files.createTempDirectory("appearance").toFile()
        assertEquals(ProjectColour.Dynamic, ThemeSettings(dir).colourOf("any"))
        assertEquals(ProjectColour.Dynamic, ThemeSettings(dir).colourOf(null))
        dir.deleteRecursively()
    }

    @Test
    fun an_unknown_colour_name_falls_back_to_the_default() {
        val dir = Files.createTempDirectory("appearance").toFile()
        File(dir, "appearance").writeText("theme=LIGHT\naccent=NEON\nproject.a=NEON\nproject.b=PLUM\n")
        val settings = ThemeSettings(dir)
        assertEquals(ProjectColour.Dynamic, settings.colourOf("a"))
        assertEquals(ProjectColour.Palette(AccentPalette.PLUM), settings.colourOf("b"))
        dir.deleteRecursively()
    }
}
