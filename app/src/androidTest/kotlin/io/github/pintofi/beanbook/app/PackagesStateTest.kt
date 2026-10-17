package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.packages.PackagesState
import io.github.pintofi.beanbook.app.packages.PackagesStatus
import io.github.pintofi.beanbook.app.plugins.PluginsState
import io.github.pintofi.beanbook.core.data.InstallResult
import io.github.pintofi.beanbook.core.data.PluginStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    /**
     * This test's own package directory, not the app's.
     *
     * The first version of this class used the real one and asserted that nothing had been
     * fetched into it. That is not a property of the code under test — `BeangrowOnDeviceTest`
     * installs 143 MB into the same directory earlier in the run, so the assertion failed,
     * and the cleanup here would have deleted that test's work if the order had been the
     * other way round. Anything asserting "nothing is there" has to own the directory.
     */
    private val ownRoot = File(context.cacheDir, "packages-root-${System.nanoTime()}")

    @After fun clean() {
        // Stop background refreshes first: a refresh left running reads a project this is
        // about to delete, and used to crash the process in the next test.
        scope.cancel()
        project.deleteRecursively()
        ownRoot.deleteRecursively()
        // The incompleteness marker for the *shared* root, cleared here rather than at the
        // end of the test that writes it. A trailing `delete()` never runs when the
        // assertion above it fails, and this marker is not inert litter: it makes the real
        // install root look half-merged, so the next test to ask for it is handed nothing
        // and fails for a reason that has nothing to do with itself. Seen for real while
        // building the negative control for it.
        io.github.pintofi.beanbook.app.packages.OnDemandPackages
            .marker(io.github.pintofi.beanbook.app.packages.OnDemandPackages.root(context))
            .delete()
    }

    private fun state() = PackagesState(context, currentPluginPolicy(), scope, root = ownRoot)

    /**
     * Nothing is downloaded until the user has seen what and how much.
     *
     * Asking is a separate step from fetching, and this is the property that makes the
     * warning on the screen meaningful rather than decorative.
     */
    @Test fun askingForAPackageDownloadsNothingByItself() = runBlocking {
        val packages = state()

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
            ownRoot.isDirectory && ownRoot.walkTopDown().any { it.isFile },
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
            ownRoot.walkTopDown().any { it.isFile },
        )
    }

    /** `confirm` with nothing pending is a no-op rather than a crash or a blind fetch. */
    @Test fun confirmingWithNothingPendingDoesNothing() = runBlocking {
        val packages = state()

        packages.confirm()

        assertEquals(PackagesStatus.Idle, packages.status.value)
        assertFalse(
            "a confirmation with no pending request fetched something",
            ownRoot.walkTopDown().any { it.isFile },
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

        // The real directory, because that is the one `enabledDirectories` names — but only
        // a file of this test's own is added, and only that file is taken away again, so a
        // fetch another test made survives this one.
        val fetched = io.github.pintofi.beanbook.app.packages.OnDemandPackages.root(context).apply { mkdirs() }
        val marker = File(fetched, "marker-${System.nanoTime()}.py")
        marker.writeText("# stands in for a fetched package\n")

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
        marker.delete()
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
        val marker = io.github.pintofi.beanbook.app.packages.OnDemandPackages.root(context)
            .apply { mkdirs() }
            .resolve("marker-${System.nanoTime()}.py")
        marker.writeText("# fetched\n")

        val plugins = PluginsState(context, currentPluginPolicy(), scope)
        plugins.openProject(project)
        io.github.pintofi.beanbook.core.data.PluginSafeMode(project).isSafeMode = true

        assertEquals("safe mode let something through", "", plugins.enabledDirectories())
        marker.delete()
    }

    /**
     * A merge that never finished must not look like an install.
     *
     * The bug this pins: unpacking is staged and digest-checked, but the last step copies
     * staging over the install root file by file, which is not atomic. Lose the process
     * partway -- an out-of-memory kill, which is how this was found -- and a half-copied
     * package is left behind looking exactly like a whole one. Python then failed on import
     * with a message naming a transitive dependency the user has never heard of.
     *
     * The interruption is simulated rather than induced: a package directory beside the
     * marker is precisely the state a killed merge leaves.
     */
    @Test fun aHalfFinishedMergeIsNotReportedAsInstalled() {
        assumeTrue(
            "this edition cannot fetch packages, so there is nothing to merge",
            currentPluginPolicy().allowsPackageDownload,
        )
        val root = File(context.cacheDir, "ondemand-b37-${System.nanoTime()}")
        File(root, "pytz").apply { mkdirs() }
            .resolve("__init__.py").writeText("# half copied\n")

        // One state instance throughout, and the assertion is on the *transition* it makes.
        // Asserting `installed.first()` on a fresh instance instead would pass without the
        // guard ever running: the flow starts empty and `refresh` is asynchronous, so the
        // initial value arrives before the directory is ever read. Watching a known
        // non-empty value go back to empty cannot pass that way.
        val state = PackagesState(context, currentPluginPolicy(), scope, root = root)
        state.refresh()
        val beforeMarker = runBlocking { withTimeout(5_000) { state.installed.first { it.isNotEmpty() } } }
        assertEquals("the fixture did not land", listOf("pytz"), beforeMarker)

        io.github.pintofi.beanbook.app.packages.OnDemandPackages.marker(root).writeText("pytz\n")
        state.refresh()
        runBlocking {
            withTimeout(5_000) { state.installed.first { it.isEmpty() } }
        }

        root.deleteRecursively()
        io.github.pintofi.beanbook.app.packages.OnDemandPackages.marker(root).delete()
    }

    /** Recovery throws the whole closure away, and says what it was. */
    @Test fun recoveringAHalfFinishedMergeClearsItAndNamesIt() {
        val root = File(context.cacheDir, "ondemand-b37-${System.nanoTime()}")
        File(root, "pytz").apply { mkdirs() }.resolve("__init__.py").writeText("# half\n")
        File(root, "numpy").apply { mkdirs() }.resolve("__init__.py").writeText("# whole\n")
        io.github.pintofi.beanbook.app.packages.OnDemandPackages.marker(root).writeText("pytz\nnumpy\n")

        val discarded = io.github.pintofi.beanbook.app.packages.OnDemandPackages.discardIncomplete(root)

        assertEquals("recovery did not name what it discarded", listOf("pytz", "numpy"), discarded)
        assertFalse("the half-merged root survived recovery", root.exists())
        assertFalse(
            "the marker outlived the root it described",
            io.github.pintofi.beanbook.app.packages.OnDemandPackages.marker(root).exists(),
        )
        // A root that was never interrupted is left alone, and says so by returning nothing.
        val clean = File(context.cacheDir, "ondemand-clean-${System.nanoTime()}")
        File(clean, "pytz").apply { mkdirs() }
        assertEquals(emptyList<String>(), io.github.pintofi.beanbook.app.packages.OnDemandPackages.discardIncomplete(clean))
        assertTrue("an untouched root was cleared", clean.exists())
        clean.deleteRecursively()
    }

    /**
     * The engine is the path that actually breaks, so it gets its own test.
     *
     * `installed()` refusing to name the packages is not enough: `enabledDirectories` is
     * what reaches Python, and handing the directory over is what turned a half-copied file
     * into an import error.
     */
    @Test fun aHalfFinishedMergeIsWithheldFromTheEngine() {
        project.mkdirs()
        val source = File(project, "src").apply { mkdirs() }
        File(source, "noop_plugin.py").writeText("__plugins__ = []\n")
        val store = PluginStore(project)
        val installed = store.install(source, currentPluginPolicy(), "packages state test")
        store.setEnabled((installed as InstallResult.Installed).plugin.id, true)

        val fetched = io.github.pintofi.beanbook.app.packages.OnDemandPackages.root(context).apply { mkdirs() }
        val stand_in = File(fetched, "marker-${System.nanoTime()}.py")
        stand_in.writeText("# stands in for a fetched package\n")
        val marker = io.github.pintofi.beanbook.app.packages.OnDemandPackages.marker(fetched)
        marker.writeText("pytz\n")

        val plugins = PluginsState(context, currentPluginPolicy(), scope)
        plugins.openProject(project)
        val directories = plugins.enabledDirectories().split(",").filter { it.isNotBlank() }

        assertFalse(
            "a half-merged package directory was handed to the engine: $directories",
            directories.contains(fetched.absolutePath),
        )
        assertTrue(
            "the plugin's own directory was withheld too, which is more than asked",
            directories.any { it.contains("plugins") },
        )
        stand_in.delete()
    }
}
