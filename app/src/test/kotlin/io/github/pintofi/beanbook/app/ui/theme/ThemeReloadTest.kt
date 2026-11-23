package io.github.pintofi.beanbook.app.ui.theme

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/** A restored backup replaces the appearance file; the running app must take it up and not write the old one back. */
class ThemeReloadTest {

    @Test fun reloadTakesUpAReplacedFileAndLaterChangesKeepIt() {
        val dir = Files.createTempDirectory("theme").toFile()
        try {
            File(dir, "appearance").writeText("theme=LIGHT\n")
            val settings = ThemeSettings(dir)
            assertEquals(ThemeChoice.LIGHT, settings.choice.value)

            File(dir, "appearance").writeText("theme=DARK\nmotion=reduced\n")
            settings.reload()
            assertEquals(ThemeChoice.DARK, settings.choice.value)
            assertEquals(true, settings.comfort.value.reduceMotion)

            settings.setChoice(ThemeChoice.DARK)
            assertEquals(true, File(dir, "appearance").readText().contains("motion=reduced"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
