package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.core.data.InstallResult
import dev.beancount.core.data.PluginStore
import dev.beancount.core.engine.EngineStatus
import dev.beancount.engine.local.LocalEngineClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Can a Fava extension installed through the app actually be loaded by Fava? (V8)
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
        // extension the user installed. Before M14 the failure was
        // `No module named 'fava_investor'` — the extension itself, invisible because the
        // install directory never reached the serving path. It now fails, when it fails, on
        // one of the extension's own dependencies, which is a different and honest problem.
        assertFalse(
            "Fava cannot see the extension at all, which is the plumbing this fixed: $errors",
            errors.contains("No module named 'fava_investor'"),
        )

        // Not asserted: that the route mounts. Every catalogued Fava extension needs a
        // third-party package the app does not bundle — `packaging` here, `yaml` for
        // fava-dashboards, `pandas` for fava-envelope, `scipy` for fava-portfolio-returns
        // (which has no Android build at all, D6). The installer installs *files*; it does
        // not resolve dependencies, and cannot (docs/known-limitations.md).
        android.util.Log.i(
            "FavaExtension",
            "extension_route=$reached — 200 means it loaded, 404 means a missing dependency",
        )
    }
}
