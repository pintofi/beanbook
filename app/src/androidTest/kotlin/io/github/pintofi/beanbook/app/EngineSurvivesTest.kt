package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.engine.local.LocalEngineClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine starts again after the app lost track of it.
 *
 * Both failures were seen on a phone after switching away and back: Android had ended the
 * app's own process, or the engine's, and the next start reported "engine already started;
 * call stop() first" or "Transaction failed on small parcel; remote process probably died".
 */
@RunWith(AndroidJUnit4::class)
class EngineSurvivesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun project(): File {
        val root = File(context.cacheDir, "engine-survives-${System.nanoTime()}")
        return ProjectWorkspace(root).createProject("survives", title = "Survives")
    }

    /** A second client -- the app process that came back -- finds the old server running. */
    @Test fun aNewClientReplacesAServerItCannotReach() = runBlocking {
        val main = project()
        val first = LocalEngineClient(context)
        val second = LocalEngineClient(context)
        try {
            assertTrue(first.start(main.absolutePath) is EngineStatus.Serving)
            val again = second.start(main.absolutePath)
            assertTrue("the second start failed: $again", again is EngineStatus.Serving)
        } finally {
            second.stop()
            main.parentFile?.parentFile?.deleteRecursively()
        }
    }

    /** The engine process is ended under a client; the next start binds a new one. */
    @Test fun aStartAfterTheProcessDiedBindsANewOne() = runBlocking {
        val main = project()
        val client = LocalEngineClient(context)
        try {
            assertTrue(client.start(main.absolutePath) is EngineStatus.Serving)
            val pid = client.enginePid()
            if (pid != null) android.os.Process.killProcess(pid)
            client.stop()
            val again = client.start(main.absolutePath)
            assertTrue("the start after the process died failed: $again", again is EngineStatus.Serving)
        } finally {
            client.stop()
            main.parentFile?.parentFile?.deleteRecursively()
        }
    }
}
