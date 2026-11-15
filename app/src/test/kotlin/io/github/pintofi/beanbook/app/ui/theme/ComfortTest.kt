package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortTest {
    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun higher_contrast_is_stronger_than_the_apps_own_and_secondary_text_clears_7_to_1() {
        listOf(false to LightColours, true to DarkColours).forEach { (dark, own) ->
            val high = own.withHigherContrast(dark)
            listOf(high.background, high.surfaceContainerHigh).forEach { ground ->
                assertTrue("secondary text on $ground", contrast(high.onSurfaceVariant, ground) >= 7.0)
                assertTrue("ink on $ground", contrast(high.onSurface, ground) >= 12.0)
                assertTrue("rule on $ground", contrast(high.outlineVariant, ground) >= 3.0)
            }
            fun gain(pick: (androidx.compose.material3.ColorScheme) -> Color) =
                contrast(pick(high), high.background) - contrast(pick(own), own.background)
            assertTrue(gain { it.outlineVariant } > 0)
            assertTrue(gain { it.onSurfaceVariant } > 0)
        }
    }

    @Test
    fun the_options_survive_a_restart() {
        val dir = Files.createTempDirectory("comfort").toFile()
        val settings = ThemeSettings(dir)
        assertEquals(Comfort(), settings.comfort.value)
        settings.setComfort(Comfort(true, true, true, true, ChangeMarks.CLEAR))
        assertEquals(Comfort(true, true, true, true, ChangeMarks.CLEAR), ThemeSettings(dir).comfort.value)
        File(dir, "appearance").appendText("changes=SPARKLY\n")
        assertEquals(ChangeMarks.SUBTLE, ThemeSettings(dir).comfort.value.changeMarks)
        dir.deleteRecursively()
    }
}
