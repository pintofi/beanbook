package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.engine.local.PluginLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An updated plugin is proved to import, in the real engine, before the update is kept. The probe is a
 * fresh import that leaves the running interpreter as it was, so these run against the same process
 * the app uses.
 */
@RunWith(AndroidJUnit4::class)
class PluginProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "probe-${System.nanoTime()}")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun site(name: String, vararg files: Pair<String, String>): String {
        files.forEach { (relative, text) ->
            File(root, "$name/$relative").apply { parentFile?.mkdirs() }.writeText(text)
        }
        return File(root, name).absolutePath
    }

    @Test
    fun aVersionThatImportsPassesAndOneThatDoesNotFailsWithTheReason() = runBlocking {
        val loader = PluginLoader(context)
        val good = site("good", "probedpkg/__init__.py" to "", "probedpkg/mod.py" to "X = 1\n")
        val bad = site("bad", "probedpkg/__init__.py" to "import module_that_is_not_there\n")

        val ok = loader.probe(good, "probedpkg.mod", allowBridge = true).getOrThrow()
        val broken = loader.probe(bad, "probedpkg", allowBridge = true).getOrThrow()

        assertTrue(ok, "'failed': 0" in ok)
        assertTrue(broken, "'failed': 1" in broken)
        assertTrue(broken, "module_that_is_not_there" in broken)
    }

    @Test
    fun theBridgeStaysBlockedForTheProbeWhenTheEditionBlocksIt() = runBlocking {
        val loader = PluginLoader(context)
        val bridge = site("bridge", "probebridge/__init__.py" to "import java\n")

        val answer = loader.probe(bridge, "probebridge", allowBridge = false).getOrThrow()

        assertTrue(answer, "'failed': 1" in answer)
    }
}
