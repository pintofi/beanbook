package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.data.InstallResult
import io.github.pintofi.beanbook.core.data.PluginStore
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.engine.local.LocalEngineClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Can a Fava extension installed through the app actually be loaded by Fava?
 *
 * The catalogue calls the four Fava extensions **partial**, with the reason "importable here,
 * but its UI needs a WebView and is not exercised". That reason assumes the extension gets as
 * far as Fava. Reading the engine says otherwise: `IEngineService.startEngine` takes a ledger
 * path and a token and nothing else, while `readLedger` takes `pluginDirs` — so the
 * directories a plugin is installed into are put on `sys.path` for **parsing** and not for
 * **serving**.
 *
 * This is the difference between reading that and knowing it. A ledger that asks for an
 * extension is served, and what Fava does about it is recorded.
 */
@RunWith(AndroidJUnit4::class)
class FavaExtensionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A Fava extension, with the dependencies it needs fetched on demand.
     *
     * Every catalogued Fava extension was recorded as unavailable for wanting a package the
     * app does not bundle — `pandas`, `yaml`, `packaging`, `scipy`. The first three are now
     * fetchable, and the fourth is not needed: `fava_portfolio_returns` vendors beangrow and
     * calls SciPy once, which the overlay replaces.
     *
     * So this is the end-to-end claim the catalogue could never make: a Fava extension the
     * user installed, with its dependencies obtained on the device, **loaded by Fava** — its
     * route mounted rather than 404ing on an import error.
     */
    @Test fun anExtensionLoadsOnceItsDependenciesAreFetched() {
        // The whole point of this case is the dependency arriving on the device, which the
        // Play edition may not do. Skipped there rather than failed: the capability is
        // absent by policy, and its absence is asserted by WheelFetcherTest's Play stub.
        assumeTrue(
            "this edition cannot fetch packages, so there is nothing to load",
            currentPluginPolicy().allowsPackageDownload,
        )
        val source = File("/data/local/tmp/bca-catalog/fava_investor")
        assumeTrue("no Fava extension pushed to the device", source.isDirectory)

        val project = File(context.cacheDir, "fava-deps-${System.nanoTime()}").apply { mkdirs() }
        val store = PluginStore(project)
        val installed = store.install(source, currentPluginPolicy(), "compatibility harness")
        assertTrue("the extension did not install: $installed", installed is InstallResult.Installed)
        val root = store.directoryFor((installed as InstallResult.Installed).plugin).absolutePath

        // `packaging` is what fava_investor was missing. Pure Python, so this is a small
        // download rather than the 37 MB beangrow needs.
        val packages = File(context.filesDir, "ondemand-fava")
        val fetcher = io.github.pintofi.beanbook.app.packages.WheelFetcher(
            packages, allowed = true, allowNative = true, assets = context.assets,
        )
        val resolution = fetcher.resolve(listOf("packaging")).getOrElse {
            throw AssertionError("could not resolve packaging: $it")
        }
        fetcher.install(resolution.wheels)
        fetcher.preloadNativeLibraries()

        val ledger = File(project, "main.beancount")
        ledger.writeText(
            "2000-01-01 custom \"fava-extension\" \"fava_investor\"\n\n" +
                "2026-01-01 open Assets:Bank:Checking\n" +
                "2026-01-01 open Expenses:Food\n\n" +
                "2026-02-01 * \"Shop\"\n" +
                "  Expenses:Food   12.00 USD\n" +
                "  Assets:Bank:Checking\n",
        )

        val engine = LocalEngineClient(context)
        // Both roots: the extension itself, and what was fetched for it.
        engine.pluginDirs = listOf(root, packages.absolutePath)
        var reached = -1
        var errors = ""
        try {
            val started = runBlocking { engine.start(ledger.absolutePath) }
            assertTrue("the engine did not start: $started", started is EngineStatus.Serving)
            started as EngineStatus.Serving
            reached = get(
                "${started.origin}/${started.ledgerSlug}/extension/Investor/",
                engine.sessionToken,
            )
            errors = body("${started.origin}/${started.ledgerSlug}/api/errors", engine.sessionToken)
        } finally {
            runBlocking { runCatching { engine.stop() } }
            project.deleteRecursively()
            packages.deleteRecursively()
        }

        android.util.Log.i("FavaExtension", "with dependencies: route=$reached errors=$errors")
        assertFalse(
            "Fava still could not import the extension: $errors",
            errors.contains("FavaExtensionError"),
        )
        assertEquals("Fava did not mount the extension's route", 200, reached)
    }

    private fun open(url: String, token: String?): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            token?.let { setRequestProperty("Cookie", "bca_token=$it") }
        }

    private fun get(url: String, token: String?): Int =
        runCatching { open(url, token).let { it.responseCode.also { _ -> it.disconnect() } } }
            .getOrElse { -1 }

    private fun body(url: String, token: String?): String =
        runCatching {
            open(url, token).let { c ->
                val text = (if (c.responseCode < 400) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                c.disconnect()
                text
            }
        }.getOrElse { "" }

    @Test fun aFavaExtensionInstalledThroughTheAppIsReachableByFava() {
        val source = File("/data/local/tmp/bca-catalog/fava_investor")
        assumeTrue(
            "no Fava extension on the device; push one with tools/plugin-compat/push-to-device.sh",
            source.isDirectory,
        )

        val project = File(context.cacheDir, "fava-ext-${System.nanoTime()}").apply { mkdirs() }
        val store = PluginStore(project)
        val installed = store.install(source, currentPluginPolicy(), "compatibility harness")
        assertTrue("the extension did not install: $installed", installed is InstallResult.Installed)
        val root = store.directoryFor((installed as InstallResult.Installed).plugin).absolutePath

        // How a user asks Fava for an extension.
        val ledger = File(project, "main.beancount")
        ledger.writeText(
            "2000-01-01 custom \"fava-extension\" \"fava_investor\"\n\n" +
                "2026-01-01 open Assets:Bank:Checking\n" +
                "2026-01-01 open Expenses:Food\n\n" +
                "2026-02-01 * \"Shop\"\n" +
                "  Expenses:Food   12.00 USD\n" +
                "  Assets:Bank:Checking\n",
        )

        val engine = LocalEngineClient(context)
        // What the app now does before starting: hand Fava the directories the user's plugins
        // were installed into. Without this the extension is invisible to Fava.
        engine.pluginDirs = listOf(root)
        var reached = -1
        var errors = ""
        val status = try {
            val started = runBlocking { engine.start(ledger.absolutePath) }
            if (started is EngineStatus.Serving) {
                // Fava mounts a loaded extension at /<slug>/extension/<ClassName>/. If the
                // module could not be imported the route does not exist, and asking for it is
                // the difference between "the engine survived" and "the extension works".
                reached = get("${started.origin}/${started.ledgerSlug}/extension/Investor/",
                    engine.sessionToken)
                errors = body("${started.origin}/${started.ledgerSlug}/api/errors", engine.sessionToken)
            }
            started
        } finally {
            // Started or not, nothing is left serving.
            runBlocking { runCatching { engine.stop() } }
        }

        val layout = File(root).walkTopDown().maxDepth(2)
            .map { it.relativeTo(File(root)).path }.filter { it.isNotEmpty() }.take(8).toList()
        android.util.Log.i(
            "FavaExtension",
            "install_root=$root layout=$layout engine=$status extension_route=$reached " +
                "errors=${errors.take(400)}",
        )
        project.deleteRecursively()

        assertTrue(
            "the engine failed on a ledger that asks for a Fava extension: $status",
            status is EngineStatus.Serving,
        )

        // The property this test exists for, and the one that was broken: Fava can *find* an
        // extension the user installed. Before the fix the failure was
        // `No module named 'fava_investor'` — the extension itself, invisible because the
        // install directory never reached the serving path. It now fails, when it fails, on
        // one of the extension's own dependencies, which is a different and honest problem.
        assertFalse(
            "Fava cannot see the extension at all, which is the plumbing this fixed: $errors",
            errors.contains("No module named 'fava_investor'"),
        )

        // Not asserted: that the route mounts. Every catalogued Fava extension needs a
        // third-party package the app does not bundle — `packaging` here, `yaml` for
        // fava-dashboards, `pandas` for fava-envelope. The installer installs *files*; it
        // does not resolve dependencies, and cannot (docs/known-limitations.md). Fetching
        // them is a separate, deliberate step, which is what
        // [anExtensionLoadsOnceItsDependenciesAreFetched] does.
        //
        // The code is recorded, not asserted, and deliberately so: it is order-dependent.
        // The engine runs in its own process that outlives a single test, so once the
        // fetching test has imported `packaging` there, it stays in `sys.modules` even
        // after the files are deleted. Run alone this test sees 404 — the route absent;
        // run after that one it sees 500 — the route mounted against a module whose files
        // have gone. Measured both ways, 2026-08-28. The property this test does assert
        // survives either, because it is about the extension being visible at all.
        android.util.Log.i(
            "FavaExtension",
            "extension_route=$reached — 200 loaded, 404 dependency missing, " +
                "500 imported earlier in this process (see the comment above)",
        )
    }
}
