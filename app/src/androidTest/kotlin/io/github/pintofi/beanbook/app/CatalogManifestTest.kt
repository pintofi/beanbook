package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.github.pintofi.beanbook.core.data.InstallResult
import io.github.pintofi.beanbook.core.data.PluginStore
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
        val configured = mutableListOf<String>()

        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i)
            val id = entry.getString("id")

            // Extensions are in the manifest so they reach the device — FavaExtensionTest
            // needs them there — but Fava loads them from a `custom "fava-extension"`
            // directive, not a `plugin` one. Running them here would fail them for not being
            // something they never claimed to be.
            // Extensions and libraries are in the manifest so they reach the device, but
            // neither is loaded by a `plugin` directive — Fava reads a custom directive, and
            // a library is imported by other code. Running them here would fail them for not
            // being something they never claimed to be. everyExtensionAndLibraryImportsOnAndroid
            // covers both.
            if (entry.getString("kind") != "beancount-plugin") continue

            // A plugin that needs a configuration cannot be loaded bare, so the question
            // this test asks does not apply to it. What can still be established is that its
            // code is importable on this interpreter and this ABI, which is the Android part
            // of the claim; CatalogPluginTest supplies real configurations where it has them.
            if (entry.getString("desktop_status") != "supported") {
                configured += entry.getString("id")
                continue
            }
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

        // The ones a bare load cannot speak for: import them, so every plugin in the
        // catalogue has *some* answer from this device rather than none.
        val importedOnly = mutableListOf<String>()
        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i)
            if (entry.getString("id") !in configured) continue
            val module = entry.getString("module")
            val pushed = File(catalogDir, entry.getString("package_name"))
            val project = File(context.cacheDir, "cfg-${System.nanoTime()}").apply { mkdirs() }
            try {
                val store = PluginStore(project)
                val installed = store.install(pushed, policy, "catalogue manifest")
                if (installed !is InstallResult.Installed) {
                    failures += "${entry.getString("id")}: did not install: $installed"
                    continue
                }
                val root = store.directoryFor(installed.plugin).absolutePath
                val outcome = importOnce(root, module)
                outcome.fold(
                    onSuccess = { importedOnly += entry.getString("id") },
                    onFailure = { failures += "${entry.getString("id")} ($module) does not " +
                        "import on Android: ${it.message.orEmpty().take(300)}" },
                )
            } finally {
                project.deleteRecursively()
            }
        }

        android.util.Log.i(
            "CatalogManifest",
            "loaded on device (${ran.size}/${manifest.length()}): ${ran.joinToString(", ")}",
        )
        android.util.Log.i(
            "CatalogManifest",
            "import-only, needs a configuration to load (${importedOnly.size}): " +
                importedOnly.joinToString(", "),
        )
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("nothing actually ran", ran.isNotEmpty())
    }

    /**
     * Every Fava extension and library in the catalogue, imported on Android.
     *
     * A Fava extension is not loaded by a `plugin` directive, and a library is not loaded by
     * a directive at all, so [everyPluginTheDesktopCouldLoadAlsoLoadsOnAndroid] cannot speak
     * for either. What can be established here is the thing the catalogue's `partial` status
     * actually claims: the module imports on this device, on this interpreter, on this ABI.
     *
     * Failing to import is recorded rather than failed **only** when the cause is a package
     * the app does not bundle — `pandas`, `yaml` and friends, which the Expert edition fetches
     * on demand. Anything else is a real failure: a syntax error, a missing attribute, a
     * Beancount 2 API. The distinction is the point. Calling every import failure "needs a
     * dependency" would hide exactly the breakage this is meant to find.
     */
    @Test fun everyExtensionAndLibraryImportsOnAndroid() {
        val manifestFile = File(catalogDir, "manifest.json")
        assumeTrue(
            "no catalogue on the device; run tools/plugin-compat/push-to-device.sh",
            manifestFile.isFile,
        )
        val manifest = JSONArray(manifestFile.readText())

        val imported = mutableListOf<String>()
        val needsPackage = mutableListOf<String>()
        val failures = mutableListOf<String>()

        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i)
            val kind = entry.getString("kind")
            if (kind != "fava-extension" && kind != "library") continue

            val id = entry.getString("id")
            val module = entry.getString("module")
            val pushed = File(catalogDir, entry.getString("package_name"))
            if (!pushed.exists()) {
                failures += "$id: $pushed was never pushed"
                continue
            }

            val project = File(context.cacheDir, "import-${System.nanoTime()}").apply { mkdirs() }
            try {
                val store = PluginStore(project)
                val installed = store.install(pushed, policy, "catalogue manifest")
                if (installed !is InstallResult.Installed) {
                    failures += "$id: did not install under ${policy.name}: $installed"
                    continue
                }
                val root = store.directoryFor(installed.plugin).absolutePath

                val outcome = importOnce(root, module)

                outcome.fold(
                    onSuccess = { imported += id },
                    onFailure = { error ->
                        val text = error.message.orEmpty()
                        val missing = Regex("No module named '([^']+)'").find(text)?.groupValues?.get(1)
                        // Its own package failing to import is the extension's problem. A
                        // third-party one it depends on is the install's, and fetchable.
                        val ownPackage = missing != null && module.startsWith(missing)
                        if (missing != null && !ownPackage) {
                            needsPackage += "$id needs $missing"
                        } else {
                            failures += "$id ($module): ${text.take(300)}"
                        }
                    },
                )
            } finally {
                project.deleteRecursively()
            }
        }

        android.util.Log.i(
            "CatalogManifest",
            "imported on device (${imported.size}): ${imported.joinToString(", ")}",
        )
        android.util.Log.i(
            "CatalogManifest",
            "need a fetched package (${needsPackage.size}): ${needsPackage.joinToString(", ")}",
        )
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue(
            "nothing was imported and nothing named a missing package — the run did nothing",
            imported.isNotEmpty() || needsPackage.isNotEmpty(),
        )
    }

    /**
     * Import [module] with [root] on `sys.path`, then leave the interpreter as it was found.
     *
     * The purge is the part that matters. The engine's interpreter outlives every test in
     * this class, so a package imported for one entry stays in `sys.modules` pointing at an
     * install directory the next line deletes. Four `reds.*` entries failed with
     * `No module named 'beancount_reds_plugins.effective_date'` for exactly that reason —
     * the package was cached from `capital_gains_classifier`'s install, and the submodule
     * was looked for under a path that no longer existed.
     */
    private fun importOnce(root: String, module: String): Result<Unit> {
        val python = Python.getInstance()
        val sys = python.getModule("sys")
        val path = sys.get("path")!!
        val modules = sys.get("modules")!!
        val top = module.split(".").first()

        path.callAttr("insert", 0, root)
        python.getModule("importlib").callAttr("invalidate_caches")
        val outcome = runCatching { python.getModule(module); Unit }
        path.callAttr("remove", root)

        val cached = python.getModule("builtins").callAttr("list", modules.callAttr("keys"))
        for (i in 0 until cached.callAttr("__len__").toInt()) {
            val name = cached.callAttr("__getitem__", i).toString()
            if (name == top || name.startsWith("$top.")) {
                modules.callAttr("pop", name, null)
            }
        }
        return outcome
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
