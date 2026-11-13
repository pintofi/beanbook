package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.PluginPolicy
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replacing a plugin the user added with a newer file, and going back to the one it replaced. */
class PluginStoreReplaceTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "pluginreplace-${System.nanoTime()}")
        .apply { mkdirs() }
    private val incoming = File(root, "incoming").apply { mkdirs() }
    private val project = File(root, "project").apply { mkdirs() }
    private val keep = File(root, "keep")
    private val store = PluginStore(project, keep)
    private val policy = Expert

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun py(name: String, version: Int) = File(incoming, name).apply {
        writeText("__plugins__ = ['go']\nVERSION = $version\n\ndef go(entries, options_map):\n    return entries, []\n")
    }

    private fun added(version: Int = 1): InstalledPlugin {
        val installed = store.install(py("tidy.py", version), policy, "tidy.py")
        return assertIs<InstallResult.Installed>(installed).plugin
    }

    private fun running(id: String) = File(project, "plugins/$id/tidy.py").readText()

    @Test
    fun `a newer file replaces the plugin, starts off, and the earlier copy is kept`() {
        val first = added(1)
        store.setEnabled(first.id, true)

        val result = store.replace(first.id, py("tidy.py", 2), policy, "tidy.py")

        val replaced = assertIs<ReplaceResult.Replaced>(result).plugin
        assertFalse(replaced.enabled)
        assertTrue(running(first.id).contains("VERSION = 2"))
        assertEquals(PluginIntegrity.Unchanged, store.verify(store.list().single()))
        assertEquals(first.installedSha256, store.previous(first.id)?.installedSha256)
        assertTrue(File(keep, "${first.id}/files/tidy.py").readText().contains("VERSION = 1"))
    }

    @Test
    fun `the same file changes nothing`() {
        val first = added(1)

        assertIs<ReplaceResult.Same>(store.replace(first.id, py("tidy.py", 1), policy, "tidy.py"))

        assertNull(store.previous(first.id))
    }

    @Test
    fun `a refused file leaves the plugin and its earlier copy untouched`() {
        val first = added(1)
        val bad = File(incoming, "fast-1.0.whl").apply {
            java.util.zip.ZipOutputStream(outputStream()).use { out ->
                out.putNextEntry(java.util.zip.ZipEntry("fast/__init__.py")); out.closeEntry()
                out.putNextEntry(java.util.zip.ZipEntry("fast/_core.so"))
                out.write(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)); out.closeEntry()
            }
        }

        val result = store.replace(first.id, bad, Play, "fast-1.0.whl")

        assertIs<ReplaceResult.Refused>(result)
        assertTrue(running(first.id).contains("VERSION = 1"))
        assertNull(store.previous(first.id))
        assertFalse(File(project, "plugins/${first.id}.installing").exists())
    }

    @Test
    fun `going back restores the earlier copy and its record, and keeps nothing`() {
        val first = added(1)
        store.setEnabled(first.id, true)
        store.replace(first.id, py("tidy.py", 2), policy, "tidy.py")

        val back = assertIs<ReplaceResult.Replaced>(store.goBack(first.id)).plugin

        assertTrue(running(first.id).contains("VERSION = 1"))
        assertTrue(back.enabled)
        assertEquals(PluginIntegrity.Unchanged, store.verify(store.list().single()))
        assertNull(store.previous(first.id))
        assertFalse(File(keep, first.id).exists())
    }

    @Test
    fun `a kept copy that was altered is not brought back`() {
        val first = added(1)
        store.replace(first.id, py("tidy.py", 2), policy, "tidy.py")
        File(keep, "${first.id}/files/tidy.py").appendText("# tampered\n")

        assertIs<ReplaceResult.Failed>(store.goBack(first.id))

        assertTrue(running(first.id).contains("VERSION = 2"))
    }

    @Test
    fun `a third version drops the first, so one earlier copy is kept`() {
        val first = added(1)
        store.replace(first.id, py("tidy.py", 2), policy, "tidy.py")
        store.replace(first.id, py("tidy.py", 3), policy, "tidy.py")

        store.goBack(first.id)

        assertTrue(running(first.id).contains("VERSION = 2"))
    }

    @Test
    fun `a replace killed after the old files were set aside is undone`() {
        val first = added(1)
        store.setEnabled(first.id, true)
        // The note and the moved files are on disk; the new files and the index never arrived.
        File(keep, first.id).mkdirs()
        File(keep, "${first.id}/pending.json").writeText(store.list().single().toJson().write())
        File(project, "plugins/${first.id}").renameTo(File(keep, "${first.id}/files"))

        store.recover()

        assertTrue(running(first.id).contains("VERSION = 1"))
        assertEquals(PluginIntegrity.Unchanged, store.verify(store.list().single()))
        assertFalse(File(keep, first.id).exists())
    }

    @Test
    fun `a replace killed after the new files were in but before the index is undone`() {
        val first = added(1)
        File(keep, first.id).mkdirs()
        File(keep, "${first.id}/pending.json").writeText(store.list().single().toJson().write())
        File(project, "plugins/${first.id}").renameTo(File(keep, "${first.id}/files"))
        File(project, "plugins/${first.id}").mkdirs()
        File(project, "plugins/${first.id}/tidy.py").writeText("VERSION = 2\n")

        store.recover()

        assertTrue(running(first.id).contains("VERSION = 1"))
    }

    @Test
    fun `a replace killed after the index but before the note was cleared is finished`() {
        val first = added(1)
        store.replace(first.id, py("tidy.py", 2), policy, "tidy.py")
        // As if the kill came before the last two steps.
        File(keep, "${first.id}/pending.json").writeText(File(keep, "${first.id}/previous.json").readText())
        File(keep, "${first.id}/previous.json").delete()

        store.recover()

        assertTrue(running(first.id).contains("VERSION = 2"))
        assertNotNull(store.previous(first.id))
        assertFalse(File(keep, "${first.id}/pending.json").exists())
    }

    @Test
    fun `a go back killed after the current plugin was set aside is undone`() {
        val first = added(1)
        store.replace(first.id, py("tidy.py", 2), policy, "tidy.py")
        File(keep, "${first.id}/swap.note").writeText("1")
        File(project, "plugins/${first.id}").renameTo(File(keep, "${first.id}/swap"))

        store.recover()

        assertTrue(running(first.id).contains("VERSION = 2"))
        assertNotNull(store.previous(first.id))
    }

    @Test
    fun `adding a file under a name already added is reported when overwriting was not asked for`() {
        val first = added(1)

        val again = store.install(py("tidy.py", 2), policy, "tidy.py", overwrite = false)

        assertEquals(first.id, assertIs<InstallResult.Exists>(again).plugin.id)
        assertTrue(running(first.id).contains("VERSION = 1"))
    }

    @Test
    fun `removing a plugin removes its earlier copy`() {
        val first = added(1)
        store.replace(first.id, py("tidy.py", 2), policy, "tidy.py")

        store.remove(first.id)

        assertFalse(File(keep, first.id).exists())
    }

    @Test
    fun `without a place to keep the earlier copy nothing is replaced`() {
        val first = added(1)

        val result = PluginStore(project).replace(first.id, py("tidy.py", 2), policy, "tidy.py")

        assertIs<ReplaceResult.Failed>(result)
        assertTrue(running(first.id).contains("VERSION = 1"))
    }

    private object Play : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = false
        override val allowsAndroidBridge = false
        override val allowsPackageDownload = false
        override val name = "play"
    }

    private object Expert : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = true
        override val allowsAndroidBridge = true
        override val allowsPackageDownload = true
        override val name = "expert"
    }
}
