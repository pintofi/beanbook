package dev.beancount.core.data

import dev.beancount.core.domain.PluginPolicy
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Getting out of the loop where a plugin kills the app on every launch.
 */
class PluginSafeModeTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "safemode-${System.nanoTime()}")
        .apply { mkdirs() }
    private val project = File(root, "project").apply { mkdirs() }
    private val incoming = File(root, "incoming").apply { mkdirs() }
    private val store = PluginStore(project)
    private val safeMode = PluginSafeMode(project)

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private val plain = "__plugins__ = []\n"

    private fun install(name: String): InstalledPlugin {
        val file = File(incoming, "$name.py").apply { writeText(plain + "# $name\n") }
        val result = store.install(file, Expert, "Downloads/$name.py") as InstallResult.Installed
        store.setEnabled(result.plugin.id, true)
        return result.plugin
    }

    /** Exactly what Python leaves behind when a plugin takes the process down. */
    private fun leaveCrashMarker(pluginId: String, module: String) {
        File(project, "plugins").mkdirs()
        File(project, "plugins/loading.marker").writeText("$pluginId\n$module")
    }

    @Test
    fun `a clean run leaves nothing to recover from`() {
        install("tidy")

        assertNull(safeMode.interrupted())
        assertNull(safeMode.recoverFromCrash(store))
    }

    @Test
    fun `the plugin that killed the process is turned off, and only that one`() {
        val killer = install("killer")
        val innocent = install("tidy")
        leaveCrashMarker(killer.id, "killer")

        val recovery = safeMode.recoverFromCrash(store)!!

        assertEquals(killer.id, recovery.pluginId)
        assertTrue(recovery.disabled)
        assertFalse(store.list().single { it.id == killer.id }.enabled)
        assertTrue(
            store.list().single { it.id == innocent.id }.enabled,
            "an unrelated plugin was turned off too",
        )
    }

    @Test
    fun `the message names the plugin and says what was done about it`() {
        val killer = install("killer")
        leaveCrashMarker(killer.id, "killer")

        val message = safeMode.recoverFromCrash(store)!!.describe()

        assertTrue(message.contains(killer.id), message)
        assertTrue(message.contains("turned off"), message)
        // A dead end helps nobody: say how to undo it.
        assertTrue(message.contains("turn it back on"), message)
    }

    @Test
    fun `recovering clears the marker, so the same crash is not recovered from forever`() {
        val killer = install("killer")
        leaveCrashMarker(killer.id, "killer")

        safeMode.recoverFromCrash(store)

        assertNull(safeMode.interrupted())
        assertNull(safeMode.recoverFromCrash(store), "the same crash was recovered from twice")
    }

    @Test
    fun `a marker naming a plugin that has since been removed still clears`() {
        // Otherwise the app recovers from a plugin that is not there on every single launch.
        leaveCrashMarker("long-gone", "long_gone")

        val recovery = safeMode.recoverFromCrash(store)!!

        assertFalse(recovery.disabled)
        assertTrue(recovery.describe().contains("no longer installed"), recovery.describe())
        assertNull(safeMode.interrupted())
    }

    @Test
    fun `an empty or truncated marker is not treated as a crash`() {
        File(project, "plugins").mkdirs()
        File(project, "plugins/loading.marker").writeText("\n")

        assertNull(safeMode.interrupted())
    }

    @Test
    fun `safe mode holds every plugin back without forgetting which were on`() {
        val one = install("one")
        val two = install("two")
        assertEquals(2, safeMode.pluginsToLoad(store).size)

        safeMode.isSafeMode = true
        assertTrue(safeMode.pluginsToLoad(store).isEmpty())

        // The user's own choices survive it, which is the whole difference between safe
        // mode and turning everything off.
        safeMode.isSafeMode = false
        assertEquals(
            setOf(one.id, two.id),
            safeMode.pluginsToLoad(store).map { it.id }.toSet(),
        )
    }

    @Test
    fun `safe mode survives a restart, because it is on disk and not in memory`() {
        install("one")
        safeMode.isSafeMode = true

        assertTrue(PluginSafeMode(project).isSafeMode)
        assertTrue(PluginSafeMode(project).pluginsToLoad(store).isEmpty())
    }

    @Test
    fun `a disabled plugin is not loaded even outside safe mode`() {
        val one = install("one")
        store.setEnabled(one.id, false)

        assertTrue(safeMode.pluginsToLoad(store).isEmpty())
    }

    private object Expert : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = true
        override val allowsAndroidBridge = true
        override val allowsSelfUpdate = true
        override val name = "expert"
    }
}
