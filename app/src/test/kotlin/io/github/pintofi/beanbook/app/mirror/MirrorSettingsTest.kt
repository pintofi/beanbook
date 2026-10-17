package io.github.pintofi.beanbook.app.mirror

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mirror answers for one project at a time. */
class MirrorSettingsTest {

    private fun dir(): File = createTempDirectory("mirror").toFile()

    @Test
    fun `a second project does not inherit the first project's folder`() {
        val settings = MirrorSettings(dir())
        settings.use("hmw")
        settings.setFolder("content://tree/hmw")
        settings.setEnabled(true)

        settings.use("scratch")

        assertNull(settings.folderUri())
        assertFalse(settings.enabled())
        settings.use("hmw")
        assertEquals("content://tree/hmw", settings.folderUri())
        assertTrue(settings.enabled())
    }

    @Test
    fun `a folder chosen before settings were per project goes to the first project opened, once`() {
        val state = dir()
        File(state, "mirror-folder").writeText("content://tree/old")
        File(state, "mirror-enabled").writeText("true")
        val settings = MirrorSettings(state)

        settings.use("hmw")
        settings.use("scratch")

        assertNull(settings.folderUri())
        settings.use("hmw")
        assertEquals("content://tree/old", settings.folderUri())
        assertTrue(settings.enabled())
    }

    @Test
    fun `nothing is read or written before a project is opened`() {
        val settings = MirrorSettings(dir())
        settings.setFolder("content://tree/x")
        assertNull(settings.folderUri())
        assertFalse(settings.enabled())
    }
}
