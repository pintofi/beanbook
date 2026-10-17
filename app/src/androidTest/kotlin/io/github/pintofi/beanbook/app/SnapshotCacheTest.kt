package io.github.pintofi.beanbook.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.engine.local.LedgerReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The snapshot cache must be fast, and must never be wrong.
 *
 * Opening a 100k-entry ledger cost ~30 s on a phone because the native screens waited for a
 * full Beancount load they do not need — the Journal shows a 200-row window and Balances one
 * row per account, neither of which grows with the ledger. The answers are now cached and
 * keyed by the content of every file that fed them.
 *
 * Speed is the easy half. The half worth testing is that a cached answer is *the same answer*,
 * and that it stops being used the moment the ledger changes: a cache that shows yesterday's
 * balance after today's edit is worse than no cache at all in an accounting app.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotCacheTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = LedgerReader(context)
    private val dir = File(context.cacheDir, "snapcache-${System.nanoTime()}")

    @After fun clean() {
        dir.deleteRecursively()
    }

    private fun ledger(body: String): File {
        dir.mkdirs()
        return File(dir, "main.beancount").apply { writeText(body) }
    }

    private val base = """
        option "operating_currency" "USD"
        2024-01-01 open Assets:Cash USD
        2024-01-01 open Expenses:Food USD

        2024-01-02 * "Grocer" "One"
          Expenses:Food      1.00 USD
          Assets:Cash
    """.trimIndent()

    @Test fun theSecondLoadIsTheSameAnswerAndArrivesFaster() {
        val main = ledger(base + "\n")

        // Timed here rather than trusting the snapshot's own figure. An earlier version of
        // this test compared `loadMillis` across the two paths and failed, because the cold
        // path reported the engine's internal load time while the cached path reported wall
        // clock -- two different measurements. The reader now reports one, but a test that
        // measures the thing it asserts on cannot be fooled by that again.
        val coldStart = System.nanoTime()
        val cold = runBlocking { reader.read(main.absolutePath) }.getOrThrow()
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000

        val warmStart = System.nanoTime()
        val warm = runBlocking { reader.read(main.absolutePath) }.getOrThrow()
        val warmMs = (System.nanoTime() - warmStart) / 1_000_000

        // Everything the screens draw must survive the round trip. loadMillis is excluded on
        // purpose: it describes *this* load, so the two are expected to differ, and that is
        // the point of the assertion below it.
        assertEquals("entry count changed", cold.entryCount, warm.entryCount)
        assertEquals("accounts changed", cold.accounts, warm.accounts)
        assertEquals("journal changed", cold.journal, warm.journal)
        assertEquals("errors changed", cold.errors, warm.errors)
        assertEquals("commodities changed", cold.commodities, warm.commodities)
        assertEquals("title changed", cold.title, warm.title)
        assertEquals("operating currencies changed", cold.operatingCurrencies, warm.operatingCurrencies)

        Log.i("SnapshotCacheTest", "cold=${coldMs}ms warm=${warmMs}ms")
        // A deliberately loose bound. This fixture is three directives, where a real load is
        // already milliseconds and the cache cannot save much -- the saving this exists for
        // shows up at 100k entries, which is measured separately and recorded elsewhere. What
        // is asserted here is that the cached path is not *slower*, which is what a broken
        // fingerprint or a re-read would look like.
        assertTrue(
            "the cached load took ${warmMs} ms against a cold ${coldMs} ms",
            warmMs <= coldMs + 250,
        )
    }

    /**
     * The case the whole design rests on: an edit must not be served from the cache.
     *
     * The fingerprint is over file *content*, not mtime and size, so this stays true even
     * for an edit that preserves both — which is the shortcut that would eventually show
     * someone a stale balance.
     */
    @Test fun anEditIsNotServedFromTheCache() {
        val main = ledger(base + "\n")
        val before = runBlocking { reader.read(main.absolutePath) }.getOrThrow()
        assertEquals(1, before.journal.size)

        main.appendText(
            "\n2024-01-03 * \"Grocer\" \"Two\"\n" +
                "  Expenses:Food      2.00 USD\n" +
                "  Assets:Cash\n",
        )

        val after = runBlocking { reader.read(main.absolutePath) }.getOrThrow()
        assertEquals("the edit was not picked up", 2, after.journal.size)
        assertNotEquals("the accounts did not change after an edit", before.accounts, after.accounts)
    }

    /** A same-length edit, which mtime-and-size caching would miss. */
    @Test fun anEditThatPreservesLengthIsStillNoticed() {
        val main = ledger(base + "\n")
        val before = runBlocking { reader.read(main.absolutePath) }.getOrThrow()

        // "One" -> "Two": same byte count, same file length, and on a coarse filesystem
        // clock possibly the same mtime.
        main.writeText(main.readText().replace("\"One\"", "\"Two\""))
        assertEquals("the fixture changed length", base.length + 1, main.readText().length)

        val after = runBlocking { reader.read(main.absolutePath) }.getOrThrow()
        assertEquals(
            "a same-length edit was served from the cache",
            "Two",
            after.journal.first().narration,
        )
    }

    /** A ledger that grew an `include` must be re-read, and then tracked by the wider set. */
    @Test fun addingAnIncludeInvalidatesTheCache() {
        val main = ledger(base + "\n")
        runBlocking { reader.read(main.absolutePath) }.getOrThrow()

        File(dir, "more.beancount").writeText(
            "2024-01-04 * \"Grocer\" \"Three\"\n" +
                "  Expenses:Food      3.00 USD\n" +
                "  Assets:Cash\n",
        )
        main.writeText(main.readText() + "\ninclude \"more.beancount\"\n")

        val after = runBlocking { reader.read(main.absolutePath) }.getOrThrow()
        assertEquals("the included file was not read", 2, after.journal.size)

        // And now the included file is part of what the fingerprint covers: editing it,
        // without touching main, must also invalidate.
        File(dir, "more.beancount").appendText(
            "\n2024-01-05 * \"Grocer\" \"Four\"\n" +
                "  Expenses:Food      4.00 USD\n" +
                "  Assets:Cash\n",
        )
        val later = runBlocking { reader.read(main.absolutePath) }.getOrThrow()
        assertEquals("an edit to an included file was served from the cache", 3, later.journal.size)
    }

    @Test fun aChangedPluginInvalidatesTheAnswerItProduced() {
        // The ledger's own files do not change; the plugin it names does. A plugin is code the
        // load runs, so the cached answer from before the edit must not be served after it.
        val plugins = File(dir, "plugins").apply { mkdirs() }
        val plugin = File(plugins, "reviewplug.py")
        plugin.writeText(
            "from collections import namedtuple\n" +
                "E = namedtuple('E', 'source message entry')\n" +
                "__plugins__ = ('check',)\n" +
                "def check(entries, options_map):\n    return entries, []\n",
        )
        val main = ledger("plugin \"reviewplug\"\n" + base + "\n")

        val first = runBlocking { reader.read(main.absolutePath, pluginDirs = plugins.absolutePath).getOrThrow() }
        assertEquals(0, first.errors.size)

        plugin.writeText(
            "from collections import namedtuple\n" +
                "E = namedtuple('E', 'source message entry')\n" +
                "__plugins__ = ('check',)\n" +
                "def check(entries, options_map):\n    return entries, [E(None, 'the plugin changed', None)]\n",
        )
        val second = runBlocking { reader.read(main.absolutePath, pluginDirs = plugins.absolutePath).getOrThrow() }

        assertEquals(listOf("the plugin changed"), second.errors.map { it.message })
    }
}
