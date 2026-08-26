package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.beancount.core.data.InstallResult
import dev.beancount.core.data.PluginStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A plugin from `catalog/plugins.json`, on an Android device, through the app's own stack.
 *
 * The desktop harness (`tools/plugin-compat/run_catalog.py`) answers three of the five steps
 * in plugin-compatibility.md §1 against real CPython. It cannot answer **installs**, which is
 * the Kotlin policy check and differs per flavor, and it does not run on Android at all — the
 * ABI, the interpreter build and the packaging are all different here.
 *
 * The plugin source is pushed by `tools/plugin-compat/push-to-device.sh` rather than bundled,
 * so this file does not carry a copy of somebody else's GPL/MIT source into the repository.
 * Without it the test cannot run, and it **skips loudly** instead of passing vacuously — a
 * distinction worth checking in the XML rather than trusting `BUILD SUCCESSFUL`, because a
 * skipped test and a passing one look identical from the console.
 */
@RunWith(AndroidJUnit4::class)
class CatalogPluginTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val policy = currentPluginPolicy()

    private lateinit var project: File
    private lateinit var store: PluginStore
    private lateinit var source: File

    private val probe = """
        plugin "autobean.sorted"

        2026-01-01 open Assets:Bank:Checking
        2026-01-01 open Expenses:Food:Groceries

        2026-02-01 * "Small shop" "under the line"
          Expenses:Food:Groceries    12.00 USD
          Assets:Bank:Checking

        2026-02-02 * "Big shop" "over the line"
          Expenses:Food:Groceries   250.00 USD
          Assets:Bank:Checking

        2026-02-03 note Assets:Bank:Checking "a note that must survive"
    """.trimIndent()

    @Before fun setUp() {
        // /data/local/tmp, not the app's own external cache: the Gradle test task
        // uninstalls the app when it finishes, and an app's external directory goes with it
        // — so anything pushed there before the run is gone by the time the test looks.
        source = File("/data/local/tmp/bca-catalog/autobean")
        assumeTrue(
            "no catalogued plugin on the device; run tools/plugin-compat/push-to-device.sh",
            source.isDirectory,
        )
        project = File(context.cacheDir, "catalog-${System.nanoTime()}").apply { mkdirs() }
        store = PluginStore(project)
    }

    @Test fun aCataloguedPluginInstallsLoadsAndLeavesTheLedgerIntact() {
        val installed = store.install(source, policy, "pushed for the compatibility harness")
        assertTrue(
            "step 'installs' failed under ${policy.name}: $installed",
            installed is InstallResult.Installed,
        )
        val plugin = (installed as InstallResult.Installed).plugin

        // The store copies the package under its own directory, so the importable root is
        // the directory that *contains* the package.
        val root = store.directoryFor(plugin).absolutePath
        val ledger = File(project, "main.beancount").apply { writeText(probe + "\n") }

        val result = JSONObject(
            Python.getInstance().getModule("ledger")
                .callAttr("inspect", ledger.absolutePath, 200, root, policy.allowsAndroidBridge)
                .toString(),
        )

        val errors = result.getJSONArray("errors")
        assertEquals("autobean.sorted did not load cleanly: $errors", 0, errors.length())

        val counts = result.getJSONObject("entry_counts")
        assertEquals(2, counts.getInt("Transaction"))
        assertEquals("a directive the plugin never touched disappeared", 1, counts.getInt("Note"))
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun startPython() {
            if (!Python.isStarted()) {
                Python.start(
                    AndroidPlatform(
                        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                    ),
                )
            }
        }
    }
}
