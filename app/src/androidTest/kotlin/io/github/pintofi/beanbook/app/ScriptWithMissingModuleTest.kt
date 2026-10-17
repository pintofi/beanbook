package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.engine.local.PluginLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A script that needs a package the app does not carry, and what happens next.
 *
 * The whole journey a person actually takes: run a script, meet `ModuleNotFoundError`, fetch
 * the module from the Plugins screen, run it again. It used to end at step three -- fetching
 * changed nothing, because the packages directory was only put on `sys.path` **when a plugin
 * was installed**, and a script is not a plugin. The install had worked; nothing could use it.
 *
 * **Uses the network**, like the other installer tests, and says so when there is none.
 */
@RunWith(AndroidJUnit4::class)
class ScriptWithMissingModuleTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aFetchedPackageReachesTheNextRunOfAScript() {
        assumeTrue(
            "this edition cannot fetch packages, so there is nothing to test",
            currentPluginPolicy().allowsPackageDownload,
        )
        val script = File("/data/local/tmp/commodities.py")
        assertTrue(
            "push the script first: adb push commodities.py /data/local/tmp/",
            script.isFile,
        )

        val root = File(context.cacheDir, "script-${System.nanoTime()}")
        val workspace = ProjectWorkspace(root)
        val ledger = workspace.createProject("script", title = "Script")
        val packages = File(context.cacheDir, "script-packages-${System.nanoTime()}")
        val loader = PluginLoader(context)

        try {
            // Before: the module is missing, and the engine says which one.
            val first = runBlocking {
                loader.runScript(
                    scriptPath = script.absolutePath,
                    ledgerPath = ledger.absolutePath,
                    timeoutSeconds = 60.0,
                    allowBridge = true,
                    handle = "before",
                )
            }.getOrElse { throw AssertionError("the engine could not run the script: $it") }
            android.util.Log.i("ScriptModule", "before=$first")
            assertTrue(
                "the failure should name the module: $first",
                first.contains("tabulate"),
            )

            // Fetch it exactly as the Plugins screen does.
            val fetcher = io.github.pintofi.beanbook.app.packages.WheelFetcher(
                packages, allowed = true, allowNative = true, assets = context.assets,
            )
            val resolution = fetcher.resolve(listOf("tabulate")).getOrElse {
                throw AssertionError("could not resolve tabulate (network?): $it")
            }
            fetcher.install(resolution.wheels)

            // A new engine process, because Python has already answered this import once.
            val engine = io.github.pintofi.beanbook.engine.local.LocalEngineClient(context)
            runBlocking { engine.restartProcess() }

            val second = runBlocking {
                loader.runScript(
                    scriptPath = script.absolutePath,
                    ledgerPath = ledger.absolutePath,
                    timeoutSeconds = 60.0,
                    allowBridge = true,
                    handle = "after",
                    pluginDirs = packages.absolutePath,
                )
            }.getOrElse { throw AssertionError("the engine could not run the script: $it") }
            android.util.Log.i("ScriptModule", "after=$second")
            assertFalse("the module is still missing: $second", second.contains("ModuleNotFoundError"))
            assertTrue("the script produced no output: $second", second.contains("commodity"))
        } finally {
            root.deleteRecursively()
            packages.deleteRecursively()
        }
    }
}
