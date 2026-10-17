package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.engine.local.LocalEngineClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restarting the engine ends the interpreter, rather than just the server.
 *
 * The distinction is the whole feature. Python answers an import once and caches the answer,
 * so a package fetched after the process started is invisible to it and an upgraded one keeps
 * its old code — "reopen the ledger" re-parses inside that same interpreter and changes
 * nothing. Only a new process reads what is on disk now.
 *
 * Proved by the process identity: the engine answers from a different pid afterwards, which
 * is the only thing that clears Python's module cache. The port changing is a symptom.
 */
@RunWith(AndroidJUnit4::class)
class EngineRestartTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aRestartEndsTheInterpreterRatherThanJustTheServer() {
        val root = File(context.cacheDir, "restart-${System.currentTimeMillis()}")
        val workspace = ProjectWorkspace(root)
        val main = workspace.createProject("restart", title = "Restart")
        val engine = LocalEngineClient(context)
        val plugins = File(context.cacheDir, "restart-plugins-${System.currentTimeMillis()}")
            .apply { mkdirs() }
        try {
            engine.pluginDirs = listOf(plugins.absolutePath)
            val first = runBlocking { engine.start(main.absolutePath) }
            assertTrue("engine did not start: $first", first is EngineStatus.Serving)
            val firstOrigin = (first as EngineStatus.Serving).origin
            val firstPid = runBlocking { engine.enginePid() }
            assertTrue("the engine did not report a pid", firstPid != null)

            // Written *after* Python is running, which is exactly the case the restart is for.
            File(plugins, "bca_restart_probe.py").writeText("VALUE = 'after'\n")

            runBlocking { engine.restartProcess() }
            val second = runBlocking { engine.start(main.absolutePath) }
            assertTrue("engine did not come back: $second", second is EngineStatus.Serving)
            val secondOrigin = (second as EngineStatus.Serving).origin

            val secondPid = runBlocking { engine.enginePid() }
            // The property that matters: a different process, so nothing Python imported
            // before survives into the new engine.
            assertNotEquals(
                "the engine came back in the same process, so its module cache is intact",
                firstPid,
                secondPid,
            )
            assertNotEquals(
                "the engine kept the same origin, so the server was never replaced",
                firstOrigin,
                secondOrigin,
            )
            // The file written while the old interpreter was running is what the new one can
            // see; nothing here imports it, and the register says so.
            assertTrue(File(plugins, "bca_restart_probe.py").isFile)
        } finally {
            runBlocking { engine.stop() }
            root.deleteRecursively()
            plugins.deleteRecursively()
        }
    }
}
