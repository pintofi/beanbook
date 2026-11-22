package io.github.pintofi.beanbook.app.plugins

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class SavedScriptsTest {

    @Test
    fun `a script that cannot be read while listing is left out instead of crashing the app`() {
        val state = createTempDirectory("scripts").toFile()
        val store = SavedScripts(state)
        val kept = state.resolve("scripts").apply { mkdirs() }.resolve("kept.py").apply { writeText("print(1)") }
        val unreadable = kept.resolveSibling("gone.py").apply { writeText("print(2)") }
        // What a script deleted between the folder listing and the read looks like to the reader.
        assumeTrue(unreadable.setReadable(false) && !unreadable.canRead())

        assertEquals(listOf("kept.py"), store.list().map { it.id })
    }
}
