package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.data.InstallResult
import io.github.pintofi.beanbook.core.data.PluginStore
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.engine.local.LocalEngineClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Fava extension, installed and reached end to end (and the gap an earlier test left open).
 *
 * An earlier change fixed the plumbing and could not exercise it: every catalogued extension needed
 * a package no Android build existed for, so "Fava can find an installed extension" was
 * tested and "a user can install one and see its page" never was. `beancount-zakat` closes
 * that -- pure Python, and its only dependency, `hijridate`, is pure Python too.
 *
 * **This test uses the network**, like the three that already do: it fetches one small wheel
 * through the app's own installer. It fails with the reason rather than passing quietly when
 * there is no network.
 */
@RunWith(AndroidJUnit4::class)
class ZakatExtensionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun anInstalledExtensionAppearsInFavaOnceItsDependencyIsFetched() {
        assumeTrue(
            "this edition cannot fetch packages, so there is nothing to test",
            currentPluginPolicy().allowsPackageDownload,
        )
        val project = File(context.cacheDir, "zakat-${System.nanoTime()}").apply { mkdirs() }
        val packages = File(context.cacheDir, "zakat-packages-${System.nanoTime()}")
        val bundle = File("/data/local/tmp/beancount_zakat.zip")
        assertTrue(
            "push the plugin first: adb push beancount_zakat.zip /data/local/tmp/",
            bundle.isFile,
        )

        val store = PluginStore(project)
        val installed = store.install(bundle, currentPluginPolicy(), "beancount_zakat.zip")
        assertTrue("the plugin did not install: $installed", installed is InstallResult.Installed)
        val plugin = (installed as InstallResult.Installed).plugin
        val root = store.directoryFor(plugin).absolutePath

        // The ledger has to *name* what it loads; installing the files is not enough.
        val ledger = File(project, "main.beancount")
        val base = "2026-01-01 open Assets:Cash\n" +
            "2026-01-01 open Income:Salary\n\n" +
            "2026-02-01 * \"pay\"\n" +
            "  Assets:Cash    1000.00 USD\n" +
            "  Income:Salary\n"
        ledger.writeText(
            "2000-01-01 custom \"fava-extension\" \"beancount_zakat.fava_extension\"\n\n" + base,
        )

        val engine = LocalEngineClient(context)
        var before = ""
        var after = ""
        var route = -1
        try {
            // First without the dependency, which is the state a user meets: the extension is
            // installed and named, and Fava says exactly what is missing.
            engine.pluginDirs = listOf(root)
            val first = runBlocking { engine.start(ledger.absolutePath) }
            assertTrue("the engine did not start: $first", first is EngineStatus.Serving)
            first as EngineStatus.Serving
            before = body("${first.origin}/${first.ledgerSlug}/api/errors", engine.sessionToken)
            runBlocking { engine.restartProcess() }

            // Then through the app's own installer, which is what the Plugins screen calls.
            val fetcher = io.github.pintofi.beanbook.app.packages.WheelFetcher(
                packages, allowed = true, allowNative = true, assets = context.assets,
            )
            val resolution = fetcher.resolve(listOf("hijridate")).getOrElse {
                throw AssertionError("could not resolve hijridate (network?): $it")
            }
            fetcher.install(resolution.wheels)

            engine.pluginDirs = listOf(root, packages.absolutePath)
            val second = runBlocking { engine.start(ledger.absolutePath) }
            assertTrue("the engine did not come back: $second", second is EngineStatus.Serving)
            second as EngineStatus.Serving
            after = body("${second.origin}/${second.ledgerSlug}/api/errors", engine.sessionToken)
            route = status(
                "${second.origin}/${second.ledgerSlug}/extension/ZakatDashboard/",
                engine.sessionToken,
            )
        } finally {
            runBlocking { runCatching { engine.stop() } }
            project.deleteRecursively()
            packages.deleteRecursively()
        }

        android.util.Log.i("Zakat", "before=$before")
        android.util.Log.i("Zakat", "after=$after route=$route")
        assertTrue(
            "without its dependency Fava should have said so: $before",
            before.contains("hijridate") || before.contains("FavaExtensionError"),
        )
        assertFalse("Fava still could not load the extension: $after", after.contains("hijridate"))
        assertTrue("the extension's page was not served: route=$route", route in 200..399)
    }

    private fun open(url: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            token?.let { setRequestProperty("Cookie", "bca_token=$it") }
            connectTimeout = 30_000
            readTimeout = 30_000
        }

    private fun status(url: String, token: String?): Int = open(url, token).run {
        try { responseCode } finally { disconnect() }
    }

    private fun body(url: String, token: String?): String = open(url, token).run {
        try {
            (if (responseCode in 200..299) inputStream else errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        } finally {
            disconnect()
        }
    }
}
