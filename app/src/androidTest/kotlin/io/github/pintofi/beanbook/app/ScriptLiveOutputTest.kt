package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.engine.local.PluginLoader
import java.io.File
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A running script's output reaches the app while the script is still running, not only when it ends.
 *
 * The engine writes what a script prints to `beanbook-script-<handle>.live` in its temporary
 * directory, and the app reads that file from its own cache directory twice a second. If the two
 * folders are not the same, the terminal on the Scripts screen stays empty until the run is over.
 */
@RunWith(AndroidJUnit4::class)
class ScriptLiveOutputTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun whatAScriptPrintsIsInTheCacheFolderWhileItStillRuns() {
        val root = File(context.cacheDir, "live-${System.nanoTime()}")
        val ledger = ProjectWorkspace(root).createProject("live", title = "Live")
        val script = File(context.cacheDir, "live-${System.nanoTime()}.py").apply {
            writeText(
                """
                import time
                for i in range(8):
                    print(f"line {i}")
                    time.sleep(0.5)
                """.trimIndent(),
            )
        }
        val handle = "live-${System.nanoTime()}"
        val liveFile = File(context.cacheDir, "beanbook-script-$handle.live")

        var result: Result<String>? = null
        val runner = thread {
            result = kotlinx.coroutines.runBlocking {
                PluginLoader(context).runScript(
                    scriptPath = script.absolutePath,
                    ledgerPath = ledger.absolutePath,
                    timeoutSeconds = 60.0,
                    allowBridge = false,
                    handle = handle,
                    liveDir = context.cacheDir.absolutePath,
                )
            }
        }

        // Read while it runs: the first lines must be there well before the eight half-seconds are up.
        var seen = ""
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && !seen.contains("line 1")) {
            Thread.sleep(100)
            if (liveFile.isFile) seen = liveFile.readText()
        }
        val stillRunning = runner.isAlive
        runner.join(30_000)
        android.util.Log.i("ScriptLive", "seen=$seen result=$result")

        assertTrue("the script finished before its output was read: $result", stillRunning)
        assertTrue("nothing was in ${liveFile.name} while the script ran; saw \"$seen\"", seen.contains("line 1"))
        assertTrue(result?.isSuccess == true)
        assertEquals("script finished and removed its live file", false, liveFile.exists())
    }
}
