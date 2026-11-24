package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.backup.AppDataSource
import io.github.pintofi.beanbook.core.data.InstallResult
import io.github.pintofi.beanbook.core.data.PluginStore
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A plugin installed the way the Plugins screen installs one is offered in the Backup checklist. */
@RunWith(AndroidJUnit4::class)
class BackupChecklistDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun anInstalledPluginIsOfferedAndCanBeLeftOut() {
        val base = File(context.cacheDir, "checklist-${System.nanoTime()}").apply { mkdirs() }
        val workspace = ProjectWorkspace(base)
        workspace.createProject("main", title = "main")
        val script = File(base, "hello.py").apply { writeText("def hello():\n    return 1\n") }

        val installed = PluginStore(workspace.projectDir("main")).install(script, currentPluginPolicy(), "hello.py")
        assertTrue("did not install: $installed", installed is InstallResult.Installed)

        val source = AppDataSource(workspace, File(base, "state"))
        val offered = source.available("main")
        val plugin = offered.filter { it.key.startsWith("plugin:") }
        assertEquals("offered: $offered", 1, plugin.size)
        assertEquals(setOf((installed as InstallResult.Installed).plugin.id), source.pluginsLeftOut("main", emptySet()))
        base.deleteRecursively()
    }
}
