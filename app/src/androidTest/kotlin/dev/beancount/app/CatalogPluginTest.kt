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

    /**
     * One catalogued plugin, and a ledger that exercises it.
     *
     * [expectTransactions] and [expectNotes] are what must survive the plugin running.
     * Invariant 4 is the point of the note in every case: a plugin may reorder, annotate or
     * reject, and a directive it has no opinion about must come out the other side.
     */
    private data class Case(
        val plugin: String,
        val ledger: String,
        val expectTransactions: Int,
        val expectNotes: Int = 1,
    )

    private val opened = """
        2026-01-01 open Assets:Bank:Checking
        2026-01-01 open Expenses:Food:Groceries
    """.trimIndent()

    private val twoTransactions = """
        2026-02-01 * "Small shop" "under the line"
          Expenses:Food:Groceries    12.00 USD
          Assets:Bank:Checking

        2026-02-02 * "Big shop" "over the line"
          Expenses:Food:Groceries   250.00 USD
          Assets:Bank:Checking

        2026-02-03 note Assets:Bank:Checking "a note that must survive"
    """.trimIndent()

    /**
     * The GPL-2.0 half of the catalogue's `supported` set, which is the half this project can
     * point a user at without also pointing them at a licence problem — see
     * docs/plugin-compatibility.md on the `reds.*` entries.
     *
     * Five plugins from one package, which is why they can be run together: the app installs
     * the package once and names the module per ledger.
     */
    private val cases = listOf(
        Case("autobean.sorted", "plugin \"autobean.sorted\"\n\n$opened\n\n$twoTransactions", 2),
        Case("autobean.narration", "plugin \"autobean.narration\"\n\n$opened\n\n$twoTransactions", 2),
        Case("autobean.xcheck", "plugin \"autobean.xcheck\"\n\n$opened\n\n$twoTransactions", 2),
        Case("autobean.include", "plugin \"autobean.include\"\n\n$opened\n\n$twoTransactions", 2),
        // autobean.truelayer is deliberately absent: it imports `beancount.ingest`, which
        // Beancount 3 removed, and fails identically on desktop. It is a Beancount 2 plugin,
        // and the catalogue now says so (docs/milestones/m14-part25.md).
    )

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

    @Test fun everyGplTwoCataloguedPluginInstallsLoadsAndLeavesTheLedgerIntact() {
        val installed = store.install(source, policy, "pushed for the compatibility harness")
        assertTrue(
            "step 'installs' failed under ${policy.name}: $installed",
            installed is InstallResult.Installed,
        )
        val plugin = (installed as InstallResult.Installed).plugin

        // The store copies the package under its own directory, so the importable root is
        // the directory that *contains* the package.
        val root = store.directoryFor(plugin).absolutePath

        val failures = mutableListOf<String>()
        cases.forEach { case ->
            val ledger = File(project, "${case.plugin.replace('.', '-')}.beancount")
                .apply { writeText(case.ledger + "\n") }

            val result = JSONObject(
                Python.getInstance().getModule("ledger")
                    .callAttr("inspect", ledger.absolutePath, 200, root, policy.allowsAndroidBridge)
                    .toString(),
            )

            val errors = result.getJSONArray("errors")
            if (errors.length() != 0) {
                failures += "${case.plugin}: did not load cleanly — $errors"
                return@forEach
            }

            val counts = result.getJSONObject("entry_counts")
            val transactions = counts.optInt("Transaction")
            val notes = counts.optInt("Note")
            if (transactions != case.expectTransactions) {
                failures += "${case.plugin}: $transactions transactions, expected " +
                    "${case.expectTransactions}"
            }
            if (notes != case.expectNotes) {
                failures += "${case.plugin}: a directive it has no opinion about disappeared " +
                    "— $notes notes, expected ${case.expectNotes}"
            }
            android.util.Log.i(
                "CatalogPlugin",
                "${case.plugin} ok transactions=$transactions notes=$notes",
            )
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
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
