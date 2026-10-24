package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.engine.local.LedgerReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An entry whose narration a plugin rewrites can still be deleted and edited (#70).
 *
 * The listed entry comes from the loaded ledger, where the plugin has already changed the
 * narration; the file still says what the person wrote. The check that decides whether the entry at
 * a line is the one that was listed used to compare the two and refuse. This runs the real
 * engine with a real plugin and then the same check.
 */
@RunWith(AndroidJUnit4::class)
class PluginRewriteEntryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = LedgerReader(context)
    private val dir = File(context.cacheDir, "pluginrewrite-${System.nanoTime()}")

    @After fun clean() {
        dir.deleteRecursively()
    }

    private val plugin = """
        from beancount.core import data
        __plugins__ = ('rewrite',)

        def rewrite(entries, options_map):
            out = []
            for entry in entries:
                if isinstance(entry, data.Transaction) and entry.narration:
                    entry = entry._replace(narration=entry.narration + ' (checked)')
                out.append(entry)
            return out, []
    """.trimIndent() + "\n"

    private val ledger = """
        plugin "narrate"
        2026-01-01 open Assets:Cash USD
        2026-01-01 open Expenses:Food USD

        2026-03-05 * "Cafe" "Lunch"
          Expenses:Food   4.50 USD
          Assets:Cash

        2026-03-06 * "Bakery" "Bread"
          Expenses:Food   2.00 USD
          Assets:Cash
    """.trimIndent() + "\n"

    /** The text of the entry the way the file has it: from its line to the next blank one. */
    private fun sliceAt(file: File, line: Int): String =
        file.readLines().drop(line - 1).takeWhile { it.isNotBlank() }.joinToString("\n") + "\n"

    @Test fun anEntryAPluginRewroteIsStillTheEntryAtItsLine() {
        val plugins = File(dir, "plugins").apply { mkdirs() }
        File(plugins, "narrate.py").writeText(plugin)
        val main = File(dir, "main.beancount").apply { writeText(ledger) }

        val snapshot = runBlocking { reader.read(main.absolutePath, pluginDirs = plugins.absolutePath) }.getOrThrow()
        val lunch = snapshot.journal.first { it.date == "2026-03-05" }
        val bread = snapshot.journal.first { it.date == "2026-03-06" }

        assertTrue("the plugin did not run: ${lunch.narration}", lunch.narration.orEmpty().endsWith("(checked)"))
        assertTrue("file and line missing", lunch.line != null)
        assertTrue(
            "the entry the plugin rewrote was refused at its own line",
            sliceMatches(lunch, sliceAt(main, lunch.line!!)),
        )
        assertTrue(sliceMatches(bread, sliceAt(main, bread.line!!)))
        assertFalse(
            "another entry's text was accepted for the rewritten one",
            sliceMatches(lunch, sliceAt(main, bread.line!!)),
        )
    }
}
