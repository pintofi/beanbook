package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.beancount.core.data.InstallResult
import dev.beancount.core.data.PluginStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every catalogued plugin the desktop harness could load, run again on Android.
 *
 * [CatalogPluginTest] checks a hand-written list of cases in depth — what each plugin does to
 * a ledger shaped to exercise it. This one is the breadth counterpart: it reads the manifest
 * `run_catalog.py` emits and loads *everything* in it, so a plugin that starts working on the
 * desktop cannot quietly miss the run that actually matters.
 *
 * That gap was real. The catalogue recorded 24 of 35 entries as `unavailable` — "could not be
 * fetched" — when the fetcher was passing browser URLs to an endpoint that wanted `owner/name`
 * slugs, so every GitHub fallback 404'd. Fixing that left zero unavailable. A hand-maintained
 * device list would have carried the old set forward regardless.
 *
 * The assertion is invariant 4, for every plugin at once: a plugin may reorder, annotate or
 * reject, and a directive it has no opinion about must survive. The note is the canary.
 */
@RunWith(AndroidJUnit4::class)
class CatalogManifestTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val policy = currentPluginPolicy()
    private val catalogDir = File("/data/local/tmp/bca-catalog")

    private val probe = """
        2026-01-01 open Assets:Bank:Checking
        2026-01-01 open Expenses:Food:Groceries
        2026-01-01 open Income:Salary

        2026-02-01 * "Small shop" "under the line"
          Expenses:Food:Groceries    12.00 USD
          Assets:Bank:Checking

        2026-02-03 note Assets:Bank:Checking "a note that must survive"
    """.trimIndent()

    @Test fun everyPluginTheDesktopCouldLoadAlsoLoadsOnAndroid() {
        val manifestFile = File(catalogDir, "manifest.json")
        assumeTrue(
            "no catalogue on the device; run tools/plugin-compat/push-to-device.sh",
            manifestFile.isFile,
        )
        val manifest = JSONArray(manifestFile.readText())
        assertTrue("the manifest is empty", manifest.length() > 0)

        val failures = mutableListOf<String>()
        val ran = mutableListOf<String>()

        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i)
            val id = entry.getString("id")

            // Extensions are in the manifest so they reach the device — FavaExtensionTest
            // needs them there — but Fava loads them from a `custom "fava-extension"`
            // directive, not a `plugin` one. Running them here would fail them for not being
            // something they never claimed to be.
            if (entry.getString("kind") == "fava-extension") continue
            val module = entry.getString("module")
            val pushed = File(catalogDir, entry.getString("package_name"))
            if (!pushed.exists()) {
                failures += "$id: $pushed was never pushed"
                continue
            }

            // A fresh project per entry, so one plugin's install cannot mask another's.
            val project = File(context.cacheDir, "manifest-${System.nanoTime()}").apply { mkdirs() }
            try {
                val store = PluginStore(project)
                val installed = store.install(pushed, policy, "catalogue manifest")
                if (installed !is InstallResult.Installed) {
                    failures += "$id: did not install under ${policy.name}: $installed"
                    continue
                }
                val root = store.directoryFor(installed.plugin).absolutePath

                // Only what the desktop loaded *cleanly* is asserted here. A `needs-config`
                // or `partial` entry did not load cleanly there either — `autobean.share`
                // wants a viewpoint, `lazy.balance_extended` wants a companion plugin
                // enabled — so requiring it to load bare on Android would record the
                // harness's choice of an empty configuration as the plugin's defect.
                // CatalogPluginTest drives those with real configurations.
                val needsConfig = entry.getString("desktop_status") != "supported"

                val ledger = File(project, "probe.beancount")
                ledger.writeText("plugin \"$module\"\n\n$probe\n")

                val result = JSONObject(
                    Python.getInstance().getModule("ledger")
                        .callAttr("inspect", ledger.absolutePath, 200, root, policy.allowsAndroidBridge)
                        .toString(),
                )
                val errors = result.getJSONArray("errors")
                if (errors.length() != 0) {
                    if (!needsConfig) failures += "$id ($module): $errors"
                    continue
                }
                val notes = result.getJSONObject("entry_counts").optInt("Note")
                if (notes != 1) {
                    failures += "$id ($module): a directive it has no opinion about " +
                        "disappeared — $notes notes, expected 1"
                    continue
                }
                ran += id
            } finally {
                project.deleteRecursively()
            }
        }

        android.util.Log.i(
            "CatalogManifest",
            "loaded on device (${ran.size}/${manifest.length()}): ${ran.joinToString(", ")}",
        )
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("nothing actually ran", ran.isNotEmpty())
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
