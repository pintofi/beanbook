package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.PluginPolicy
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A plugin edited outside the app is not loaded until it is installed again (R4).
 *
 * Plugins live at `<project>/plugins/`, which is inside what auto-mirror syncs. A `.py` or a
 * native `.so` dropped into the user's mirrored cloud folder is pulled into the project, and
 * the policy check that would have refused it ran at install time — months earlier, on
 * different bytes. `PluginStore.verify` noticed the change but only for display: nothing
 * consulted it before handing the directory to the engine.
 *
 * In the Play edition that is a route to loading native code, which `PlayPluginPolicy`
 * exists to prevent — a policy hole, not only a robustness one.
 */
class ChangedPluginTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "changed-plugin-${System.nanoTime()}")
        .apply { mkdirs() }
    private val incoming = File(root, "incoming").apply { mkdirs() }
    private val project = File(root, "project").apply { mkdirs() }
    private val store = PluginStore(project)
    private val safeMode = PluginSafeMode(project)

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun install(name: String, body: String): InstalledPlugin {
        val file = File(incoming, name).apply { writeText(body) }
        val result = store.install(file, Expert, "test")
        assertIs<InstallResult.Installed>(result, "fixture failed to install: $result")
        store.setEnabled(result.plugin.id, true)
        return result.plugin
    }

    @Test
    fun `an untouched plugin is loaded`() {
        install("tagger.py", "__plugins__ = []\n")

        assertEquals(1, safeMode.pluginsToLoad(store).size, "an intact plugin was not loaded")
    }

    @Test
    fun `a plugin whose bytes changed on disk is not loaded`() {
        val plugin = install("tagger.py", "__plugins__ = []\n")

        // What the mirror does: replaces the file in place, with no policy check.
        val onDisk = store.directoryFor(plugin).walkTopDown().first { it.isFile && it.extension == "py" }
        onDisk.writeText("__plugins__ = []\nimport os\nos.system('id')\n")

        assertTrue(
            safeMode.pluginsToLoad(store).isEmpty(),
            "a plugin edited outside the app was still handed to the engine",
        )
    }

    @Test
    fun `a plugin whose files vanished is not loaded`() {
        val plugin = install("tagger.py", "__plugins__ = []\n")
        store.directoryFor(plugin).deleteRecursively()

        assertTrue(
            safeMode.pluginsToLoad(store).isEmpty(),
            "a plugin whose files are gone was still handed to the engine",
        )
    }

    private object Expert : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = true
        override val allowsAndroidBridge = true
        override val allowsPackageDownload = true
        override val name = "expert"
    }
}
