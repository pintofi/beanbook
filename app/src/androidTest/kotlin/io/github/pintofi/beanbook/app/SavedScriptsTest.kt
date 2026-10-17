package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.plugins.SavedScripts
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scripts survive the launch that picked them.
 *
 * A SAF grant is good for this process and this launch, so before this a script run weekly
 * had to be found in the picker weekly, and the runner deleted its copy the moment the run
 * ended.
 */
@RunWith(AndroidJUnit4::class)
class SavedScriptsTest {

    private val dir = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "saved-scripts-${System.nanoTime()}",
    ).apply { mkdirs() }

    private val store = SavedScripts(dir)

    @After fun clean() {
        dir.deleteRecursively()
    }

    private fun source(name: String, body: String): File =
        File(dir, name).apply { writeText(body) }

    @Test fun aSavedScriptComesBackWithItsNameAndChecksum() {
        val saved = store.save(source("scratch.py", "print('hi')\n"), "monthly-check.py")

        assertEquals("monthly-check", saved.name)
        assertEquals(listOf("monthly-check"), store.list().map { it.name })
        assertEquals(
            "the copy is not the file that was saved",
            "print('hi')\n",
            store.find(saved.id)!!.file.readText(),
        )
        assertTrue("no checksum was recorded", saved.sha256.length == 64)
    }

    @Test fun savingTheSameNameReplacesRatherThanAccumulates() {
        store.save(source("a.py", "one\n"), "check.py")
        store.save(source("b.py", "two\n"), "check.py")

        assertEquals(1, store.list().size)
        assertEquals("two\n", store.list().single().file.readText())
    }

    /** The id comes back from the UI, so it must not be usable as a path. */
    @Test fun anIdThatTriesToEscapeTheStoreRemovesNothing() {
        val victim = File(dir, "outside.py").apply { writeText("keep me\n") }
        store.save(source("c.py", "x\n"), "kept.py")

        store.remove("../outside.py")

        assertTrue("a file outside the store was deleted", victim.isFile)
        assertEquals(1, store.list().size)
    }

    @Test fun aRemovedScriptIsGone() {
        val saved = store.save(source("d.py", "y\n"), "gone.py")
        store.remove(saved.id)
        assertNull(store.find(saved.id))
        assertTrue(store.list().isEmpty())
    }

    /** A name from a file picker is not a filename. */
    @Test fun awkwardNamesBecomeOrdinaryFiles() {
        val saved = store.save(source("e.py", "z\n"), "my report/2026 *final*.py")
        assertTrue("the name still has path separators: ${saved.id}", !saved.id.contains('/'))
        assertTrue(saved.id.endsWith(".py"))
    }
}
