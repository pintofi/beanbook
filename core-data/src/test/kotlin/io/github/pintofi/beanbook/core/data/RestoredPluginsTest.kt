package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.PluginPolicy
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A plugin is code, and the list saying which ones run travels with the project. Whatever a restore or
 * an import brings in, no plugin may arrive turned on (docs/threat-model.md 3.2).
 */
class RestoredPluginsTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "restoredplugins-${System.nanoTime()}")
        .apply { mkdirs() }

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private object Expert : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = true
        override val allowsAndroidBridge = true
        override val allowsPackageDownload = true
        override val name = "expert"
    }

    private object Play : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = false
        override val allowsAndroidBridge = false
        override val allowsPackageDownload = false
        override val name = "play"
    }

    private val plain = "__plugins__ = ['go']\n\ndef go(entries, options_map):\n    return entries, []\n"

    /** A project folder holding two plugins that are turned on, as a backup from an Expert phone could. */
    private fun projectWithPluginsOn(dir: File): PluginStore {
        dir.mkdirs()
        val incoming = File(root, "incoming-${dir.name}").apply { mkdirs() }
        val store = PluginStore(dir)
        for ((name, body) in listOf("tidy.py" to plain, "bridged.py" to "import java\n")) {
            val file = File(incoming, name).apply { writeText(body) }
            val plugin = (store.install(file, Expert, "somewhere") as InstallResult.Installed).plugin
            store.setEnabled(plugin.id, true)
        }
        File(dir, "plugins/loading.marker").writeText("tidy\nmodule\n")
        File(dir, "plugins/safe-mode").writeText("on")
        return store
    }

    @Test
    fun `every plugin comes back turned off and the device's own state is dropped`() {
        val project = File(root, "project")
        val store = projectWithPluginsOn(project)
        assertEquals(2, store.list().count { it.enabled })

        val held = RestoredPlugins.hold(project, Expert)

        assertEquals(listOf("bridged.py", "tidy.py"), held.turnedOff.sorted())
        assertTrue(PluginStore(project).list().none { it.enabled })
        assertFalse(File(project, "plugins/loading.marker").exists())
        assertFalse(File(project, "plugins/safe-mode").exists())
        assertEquals(emptyList(), held.notAllowed)
    }

    @Test
    fun `a plugin this edition does not allow is said so, and still turned off`() {
        val project = File(root, "project")
        projectWithPluginsOn(project)

        val held = RestoredPlugins.hold(project, Play)

        assertEquals(listOf("bridged.py"), held.notAllowed)
        assertTrue(PluginStore(project).list().none { it.enabled })
        assertTrue(held.describe()!!.contains("not allowed in this edition"))
    }

    @Test
    fun `with no policy to judge by nothing is left turned on`() {
        val project = File(root, "project")
        projectWithPluginsOn(project)

        val held = RestoredPlugins.hold(project, null)

        assertTrue(PluginStore(project).list().none { it.enabled })
        assertEquals(emptyList(), held.notAllowed)
    }

    @Test
    fun `a project with no plugins has nothing to say`() {
        val project = File(root, "empty").apply { mkdirs() }
        assertEquals(null, RestoredPlugins.hold(project, Play).describe())
    }

    @Test
    fun `a damaged list is left exactly as it is`() {
        val project = File(root, "damaged")
        File(project, "plugins").mkdirs()
        val index = File(project, "plugins/installed.json").apply { writeText("{ not json") }

        RestoredPlugins.hold(project, Play)

        assertEquals("{ not json", index.readText())
    }

    @Test
    fun `putting a restore in place leaves its plugins turned off`() {
        val workspace = ProjectWorkspace(File(root, "app"))
        workspace.createProject("p", title = "P")
        val staging = File(root, "staging")
        projectWithPluginsOn(staging)

        val plan = RestorePlan(workspace, Play)
        plan.activate("p", staging)

        assertTrue(PluginStore(workspace.projectDir("p")).list().none { it.enabled })
        assertEquals(2, plan.lastHeld.turnedOff.size)
        assertEquals(listOf("bridged.py"), plan.lastHeld.notAllowed)
    }

    @Test
    fun `a plain export leaves out the runtime files a backup leaves out`() {
        val workspace = ProjectWorkspace(File(root, "app"))
        workspace.createProject("e", title = "E")
        val project = workspace.projectDir("e")
        File(project, "plugins").mkdirs()
        File(project, "plugins/installed.json").writeText("{\"schema\":1,\"plugins\":[]}")
        File(project, "plugins/loading.marker").writeText("x\ny\n")
        File(project, "plugins/safe-mode").writeText("on")
        File(project, "main.beancount.picklecache").writeText("cache")
        File(project, "files/transactions.beancount.tmp").writeText("half")

        val out = File(root, "out").apply { mkdirs() }
        ProjectExporter(workspace).exportFolder("e", DirectoryExportTarget(out))

        val names = out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).path.replace('\\', '/') }.toSet()
        assertTrue("plugins/installed.json" in names, "the plugins folder still travels: $names")
        assertFalse("plugins/loading.marker" in names)
        assertFalse("plugins/safe-mode" in names)
        assertFalse(names.any { it.endsWith(".picklecache") || it.endsWith(".tmp") })
    }
}
