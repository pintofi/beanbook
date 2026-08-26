package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.beancount.core.data.InstallResult
import dev.beancount.core.data.PluginStore
import dev.beancount.core.domain.PluginPolicy
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The compatibility harness, on the device, through the app's own stack.
 *
 * `tools/plugin-compat/harness.py` answers three of the five steps in
 * plugin-compatibility.md §1 on a desktop. Two it cannot: **installs**, because the edition
 * policy is Kotlin and its answer depends on the flavor, and the device is the only place
 * both flavors exist; and anything about real Android behaviour.
 *
 * These cases use plugins written for the purpose. **No entry in `catalog/plugins.json` is
 * exercised here** — this machine has no network, so none of the 35 catalogued plugins has
 * ever been fetched, and none may be called compatible (CLAUDE.md, forbidden).
 */
@RunWith(AndroidJUnit4::class)
class PluginCompatibilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val policy = currentPluginPolicy()

    private lateinit var project: File
    private lateinit var store: PluginStore

    private val tagger = """
        from beancount.core import data

        __plugins__ = ['tag_large']

        def tag_large(entries, options_map):
            out = []
            for entry in entries:
                if isinstance(entry, data.Transaction):
                    total = sum(abs(p.units.number) for p in entry.postings
                                if p.units is not None and p.units.number > 0)
                    if total >= 100:
                        entry = entry._replace(tags=(entry.tags or frozenset()) | {'large'})
                out.append(entry)
            return out, []
    """.trimIndent()

    private val ledgerText = """
        plugin "tagger"

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
        project = File(context.cacheDir, "compat-${System.nanoTime()}").apply { mkdirs() }
        store = PluginStore(project)
    }

    private fun install(name: String, body: String, under: PluginPolicy = policy): InstallResult {
        val candidate = File(context.cacheDir, "$name.py").apply { writeText(body) }
        return store.install(candidate, under, "compatibility harness")
    }

    private fun load(pluginDirs: String, allowBridge: Boolean, ledger: File): JSONObject =
        JSONObject(
            Python.getInstance().getModule("ledger")
                .callAttr("inspect", ledger.absolutePath, 200, pluginDirs, allowBridge)
                .toString(),
        )

    private fun ledgerFile(): File =
        File(project, "main.beancount").apply { writeText(ledgerText + "\n") }

    // ---- the five steps, for a plugin that works ------------------------------------------

    @Test fun aPurePythonPluginInstallsImportsExecutesAndPreservesEverything() {
        val installed = install("tagger", tagger)
        assertTrue(
            "step 'installs' failed under ${policy.name}: $installed",
            installed is InstallResult.Installed,
        )
        val plugin = (installed as InstallResult.Installed).plugin
        val dir = store.directoryFor(plugin).absolutePath

        val result = load(dir, policy.allowsAndroidBridge, ledgerFile())

        val errors = result.getJSONArray("errors")
        assertEquals("step 'imports'/'executes' failed: $errors", 0, errors.length())

        // 'preserves': the note is a directive the plugin knows nothing about, and it must
        // still be there. Invariant 4 — nothing is silently dropped.
        val counts = result.getJSONObject("entry_counts")
        assertEquals(2, counts.getInt("Transaction"))
        assertEquals("the note the plugin never touched disappeared", 1, counts.getInt("Note"))

        // And the plugin actually did its job, rather than loading and doing nothing.
        val journal = result.getJSONArray("journal")
        val tagged = (0 until journal.length())
            .map { journal.getJSONObject(it) }
            .filter { it.getJSONArray("tags").length() > 0 }
        assertEquals("exactly one transaction is over the threshold", 1, tagged.size)
        assertEquals("over the line", tagged.single().getString("narration"))
    }

    // ---- the step only the device can answer ------------------------------------------------

    @Test fun theInstallStepAnswersDifferentlyPerEditionForANativeWheel() {
        val wheel = File(context.cacheDir, "native-${System.nanoTime()}.whl")
        java.util.zip.ZipOutputStream(wheel.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("fast/__init__.py"))
            zip.write("".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("fast/_core.so"))
            zip.write(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            zip.closeEntry()
        }

        val result = store.install(wheel, policy, "compatibility harness")

        if (policy.allowsNativeCode) {
            assertTrue("expert must install a native wheel: $result", result is InstallResult.Installed)
        } else {
            assertTrue("play must refuse a native wheel: $result", result is InstallResult.Refused)
            assertTrue("a refused install wrote something", store.list().isEmpty())
        }
    }

    // ---- a plugin that misbehaves must not take the ledger with it ---------------------------

    @Test fun aPluginThatRaisesLeavesTheLedgerReadableAndIsBlamedForIt() {
        // Beancount records an ImportError from a plugin and carries on. Anything else comes
        // straight out of load_file — a plugin whose first line is `raise RuntimeError(...)`
        // would take the engine down. The ledger must survive its plugins.
        val installed = install("exploder", "raise RuntimeError('nope')\n__plugins__ = []\n")
        val plugin = (installed as InstallResult.Installed).plugin
        val ledger = File(project, "main.beancount").apply {
            writeText("plugin \"exploder\"\n\n2026-01-01 open Assets:Cash\n")
        }

        val result = load(store.directoryFor(plugin).absolutePath, policy.allowsAndroidBridge, ledger)

        val counts = result.getJSONObject("entry_counts")
        assertEquals("the ledger became unreadable because of a plugin", 1, counts.getInt("Open"))
        val messages = result.getJSONArray("errors").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("message") }
        }
        assertTrue(
            "the failure was not reported to the user: $messages",
            messages.any { it.contains("plugin stopped the ledger") },
        )
    }

    @Test fun aPluginThatDropsDirectivesIsCaughtByTheHarnessAndNotByLuck() {
        // The failure a user would never notice: the file on disk is untouched and only what
        // the app shows is wrong. It is caught by comparing against the same ledger with no
        // plugins, which is what the desktop harness does too.
        val installed = install(
            "dropper",
            "from beancount.core import data\n" +
                "__plugins__ = ['only_txns']\n" +
                "def only_txns(entries, options_map):\n" +
                "    return [e for e in entries if isinstance(e, data.Transaction)], []\n",
        )
        val plugin = (installed as InstallResult.Installed).plugin
        val ledger = ledgerFile().apply { writeText(ledgerText.replace("tagger", "dropper") + "\n") }
        val dir = store.directoryFor(plugin).absolutePath

        val withResult = load(dir, policy.allowsAndroidBridge, ledger)
        val withoutResult = load("", policy.allowsAndroidBridge, ledger)
        val withPlugin = withResult.getJSONObject("entry_counts")
        val without = withoutResult.getJSONObject("entry_counts")

        assertFalse(
            "the dropper did not actually drop anything, so this proves nothing. " +
                "with=$withPlugin errors=${withResult.getJSONArray("errors")} " +
                "without=$without",
            withPlugin.optInt("Note", 0) == without.optInt("Note", 0),
        )
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
