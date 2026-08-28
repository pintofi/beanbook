package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.app.packages.PackagesState
import dev.beancount.app.packages.PackagesStatus
import dev.beancount.app.plugins.PluginsState
import dev.beancount.core.data.InstallResult
import dev.beancount.core.data.PluginStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-demand installer as the *app* uses it, not as a test calls the fetcher.
 *
 * This exists because of a real defect. `WheelFetcher` was written, tested and documented,
 * and nothing in the app ever called it — so R8 removed it from the release build entirely,
 * and the feature three documents described was unreachable by any user. Tests that drive a
 * class directly cannot see that; only a call path from the app can.
 *
 * These tests are offline. What they cover is the wiring and the order of the decision, which
 * is where that defect lived. The downloading itself is [WheelFetcherTest].
 */
@RunWith(AndroidJUnit4::class)
class PackagesStateTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val project = File(context.cacheDir, "packages-state-${System.nanoTime()}")

    @After fun clean() {
        project.deleteRecursively()
        File(context.filesDir, "ondemand").deleteRecursively()
    }

    private fun state() = PackagesState(context, currentPluginPolicy(), scope)

    /**
     * Nothing is downloaded until the user has seen what and how much.
     *
     * Asking is a separate step from fetching, and this is the property that makes the
     * warning on the screen meaningful rather than decorative.
     */
    @Test fun askingForAPackageDownloadsNothingByItself() = runBlocking {
        val packages = state()
        val before = File(context.filesDir, "ondemand")

        packages.request("packaging")
        withTimeout(60_000) {
            packages.status.first { it !is PackagesStatus.Resolving }
        }

        if (currentPluginPolicy().allowsPackageDownload) {
            // Resolved and waiting for a decision, with nothing on disk yet.
            val status = packages.status.value
            assertTrue(
                "expected to be waiting for confirmation, was $status",
                status is PackagesStatus.Confirming,
            )
            val listed = (status as PackagesStatus.Confirming).resolution.wheels
            assertTrue("resolving found no wheel to offer", listed.isNotEmpty())
        }
        assertFalse(
            "something was downloaded before the user agreed to anything",
            before.isDirectory && before.walkTopDown().any { it.isFile },
        )
    }

    /** Backing out of the confirmation leaves the device exactly as it was. */
    @Test fun decliningTheConfirmationFetchesNothing() = runBlocking {
        val packages = state()
        packages.request("packaging")
        withTimeout(60_000) { packages.status.first { it !is PackagesStatus.Resolving } }

        packages.cancel()

        assertEquals(PackagesStatus.Idle, packages.status.value)
        assertFalse(
            "cancelling still left packages behind",
            File(context.filesDir, "ondemand").walkTopDown().any { it.isFile },
        )
    }

    /** `confirm` with nothing pending is a no-op rather than a crash or a blind fetch. */
    @Test fun confirmingWithNothingPendingDoesNothing() = runBlocking {
        val packages = state()

        packages.confirm()

        assertEquals(PackagesStatus.Idle, packages.status.value)
        assertFalse(
            "a confirmation with no pending request fetched something",
            File(context.filesDir, "ondemand").walkTopDown().any { it.isFile },
        )
    }

    /**
     * The fetched directory reaches the engine — which is the whole point of fetching.
     *
     * `PluginsState.enabledDirectories` is the single string handed to both the parse path
     * and the serving path. If what was fetched is not in it, the packages sit on disk and no
     * plugin can import them.
     */
    @Test fun whatWasFetchedIsHandedToTheEngineAlongsideThePlugins() {
        project.mkdirs()
        val source = File(project, "src").apply { mkdirs() }
        File(source, "noop_plugin.py").writeText("__plugins__ = []\n")

        val store = PluginStore(project)
        val installed = store.install(source, currentPluginPolicy(), "packages state test")
        assertTrue("the fixture plugin did not install: $installed", installed is InstallResult.Installed)
        store.setEnabled((installed as InstallResult.Installed).plugin.id, true)

        val fetched = File(context.filesDir, "ondemand").apply { mkdirs() }
        File(fetched, "marker.py").writeText("# stands in for a fetched package\n")

        val plugins = PluginsState(context, currentPluginPolicy(), scope)
        plugins.openProject(project)

        val directories = plugins.enabledDirectories().split(",").filter { it.isNotBlank() }

        assertTrue(
            "the fetched packages never reach the engine: $directories",
            directories.contains(fetched.absolutePath),
        )
        assertTrue(
            "the plugin's own directory was lost: $directories",
            directories.any { it.contains("plugins") },
        )
    }

    /**
     * Safe mode means "load nothing of mine", and fetched libraries are covered by that.
     *
     * Appending the packages directory unconditionally would have made safe mode return a
     * non-empty list, which is the one thing that switch promises never to do.
     */
    @Test fun safeModeStillHandsTheEngineNothing() {
        project.mkdirs()
        val source = File(project, "src").apply { mkdirs() }
        File(source, "noop_plugin.py").writeText("__plugins__ = []\n")
        val store = PluginStore(project)
        val installed = store.install(source, currentPluginPolicy(), "packages state test")
        store.setEnabled((installed as InstallResult.Installed).plugin.id, true)
        File(context.filesDir, "ondemand").apply { mkdirs() }
            .resolve("marker.py").writeText("# fetched\n")

        val plugins = PluginsState(context, currentPluginPolicy(), scope)
        plugins.openProject(project)
        dev.beancount.core.data.PluginSafeMode(project).isSafeMode = true

        assertEquals("safe mode let something through", "", plugins.enabledDirectories())
    }
}
